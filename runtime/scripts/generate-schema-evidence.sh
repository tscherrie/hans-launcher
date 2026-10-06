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

assert_digest() {
  actual=$(sha256_file "$1")
  [ "$actual" = "$2" ] || {
    echo "SHA-256 mismatch for $1" >&2
    exit 1
  }
}

assert_digest_and_size() {
  assert_digest "$1" "$2"
  actual_size=$(wc -c < "$1" | tr -d ' ')
  [ "$actual_size" = "$3" ] || {
    echo "byte-count mismatch for $1" >&2
    exit 1
  }
}

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
runtime_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
lock_file="$runtime_dir/runtime.lock.json"
output_dir=${1:-"$runtime_dir/build/schema"}

command -v jq >/dev/null 2>&1 || {
  echo "jq is required" >&2
  exit 2
}
runtime_version=$(jq -er '.runtime.version' "$lock_file")
download_dir=${HANS_RUNTIME_DOWNLOAD_DIR:-"$runtime_dir/build/downloads/$runtime_version"}

asset_url=$(jq -er '.schemaGenerator.releaseUrl' "$lock_file")
asset_name=$(jq -er '.schemaGenerator.releaseAsset' "$lock_file")
archive_sha=$(jq -er '.schemaGenerator.archiveSha256' "$lock_file")
archive_bytes=$(jq -er '.schemaGenerator.archiveBytes' "$lock_file")
binary_sha=$(jq -er '.schemaGenerator.extractedSha256' "$lock_file")
binary_bytes=$(jq -er '.schemaGenerator.extractedBytes' "$lock_file")
schema_sha=$(jq -er '.schemaGenerator.combinedV2SchemaSha256' "$lock_file")
schema_bytes=$(jq -er '.schemaGenerator.combinedV2SchemaBytes' "$lock_file")
binary_name=${asset_name%.tar.gz}

mkdir -p "$download_dir" "$output_dir"
archive="$download_dir/$asset_name"
temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/hans-codex-schema.XXXXXX")
trap 'rm -rf -- "$temporary_dir"' 0 HUP INT TERM

if [ ! -f "$archive" ]; then
  if [ "${HANS_RUNTIME_OFFLINE-0}" = 1 ]; then
    echo "Offline schema generation: missing pinned archive $archive" >&2
    exit 1
  fi
  curl --fail --location --retry 3 --output "$temporary_dir/$asset_name" "$asset_url"
  assert_digest_and_size "$temporary_dir/$asset_name" "$archive_sha" "$archive_bytes"
  mv "$temporary_dir/$asset_name" "$archive"
fi

assert_digest_and_size "$archive" "$archive_sha" "$archive_bytes"
tar -xzf "$archive" -C "$temporary_dir" "$binary_name"
assert_digest_and_size "$temporary_dir/$binary_name" "$binary_sha" "$binary_bytes"

schema_build_dir="$temporary_dir/schema"
mkdir -p "$schema_build_dir"
"$temporary_dir/$binary_name" app-server generate-json-schema \
  --out "$schema_build_dir" --experimental
generated_schema="$schema_build_dir/codex_app_server_protocol.v2.schemas.json"
assert_digest_and_size "$generated_schema" "$schema_sha" "$schema_bytes"
install -m 0644 "$generated_schema" \
  "$output_dir/codex_app_server_protocol.v2.schemas.json"

printf '%s\n' "$output_dir/codex_app_server_protocol.v2.schemas.json"
