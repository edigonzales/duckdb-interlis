#pragma once

#include "duckdb/common/file_system.hpp"

#include "iox/Events.h"
#include "iox/ilic/IlicModelIndex.h"

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace duckdb {

constexpr idx_t INTERLIS_XTF_INPUT_BUFFER_SIZE = 64U * 1024U;

struct XtfStreamState final {
    std::unique_ptr<FileHandle> file;
    std::unique_ptr<iox::ilic::IlicXtfReader> reader;
    std::vector<std::uint8_t> inputBuffer =
        std::vector<std::uint8_t>(INTERLIS_XTF_INPUT_BUFFER_SIZE);
    std::string currentBid;
    bool inputFinished = false;
    bool streamFinished = false;
};

XtfStreamState OpenXtfStream(ClientContext &context,
                             const metamodel::MetaModelStore &models,
                             const std::string &path);

std::optional<iox::IoxEvent> NextXtfEvent(XtfStreamState &state,
                                          const std::string &path);

} // namespace duckdb
