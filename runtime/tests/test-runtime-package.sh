#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
runtime_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
fixture_download_dir=${HANS_RUNTIME_DOWNLOAD_DIR:-"$runtime_dir/build/downloads"}
test_root=$(mktemp -d "${TMPDIR:-/tmp}/hans-runtime-test.XXXXXX")
trap 'rm -rf -- "$test_root"' 0 HUP INT TERM

# Download regression uses a local fake curl and existing pinned archives only.
# Missing fixture input is an explicit failure, never permission to use the network.
mkdir -p "$test_root/bin" "$test_root/origin"
for component in runtime codeModeHost; do
  asset=$(jq -er ".$component.releaseAsset" "$runtime_dir/runtime.lock.json")
  fixture_archive="$fixture_download_dir/$asset"
  [ -f "$fixture_archive" ] || {
    echo "Missing local pinned test fixture: $fixture_archive; explicitly prefetch pinned runtime archives before this offline test (HANS_RUNTIME_DOWNLOAD_DIR selects the cache)" >&2
    exit 1
  }
  cp "$fixture_archive" "$test_root/origin/$asset"
done
printf '%s\n' '#!/bin/sh' 'set -eu' 'printf "called\n" >> "$HANS_TEST_CURL_LOG"' \
  'out=; url=' 'while [ "$#" -gt 0 ]; do' \
  'case "$1" in --output) out=$2; shift 2;; *) url=$1; shift;; esac' 'done' \
  '[ -n "$out" ]' 'cp "$HANS_TEST_ORIGIN/${url##*/}" "$out"' > "$test_root/bin/curl"
chmod +x "$test_root/bin/curl"
export PATH="$test_root/bin:$PATH"
export HANS_TEST_CURL_LOG="$test_root/curl.log" HANS_TEST_ORIGIN="$test_root/origin"
unset HANS_RUNTIME_OFFLINE
expect_failure() {
  pattern=$1; shift
  if "$@" > "$test_root/failure.log" 2>&1; then
    echo "Unexpected packaging success: $pattern" >&2; exit 1
  fi
  grep -q "$pattern" "$test_root/failure.log"
}
packager="$runtime_dir/scripts/package-official-musl.sh"
expect_failure 'missing pinned archive' env HANS_RUNTIME_OFFLINE=1 \
  HANS_RUNTIME_DOWNLOAD_DIR="$test_root/missing" "$packager" "$test_root/miss-output"
[ ! -e "$HANS_TEST_CURL_LOG" ]
for invalid in '' true false 2 -1; do
  expect_failure 'HANS_RUNTIME_OFFLINE must be' env HANS_RUNTIME_OFFLINE="$invalid" \
    HANS_RUNTIME_DOWNLOAD_DIR="$test_root/missing" "$packager" "$test_root/invalid-output"
done
[ ! -e "$HANS_TEST_CURL_LOG" ]
[ ! -e "$test_root/invalid-output" ]

HANS_RUNTIME_DOWNLOAD_DIR="$test_root/downloads" \
  "$runtime_dir/scripts/package-official-musl.sh" "$test_root/jniLibs/arm64-v8a"
[ "$(wc -l < "$HANS_TEST_CURL_LOG" | tr -d ' ')" = 2 ]
HANS_RUNTIME_OFFLINE=0 HANS_RUNTIME_DOWNLOAD_DIR="$test_root/explicit-online-downloads" \
  "$packager" "$test_root/explicit-online-output"
[ "$(wc -l < "$HANS_TEST_CURL_LOG" | tr -d ' ')" = 4 ]
HANS_RUNTIME_OFFLINE=1 HANS_RUNTIME_DOWNLOAD_DIR="$test_root/downloads" \
  "$packager" "$test_root/jniLibs/arm64-v8a"
[ "$(wc -l < "$HANS_TEST_CURL_LOG" | tr -d ' ')" = 4 ]

asset=$(jq -er '.runtime.releaseAsset' "$runtime_dir/runtime.lock.json")
# Fixed-length corruption reaches the unchanged SHA check, not just the size check.
printf X | dd of="$test_root/downloads/$asset" bs=1 seek=0 conv=notrunc 2>/dev/null
expect_failure 'archive SHA-256 mismatch' env HANS_RUNTIME_OFFLINE=1 \
  HANS_RUNTIME_DOWNLOAD_DIR="$test_root/downloads" "$packager" "$test_root/hash-output"
printf x >> "$test_root/downloads/$asset"
expect_failure 'archive byte count mismatch' env HANS_RUNTIME_OFFLINE=1 \
  HANS_RUNTIME_DOWNLOAD_DIR="$test_root/downloads" "$packager" "$test_root/size-output"
[ "$(wc -l < "$HANS_TEST_CURL_LOG" | tr -d ' ')" = 4 ]

artifact="$test_root/jniLibs/arm64-v8a/libcodex_app_server.so"
host_artifact="$test_root/jniLibs/arm64-v8a/libcodex_code_mode_host.so"
"$runtime_dir/scripts/verify-package.sh" runtime "$artifact"
"$runtime_dir/scripts/verify-package.sh" codeModeHost "$host_artifact"

tampered="$test_root/libcodex_app_server.so"
cp "$artifact" "$tampered"
printf 'x' >> "$tampered"
if "$runtime_dir/scripts/verify-package.sh" runtime "$tampered" >/dev/null 2>&1; then
  echo "tampered runtime unexpectedly verified" >&2
  exit 1
fi

tampered_host="$test_root/libcodex_code_mode_host.so"
cp "$host_artifact" "$tampered_host"
printf 'x' >> "$tampered_host"
if "$runtime_dir/scripts/verify-package.sh" codeModeHost "$tampered_host" >/dev/null 2>&1; then
  echo "tampered code-mode host unexpectedly verified" >&2
  exit 1
fi

echo "runtime packaging regression test passed"
