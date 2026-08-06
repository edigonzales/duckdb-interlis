#include "compiled_model.hpp"

#include "duckdb/common/exception.hpp"

#include <algorithm>
#include <sstream>

namespace interlis {

namespace {

ilic::ModelCompilationInput MakeCompilationInput(std::vector<ilic::ModelSource> sources) {
    ilic::ModelCompilationInput input;
    input.request.roots.reserve(sources.size());
    for (const auto &source : sources) {
        input.request.roots.push_back(source.uri);
    }
    input.sources = std::move(sources);
    return input;
}

const metamodel::MetaModelStore &RequireSuccessfulModels(const ilic::ModelCompilation &compilation) {
    if (!compilation.success()) {
        throw duckdb::InvalidInputException("%s", FormatCompilationFailure(compilation.result()));
    }
    return compilation.models();
}

std::string DiagnosticLocation(const ilic::Diagnostic &diagnostic) {
    std::string uri = diagnostic.range.uri;
    if (uri.empty()) {
        uri = "<model>";
    }
    std::ostringstream location;
    location << uri;
    if (diagnostic.range.valid) {
        location << ":" << diagnostic.range.start.line + 1 << ":" << diagnostic.range.start.character + 1;
    }
    return location.str();
}

} // namespace

CompiledModel::CompiledModel(std::vector<ilic::ModelSource> sources)
    : compilation_(MakeCompilationInput(std::move(sources))), index_(RequireSuccessfulModels(compilation_)) {
}

const ilic::CompilationResult &CompiledModel::compilationResult() const noexcept {
    return compilation_.result();
}

const metamodel::MetaModelStore &CompiledModel::models() const {
    return compilation_.models();
}

const iox::ilic::IlicModelIndex &CompiledModel::index() const noexcept {
    return index_;
}

std::string FormatCompilationFailure(const ilic::CompilationResult &result) {
    constexpr std::size_t maxDetails = 50;
    std::ostringstream message;
    message << "INTERLIS model compilation failed:\n";

    std::size_t detailCount = 0;
    for (const auto &diagnostic : result.diagnostics) {
        if (detailCount == maxDetails) {
            break;
        }
        message << "- " << DiagnosticLocation(diagnostic);
        if (!diagnostic.code.empty()) {
            message << " " << diagnostic.code;
        }
        if (!diagnostic.message.empty()) {
            message << " " << diagnostic.message;
        }
        message << "\n";
        ++detailCount;
    }
    for (const auto &missingModel : result.missingModels) {
        if (detailCount == maxDetails) {
            break;
        }
        message << "- missing model: " << missingModel << "\n";
        ++detailCount;
    }
    if (detailCount == 0) {
        message << "- compiler returned no diagnostic details\n";
    }

    const auto totalDetails = result.diagnostics.size() + result.missingModels.size();
    if (totalDetails > maxDetails) {
        message << "- ... " << (totalDetails - maxDetails) << " additional detail(s) omitted\n";
    }
    message << "Total diagnostics: " << totalDetails;
    return message.str();
}

} // namespace interlis
