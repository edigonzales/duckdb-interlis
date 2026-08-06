#include "interlis_extension.hpp"

#include "model/compiled_model.hpp"
#include "model/model_source_resolver.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/common/file_system.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include <algorithm>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace duckdb {

namespace {

using Row = std::vector<Value>;

struct ModelFunctionData final : TableFunctionData {
    std::shared_ptr<interlis::CompiledModel> model;
    std::vector<Row> rows;

    unique_ptr<FunctionData> Copy() const override {
        auto result = make_uniq<ModelFunctionData>();
        result->model = model;
        result->rows = rows;
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

struct ModelFunctionGlobalState final : GlobalTableFunctionState {
    idx_t offset = 0;
};

std::vector<std::string> ReadModelSources(const Value &value) {
    if (value.IsNull() || value.type().id() != LogicalTypeId::LIST) {
        throw InvalidInputException("model_sources must be a non-empty VARCHAR[]");
    }
    std::vector<std::string> result;
    for (const auto &child : ListValue::GetChildren(value)) {
        if (child.IsNull()) {
            throw InvalidInputException("model_sources must not contain NULL");
        }
        result.push_back(StringValue::Get(child));
    }
    if (result.empty()) {
        throw InvalidInputException("model_sources must be a non-empty VARCHAR[]");
    }
    return result;
}

std::shared_ptr<interlis::CompiledModel> CompileModel(ClientContext &context, const Value &sourcesValue) {
    auto sources = ReadModelSources(sourcesValue);
    auto &fileSystem = FileSystem::GetFileSystem(context);
    auto resolved = interlis::ModelSourceResolver::Resolve(fileSystem, sources);
    return std::make_shared<interlis::CompiledModel>(std::move(resolved));
}

std::optional<std::string> NamedString(const TableFunctionBindInput &input, const char *name) {
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

struct ClassRecord final {
    const metamodel::Model *model = nullptr;
    const metamodel::Class *klass = nullptr;
    const metamodel::SubModel *topic = nullptr;
};

void CollectClasses(const metamodel::Package &package, const metamodel::Model &model,
                    const metamodel::SubModel *topic, std::vector<ClassRecord> &result) {
    for (const auto *element : package.Element) {
        if (element == nullptr) {
            continue;
        }
        if (const auto *childTopic = dynamic_cast<const metamodel::SubModel *>(element)) {
            CollectClasses(*childTopic, model, childTopic, result);
            continue;
        }
        if (const auto *klass = dynamic_cast<const metamodel::Class *>(element)) {
            result.push_back({&model, klass, topic});
        }
        if (const auto *childPackage = dynamic_cast<const metamodel::Package *>(element)) {
            CollectClasses(*childPackage, model, topic, result);
        }
    }
}

std::vector<ClassRecord> AllClasses(const metamodel::MetaModelStore &store) {
    std::vector<ClassRecord> result;
    for (const auto *model : store.models()) {
        if (model == nullptr || model->Name == "INTERLIS") {
            continue;
        }
        CollectClasses(*model, *model, nullptr, result);
    }
    return result;
}

std::string ClassKind(const metamodel::Class &klass) {
    if (dynamic_cast<const metamodel::View *>(&klass) != nullptr) {
        return "VIEW";
    }
    switch (klass.Kind) {
    case metamodel::Class::Structure:
        return "STRUCTURE";
    case metamodel::Class::Association:
        return "ASSOCIATION";
    case metamodel::Class::ClassVal:
    default:
        return "CLASS";
    }
}

std::string PropertyName(const iox::ilic::PropertyDescriptor &descriptor) {
    return descriptor.name.hasInterlisName() ? descriptor.name.interlisName() : descriptor.name.xmlName().localName;
}

std::string OptionalIomName(const std::optional<iox::IomName> &name) {
    if (!name.has_value()) {
        return {};
    }
    return name->hasInterlisName() ? name->interlisName() : name->xmlName().localName;
}

std::string DuckDbType(const iox::ilic::PropertyDescriptor &descriptor) {
    if (descriptor.geometry.has_value()) {
        return "GEOMETRY";
    }
    switch (descriptor.valueKind) {
    case iox::ilic::PropertyValueKind::Boolean:
        return "BOOLEAN";
    case iox::ilic::PropertyValueKind::Integer:
        return "BIGINT";
    case iox::ilic::PropertyValueKind::Double:
        return "DOUBLE";
    case iox::ilic::PropertyValueKind::Structure:
        return "UNSUPPORTED_JSON";
    case iox::ilic::PropertyValueKind::String:
    case iox::ilic::PropertyValueKind::Reference:
    case iox::ilic::PropertyValueKind::Geometry:
    case iox::ilic::PropertyValueKind::Unknown:
    default:
        return "VARCHAR";
    }
}

std::string GeometryKind(const iox::geometry::GeometryDescriptor &descriptor) {
    switch (descriptor.kind) {
    case iox::geometry::GeometryKind::Coord:
        return "COORD";
    case iox::geometry::GeometryKind::MultiCoord:
        return "MULTICOORD";
    case iox::geometry::GeometryKind::Polyline:
        return "POLYLINE";
    case iox::geometry::GeometryKind::DirectedPolyline:
        return "DIRECTED_POLYLINE";
    case iox::geometry::GeometryKind::MultiPolyline:
        return "MULTIPOLYLINE";
    case iox::geometry::GeometryKind::DirectedMultiPolyline:
        return "DIRECTED_MULTIPOLYLINE";
    case iox::geometry::GeometryKind::Surface:
        return "SURFACE";
    case iox::geometry::GeometryKind::MultiSurface:
        return "MULTISURFACE";
    case iox::geometry::GeometryKind::Area:
        return "AREA";
    case iox::geometry::GeometryKind::MultiArea:
        return "MULTIAREA";
    }
    return "UNKNOWN";
}

std::vector<const metamodel::MetaElement *> TransferOrder(const metamodel::Class &klass) {
    std::vector<const metamodel::Class *> hierarchy;
    std::vector<const metamodel::MetaElement *> result;
    const auto *current = &klass;
    while (current != nullptr) {
        hierarchy.push_back(current);
        current = dynamic_cast<const metamodel::Class *>(current->Super);
    }
    std::reverse(hierarchy.begin(), hierarchy.end());

    const auto appendUnique = [&](const metamodel::MetaElement *property) {
        if (property == nullptr || std::find(result.begin(), result.end(), property) != result.end()) {
            return;
        }
        result.push_back(property);
    };
    for (const auto *base : hierarchy) {
        if (base->Kind == metamodel::Class::Association) {
            for (const auto *role : base->Role) {
                appendUnique(role);
            }
            for (const auto *role : base->_roleaccess) {
                appendUnique(role);
            }
        }
        for (const auto *attribute : base->ClassAttribute) {
            appendUnique(attribute);
        }
        if (base->Kind != metamodel::Class::Association) {
            for (const auto *role : base->_roleaccess) {
                appendUnique(role);
            }
        }
    }
    return result;
}

bool MatchesClass(const ClassRecord &record, const std::string &filter) {
    if (filter.empty()) {
        return true;
    }
    const auto fqn = ScopedName(*record.klass);
    if (filter == fqn || filter == record.klass->Name) {
        return true;
    }
    return record.topic != nullptr && filter == record.topic->Name + "." + record.klass->Name;
}

void SetRows(ClientContext &, TableFunctionInput &input, DataChunk &output) {
    auto &bindData = input.bind_data->Cast<ModelFunctionData>();
    auto &state = input.global_state->Cast<ModelFunctionGlobalState>();
    idx_t count = 0;
    while (state.offset < bindData.rows.size() && count < STANDARD_VECTOR_SIZE) {
        const auto &row = bindData.rows[state.offset++];
        for (idx_t column = 0; column < row.size(); ++column) {
            output.SetValue(column, count, row[column]);
        }
        ++count;
    }
    output.SetCardinality(count);
}

unique_ptr<GlobalTableFunctionState> InitRows(ClientContext &, TableFunctionInitInput &) {
    return make_uniq<ModelFunctionGlobalState>();
}

unique_ptr<FunctionData> BindModels(ClientContext &context, TableFunctionBindInput &input,
                                    vector<LogicalType> &returnTypes, vector<string> &names) {
    returnTypes = {LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
                   LogicalType::VARCHAR, LogicalType::VARCHAR};
    names = {"name", "version", "issuer", "language", "ili_version"};
    auto data = make_uniq<ModelFunctionData>();
    data->model = CompileModel(context, input.inputs[0]);
    for (const auto *model : data->model->models().models()) {
        if (model == nullptr || model->Name == "INTERLIS") {
            continue;
        }
        data->rows.push_back({Value(model->Name), Value(model->Version), Value(model->At),
                              Value(model->Language), Value(model->iliVersion)});
    }
    return data;
}

unique_ptr<FunctionData> BindClasses(ClientContext &context, TableFunctionBindInput &input,
                                     vector<LogicalType> &returnTypes, vector<string> &names) {
    returnTypes = {LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
                   LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::BOOLEAN,
                   LogicalType::VARCHAR};
    names = {"model_name", "topic_name", "class_name", "class_fqn", "kind",
             "is_abstract", "base_class_fqn"};
    const auto modelFilter = NamedString(input, "model");
    auto data = make_uniq<ModelFunctionData>();
    data->model = CompileModel(context, input.inputs[0]);
    for (const auto &record : AllClasses(data->model->models())) {
        if (modelFilter.has_value() && record.model->Name != *modelFilter) {
            continue;
        }
        data->rows.push_back({Value(record.model->Name), Value(record.topic == nullptr ? "" : record.topic->Name),
                              Value(record.klass->Name), Value(ScopedName(*record.klass)),
                              Value(ClassKind(*record.klass)), Value::BOOLEAN(record.klass->Abstract),
                              record.klass->Super == nullptr ? Value() : Value(ScopedName(*record.klass->Super))});
    }
    return data;
}

template <typename RowBuilder>
unique_ptr<ModelFunctionData> BindProperties(ClientContext &context, TableFunctionBindInput &input,
                                              vector<LogicalType> &returnTypes, vector<string> &names,
                                              RowBuilder &&buildRows) {
    auto data = make_uniq<ModelFunctionData>();
    data->model = CompileModel(context, input.inputs[1]);
    for (const auto &record : AllClasses(data->model->models())) {
        if (!MatchesClass(record, StringValue::Get(input.inputs[0]))) {
            continue;
        }
        buildRows(*data, record);
    }
    return data;
}

unique_ptr<FunctionData> BindPropertiesFunction(ClientContext &context, TableFunctionBindInput &input,
                                                vector<LogicalType> &returnTypes, vector<string> &names) {
    returnTypes = {LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
                   LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
                   LogicalType::BOOLEAN, LogicalType::BIGINT, LogicalType::BIGINT,
                   LogicalType::BOOLEAN, LogicalType::BOOLEAN, LogicalType::VARCHAR};
    names = {"class_fqn", "property_name", "property_fqn", "kind", "interlis_type",
             "duckdb_type", "is_mandatory", "cardinality_min", "cardinality_max",
             "is_transient", "is_embedded", "target_class_fqn"};
    return BindProperties(context, input, returnTypes, names, [](ModelFunctionData &data, const ClassRecord &record) {
        const auto classFqn = ScopedName(*record.klass);
        for (const auto *property : TransferOrder(*record.klass)) {
            const auto descriptor = data.model->index().propertyDescriptor(
                iox::IomName(classFqn), iox::IomName(property->Name), "", iox::XtfVersion::V23);
            if (!descriptor.has_value()) {
                continue;
            }
            data.rows.push_back({Value(classFqn), Value(PropertyName(*descriptor)), Value(descriptor->propertyFqn),
                                 Value(descriptor->kind == iox::ilic::PropertyKind::Role ? "ROLE" : "ATTRIBUTE"),
                                 Value(descriptor->interlisType), Value(DuckDbType(*descriptor)),
                                 Value::BOOLEAN(descriptor->mandatory), Value::BIGINT(descriptor->cardinalityMin),
                                 descriptor->cardinalityMax.has_value() ? Value::BIGINT(*descriptor->cardinalityMax)
                                                                          : Value(),
                                 Value::BOOLEAN(descriptor->transient), Value::BOOLEAN(descriptor->embedded),
                                 descriptor->targetClass.has_value() ? Value(OptionalIomName(descriptor->targetClass))
                                                                    : Value()});
        }
    });
}

unique_ptr<FunctionData> BindGeometryProperties(ClientContext &context, TableFunctionBindInput &input,
                                                vector<LogicalType> &returnTypes, vector<string> &names) {
    returnTypes = {LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
                   LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::INTEGER,
                   LogicalType::VARCHAR, LogicalType::DOUBLE, LogicalType::LIST(LogicalType::VARCHAR),
                   LogicalType::BOOLEAN, LogicalType::BOOLEAN, LogicalType::BOOLEAN, LogicalType::BOOLEAN};
    names = {"class_fqn", "property_name", "property_fqn", "geometry_kind",
             "coordinate_domain_fqn", "dimension", "max_overlap_lexical", "max_overlap",
             "line_forms", "has_straights", "has_arcs", "has_custom_line_forms", "has_line_attributes"};
    return BindProperties(context, input, returnTypes, names, [](ModelFunctionData &data, const ClassRecord &record) {
        const auto classFqn = ScopedName(*record.klass);
        for (const auto *property : TransferOrder(*record.klass)) {
            const auto descriptor = data.model->index().propertyDescriptor(
                iox::IomName(classFqn), iox::IomName(property->Name), "", iox::XtfVersion::V23);
            if (!descriptor.has_value() || !descriptor->geometry.has_value()) {
                continue;
            }
            const auto &geometry = *descriptor->geometry;
            std::vector<Value> lineForms;
            for (const auto &lineForm : geometry.lineForms) {
                lineForms.emplace_back(lineForm.name);
            }
            data.rows.push_back({Value(classFqn), Value(PropertyName(*descriptor)), Value(descriptor->propertyFqn),
                                 Value(GeometryKind(geometry)), Value(geometry.coordinateDomainFqn),
                                 Value::INTEGER(static_cast<int32_t>(geometry.dimension)),
                                 geometry.maxOverlapLexical.has_value() ? Value(*geometry.maxOverlapLexical) : Value(),
                                 geometry.maxOverlap.has_value() ? Value::DOUBLE(*geometry.maxOverlap) : Value(),
                                 Value::LIST(LogicalType::VARCHAR, std::move(lineForms)),
                                 Value::BOOLEAN(geometry.hasStraights), Value::BOOLEAN(geometry.hasArcs),
                                 Value::BOOLEAN(geometry.hasCustomLineForms), Value::BOOLEAN(geometry.hasLineAttributes)});
        }
    });
}

} // namespace

void RegisterModelFunctions(ExtensionLoader &loader) {
    loader.RegisterFunction(TableFunction("ili_models", {LogicalType::LIST(LogicalType::VARCHAR)}, SetRows,
                                          BindModels, InitRows));

    TableFunction classes("ili_classes", {LogicalType::LIST(LogicalType::VARCHAR)}, SetRows, BindClasses, InitRows);
    classes.named_parameters["model"] = LogicalType::VARCHAR;
    loader.RegisterFunction(classes);

    loader.RegisterFunction(TableFunction("ili_properties",
                                          {LogicalType::VARCHAR, LogicalType::LIST(LogicalType::VARCHAR)}, SetRows,
                                          BindPropertiesFunction, InitRows));
    loader.RegisterFunction(TableFunction("ili_geometry_properties",
                                          {LogicalType::VARCHAR, LogicalType::LIST(LogicalType::VARCHAR)}, SetRows,
                                          BindGeometryProperties, InitRows));
}

} // namespace duckdb
