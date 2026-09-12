#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace hans::archive {

// Read-only index for the deterministic, ZIP_STORED Python archives delivered
// by Binder file descriptor. The descriptor remains owned by the JNI host.
class StoredZip {
 public:
  bool Open(int descriptor, uint64_t archive_bytes, std::string* error);
  void Reset();

  bool IsOpen() const { return descriptor_ >= 0; }
  std::optional<std::vector<uint8_t>> Read(
      std::string_view member, size_t maximum_bytes, std::string* error) const;

 private:
  struct Entry {
    uint64_t data_offset = 0;
    uint64_t size = 0;
  };

  int descriptor_ = -1;
  uint64_t archive_bytes_ = 0;
  std::unordered_map<std::string, Entry> entries_;
};

}  // namespace hans::archive
