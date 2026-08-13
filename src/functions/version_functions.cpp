#include "interlis_extension.hpp"

#include "duckdb/function/scalar_function.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/database.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include <iterator>
#include <string>

namespace duckdb {

namespace {

static std::string ComponentVersion() {
    return std::string("duckdb-interlis/") + INTERLIS_EXTENSION_VERSION + " ilic/" + INTERLIS_ILIC_VERSION +
           " iox-cpp/" + INTERLIS_IOX_VERSION + " geos/" + INTERLIS_GEOS_VERSION + " duckdb/" +
           DuckDB::LibraryVersion();
}

static void InterlisVersionFunction(DataChunk &, ExpressionState &, Vector &result) {
    result.SetVectorType(VectorType::CONSTANT_VECTOR);
    result.SetValue(0, Value(ComponentVersion()));
}

struct ComponentGlobalState final : GlobalTableFunctionState {
    idx_t offset = 0;
};

struct Component final {
    const char *name;
    const char *version;
    const char *revision;
};

static constexpr Component kComponents[] = {
    {"duckdb-interlis", INTERLIS_EXTENSION_VERSION, "working-tree"},
    {"ilic", INTERLIS_ILIC_VERSION, ""},
    {"iox-cpp", INTERLIS_IOX_VERSION, ""},
    {"geos", INTERLIS_GEOS_VERSION, ""},
};

static unique_ptr<FunctionData> BindComponents(ClientContext &, TableFunctionBindInput &,
                                                vector<LogicalType> &returnTypes, vector<string> &names) {
    returnTypes = {LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR};
    names = {"component", "version", "revision"};
    return make_uniq<TableFunctionData>();
}

static unique_ptr<GlobalTableFunctionState> InitComponents(ClientContext &, TableFunctionInitInput &) {
    return make_uniq<ComponentGlobalState>();
}

static void ComponentsFunction(ClientContext &, TableFunctionInput &input, DataChunk &output) {
    auto &state = input.global_state->Cast<ComponentGlobalState>();
    constexpr idx_t static_component_count = std::size(kComponents);
    constexpr idx_t total_component_count = static_component_count + 1;
    idx_t count = 0;
    while (state.offset < total_component_count && count < STANDARD_VECTOR_SIZE) {
        if (state.offset < static_component_count) {
            const auto &component = kComponents[state.offset];
            output.SetValue(0, count, Value(component.name));
            output.SetValue(1, count, Value(component.version));
            output.SetValue(2, count, Value(component.revision));
        } else {
            output.SetValue(0, count, Value("duckdb"));
            output.SetValue(1, count, Value(DuckDB::LibraryVersion()));
            output.SetValue(2, count, Value(DuckDB::SourceID()));
        }
        ++state.offset;
        ++count;
    }
    output.SetCardinality(count);
}

} // namespace

void RegisterVersionFunctions(ExtensionLoader &loader) {
    loader.RegisterFunction(
        ScalarFunction("interlis_version", {}, LogicalType::VARCHAR, InterlisVersionFunction));
    loader.RegisterFunction(TableFunction("interlis_components", {}, ComponentsFunction, BindComponents,
                                          InitComponents));
}

} // namespace duckdb
