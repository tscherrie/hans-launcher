#include <cassert>
#include <cstdint>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <string>

#include "hans_json.h"
#include "hans_sha256.h"
#include "hans_stored_zip.h"

uint64_t FileBytes(int descriptor) {
  struct stat metadata {};
  assert(fstat(descriptor, &metadata) == 0);
  assert(metadata.st_size >= 0);
  return static_cast<uint64_t>(metadata.st_size);
}

int main(int argc, char** argv) {
  assert(argc == 3);
  hans::crypto::Sha256 hash;
  const std::string input = "abc";
  hash.Update(reinterpret_cast<const uint8_t*>(input.data()), input.size());
  const auto digest = hash.Finish();
  constexpr uint8_t expected[] = {
      0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea,
      0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
      0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c,
      0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad,
  };
  for (size_t index = 0; index < digest.size(); ++index) {
    assert(digest[index] == expected[index]);
  }

  const std::string document =
      R"({"stdlibFd":37,"runtimeLease":{"environmentFd":41,"environmentBytes":12}})";
  std::string error;
  int64_t descriptor = -1;
  assert(hans::json::GetRequiredInteger(
      document, "stdlibFd", &descriptor, &error));
  assert(descriptor == 37);
  std::optional<std::string> lease;
  assert(hans::json::GetOptionalObject(
      document, "runtimeLease", &lease, &error));
  assert(lease.has_value());
  int64_t bytes = -1;
  assert(hans::json::GetRequiredInteger(
      *lease, "environmentBytes", &bytes, &error));
  assert(bytes == 12);

  assert(!hans::json::GetRequiredInteger(
      R"({"stdlibFd":1,"stdlibFd":2})", "stdlibFd", &descriptor, &error));
  assert(!hans::json::GetRequiredInteger(
      R"({"stdlibFd":1.5})", "stdlibFd", &descriptor, &error));

  const int stored = open(argv[1], O_RDONLY | O_CLOEXEC);
  assert(stored >= 0);
  hans::archive::StoredZip archive;
  assert(archive.Open(stored, FileBytes(stored), &error));
  const auto source = archive.Read("sample.py", 1024, &error);
  assert(source.has_value());
  assert(std::string(source->begin(), source->end()) == "result = 42\n");
  assert(archive.Read("empty.py", 1024, &error).has_value());
  assert(!archive.Read("missing.py", 1024, &error).has_value());
  assert(!archive.Read("sample.py", 4, &error).has_value());
  assert(error == "ZIP member exceeds its execution limit");
  archive.Reset();
  close(stored);

  const int deflated = open(argv[2], O_RDONLY | O_CLOEXEC);
  assert(deflated >= 0);
  assert(!archive.Open(deflated, FileBytes(deflated), &error));
  assert(error == "ZIP entry is not a canonical stored Python source");
  close(deflated);
  return 0;
}
