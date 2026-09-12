#include "hans_sha256.h"

#include <algorithm>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace hans::crypto {
namespace {

constexpr std::array<uint32_t, 64> kRoundConstants = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
};

constexpr uint32_t RotateRight(uint32_t value, uint32_t amount) {
  return (value >> amount) | (value << (32U - amount));
}

uint32_t ReadBigEndian(const uint8_t* input) {
  return (static_cast<uint32_t>(input[0]) << 24U) |
         (static_cast<uint32_t>(input[1]) << 16U) |
         (static_cast<uint32_t>(input[2]) << 8U) |
         static_cast<uint32_t>(input[3]);
}

void WriteBigEndian(uint32_t value, uint8_t* output) {
  output[0] = static_cast<uint8_t>(value >> 24U);
  output[1] = static_cast<uint8_t>(value >> 16U);
  output[2] = static_cast<uint8_t>(value >> 8U);
  output[3] = static_cast<uint8_t>(value);
}

}  // namespace

Sha256::Sha256()
    : state_{0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
             0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U} {}

void Sha256::Update(const uint8_t* data, size_t size) {
  if (finished_ || (data == nullptr && size != 0)) return;
  total_bytes_ += size;
  while (size != 0) {
    const size_t available = buffer_.size() - buffered_bytes_;
    const size_t copied = size < available ? size : available;
    std::memcpy(buffer_.data() + buffered_bytes_, data, copied);
    buffered_bytes_ += copied;
    data += copied;
    size -= copied;
    if (buffered_bytes_ == buffer_.size()) {
      Transform(buffer_.data());
      buffered_bytes_ = 0;
    }
  }
}

std::array<uint8_t, 32> Sha256::Finish() {
  if (!finished_) {
    const uint64_t bit_count = total_bytes_ * 8U;
    buffer_[buffered_bytes_++] = 0x80U;
    if (buffered_bytes_ > 56) {
      std::fill(buffer_.begin() + static_cast<ptrdiff_t>(buffered_bytes_),
                buffer_.end(), 0);
      Transform(buffer_.data());
      buffered_bytes_ = 0;
    }
    std::fill(buffer_.begin() + static_cast<ptrdiff_t>(buffered_bytes_),
              buffer_.begin() + 56, 0);
    for (size_t index = 0; index < 8; ++index) {
      buffer_[63 - index] = static_cast<uint8_t>(bit_count >> (index * 8U));
    }
    Transform(buffer_.data());
    finished_ = true;
  }
  std::array<uint8_t, 32> result{};
  for (size_t index = 0; index < state_.size(); ++index) {
    WriteBigEndian(state_[index], result.data() + index * 4);
  }
  return result;
}

void Sha256::Transform(const uint8_t block[64]) {
  std::array<uint32_t, 64> words{};
  for (size_t index = 0; index < 16; ++index) {
    words[index] = ReadBigEndian(block + index * 4);
  }
  for (size_t index = 16; index < words.size(); ++index) {
    const uint32_t first = RotateRight(words[index - 15], 7) ^
                           RotateRight(words[index - 15], 18) ^
                           (words[index - 15] >> 3U);
    const uint32_t second = RotateRight(words[index - 2], 17) ^
                            RotateRight(words[index - 2], 19) ^
                            (words[index - 2] >> 10U);
    words[index] = words[index - 16] + first + words[index - 7] + second;
  }
  uint32_t a = state_[0];
  uint32_t b = state_[1];
  uint32_t c = state_[2];
  uint32_t d = state_[3];
  uint32_t e = state_[4];
  uint32_t f = state_[5];
  uint32_t g = state_[6];
  uint32_t h = state_[7];
  for (size_t index = 0; index < words.size(); ++index) {
    const uint32_t choose = (e & f) ^ (~e & g);
    const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
    const uint32_t sum0 = RotateRight(a, 2) ^ RotateRight(a, 13) ^ RotateRight(a, 22);
    const uint32_t sum1 = RotateRight(e, 6) ^ RotateRight(e, 11) ^ RotateRight(e, 25);
    const uint32_t first = h + sum1 + choose + kRoundConstants[index] + words[index];
    const uint32_t second = sum0 + majority;
    h = g;
    g = f;
    f = e;
    e = d + first;
    d = c;
    c = b;
    b = a;
    a = first + second;
  }
  state_[0] += a;
  state_[1] += b;
  state_[2] += c;
  state_[3] += d;
  state_[4] += e;
  state_[5] += f;
  state_[6] += g;
  state_[7] += h;
}

bool Sha256FileDescriptor(
    int descriptor,
    uint64_t expected_bytes,
    std::string* digest,
    std::string* error) {
  if (descriptor < 0) {
    *error = "archive descriptor is invalid";
    return false;
  }
  const int flags = fcntl(descriptor, F_GETFL);
  if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY) {
    *error = "archive descriptor must be read-only";
    return false;
  }
  struct stat before {};
  if (fstat(descriptor, &before) != 0 || !S_ISREG(before.st_mode) ||
      before.st_size < 0 || static_cast<uint64_t>(before.st_size) != expected_bytes) {
    *error = "archive descriptor size or file type does not match its manifest";
    return false;
  }
  Sha256 hash;
  std::array<uint8_t, 64 * 1024> buffer{};
  uint64_t consumed = 0;
  while (consumed < expected_bytes) {
    const size_t requested = static_cast<size_t>(
        std::min<uint64_t>(buffer.size(), expected_bytes - consumed));
    const ssize_t read_bytes = pread(
        descriptor, buffer.data(), requested, static_cast<off_t>(consumed));
    if (read_bytes < 0 && errno == EINTR) continue;
    if (read_bytes <= 0) {
      *error = read_bytes == 0
                   ? "archive descriptor ended before its declared size"
                   : std::string("archive descriptor is not seekable/readable: ") +
                         std::strerror(errno);
      return false;
    }
    hash.Update(buffer.data(), static_cast<size_t>(read_bytes));
    consumed += static_cast<uint64_t>(read_bytes);
  }
  uint8_t extra = 0;
  while (true) {
    const ssize_t read_bytes = pread(
        descriptor, &extra, 1, static_cast<off_t>(expected_bytes));
    if (read_bytes < 0 && errno == EINTR) continue;
    if (read_bytes != 0) {
      *error = read_bytes > 0 ? "archive exceeds its declared size"
                              : "cannot verify archive boundary";
      return false;
    }
    break;
  }
  struct stat after {};
  if (fstat(descriptor, &after) != 0 || before.st_dev != after.st_dev ||
      before.st_ino != after.st_ino || before.st_size != after.st_size ||
      before.st_mtime != after.st_mtime || before.st_ctime != after.st_ctime) {
    *error = "archive descriptor changed during verification";
    return false;
  }
  const auto result = hash.Finish();
  constexpr char kHex[] = "0123456789abcdef";
  digest->clear();
  digest->reserve(result.size() * 2);
  for (uint8_t value : result) {
    digest->push_back(kHex[value >> 4U]);
    digest->push_back(kHex[value & 0x0fU]);
  }
  return true;
}

}  // namespace hans::crypto
