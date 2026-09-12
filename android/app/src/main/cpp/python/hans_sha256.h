#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

namespace hans::crypto {

class Sha256 {
 public:
  Sha256();

  void Update(const uint8_t* data, size_t size);
  std::array<uint8_t, 32> Finish();

 private:
  void Transform(const uint8_t block[64]);

  std::array<uint32_t, 8> state_{};
  std::array<uint8_t, 64> buffer_{};
  uint64_t total_bytes_ = 0;
  size_t buffered_bytes_ = 0;
  bool finished_ = false;
};

// Hashes exactly expected_bytes from a seekable, read-only regular descriptor
// using pread, leaving the caller's file offset untouched.
bool Sha256FileDescriptor(
    int descriptor,
    uint64_t expected_bytes,
    std::string* digest,
    std::string* error);

}  // namespace hans::crypto
