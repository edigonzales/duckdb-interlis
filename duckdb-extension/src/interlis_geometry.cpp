#include "interlis_geometry.h"
#include "duckdb/common/types/geometry.hpp"
#include "duckdb/common/types/vector.hpp"

#include <cstdlib>
#include <cstring>

namespace {

/*
 * Creates a temporary DuckDB Vector of VARCHAR type and calls Geometry::FromString
 * to convert WKT → internal geometry binary. The result is copied out to a
 * malloc'd buffer.
 */
bool convert_from_wkt(
    const char *wkt_data, size_t wkt_size,
    char **out_data, size_t *out_size,
    char **out_error)
{
    using namespace duckdb;

    try {
        // Create a temporary VARCHAR vector to hold the result
        Vector result_vec(LogicalType::VARCHAR);

        // Create input string_t pointing to caller's data (no copy)
        string_t input_wkt(wkt_data, static_cast<uint32_t>(wkt_size));

        string_t geometry;
        bool ok = Geometry::FromString(input_wkt, geometry, result_vec, true);
        if (!ok) {
            if (out_error) {
                *out_error = strdup("Geometry::FromString returned false");
            }
            return false;
        }

        // Copy result out of the vector's heap into a caller-owned buffer
        auto size = geometry.GetSize();
        auto *buf = static_cast<char *>(malloc(size));
        if (!buf) {
            if (out_error) {
                *out_error = strdup("Out of memory");
            }
            return false;
        }
        memcpy(buf, geometry.GetData(), size);

        *out_data = buf;
        *out_size = size;
        return true;

    } catch (const duckdb::Exception &e) {
        if (out_error) {
            *out_error = strdup(e.what());
        }
        return false;
    } catch (const std::exception &e) {
        if (out_error) {
            *out_error = strdup(e.what());
        }
        return false;
    } catch (...) {
        if (out_error) {
            *out_error = strdup("Unknown error in geometry conversion");
        }
        return false;
    }
}

/*
 * Same as above but for WKB input via Geometry::FromBinary.
 */
bool convert_from_wkb(
    const char *wkb_data, size_t wkb_size,
    char **out_data, size_t *out_size,
    char **out_error)
{
    using namespace duckdb;

    try {
        Vector result_vec(LogicalType::VARCHAR);

        string_t input_wkb(wkb_data, static_cast<uint32_t>(wkb_size));

        string_t geometry;
        bool ok = Geometry::FromBinary(input_wkb, geometry, result_vec, true);
        if (!ok) {
            if (out_error) {
                *out_error = strdup("Geometry::FromBinary returned false");
            }
            return false;
        }

        auto size = geometry.GetSize();
        auto *buf = static_cast<char *>(malloc(size));
        if (!buf) {
            if (out_error) {
                *out_error = strdup("Out of memory");
            }
            return false;
        }
        memcpy(buf, geometry.GetData(), size);

        *out_data = buf;
        *out_size = size;
        return true;

    } catch (const duckdb::Exception &e) {
        if (out_error) {
            *out_error = strdup(e.what());
        }
        return false;
    } catch (const std::exception &e) {
        if (out_error) {
            *out_error = strdup(e.what());
        }
        return false;
    } catch (...) {
        if (out_error) {
            *out_error = strdup("Unknown error in geometry conversion");
        }
        return false;
    }
}

} // anonymous namespace

extern "C" {

bool interlis_geometry_from_wkt(
    const char *wkt_data, size_t wkt_size,
    char **out_data, size_t *out_size,
    char **out_error)
{
    if (!wkt_data || !out_data || !out_size) {
        return false;
    }
    *out_data = nullptr;
    *out_size = 0;
    if (out_error) {
        *out_error = nullptr;
    }
    return convert_from_wkt(wkt_data, wkt_size, out_data, out_size, out_error);
}

bool interlis_geometry_from_wkb(
    const char *wkb_data, size_t wkb_size,
    char **out_data, size_t *out_size,
    char **out_error)
{
    if (!wkb_data || !out_data || !out_size) {
        return false;
    }
    *out_data = nullptr;
    *out_size = 0;
    if (out_error) {
        *out_error = nullptr;
    }
    return convert_from_wkb(wkb_data, wkb_size, out_data, out_size, out_error);
}

void interlis_geometry_free_buffer(char *data) {
    free(data);
}

} // extern "C"
