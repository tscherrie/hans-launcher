#!/bin/sh
set -eu

case "${HANS_RUNTIME_OFFLINE-0}" in
  0|1) ;;
  *) echo "HANS_RUNTIME_OFFLINE must be 0 (online) or 1 (offline)" >&2; exit 2 ;;
esac

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

assert_equal() {
  expected=$1
  actual=$2
  label=$3
  if [ "$expected" != "$actual" ]; then
    echo "$label mismatch: expected $expected, got $actual" >&2
    exit 1
  fi
}

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
runtime_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
lock_file="$runtime_dir/runtime.lock.json"
output_dir=${1:-"$runtime_dir/build/generated/jniLibs/arm64-v8a"}

command -v jq >/dev/null 2>&1 || {
  echo "jq is required" >&2
  exit 2
}
runtime_version=$(jq -er '.runtime.version' "$lock_file")
download_dir=${HANS_RUNTIME_DOWNLOAD_DIR:-"$runtime_dir/build/downloads/$runtime_version"}
jq -e '.runtime.version == .codeModeHost.version' "$lock_file" >/dev/null || {
  echo "App Server and Code Mode host versions must match" >&2
  exit 1
}

mkdir -p "$download_dir" "$output_dir"
temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/hans-codex-runtime.XXXXXX")
trap 'rm -rf -- "$temporary_dir"' 0 HUP INT TERM

stage_component() {
  lock_key=$1
  asset_url=$(jq -er ".$lock_key.releaseUrl" "$lock_file")
  asset_name=$(jq -er ".$lock_key.releaseAsset" "$lock_file")
  archive_sha=$(jq -er ".$lock_key.archiveSha256" "$lock_file")
  archive_bytes=$(jq -er ".$lock_key.archiveBytes" "$lock_file")
  payload_name=$(jq -er ".$lock_key.extractedName" "$lock_file")
  payload_sha=$(jq -er ".$lock_key.extractedSha256" "$lock_file")
  payload_bytes=$(jq -er ".$lock_key.extractedBytes" "$lock_file")
  apk_name=$(jq -er ".$lock_key.apkLibraryName" "$lock_file")
  archive="$download_dir/$asset_name"

  if [ ! -f "$archive" ]; then
    if [ "${HANS_RUNTIME_OFFLINE-0}" = 1 ]; then
      echo "Offline runtime packaging: missing pinned archive $archive; populate the verified cache before building offline" >&2
      exit 1
    fi
    curl --fail --location --retry 3 --output "$temporary_dir/$asset_name" "$asset_url"
    assert_equal "$archive_bytes" "$(wc -c < "$temporary_dir/$asset_name" | tr -d ' ')" "$lock_key release archive byte count"
    assert_equal "$archive_sha" "$(sha256_file "$temporary_dir/$asset_name")" "$lock_key release archive SHA-256"
    mv "$temporary_dir/$asset_name" "$archive"
  fi

  actual_archive_bytes=$(wc -c < "$archive" | tr -d ' ')
  actual_archive_sha=$(sha256_file "$archive")
  assert_equal "$archive_bytes" "$actual_archive_bytes" "$lock_key release archive byte count"
  assert_equal "$archive_sha" "$actual_archive_sha" "$lock_key release archive SHA-256"

  tar -xzf "$archive" -C "$temporary_dir" "$payload_name"
  payload="$temporary_dir/$payload_name"
  actual_payload_bytes=$(wc -c < "$payload" | tr -d ' ')
  actual_payload_sha=$(sha256_file "$payload")
  assert_equal "$payload_bytes" "$actual_payload_bytes" "$lock_key executable byte count"
  assert_equal "$payload_sha" "$actual_payload_sha" "$lock_key executable SHA-256"

  install -m 0755 "$payload" "$temporary_dir/$apk_name"
  "$script_dir/verify-package.sh" "$lock_key" "$temporary_dir/$apk_name"
}

# Validate the complete matching pair before replacing either APK input. A bad
# second archive must leave the previously generated pair untouched.
stage_component runtime
stage_component codeModeHost
for component in runtime codeModeHost; do
  apk_name=$(jq -er ".$component.apkLibraryName" "$lock_file")
  install -m 0755 "$temporary_dir/$apk_name" "$output_dir/$apk_name"
  printf '%s\n' "$output_dir/$apk_name"
done
