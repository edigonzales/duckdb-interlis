#define DUCKDB_EXTENSION_MAIN

#include "interlis_extension.hpp"

#include "duckdb/main/extension/extension_loader.hpp"

namespace duckdb {

static void LoadInternal(ExtensionLoader &loader) {
    RegisterVersionFunctions(loader);
    RegisterModelFunctions(loader);
    RegisterXtfScanFunction(loader);
    RegisterXtfValuesFunction(loader);
    RegisterXtfSetFunction(loader);
}

void InterlisExtension::Load(ExtensionLoader &loader) {
    LoadInternal(loader);
}

std::string InterlisExtension::Name() {
    return "interlis";
}

std::string InterlisExtension::Version() const {
#ifdef INTERLIS_EXTENSION_VERSION
    return INTERLIS_EXTENSION_VERSION;
#else
    return "0.2.0";
#endif
}

} // namespace duckdb

extern "C" {

DUCKDB_CPP_EXTENSION_ENTRY(interlis, loader) {
    duckdb::LoadInternal(loader);
}

} // extern "C"
