#pragma once

#include "ilic/ModelCompilation.h"
#include "iox/ilic/IlicModelIndex.h"

#include <string>
#include <vector>

namespace interlis {

class CompiledModel final {
public:
    explicit CompiledModel(std::vector<ilic::ModelSource> sources);

    CompiledModel(const CompiledModel &) = delete;
    CompiledModel &operator=(const CompiledModel &) = delete;

    const ilic::CompilationResult &compilationResult() const noexcept;
    const metamodel::MetaModelStore &models() const;
    const iox::ilic::IlicModelIndex &index() const noexcept;

private:
    ilic::ModelCompilation compilation_;
    iox::ilic::IlicModelIndex index_;
};

std::string FormatCompilationFailure(const ilic::CompilationResult &result);

} // namespace interlis
