#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
runtime_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
lock_file="$runtime_dir/runtime.lock.json"
schema="$runtime_dir/evidence/codex_app_server_protocol.v2.schemas.json"

jq -e '
  .schemaVersion == 1 and
  .upstream.tag == "rust-v0.154.0" and
  .upstream.commit == "6b9826e3aa83b1a5947db50f4332cb9c65f1b340" and
  .runtime.minimumAndroidApi == 31 and
  .runtime.abi == "arm64-v8a" and
  .runtime.version == "0.154.0" and
  .codeModeHost.version == "0.154.0" and
  .schemaGenerator.extractedBytes == 227482840 and
  .schemaGenerator.combinedV2SchemaBytes == 723247
' "$lock_file" >/dev/null

expected_schema_sha=$(jq -er '.schemaGenerator.combinedV2SchemaSha256' "$lock_file")
if command -v sha256sum >/dev/null 2>&1; then
  actual_schema_sha=$(sha256sum "$schema" | awk '{print $1}')
else
  actual_schema_sha=$(shasum -a 256 "$schema" | awk '{print $1}')
fi

[ "$expected_schema_sha" = "$actual_schema_sha" ] || {
  echo "checked-in schema does not match runtime lock" >&2
  exit 1
}

expected_schema_bytes=$(jq -er '.schemaGenerator.combinedV2SchemaBytes' "$lock_file")
actual_schema_bytes=$(wc -c < "$schema" | tr -d ' ')
[ "$expected_schema_bytes" = "$actual_schema_bytes" ] || {
  echo "checked-in schema byte count does not match runtime lock" >&2
  exit 1
}

echo "runtime lock and schema evidence test passed"
