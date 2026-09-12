#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace hans::json {

// Parses one top-level JSON field. A missing field and an explicit null both
// return std::nullopt; malformed JSON is reported through error.
bool GetOptionalString(
    std::string_view document,
    std::string_view key,
    std::optional<std::string>* value,
    std::string* error);

bool GetRequiredString(
    std::string_view document,
    std::string_view key,
    std::string* value,
    std::string* error);

bool GetRequiredInteger(
    std::string_view document,
    std::string_view key,
    int64_t* value,
    std::string* error);

// Returns the exact JSON bytes for a top-level object field. Null/missing map
// to std::nullopt; all other value types are rejected.
bool GetOptionalObject(
    std::string_view document,
    std::string_view key,
    std::optional<std::string>* value,
    std::string* error);

std::string Quote(std::string_view value);

}  // namespace hans::json
