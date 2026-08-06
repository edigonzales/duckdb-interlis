#include "interlis_extension.hpp"

#include "model/compiled_model.hpp"
#include "model/model_source_resolver.hpp"
#include "xtf/xtf_stream.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/common/file_open_flags.hpp"
#include "duckdb/common/file_system.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include "iox/IomPath.h"
#include "iox/Writer.h"
#include "iox/xtf/XtfWriter.h"
#include "iox/ilic/IlicModelIndex.h"

#include <atomic>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include <unistd.h>

namespace duckdb {

namespace {

struct XtfSetBindData final : TableFunctionData {
    std::string input;
    std::string output;
    std::string className;
    std::string tid;
    iox::IomPath path;
    std::optional<std::string> bid;
    std::string newValue;
    std::optional<std::string> expected;
    bool overwrite = false;
    std::shared_ptr<interlis::CompiledModel> model;

    explicit XtfSetBindData(iox::IomPath pathValue)
        : path(std::move(pathValue)) {
    }

    unique_ptr<FunctionData> Copy() const override {
        auto result = make_uniq<XtfSetBindData>(path);
        result->input = input;
        result->output = output;
        result->className = className;
        result->tid = tid;
        result->bid = bid;
        result->newValue = newValue;
        result->expected = expected;
        result->overwrite = overwrite;
        result->model = model;
        result->column_ids = column_ids;
        return result;
    }

    bool Equals(const FunctionData &) const override {
        return false;
    }

