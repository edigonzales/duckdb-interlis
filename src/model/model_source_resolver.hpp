#pragma once

#include "ilic/ModelCompilation.h"

#include <string>
#include <vector>

namespace duckdb {
class FileSystem;
}

namespace interlis {

class ModelSourceResolver final {
public:
    static std::vector<ilic::ModelSource> Resolve(
        duckdb::FileSystem &fileSystem,
        const std::vector<std::string> &sourceSpecs);

private:
    static void AddFile(duckdb::FileSystem &fileSystem,
                        const std::string &path,
                        std::vector<ilic::ModelSource> &sources,
                        std::vector<std::string> &normalizedPaths);
    static void AddDirectory(duckdb::FileSystem &fileSystem,
                             const std::string &directory,
                             std::vector<ilic::ModelSource> &sources,
                             std::vector<std::string> &normalizedPaths);
    static std::string NormalizePath(duckdb::FileSystem &fileSystem,
                                     const std::string &path);
    static bool IsRemoteUri(const std::string &path);
};

} // namespace interlis
