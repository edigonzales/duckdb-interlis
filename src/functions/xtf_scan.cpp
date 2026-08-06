#include "interlis_extension.hpp"

#include "model/compiled_model.hpp"
#include "model/model_source_resolver.hpp"
#include "xtf/xtf_stream.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/common/operator/double_cast_operator.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include "iox/Events.h"
#include "iox/geometry/IomGeometryConverter.h"
#include "iox/ilic/IlicModelIndex.h"

#include <algorithm>
#include <charconv>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <system_error>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace duckdb {

namespace {

struct ScanProperty final {
    iox::ilic::PropertyDescriptor descriptor;
    std::string name;
    LogicalType type;
};

struct XtfScanBindData final : TableFunctionData {
    std::string path;
    std::string className;
    std::shared_ptr<interlis::CompiledModel> model;
    std::vector<ScanProperty> properties;
    std::vector<LogicalType> resultTypes;
    std::vector<std::string> resultNames;
    std::string geometryErrors = "error";
    std::optional<double> arcToleranceOverride;

    unique_ptr<FunctionData> Copy() const override {
        auto result = make_uniq<XtfScanBindData>();
        result->path = path;
        result->className = className;
        result->model = model;
        result->properties = properties;
        result->resultTypes = resultTypes;
        result->resultNames = resultNames;
        result->geometryErrors = geometryErrors;
        result->arcToleranceOverride = arcToleranceOverride;
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

struct XtfScanGlobalState final : GlobalTableFunctionState {
    idx_t MaxThreads() const override {
        return 1;
    }
};

struct XtfScanLocalState final : LocalTableFunctionState {
    XtfStreamState stream;
    unique_ptr<iox::geometry::IomGeometryConverter> geometryConverter;
};

std::optional<std::string> NamedString(const TableFunctionBindInput &input, const char *name) {
    const auto found = input.named_parameters.find(name);
    if (found == input.named_parameters.end() || found->second.IsNull()) {
        return std::nullopt;
    }
    return StringValue::Get(found->second);
}

std::optional<double> NamedDouble(const TableFunctionBindInput &input, const char *name) {
    const auto found = input.named_parameters.find(name);
    if (found == input.named_parameters.end() || found->second.IsNull()) {
        return std::nullopt;
    }
    return DoubleValue::Get(found->second);
}

std::string NameOf(const iox::IomName &name) {
    if (name.hasInterlisName()) {
        return name.interlisName();
    }
    return name.hasXmlName() ? name.xmlName().localName : std::string();
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

bool HasClassInPackage(const metamodel::Package &package, const std::string &className) {
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

bool HasClass(const metamodel::MetaModelStore &store, const std::string &className) {
    for (const auto *model : store.models()) {
        if (model != nullptr && HasClassInPackage(*model, className)) {
            return true;
        }
    }
    return false;
}

LogicalType PropertyType(const iox::ilic::PropertyDescriptor &descriptor) {
    if (descriptor.geometry.has_value()) {
        return LogicalType::GEOMETRY();
    }
    switch (descriptor.valueKind) {
    case iox::ilic::PropertyValueKind::Boolean:
        return LogicalType::BOOLEAN;
    case iox::ilic::PropertyValueKind::Integer:
        return LogicalType::BIGINT;
    case iox::ilic::PropertyValueKind::Double:
        return LogicalType::DOUBLE;
    case iox::ilic::PropertyValueKind::String:
    case iox::ilic::PropertyValueKind::Reference:
    default:
        return LogicalType::VARCHAR;
    }
}

bool IsSupported(const iox::ilic::PropertyDescriptor &descriptor) {
    if (!descriptor.cardinalityMax.has_value() || *descriptor.cardinalityMax != 1) {
        return false;
    }
    switch (descriptor.valueKind) {
    case iox::ilic::PropertyValueKind::String:
    case iox::ilic::PropertyValueKind::Boolean:
    case iox::ilic::PropertyValueKind::Integer:
    case iox::ilic::PropertyValueKind::Double:
    case iox::ilic::PropertyValueKind::Reference:
        return true;
    case iox::ilic::PropertyValueKind::Geometry:
        return descriptor.geometry.has_value();
    case iox::ilic::PropertyValueKind::Structure:
    case iox::ilic::PropertyValueKind::Unknown:
    default:
        return false;
    }
}

std::string OperationName(iox::ObjectOperation operation) {
    switch (operation) {
    case iox::ObjectOperation::Insert:
        return "INSERT";
    case iox::ObjectOperation::Update:
        return "UPDATE";
    case iox::ObjectOperation::Delete:
        return "DELETE";
    case iox::ObjectOperation::None:
    default:
        return "NONE";
    }
}

std::string JsonEscape(std::string_view value) {
    std::string result;
    result.reserve(value.size() + 2);
    for (const auto character : value) {
        switch (character) {
        case '\\':
            result += "\\\\";
            break;
        case '"':
            result += "\\\"";
            break;
        case '\n':
            result += "\\n";
            break;
        case '\r':
            result += "\\r";
            break;
        case '\t':
            result += "\\t";
            break;
        default:
            result.push_back(character);
            break;
        }
    }
    return result;
}

void AppendJsonValue(std::ostringstream &output, const iox::IomValue &value);

void AppendJsonObject(std::ostringstream &output, const iox::IomObject &object) {
    output << "{\"_tag\":\"" << JsonEscape(NameOf(object.tag())) << "\"";
    if (object.oid().has_value()) {
        output << ",\"_tid\":\"" << JsonEscape(*object.oid()) << "\"";
    }
    if (object.isReference()) {
        if (object.reference().targetOid.has_value()) {
            output << ",\"ref\":\"" << JsonEscape(*object.reference().targetOid) << "\"";
        }
        if (object.reference().targetBasketId.has_value()) {
            output << ",\"bid\":\"" << JsonEscape(*object.reference().targetBasketId) << "\"";
        }
        if (object.reference().orderPosition.has_value()) {
            output << ",\"order\":" << *object.reference().orderPosition;
        }
    }
    output << ",\"_attrs\":{";
    for (std::size_t attributeIndex = 0; attributeIndex < object.attributeCount(); ++attributeIndex) {
        if (attributeIndex != 0) {
            output << ',';
        }
        const auto &name = object.attributeName(attributeIndex);
        output << "\"" << JsonEscape(NameOf(name)) << "\":[";
        const auto count = object.valueCount(name.interlisName());
        for (std::size_t valueIndex = 0; valueIndex < count; ++valueIndex) {
            if (valueIndex != 0) {
                output << ',';
            }
            AppendJsonValue(output, object.value(name.interlisName(), valueIndex));
        }
        output << ']';
    }
    output << "}}";
}

void AppendJsonValue(std::ostringstream &output, const iox::IomValue &value) {
    if (value.isPrimitive()) {
        output << "\"" << JsonEscape(value.primitive()) << "\"";
    } else {
        AppendJsonObject(output, value.object());
    }
}

std::string UnsupportedJson(const iox::IomObject &object,
                            const std::unordered_set<std::string> &emittedProperties,
                            const std::unordered_map<std::string, std::string> &diagnostics) {
    std::ostringstream output;
    output << '{';
    bool first = true;
    for (std::size_t attributeIndex = 0; attributeIndex < object.attributeCount(); ++attributeIndex) {
        const auto &name = object.attributeName(attributeIndex);
        const auto propertyName = NameOf(name);
        if (emittedProperties.find(propertyName) != emittedProperties.end()) {
            continue;
        }
        if (!first) {
            output << ',';
        }
        first = false;
        output << "\"" << JsonEscape(propertyName) << "\":[";
        const auto diagnostic = diagnostics.find(propertyName);
        if (diagnostic != diagnostics.end()) {
            output << "{\"_error\":\"" << JsonEscape(diagnostic->second) << "\"}";
        } else {
            const auto count = object.valueCount(name.interlisName());
            for (std::size_t valueIndex = 0; valueIndex < count; ++valueIndex) {
                if (valueIndex != 0) {
                    output << ',';
                }
                AppendJsonValue(output, object.value(name.interlisName(), valueIndex));
            }
        }
        output << ']';
    }
    output << '}';
    return output.str();
}

std::string ReadContext(const XtfScanBindData &bindData, const iox::IomObject *object,
                        std::string_view property = {}, std::string_view bid = {}) {
    std::ostringstream message;
    message << "XTF read failed: " << bindData.path;
    if (object != nullptr) {
        if (object->oid().has_value()) {
            message << " TID=" << *object->oid();
        }
        if (!bid.empty()) {
            message << " BID=" << bid;
        }
        message << " class=" << NameOf(object->tag());
        if (!property.empty()) {
            message << " property=" << property;
        }
        const auto &location = object->sourceLocation();
        if (!location.empty()) {
            message << " at " << location.sourceName << ':' << location.line << ':' << location.column;
        }
    }
    return message.str();
}

Value ParsePrimitive(const ScanProperty &property, std::string_view lexical,
                     const XtfScanBindData &bindData, const iox::IomObject &object,
                     std::string_view bid) {
    switch (property.descriptor.valueKind) {
    case iox::ilic::PropertyValueKind::Boolean:
        if (lexical == "true") {
            return Value::BOOLEAN(true);
        }
        if (lexical == "false") {
            return Value::BOOLEAN(false);
        }
            throw InvalidInputException("%s invalid boolean lexical value '%s'",
                                        ReadContext(bindData, &object, property.name, bid),
                                    std::string(lexical));
    case iox::ilic::PropertyValueKind::Integer: {
        int64_t value = 0;
        const auto parsed = std::from_chars(lexical.data(), lexical.data() + lexical.size(), value);
        if (parsed.ec != std::errc() || parsed.ptr != lexical.data() + lexical.size()) {
            throw InvalidInputException("%s invalid integer lexical value '%s'",
                                        ReadContext(bindData, &object, property.name, bid),
                                        std::string(lexical));
        }
        return Value::BIGINT(value);
    }
    case iox::ilic::PropertyValueKind::Double: {
        double value = 0;
        if (!TryDoubleCast(lexical.data(), lexical.size(), value, true)) {
            throw InvalidInputException("%s invalid double lexical value '%s'",
                                        ReadContext(bindData, &object, property.name, bid),
                                        std::string(lexical));
        }
        return Value::DOUBLE(value);
    }
    case iox::ilic::PropertyValueKind::String:
    default:
        return Value(std::string(lexical));
    }
}

std::string ReferenceLexical(const iox::IomValue &value) {
    if (value.isPrimitive()) {
        return value.primitive();
    }
    const auto &object = value.object();
    if (object.reference().targetOid.has_value()) {
        return *object.reference().targetOid;
    }
    if (object.oid().has_value()) {
        return *object.oid();
    }
    return {};
}

Value GeometryValue(const ScanProperty &property, const iox::IomValue &value,
                    XtfScanLocalState &local, const XtfScanBindData &bindData,
                    const iox::IomObject &object) {
    if (!value.isObject()) {
        throw InvalidInputException("%s geometry value is not an IOM object",
                                    ReadContext(bindData, &object, property.name, local.stream.currentBid));
    }
    const auto result = local.geometryConverter->toWkb(value.object(), *property.descriptor.geometry);
    return Value::GEOMETRY(result.wkb.data(), result.wkb.size());
}

Value PropertyValue(const ScanProperty &property, const iox::IomObject &object,
                    XtfScanLocalState &local, const XtfScanBindData &bindData) {
    const auto count = object.valueCount(property.name);
    if (count == 0) {
        return Value();
    }
    if (count != 1) {
        throw InvalidInputException("%s expected one value but found %llu",
                                    ReadContext(bindData, &object, property.name, local.stream.currentBid),
                                    static_cast<unsigned long long>(count));
    }
    const auto &value = object.value(property.name, 0);
    if (property.descriptor.valueKind == iox::ilic::PropertyValueKind::Reference) {
        return Value(ReferenceLexical(value));
    }
    if (property.descriptor.geometry.has_value()) {
        return GeometryValue(property, value, local, bindData, object);
    }
    if (!value.isPrimitive()) {
        throw InvalidInputException("%s expected a primitive value",
                                    ReadContext(bindData, &object, property.name, local.stream.currentBid));
    }
    return ParsePrimitive(property, value.primitive(), bindData, object, local.stream.currentBid);
}

std::optional<std::vector<Value>> NextMatchingRow(XtfScanLocalState &local,
                                                  const XtfScanBindData &bindData) {
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
        if (NameOf(object.tag()) != bindData.className) {
            continue;
        }

        std::vector<Value> row;
        row.reserve(6 + bindData.properties.size());
        row.emplace_back(local.stream.currentBid);
        row.emplace_back(object.oid().has_value() ? Value(*object.oid()) : Value());
        row.emplace_back(NameOf(object.tag()));
        row.emplace_back(OperationName(object.operation()));
        std::unordered_set<std::string> emittedProperties;
        std::unordered_map<std::string, std::string> unsupportedDiagnostics;
        for (const auto &property : bindData.properties) {
            if (!IsSupported(property.descriptor)) {
                row.emplace_back(Value());
                continue;
            }
            const auto count = object.valueCount(property.name);
            if (count == 0) {
                row.emplace_back(Value());
                continue;
            }
            if (count != 1) {
                throw InvalidInputException("%s expected one value but found %llu",
                                            ReadContext(bindData, &object, property.name, local.stream.currentBid),
                                            static_cast<unsigned long long>(count));
            }
            if (property.descriptor.valueKind == iox::ilic::PropertyValueKind::Reference) {
                const auto &reference = object.value(property.name, 0);
                if (reference.isObject() && reference.object().reference().targetBasketId.has_value()) {
                    row.emplace_back(Value());
                    continue;
                }
            }
            try {
                row.emplace_back(PropertyValue(property, object, local, bindData));
                emittedProperties.insert(property.name);
            } catch (const std::exception &error) {
                if (property.descriptor.geometry.has_value() && bindData.geometryErrors == "null") {
                    row.emplace_back(Value());
                    unsupportedDiagnostics.emplace(property.name, error.what());
                    continue;
                }
                throw InvalidInputException("%s: %s",
                                            ReadContext(bindData, &object, property.name, local.stream.currentBid),
                                            error.what());
            }
        }
        row.emplace_back(UnsupportedJson(object, emittedProperties, unsupportedDiagnostics));
        return row;
    }
}

unique_ptr<FunctionData> BindXtfScan(ClientContext &context, TableFunctionBindInput &input,
                                     vector<LogicalType> &returnTypes, vector<string> &names) {
    if (input.inputs.size() != 3) {
        throw InvalidInputException("xtf_scan requires path, class_name, and model_sources");
    }
    auto data = make_uniq<XtfScanBindData>();
    data->path = StringValue::Get(input.inputs[0]);
    data->className = StringValue::Get(input.inputs[1]);
    if (data->path.empty() || data->className.empty()) {
        throw InvalidInputException("xtf_scan path and class_name must not be empty");
    }
    const auto firstClassSeparator = data->className.find('.');
    const auto secondClassSeparator = firstClassSeparator == std::string::npos
                                          ? std::string::npos
                                          : data->className.find('.', firstClassSeparator + 1);
    if (firstClassSeparator == std::string::npos || secondClassSeparator == std::string::npos) {
        throw InvalidInputException("xtf_scan class_name must be fully qualified as Model.Topic.Class: %s",
                                    data->className);
    }
    const auto geometryErrors = NamedString(input, "geometry_errors");
    if (geometryErrors.has_value()) {
        data->geometryErrors = *geometryErrors;
    }
    if (data->geometryErrors != "error" && data->geometryErrors != "null") {
        throw InvalidInputException("xtf_scan geometry_errors must be 'error' or 'null'");
    }
    data->arcToleranceOverride = NamedDouble(input, "arc_tolerance_override");
    if (data->arcToleranceOverride.has_value() && *data->arcToleranceOverride <= 0) {
        throw InvalidInputException("xtf_scan arc_tolerance_override must be positive");
    }

    const auto sourcesValue = input.inputs[2];
    if (sourcesValue.IsNull() || sourcesValue.type().id() != LogicalTypeId::LIST) {
        throw InvalidInputException("xtf_scan model_sources must be a non-empty VARCHAR[]");
    }
    std::vector<std::string> sourceSpecs;
    for (const auto &source : ListValue::GetChildren(sourcesValue)) {
        if (source.IsNull()) {
            throw InvalidInputException("xtf_scan model_sources must not contain NULL");
        }
        sourceSpecs.emplace_back(StringValue::Get(source));
    }
    if (sourceSpecs.empty()) {
        throw InvalidInputException("xtf_scan model_sources must be a non-empty VARCHAR[]");
    }
    auto &fileSystem = FileSystem::GetFileSystem(context);
    auto resolved = interlis::ModelSourceResolver::Resolve(fileSystem, sourceSpecs);
    data->model = std::make_shared<interlis::CompiledModel>(std::move(resolved));
    if (!HasClass(data->model->models(), data->className)) {
        throw InvalidInputException("xtf_scan class_name is not a fully qualified model class: %s", data->className);
    }

    const auto modelSeparator = data->className.find('.');
    const auto targetModel = data->className.substr(0, modelSeparator);
    const auto descriptors = data->model->index().transferPropertyDescriptors(
        iox::IomName(data->className), targetModel, iox::XtfVersion::V23);
    std::unordered_set<std::string> reserved{"_bid", "_tid", "_class", "_operation", "_unsupported_json"};
    data->resultTypes = {LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
                         LogicalType::VARCHAR};
    data->resultNames = {"_bid", "_tid", "_class", "_operation"};
    for (const auto &descriptor : descriptors) {
        const auto propertyName = NameOf(descriptor.name);
        if (propertyName.empty() || reserved.find(propertyName) != reserved.end()) {
            throw InvalidInputException("xtf_scan property name collides with a reserved result column: %s", propertyName);
        }
        if (!IsSupported(descriptor)) {
            continue;
        }
        data->properties.push_back({descriptor, propertyName, PropertyType(descriptor)});
        data->resultTypes.push_back(PropertyType(descriptor));
        data->resultNames.push_back(propertyName);
    }
    data->resultTypes.push_back(LogicalType::VARCHAR);
    data->resultNames.push_back("_unsupported_json");
    returnTypes.clear();
    returnTypes.insert(returnTypes.end(), data->resultTypes.begin(), data->resultTypes.end());
    names.clear();
    names.insert(names.end(), data->resultNames.begin(), data->resultNames.end());
    return data;
}

unique_ptr<GlobalTableFunctionState> InitXtfScanGlobal(ClientContext &, TableFunctionInitInput &) {
    return make_uniq<XtfScanGlobalState>();
}

unique_ptr<LocalTableFunctionState> InitXtfScanLocal(ExecutionContext &context, TableFunctionInitInput &input,
                                                     GlobalTableFunctionState *) {
    auto &data = input.bind_data->Cast<XtfScanBindData>();
    auto result = make_uniq<XtfScanLocalState>();
    result->stream = OpenXtfStream(context.client, data.model->models(), data.path);
    iox::geometry::GeometryConversionOptions geometryOptions;
    geometryOptions.arcToleranceOverride = data.arcToleranceOverride;
    result->geometryConverter = make_uniq<iox::geometry::IomGeometryConverter>(geometryOptions);
    return result;
}

void XtfScanFunction(ClientContext &, TableFunctionInput &input, DataChunk &output) {
    auto &data = input.bind_data->Cast<XtfScanBindData>();
    auto &local = input.local_state->Cast<XtfScanLocalState>();
    idx_t rowIndex = 0;
    while (rowIndex < STANDARD_VECTOR_SIZE) {
        const auto row = NextMatchingRow(local, data);
        if (!row.has_value()) {
            break;
        }
        for (idx_t columnIndex = 0; columnIndex < row->size(); ++columnIndex) {
            output.SetValue(columnIndex, rowIndex, (*row)[columnIndex]);
        }
        ++rowIndex;
    }
    output.SetCardinality(rowIndex);
}

} // namespace

void RegisterXtfScanFunction(ExtensionLoader &loader) {
    TableFunction function("xtf_scan", {LogicalType::VARCHAR, LogicalType::VARCHAR,
                                         LogicalType::LIST(LogicalType::VARCHAR)},
                           XtfScanFunction, BindXtfScan, InitXtfScanGlobal, InitXtfScanLocal);
    function.named_parameters["geometry_errors"] = LogicalType::VARCHAR;
    function.named_parameters["arc_tolerance_override"] = LogicalType::DOUBLE;
    loader.RegisterFunction(function);
}

} // namespace duckdb