    bool SupportStatementCache() const override {
        return false;
    }
};

struct XtfSetGlobalState final : GlobalTableFunctionState {
    idx_t MaxThreads() const override {
        return 1;
    }
};

struct XtfSetLocalState final : LocalTableFunctionState {
    bool done = false;
    std::vector<Value> result;
};

std::optional<std::string> NamedString(const TableFunctionBindInput &input,
                                       const char *name) {
    const auto found = input.named_parameters.find(name);
    if (found == input.named_parameters.end() || found->second.IsNull()) {
        return std::nullopt;
    }
    return StringValue::Get(found->second);
}

bool NamedBoolean(const TableFunctionBindInput &input, const char *name,
                  bool defaultValue) {
    const auto found = input.named_parameters.find(name);
    if (found == input.named_parameters.end() || found->second.IsNull()) {
        return defaultValue;
    }
    return BooleanValue::Get(found->second);
}

iox::IomPath ParsePath(const std::string &expression) {
    try {
        return iox::IomPath::parse(expression);
    } catch (const iox::IoxError &error) {
        throw InvalidInputException("xtf_set invalid path_expression '%s': %s",
                                    expression, error.what());
    }
}

std::vector<std::string> ReadModelSources(const Value &value) {
    if (value.IsNull() || value.type().id() != LogicalTypeId::LIST) {
        throw InvalidInputException("xtf_set model_sources must be a non-empty VARCHAR[]");
    }
    std::vector<std::string> result;
    for (const auto &child : ListValue::GetChildren(value)) {
        if (child.IsNull()) {
            throw InvalidInputException("xtf_set model_sources must not contain NULL");
        }
        result.emplace_back(StringValue::Get(child));
    }
    if (result.empty()) {
        throw InvalidInputException("xtf_set model_sources must be a non-empty VARCHAR[]");
    }
    return result;
}

std::shared_ptr<interlis::CompiledModel> CompileModel(ClientContext &context,
                                                       const Value &sourcesValue) {
    auto sources = ReadModelSources(sourcesValue);
    auto &fileSystem = FileSystem::GetFileSystem(context);
    auto resolved = interlis::ModelSourceResolver::Resolve(fileSystem, sources);
    return std::make_shared<interlis::CompiledModel>(std::move(resolved));
}

std::string NormalizePath(const std::string &path) {
    std::error_code error;
    auto absolute = std::filesystem::absolute(std::filesystem::path(path), error);
    if (error) {
        return std::filesystem::path(path).lexically_normal().generic_string();
    }
    return absolute.lexically_normal().generic_string();
}

std::string PropertyName(const iox::IomName &name) {
    if (name.hasInterlisName()) {
        return name.interlisName();
    }
    return name.hasXmlName() ? name.xmlName().localName : std::string();
}

const iox::ilic::PropertyDescriptor *FindProperty(
    const std::vector<iox::ilic::PropertyDescriptor> &descriptors,
    const std::string &name) {
    for (const auto &descriptor : descriptors) {
        if (PropertyName(descriptor.name) == name) {
            return &descriptor;
        }
    }
    return nullptr;
}

void ValidateTargetProperty(const XtfSetBindData &data) {
    const auto separator = data.className.find('.');
    const auto targetModel = data.className.substr(0, separator);
    const auto descriptors = data.model->index().transferPropertyDescriptors(
        iox::IomName(data.className), targetModel, iox::XtfVersion::V23);
    const auto &firstStep = data.path.steps().front();
    const auto *descriptor = FindProperty(descriptors, firstStep.attribute);
    if (descriptor == nullptr) {
        throw InvalidInputException("xtf_set target property does not exist: %s.%s",
                                    data.className, firstStep.attribute);
    }
    if (data.path.steps().size() > 1) {
        if (descriptor->valueKind != iox::ilic::PropertyValueKind::Structure ||
            !descriptor->cardinalityMax.has_value() ||
            *descriptor->cardinalityMax != 1) {
            throw InvalidInputException("xtf_set nested path must start with one structure: %s",
                                        data.path.expression());
        }
        return;
    }
    if (descriptor->kind == iox::ilic::PropertyKind::Role) {
        throw InvalidInputException("xtf_set roles are not supported: %s", firstStep.attribute);
    }
    if (descriptor->geometry.has_value() ||
        descriptor->valueKind == iox::ilic::PropertyValueKind::Geometry) {
        throw InvalidInputException("xtf_set geometries are not supported: %s", firstStep.attribute);
    }
    if (descriptor->valueKind != iox::ilic::PropertyValueKind::String &&
        descriptor->valueKind != iox::ilic::PropertyValueKind::Boolean &&
        descriptor->valueKind != iox::ilic::PropertyValueKind::Integer &&
        descriptor->valueKind != iox::ilic::PropertyValueKind::Double) {
        throw InvalidInputException("xtf_set target property is not primitive: %s", firstStep.attribute);
    }
    if (!descriptor->cardinalityMax.has_value() || *descriptor->cardinalityMax != 1) {
        throw InvalidInputException("xtf_set target property must be single-valued: %s", firstStep.attribute);
    }
    if (descriptor->transient) {
        throw InvalidInputException("xtf_set transient properties are not supported: %s", firstStep.attribute);
    }
}

unique_ptr<FunctionData> BindXtfSet(ClientContext &context,
                                    TableFunctionBindInput &input,
                                    vector<LogicalType> &returnTypes,
                                    vector<string> &names) {
    if (input.inputs.size() != 7) {
        throw InvalidInputException("xtf_set requires input, output, class_name, tid, path_expression, value, and model_sources");
    }
    returnTypes = {LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
                   LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
                   LogicalType::VARCHAR, LogicalType::VARCHAR};
    names = {"bid", "tid", "class_name", "path_expression", "old_value",
             "new_value", "status", "message"};

    const auto inputPath = StringValue::Get(input.inputs[0]);
    const auto outputPath = StringValue::Get(input.inputs[1]);
    const auto className = StringValue::Get(input.inputs[2]);
    const auto tid = StringValue::Get(input.inputs[3]);
    const auto pathExpression = StringValue::Get(input.inputs[4]);
    if (inputPath.empty() || outputPath.empty() || className.empty() || tid.empty() ||
        pathExpression.empty()) {
        throw InvalidInputException("xtf_set input, output, class_name, tid, and path_expression must not be empty");
    }
    if (NormalizePath(inputPath) == NormalizePath(outputPath)) {
        throw InvalidInputException("xtf_set input and output must be different files");
    }

    auto result = make_uniq<XtfSetBindData>(ParsePath(pathExpression));
    result->input = inputPath;
    result->output = outputPath;
    result->className = className;
    result->tid = tid;
    result->newValue = StringValue::Get(input.inputs[5]);
    result->bid = NamedString(input, "bid");
    result->expected = NamedString(input, "expected");
    result->overwrite = NamedBoolean(input, "overwrite", false);
    if (result->path.containsWildcard()) {
        throw InvalidInputException("xtf_set path_expression must not contain a wildcard");
    }
    if (!FileSystem::GetFileSystem(context).FileExists(result->input)) {
        throw InvalidInputException("xtf_set input file does not exist: %s", result->input);
    }
    if (!result->overwrite && FileSystem::GetFileSystem(context).FileExists(result->output)) {
        throw InvalidInputException("xtf_set output file already exists: %s", result->output);
    }
    result->model = CompileModel(context, input.inputs[6]);
    const auto firstSeparator = result->className.find('.');
    const auto secondSeparator = firstSeparator == std::string::npos
                                     ? std::string::npos
                                     : result->className.find('.', firstSeparator + 1);
    if (firstSeparator == std::string::npos || secondSeparator == std::string::npos) {
        throw InvalidInputException("xtf_set class_name must be fully qualified as Model.Topic.Class: %s",
                                    result->className);
    }
    if (result->model->index().transferPropertyDescriptors(
            iox::IomName(result->className), result->className.substr(0, firstSeparator),
            iox::XtfVersion::V23).empty()) {
        throw InvalidInputException("xtf_set class_name is not a model class: %s", result->className);
    }
    ValidateTargetProperty(*result);
    return result;
}

class FileOutputSink final : public iox::OutputSink {
public:
    FileOutputSink(FileSystem &fileSystem, const std::string &path)
        : fileSystem_(fileSystem), path_(path) {
        file_ = fileSystem_.OpenFile(
            path_, FileFlags::FILE_FLAGS_WRITE | FileFlags::FILE_FLAGS_FILE_CREATE_NEW);
        if (!file_) {
            throw IOException("xtf_set could not create temporary output: %s", path_);
        }
    }

