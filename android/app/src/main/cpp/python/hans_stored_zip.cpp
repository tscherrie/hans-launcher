#include "hans_stored_zip.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <limits>
#include <utility>

#include <unistd.h>

namespace hans::archive {
namespace {

constexpr uint32_t kLocalSignature = 0x04034b50U;
constexpr uint32_t kCentralSignature = 0x02014b50U;
constexpr uint32_t kEndSignature = 0x06054b50U;
constexpr size_t kEndFixedBytes = 22;
constexpr size_t kEndSearchBytes = 65'557;
constexpr size_t kCentralFixedBytes = 46;
constexpr size_t kLocalFixedBytes = 30;

uint16_t Read16(const uint8_t* bytes) {
  return static_cast<uint16_t>(bytes[0]) |
         (static_cast<uint16_t>(bytes[1]) << 8U);
}

uint32_t Read32(const uint8_t* bytes) {
  return static_cast<uint32_t>(Read16(bytes)) |
         (static_cast<uint32_t>(Read16(bytes + 2)) << 16U);
}

bool ReadExactly(int descriptor, uint64_t offset, uint8_t* output, size_t size,
                 std::string* error) {
  size_t consumed = 0;
  while (consumed < size) {
    if (offset + consumed >
        static_cast<uint64_t>(std::numeric_limits<off_t>::max())) {
      *error = "ZIP offset exceeds the platform range";
      return false;
    }
    const ssize_t read_bytes = pread(
        descriptor, output + consumed, size - consumed,
        static_cast<off_t>(offset + consumed));
    if (read_bytes < 0 && errno == EINTR) continue;
    if (read_bytes <= 0) {
      *error = read_bytes == 0
                   ? "ZIP ended before its directory"
                   : std::string("cannot read ZIP descriptor: ") +
                         std::strerror(errno);
      return false;
    }
    consumed += static_cast<size_t>(read_bytes);
  }
  return true;
}

bool IsSafeMember(std::string_view name) {
  if (name.empty() || name.front() == '/' || name.back() == '/' ||
      name.find('\0') != std::string_view::npos ||
      name.find('\\') != std::string_view::npos) {
    return false;
  }
  size_t start = 0;
  while (start <= name.size()) {
    const size_t end = name.find('/', start);
    const std::string_view component = name.substr(
        start, end == std::string_view::npos ? name.size() - start : end - start);
    if (component.empty() || component == "." || component == "..") return false;
    if (end == std::string_view::npos) break;
    start = end + 1;
  }
  return true;
}

}  // namespace

bool StoredZip::Open(int descriptor, uint64_t archive_bytes,
                     std::string* error) {
  Reset();
  if (descriptor < 0 || archive_bytes < kEndFixedBytes ||
      archive_bytes > static_cast<uint64_t>(std::numeric_limits<off_t>::max())) {
    *error = "stored ZIP descriptor contract is invalid";
    return false;
  }
  const size_t tail_size = static_cast<size_t>(
      std::min<uint64_t>(archive_bytes, kEndSearchBytes));
  std::vector<uint8_t> tail(tail_size);
  if (!ReadExactly(descriptor, archive_bytes - tail_size, tail.data(),
                   tail.size(), error)) {
    return false;
  }
  std::optional<size_t> end_position;
  for (size_t position = tail.size() - kEndFixedBytes + 1; position-- > 0;) {
    if (Read32(tail.data() + position) != kEndSignature) continue;
    const uint16_t comment = Read16(tail.data() + position + 20);
    if (position + kEndFixedBytes + comment == tail.size()) {
      end_position = position;
      break;
    }
  }
  if (!end_position.has_value()) {
    *error = "stored ZIP has no canonical end record";
    return false;
  }
  const uint8_t* end = tail.data() + *end_position;
  const uint16_t disk = Read16(end + 4);
  const uint16_t directory_disk = Read16(end + 6);
  const uint16_t disk_entries = Read16(end + 8);
  const uint16_t total_entries = Read16(end + 10);
  const uint32_t directory_size = Read32(end + 12);
  const uint32_t directory_offset = Read32(end + 16);
  if (disk != 0 || directory_disk != 0 || disk_entries != total_entries ||
      static_cast<uint64_t>(directory_offset) + directory_size > archive_bytes) {
    *error = "multi-disk, ZIP64, or out-of-range ZIP is unsupported";
    return false;
  }

  uint64_t cursor = directory_offset;
  std::unordered_map<std::string, Entry> parsed;
  parsed.reserve(total_entries);
  for (uint16_t index = 0; index < total_entries; ++index) {
    std::array<uint8_t, kCentralFixedBytes> central{};
    if (cursor + central.size() > archive_bytes ||
        !ReadExactly(descriptor, cursor, central.data(), central.size(), error) ||
        Read32(central.data()) != kCentralSignature) {
      if (error->empty()) *error = "invalid ZIP central directory entry";
      return false;
    }
    const uint16_t flags = Read16(central.data() + 8);
    const uint16_t method = Read16(central.data() + 10);
    const uint32_t compressed_size = Read32(central.data() + 20);
    const uint32_t uncompressed_size = Read32(central.data() + 24);
    const uint16_t name_size = Read16(central.data() + 28);
    const uint16_t extra_size = Read16(central.data() + 30);
    const uint16_t comment_size = Read16(central.data() + 32);
    const uint16_t start_disk = Read16(central.data() + 34);
    const uint32_t local_offset = Read32(central.data() + 42);
    const uint64_t record_size = static_cast<uint64_t>(central.size()) +
                                 name_size + extra_size + comment_size;
    if ((flags & ~0x0800U) != 0 || method != 0 ||
        compressed_size != uncompressed_size || name_size == 0 ||
        start_disk != 0 || cursor + record_size > archive_bytes) {
      *error = "ZIP entry is not a canonical stored Python source";
      return false;
    }
    std::vector<uint8_t> encoded_name(name_size);
    if (!ReadExactly(descriptor, cursor + central.size(), encoded_name.data(),
                     encoded_name.size(), error)) {
      return false;
    }
    const std::string name(encoded_name.begin(), encoded_name.end());
    if (!IsSafeMember(name) || parsed.find(name) != parsed.end()) {
      *error = "ZIP contains an unsafe or duplicate member";
      return false;
    }
    std::array<uint8_t, kLocalFixedBytes> local{};
    if (static_cast<uint64_t>(local_offset) + local.size() > archive_bytes ||
        !ReadExactly(descriptor, local_offset, local.data(), local.size(), error) ||
        Read32(local.data()) != kLocalSignature ||
        Read16(local.data() + 6) != flags || Read16(local.data() + 8) != method ||
        Read16(local.data() + 26) != name_size) {
      if (error->empty()) *error = "ZIP local header does not match its directory";
      return false;
    }
    const uint16_t local_extra_size = Read16(local.data() + 28);
    std::vector<uint8_t> local_name(name_size);
    if (!ReadExactly(descriptor, static_cast<uint64_t>(local_offset) + local.size(),
                     local_name.data(), local_name.size(), error) ||
        local_name != encoded_name) {
      if (error->empty()) *error = "ZIP local member name does not match";
      return false;
    }
    const uint64_t data_offset = static_cast<uint64_t>(local_offset) +
                                 local.size() + name_size + local_extra_size;
    if (data_offset + uncompressed_size > directory_offset) {
      *error = "ZIP member data overlaps its directory";
      return false;
    }
    parsed.emplace(name, Entry{data_offset, uncompressed_size});
    cursor += record_size;
  }
  if (cursor != static_cast<uint64_t>(directory_offset) + directory_size) {
    *error = "ZIP directory byte count does not match its end record";
    return false;
  }
  descriptor_ = descriptor;
  archive_bytes_ = archive_bytes;
  entries_ = std::move(parsed);
  return true;
}

void StoredZip::Reset() {
  descriptor_ = -1;
  archive_bytes_ = 0;
  entries_.clear();
}

std::optional<std::vector<uint8_t>> StoredZip::Read(
    std::string_view member, size_t maximum_bytes, std::string* error) const {
  error->clear();
  if (!IsOpen()) return std::nullopt;
  const auto found = entries_.find(std::string(member));
  if (found == entries_.end()) return std::nullopt;
  if (found->second.size > maximum_bytes ||
      found->second.size > std::numeric_limits<size_t>::max()) {
    *error = "ZIP member exceeds its execution limit";
    return std::nullopt;
  }
  std::vector<uint8_t> result(static_cast<size_t>(found->second.size));
  if (!result.empty() &&
      !ReadExactly(descriptor_, found->second.data_offset, result.data(),
                   result.size(), error)) {
    return std::nullopt;
  }
  return result;
}

}  // namespace hans::archive
