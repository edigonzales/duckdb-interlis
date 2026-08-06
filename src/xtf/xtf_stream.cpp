#include "xtf/xtf_stream.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/common/file_open_flags.hpp"

#include <sstream>

namespace duckdb {

namespace {

void ThrowDiagnostics(const std::string &path,
                      std::vector<iox::Diagnostic> diagnostics) {
    for (const auto &diagnostic : diagnostics) {
        if (diagnostic.severity != iox::DiagnosticSeverity::Error &&
            diagnostic.severity != iox::DiagnosticSeverity::Fatal) {
            continue;
        }
        std::ostringstream message;
        message << "XTF read failed: " << path;
        if (!diagnostic.location.sourceName.empty()) {
            message << " at " << diagnostic.location.sourceName << ':'
                    << diagnostic.location.line << ':'
                    << diagnostic.location.column;
        }
        message << ' ' << iox::diagnosticCodeName(diagnostic.code) << ": "
                << diagnostic.message;
        throw InvalidInputException("%s", message.str());
    }
}

} // namespace

XtfStreamState OpenXtfStream(ClientContext &context,
                             const metamodel::MetaModelStore &models,
                             const std::string &path) {
    XtfStreamState result;
    auto &fileSystem = FileSystem::GetFileSystem(context);
    result.file = fileSystem.OpenFile(path, FileFlags::FILE_FLAGS_READ);
    if (!result.file || result.file->GetType() != FileType::FILE_TYPE_REGULAR) {
        throw InvalidInputException("XTF input is not a readable regular file: %s", path);
    }

    iox::ilic::IlicXtfReaderOptions options;
    options.xtf.sourceName = path;
    options.rejectUnknownTopics = false;
    options.rejectUnknownClasses = false;
    options.rejectUnknownProperties = false;
    result.reader = make_uniq<iox::ilic::IlicXtfReader>(models, options);
    return result;
}

std::optional<iox::IoxEvent> NextXtfEvent(XtfStreamState &state,
                                          const std::string &path) {
    while (!state.streamFinished) {
        try {
            const auto outcome = state.reader->next();
            ThrowDiagnostics(path, state.reader->takeDiagnostics());
            if (outcome.progress == iox::ReaderProgress::Event) {
                return outcome.event;
            }
            if (outcome.progress == iox::ReaderProgress::End) {
                state.streamFinished = true;
                return std::nullopt;
            }

            if (state.inputFinished) {
                state.reader->finish();
                continue;
            }
            const auto read = state.file->Read(state.inputBuffer.data(),
                                               state.inputBuffer.size());
            if (read <= 0) {
                state.inputFinished = true;
                state.reader->finish();
            } else {
                state.reader->feed(iox::ByteView(
                    state.inputBuffer.data(), static_cast<std::size_t>(read)));
            }
        } catch (const iox::IoxError &error) {
            std::ostringstream message;
            message << "XTF read failed: " << path << ' ' << error.what();
            throw InvalidInputException("%s", message.str());
        }
    }
    return std::nullopt;
}

} // namespace duckdb
