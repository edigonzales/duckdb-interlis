#pragma once

#include "duckdb/main/extension.hpp"

namespace duckdb {

class InterlisExtension final : public Extension {
public:
    void Load(ExtensionLoader &loader) override;
    std::string Name() override;
    std::string Version() const override;
};

void RegisterVersionFunctions(ExtensionLoader &loader);
void RegisterModelFunctions(ExtensionLoader &loader);
void RegisterXtfScanFunction(ExtensionLoader &loader);
void RegisterXtfValuesFunction(ExtensionLoader &loader);
void RegisterXtfSetFunction(ExtensionLoader &loader);

} // namespace duckdb
