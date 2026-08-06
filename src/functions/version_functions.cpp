#include "interlis_extension.hpp"

#include "duckdb/function/scalar_function.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

namespace duckdb {

namespace {

static constexpr const char *ComponentVersion() {
    return "duckdb-interlis/" INTERLIS_EXTENSION_VERSION " ilic/" INTERLIS_ILIC_VERSION
           " iox-cpp/" INTERLIS_IOX_VERSION " geos/" INTERLIS_GEOS_VERSION " duckdb/"
           INTERLIS_DUCKDB_VERSION;
}

static void InterlisVersionFunction(DataChunk &, ExpressionState &, Vector &result) {
    result.SetVectorType(VectorType::CONSTANT_VECTOR);
    result.SetValue(0, Value(ComponentVersion()));
}

} // namespace

void RegisterVersionFunctions(ExtensionLoader &loader) {
    loader.RegisterFunction(
        ScalarFunction("interlis_version", {}, LogicalType::VARCHAR, InterlisVersionFunction));
}

} // namespace duckdb
