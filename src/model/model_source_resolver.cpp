#include "model_source_resolver.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/common/file_open_flags.hpp"
#include "duckdb/common/file_system.hpp"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <limits>

namespace interlis {

namespace {

bool HasIliExtension(duckdb::FileSystem &fileSystem, const std::string &path) {
    auto extension = fileSystem.ExtractExtension(path);
    std::transform(extension.begin(), extension.end(), extension.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return extension == "ili";
}

std::string ListedPath(duckdb::FileSystem &fileSystem, const std::string &directory,
                      const std::string &listedPath) {
    if (fileSystem.IsPathAbsolute(listedPath)) {
        return listedPath;
    }
    return fileSystem.JoinPath(directory, listedPath);
}

} // namespace

std::vector<ilic::ModelSource> ModelSourceResolver::Resolve(
    duckdb::FileSystem &fileSystem, const std::vector<std::string> &sourceSpecs) {
    if (sourceSpecs.empty()) {
        throw duckdb::InvalidInputException("At least one INTERLIS model source is required");
    }

    std::vector<ilic::ModelSource> sources;
    std::vector<std::string> normalizedPaths;
    for (const auto &sourceSpec : sourceSpecs) {
        if (sourceSpec.empty()) {
            throw duckdb::InvalidInputException("INTERLIS model source must not be empty");
        }
        if (IsRemoteUri(sourceSpec)) {
            throw duckdb::InvalidInputException("Remote model sources are not supported by the native MVP");
        }
        if (fileSystem.DirectoryExists(sourceSpec)) {
            AddDirectory(fileSystem, sourceSpec, sources, normalizedPaths);
        } else {
            AddFile(fileSystem, sourceSpec, sources, normalizedPaths);
        }
    }
    if (sources.empty()) {
        throw duckdb::InvalidInputException("No .ili model sources were found");
    }
    return sources;
}

void ModelSourceResolver::AddFile(duckdb::FileSystem &fileSystem, const std::string &path,
                                  std::vector<ilic::ModelSource> &sources,
                                  std::vector<std::string> &normalizedPaths) {
    if (!fileSystem.FileExists(path)) {
        throw duckdb::InvalidInputException("INTERLIS model source does not exist: %s", path);
    }
    if (!HasIliExtension(fileSystem, path)) {
        throw duckdb::InvalidInputException("INTERLIS model source must have a .ili extension: %s", path);
    }

    auto normalizedPath = NormalizePath(fileSystem, path);
    if (std::find(normalizedPaths.begin(), normalizedPaths.end(), normalizedPath) != normalizedPaths.end()) {
        return;
    }

    auto handle = fileSystem.OpenFile(path, duckdb::FileFlags::FILE_FLAGS_READ);
    if (!handle || handle->GetType() != duckdb::FileType::FILE_TYPE_REGULAR) {
        throw duckdb::InvalidInputException("INTERLIS model source is not a regular file: %s", path);
    }
    const auto size = fileSystem.GetFileSize(*handle);
    if (size < 0 || static_cast<std::uint64_t>(size) > std::numeric_limits<std::size_t>::max()) {
        throw duckdb::InvalidInputException("INTERLIS model source is too large: %s", path);
    }

    ilic::ModelSource source;
    source.uri = normalizedPath;
    source.utf8.resize(static_cast<std::size_t>(size));
    if (size != 0) {
        fileSystem.Read(*handle, source.utf8.data(), size, 0);
    }
    sources.push_back(std::move(source));
    normalizedPaths.push_back(std::move(normalizedPath));
}

void ModelSourceResolver::AddDirectory(duckdb::FileSystem &fileSystem, const std::string &directory,
                                       std::vector<ilic::ModelSource> &sources,
                                       std::vector<std::string> &normalizedPaths) {
    std::vector<std::string> files;
    const auto listed = fileSystem.ListFiles(directory, [&](const std::string &path, bool isDirectory) {
        if (!isDirectory) {
            auto listedPath = ListedPath(fileSystem, directory, path);
            if (HasIliExtension(fileSystem, listedPath)) {
                files.push_back(std::move(listedPath));
            }
        }
    });
    if (!listed) {
        throw duckdb::InvalidInputException("Unable to list INTERLIS model directory: %s", directory);
    }
    if (files.empty()) {
        throw duckdb::InvalidInputException("INTERLIS model directory contains no .ili files: %s", directory);
    }

    std::sort(files.begin(), files.end());
    for (const auto &file : files) {
        AddFile(fileSystem, file, sources, normalizedPaths);
    }
}

std::string ModelSourceResolver::NormalizePath(duckdb::FileSystem &fileSystem, const std::string &path) {
    return fileSystem.CanonicalizePath(fileSystem.ExpandPath(path));
}

bool ModelSourceResolver::IsRemoteUri(const std::string &path) {
    return path.rfind("http://", 0) == 0 || path.rfind("https://", 0) == 0;
}

} // namespace interlis