    ~FileOutputSink() override {
        try {
            close();
        } catch (...) {
        }
    }

    std::size_t write(const void *data, std::size_t size) override {
        if (closed_ || !file_) {
            throw std::runtime_error("xtf_set output sink is closed");
        }
        auto *mutableData = const_cast<void *>(data);
        const auto written = file_->Write(mutableData, size);
        if (written != size) {
            throw std::runtime_error("xtf_set short write");
        }
        return written;
    }

    void flush() override {
        if (file_ && !closed_) {
            file_->Sync();
        }
    }

    void close() override {
        if (file_ && !closed_) {
            file_->Sync();
            file_->Close();
            closed_ = true;
        }
    }

private:
    FileSystem &fileSystem_;
    std::string path_;
    unique_ptr<FileHandle> file_;
    bool closed_ = false;
};

class TempFileGuard final {
public:
    TempFileGuard(FileSystem &fileSystem, std::string path)
        : fileSystem_(fileSystem), path_(std::move(path)) {
    }

    ~TempFileGuard() {
        if (!committed_) {
            fileSystem_.TryRemoveFile(path_);
        }
    }

    void commit() noexcept {
        committed_ = true;
    }

    const std::string &path() const noexcept {
        return path_;
    }

private:
    FileSystem &fileSystem_;
    std::string path_;
    bool committed_ = false;
};

void ThrowWriterDiagnostics(const std::string &path,
                            std::vector<iox::Diagnostic> diagnostics) {
    for (const auto &diagnostic : diagnostics) {
        if (diagnostic.severity != iox::DiagnosticSeverity::Error &&
            diagnostic.severity != iox::DiagnosticSeverity::Fatal) {
            continue;
        }
        throw InvalidInputException("xtf_set writer failed: %s %s: %s", path,
                                    std::string(iox::diagnosticCodeName(diagnostic.code)),
                                    diagnostic.message);
    }
}

std::string CreateTempPath(FileSystem &fileSystem, const std::string &output) {
    static std::atomic<std::uint64_t> counter{0};
    const auto outputPath = std::filesystem::path(output);
    const auto parent = outputPath.parent_path().empty()
                            ? std::filesystem::path(".")
                            : outputPath.parent_path();
    const auto filename = outputPath.filename().string();
    for (;;) {
        const auto suffix = std::to_string(static_cast<unsigned long long>(getpid())) +
                            "-" + std::to_string(static_cast<unsigned long long>(counter.fetch_add(1)));
        const auto candidate = fileSystem.JoinPath(parent.generic_string(),
                                                   "." + filename + ".interlis-tmp-" + suffix);
        if (!fileSystem.FileExists(candidate)) {
            return candidate;
        }
    }
}

std::vector<Value> RewriteXtf(ClientContext &context,
                              const XtfSetBindData &bindData) {
    auto &fileSystem = FileSystem::GetFileSystem(context);
    const auto tempPath = CreateTempPath(fileSystem, bindData.output);
    TempFileGuard temporary(fileSystem, tempPath);
    auto source = OpenXtfStream(context, bindData.model->models(), bindData.input);
    auto sink = std::make_shared<FileOutputSink>(fileSystem, tempPath);
    std::unique_ptr<iox::xtf::XtfWriter> writer;
    std::size_t matchCount = 0;
    std::string oldValue;
    std::string matchedBid;

    while (true) {
        const auto event = NextXtfEvent(source, bindData.input);
        if (!event.has_value()) {
            break;
        }
        if (!writer) {
            const auto *transfer = std::get_if<iox::StartTransferEvent>(&*event);
            if (transfer == nullptr) {
                throw InvalidInputException("xtf_set input did not start with a transfer event");
            }
            iox::xtf::XtfWriterOptions options;
            options.version = transfer->header.version;
            options.pretty = false;
            options.preserveUnknownExtensions = true;
            writer = std::make_unique<iox::xtf::XtfWriter>(sink, options);
        }
        if (const auto *basket = std::get_if<iox::StartBasketEvent>(&*event)) {
            source.currentBid = basket->basket.basketId;
        }
        auto mutableEvent = *event;
        if (auto *objectEvent = std::get_if<iox::ObjectEvent>(&mutableEvent)) {
            const auto &object = objectEvent->object;
            const auto objectClass = object.tag().hasInterlisName()
                                         ? object.tag().interlisName()
                                         : std::string();
            if (objectClass == bindData.className && object.oid().has_value() &&
                *object.oid() == bindData.tid &&
                (!bindData.bid.has_value() || *bindData.bid == source.currentBid)) {
                ++matchCount;
                if (matchCount > 1) {
                    throw InvalidInputException("xtf_set matched more than one object: TID=%s", bindData.tid);
                }
                try {
                    std::optional<std::string_view> expected;
                    if (bindData.expected.has_value()) {
                        expected = *bindData.expected;
                    }
                    oldValue = bindData.path.replaceSinglePrimitive(
                        objectEvent->object, bindData.newValue, expected);
                    matchedBid = source.currentBid;
                } catch (const iox::IoxError &error) {
                    throw InvalidInputException(
                        "xtf_set failed: %s TID=%s BID=%s class=%s path=%s: %s",
                        bindData.input, bindData.tid, source.currentBid,
                        bindData.className, bindData.path.expression(), error.what());
                }
            }
        }
        writer->write(mutableEvent);
    }
    if (!writer) {
        throw InvalidInputException("xtf_set input contained no transfer");
    }
    writer->close();
    ThrowWriterDiagnostics(bindData.output, writer->takeDiagnostics());
    writer.reset();
    sink.reset();
    if (matchCount == 0) {
        throw InvalidInputException("xtf_set found no matching object: TID=%s", bindData.tid);
    }
    if (matchCount != 1) {
        throw InvalidInputException("xtf_set expected exactly one matching object");
    }
    if (!bindData.overwrite && fileSystem.FileExists(bindData.output)) {
        throw InvalidInputException("xtf_set output file appeared during rewrite: %s", bindData.output);
    }
    fileSystem.MoveFile(tempPath, bindData.output);
    temporary.commit();
    return {Value(matchedBid), Value(bindData.tid), Value(bindData.className),
            Value(bindData.path.expression()), Value(oldValue), Value(bindData.newValue),
            Value("UPDATED"), Value("one primitive value rewritten")};
}

unique_ptr<GlobalTableFunctionState> InitXtfSetGlobal(ClientContext &,
                                                       TableFunctionInitInput &) {
    return make_uniq<XtfSetGlobalState>();
}

unique_ptr<LocalTableFunctionState> InitXtfSetLocal(
    ExecutionContext &, TableFunctionInitInput &, GlobalTableFunctionState *) {
    return make_uniq<XtfSetLocalState>();
}

void XtfSetFunction(ClientContext &context, TableFunctionInput &input,
                    DataChunk &output) {
    auto &local = input.local_state->Cast<XtfSetLocalState>();
    if (local.done) {
        output.SetCardinality(0);
        return;
    }
    auto &bindData = input.bind_data->Cast<XtfSetBindData>();
    local.result = RewriteXtf(context, bindData);
    for (idx_t column = 0; column < local.result.size(); ++column) {
        output.SetValue(column, 0, local.result[column]);
    }
    output.SetCardinality(1);
    local.done = true;
}

} // namespace

void RegisterXtfSetFunction(ExtensionLoader &loader) {
    TableFunction function("xtf_set",
                           {LogicalType::VARCHAR, LogicalType::VARCHAR,
                            LogicalType::VARCHAR, LogicalType::VARCHAR,
                            LogicalType::VARCHAR, LogicalType::VARCHAR,
                            LogicalType::LIST(LogicalType::VARCHAR)},
                           XtfSetFunction, BindXtfSet, InitXtfSetGlobal,
                           InitXtfSetLocal);
    function.named_parameters["bid"] = LogicalType::VARCHAR;
    function.named_parameters["expected"] = LogicalType::VARCHAR;
    function.named_parameters["overwrite"] = LogicalType::BOOLEAN;
    loader.RegisterFunction(function);
}

} // namespace duckdb
