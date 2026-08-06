#include "interlis_extension.hpp"

#include "model/compiled_model.hpp"
#include "model/model_source_resolver.hpp"
#include "xtf/xtf_stream.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/common/file_system.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include "iox/IomPath.h"
#include "iox/ilic/IlicModelIndex.h"

#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace duckdb {

namespace {

struct XtfValuesBindData final : TableFunctionData {
    std::string path;
    std::string className;
    iox::IomPath iomPath;
    std::optional<std::string> tid;
    std::optional<std::string> bid;
    std::shared_ptr<interlis::CompiledModel> model;

    explicit XtfValuesBindData(iox::IomPath pathValue)
        : iomPath(std::move(pathValue)) {
    }

    unique_ptr<FunctionData> Copy() const override {
        auto result = make_uniq<XtfValuesBindData>(iomPath);
        result->path = path;
        result->className = className;
        result->iomPath = iomPath;
        result->tid = tid;
        result->bid = bid;
        result->model = model;
        result->column_ids = column_ids;
        return result;
    }

    bool Equals(const FunctionData &) const override {
        return false;
    }

    bool SupportStatementCache() const override {
        return false;
    }
};

struct XtfValuesGlobalState final : GlobalTableFunctionState {
    idx_t MaxThreads() const override {
        return 1;
    }
};

struct XtfValuesLocalState final : LocalTableFunctionState {
    XtfStreamState stream;
    std::vector<std::vector<Value>> pendingRows;
    idx_t pendingOffset = 0;
};

std::optional<std::string> NamedString(const TableFunctionBindInput &input,
                                       const char *name) {
    const auto found = input.named_parameters.find(name);
    if (found == input.named_parameters.end() || found->second.IsNull()) {
        return std::nullopt;
    }
    return StringValue::Get(found->second);
}

std::string ScopedName(const metamodel::MetaElement &element) {
    std::vector<std::string> components;
    const metamodel::MetaElement *current = &element;
    while (current != nullptr) {
        if (!current->Name.empty()) {
            components.push_back(current->Name);
        }
        current = current->ElementInPackage;
    }
    std::string result;
    for (auto iterator = components.rbegin(); iterator != components.rend(); ++iterator) {
        if (!result.empty()) {
            result.push_back('.');
        }
        result += *iterator;
    }
    return result;
}

bool HasClassInPackage(const metamodel::Package &package,
                       const std::string &className) {
    for (const auto *element : package.Element) {
        if (element == nullptr) {
            continue;
        }
        if (const auto *klass = dynamic_cast<const metamodel::Class *>(element)) {
            if (ScopedName(*klass) == className) {
                return true;
            }
        }
        if (const auto *child = dynamic_cast<const metamodel::Package *>(element)) {
            if (HasClassInPackage(*child, className)) {
                return true;
            }
        }
    }
    return false;
}

bool HasClass(const metamodel::MetaModelStore &store,
             const std::string &className) {
    for (const auto *model : store.models()) {
        if (model != nullptr && HasClassInPackage(*model, className)) {
            return true;
        }
    }
    return false;
}

std::vector<std::string> ReadModelSources(const Value &value) {
    if (value.IsNull() || value.type().id() != LogicalTypeId::LIST) {
        throw InvalidInputException("xtf_values model_sources must be a non-empty VARCHAR[]");
    }
    std::vector<std::string> result;
    for (const auto &child : ListValue::GetChildren(value)) {
        if (child.IsNull()) {
            throw InvalidInputException("xtf_values model_sources must not contain NULL");
        }
        result.emplace_back(StringValue::Get(child));
    }
    if (result.empty()) {
        throw InvalidInputException("xtf_values model_sources must be a non-empty VARCHAR[]");
    }
    return result;
}

std::shared_ptr<interlis::CompiledModel> CompileModel(ClientContext &context,
                                                       const Value &sourcesValue) {
    auto sources = ReadModelSources(sourcesValue);
    auto &fileSystem = FileSystem::GetFileSystem(context);
    auto resolved = interlis::ModelSourceResolver::Resolve(fileSystem, sources);
    return std::make_shared<interlis::CompiledModel>(std::move(resolved));
}

iox::IomPath ParsePath(const std::string &expression) {
    try {
        return iox::IomPath::parse(expression);
    } catch (const iox::IoxError &error) {
        throw InvalidInputException("xtf_values invalid path_expression '%s': %s", expression, error.what());
    }
}

unique_ptr<FunctionData> BindXtfValues(ClientContext &context,
                                       TableFunctionBindInput &input,
                                       vector<LogicalType> &returnTypes,
                                       vector<string> &names) {
    if (input.inputs.size() != 4) {
        throw InvalidInputException("xtf_values requires path, class_name, path_expression, and model_sources");
    }
    returnTypes = {LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
                   LogicalType::INTEGER, LogicalType::VARCHAR};
    names = {"bid", "tid", "class_name", "occurrence", "value"};

    const auto expression = StringValue::Get(input.inputs[2]);
    auto result = make_uniq<XtfValuesBindData>(ParsePath(expression));
    result->path = StringValue::Get(input.inputs[0]);
    result->className = StringValue::Get(input.inputs[1]);
    if (result->path.empty() || result->className.empty()) {
        throw InvalidInputException("xtf_values path and class_name must not be empty");
    }
    const auto firstSeparator = result->className.find('.');
    const auto secondSeparator = firstSeparator == std::string::npos
                                     ? std::string::npos
                                     : result->className.find('.', firstSeparator + 1);
    if (firstSeparator == std::string::npos || secondSeparator == std::string::npos) {
        throw InvalidInputException("xtf_values class_name must be fully qualified as Model.Topic.Class: %s",
                                    result->className);
    }
    result->tid = NamedString(input, "tid");
    result->bid = NamedString(input, "bid");
    result->model = CompileModel(context, input.inputs[3]);
    if (!HasClass(result->model->models(), result->className)) {
        throw InvalidInputException("xtf_values class_name is not a model class: %s", result->className);
    }
    return result;
}

unique_ptr<GlobalTableFunctionState> InitXtfValuesGlobal(ClientContext &,
                                                          TableFunctionInitInput &) {
    return make_uniq<XtfValuesGlobalState>();
}

unique_ptr<LocalTableFunctionState> InitXtfValuesLocal(
    ExecutionContext &context, TableFunctionInitInput &input,
    GlobalTableFunctionState *) {
    auto &data = input.bind_data->Cast<XtfValuesBindData>();
    auto result = make_uniq<XtfValuesLocalState>();
    result->stream = OpenXtfStream(context.client, data.model->models(), data.path);
    return result;
}

std::optional<std::vector<Value>> NextValueRow(XtfValuesLocalState &local,
                                               const XtfValuesBindData &bindData) {
    if (local.pendingOffset < local.pendingRows.size()) {
        return local.pendingRows[local.pendingOffset++];
    }
    local.pendingRows.clear();
    local.pendingOffset = 0;

    while (true) {
        const auto event = NextXtfEvent(local.stream, bindData.path);
        if (!event.has_value()) {
            return std::nullopt;
        }
        if (const auto *basket = std::get_if<iox::StartBasketEvent>(&*event)) {
            local.stream.currentBid = basket->basket.basketId;
            continue;
        }
        const auto *objectEvent = std::get_if<iox::ObjectEvent>(&*event);
        if (objectEvent == nullptr) {
            continue;
        }
        const auto &object = objectEvent->object;
        if (object.tag().hasInterlisName() &&
            object.tag().interlisName() != bindData.className) {
            continue;
        }
        if (!object.oid().has_value() ||
            (bindData.tid.has_value() && *bindData.tid != *object.oid()) ||
            (bindData.bid.has_value() && *bindData.bid != local.stream.currentBid)) {
            continue;
        }

        std::vector<iox::IomPathMatch> matches;
        try {
            matches = bindData.iomPath.primitiveMatches(object);
        } catch (const iox::IoxError &error) {
            throw InvalidInputException(
                "XTF values failed: %s TID=%s BID=%s class=%s path=%s: %s",
                bindData.path, *object.oid(), local.stream.currentBid,
                bindData.className, bindData.iomPath.expression(), error.what());
        }
        for (std::size_t index = 0; index < matches.size(); ++index) {
            local.pendingRows.push_back({
                Value(local.stream.currentBid), Value(*object.oid()),
                Value(bindData.className),
                Value::INTEGER(static_cast<int32_t>(index + 1)),
                Value(matches[index].value)});
        }
        if (!local.pendingRows.empty()) {
            return local.pendingRows[local.pendingOffset++];
        }
    }
}

void XtfValuesFunction(ClientContext &, TableFunctionInput &input,
                       DataChunk &output) {
    auto &bindData = input.bind_data->Cast<XtfValuesBindData>();
    auto &local = input.local_state->Cast<XtfValuesLocalState>();
    idx_t rowIndex = 0;
    while (rowIndex < STANDARD_VECTOR_SIZE) {
        const auto row = NextValueRow(local, bindData);
        if (!row.has_value()) {
            break;
        }
        for (idx_t column = 0; column < row->size(); ++column) {
            output.SetValue(column, rowIndex, (*row)[column]);
        }
        ++rowIndex;
    }
    output.SetCardinality(rowIndex);
}

} // namespace

void RegisterXtfValuesFunction(ExtensionLoader &loader) {
    TableFunction function("xtf_values",
                           {LogicalType::VARCHAR, LogicalType::VARCHAR,
                            LogicalType::VARCHAR,
                            LogicalType::LIST(LogicalType::VARCHAR)},
                           XtfValuesFunction, BindXtfValues,
                           InitXtfValuesGlobal, InitXtfValuesLocal);
    function.named_parameters["tid"] = LogicalType::VARCHAR;
    function.named_parameters["bid"] = LogicalType::VARCHAR;
    loader.RegisterFunction(function);
}

} // namespace duckdb
