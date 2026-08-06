#include "model/compiled_model.hpp"
#include "model/model_source_resolver.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb.hpp"

#include <cassert>
#include <filesystem>
#include <fstream>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace {

const char *kModelA = R"ili(INTERLIS 2.3;
MODEL Alpha (en) AT "https://example.invalid" VERSION "1" =
  TOPIC Topic =
    CLASS Thing =
      Name : TEXT*20;
    END Thing;
  END Topic;
END Alpha.
)ili";

const char *kModelB = R"ili(INTERLIS 2.3;
MODEL Beta (de) AT "https://example.invalid" VERSION "1" =
  TOPIC Topic =
    CLASS Thing =
      Name : TEXT*20;
    END Thing;
  END Topic;
END Beta.
)ili";

void WriteFile(const std::filesystem::path &path, const std::string &content) {
    std::ofstream output(path, std::ios::binary);
    output << content;
    assert(output.good());
}

void ExpectInvalidInput(const std::function<void()> &operation, const std::string &message) {
    try {
        operation();
        assert(false);
    } catch (const duckdb::InvalidInputException &exception) {
        assert(std::string(exception.what()).find(message) != std::string::npos);
    }
}

} // namespace

int main() {
    const auto root = std::filesystem::temp_directory_path() / "duckdb-interlis-phase8-model-sources";
    std::error_code error;
    std::filesystem::remove_all(root, error);
    std::filesystem::create_directories(root / "models", error);
    assert(!error);

    const auto alpha = root / "models" / "alpha.ili";
    const auto beta = root / "models" / "beta.ILI";
    const auto ignored = root / "models" / "ignored.txt";
    WriteFile(alpha, kModelA);
    WriteFile(beta, kModelB);
    WriteFile(ignored, "not an INTERLIS model");

    duckdb::DuckDB database(nullptr);
    auto &fileSystem = database.GetFileSystem();

    const auto directorySources = interlis::ModelSourceResolver::Resolve(fileSystem, { (root / "models").string() });
    assert(directorySources.size() == 2);
    assert(directorySources[0].uri < directorySources[1].uri);
    assert(directorySources[0].utf8 == kModelA);
    assert(directorySources[1].utf8 == kModelB);

    const auto duplicateSources = interlis::ModelSourceResolver::Resolve(
        fileSystem, {alpha.string(), (root / "models").string(), alpha.string()});
    assert(duplicateSources.size() == 2);

    ExpectInvalidInput([&] { interlis::ModelSourceResolver::Resolve(fileSystem, {"missing.ili"}); },
                       "does not exist");
    ExpectInvalidInput(
        [&] { interlis::ModelSourceResolver::Resolve(fileSystem, {"https://example.invalid/model.ili"}); },
        "Remote model sources are not supported by the native MVP");
    ExpectInvalidInput([&] { interlis::ModelSourceResolver::Resolve(fileSystem, {ignored.string()}); },
                       "must have a .ili extension");

    auto model = std::make_shared<interlis::CompiledModel>(directorySources);
    assert(model->compilationResult().success);
    assert(model->index().modelLanguage("Alpha") == std::optional<std::string>("en"));
    assert(model->index().modelLanguage("Beta") == std::optional<std::string>("de"));

    const auto invalid = root / "invalid.ili";
    WriteFile(invalid, "INTERLIS 2.3; MODEL Broken =");
    ExpectInvalidInput(
        [&] {
            interlis::CompiledModel broken(interlis::ModelSourceResolver::Resolve(fileSystem, {invalid.string()}));
        },
        "INTERLIS model compilation failed");

    model.reset();
    std::filesystem::remove_all(root, error);
    assert(!error);
    return 0;
}
