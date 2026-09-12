#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
runtime_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
lock_file="$runtime_dir/runtime.lock.json"
lock_key=${1:?"usage: verify-package.sh LOCK_KEY PATH_TO_EXECUTABLE"}
artifact=${2:?"usage: verify-package.sh LOCK_KEY PATH_TO_EXECUTABLE"}

command -v jq >/dev/null 2>&1 || {
  echo "jq is required" >&2
  exit 2
}

case "$lock_key" in
  runtime|codeModeHost) ;;
  *)
    echo "unsupported runtime lock key: $lock_key" >&2
    exit 2
    ;;
esac

expected_name=$(jq -er ".$lock_key.apkLibraryName" "$lock_file")
expected_bytes=$(jq -er ".$lock_key.extractedBytes" "$lock_file")
expected_sha=$(jq -er ".$lock_key.extractedSha256" "$lock_file")

[ -f "$artifact" ] || {
  echo "runtime artifact is not a regular file: $artifact" >&2
  exit 1
}
[ "$(basename -- "$artifact")" = "$expected_name" ] || {
  echo "$lock_key APK library must be named $expected_name" >&2
  exit 1
}
[ -x "$artifact" ] || {
  echo "runtime artifact is not executable: $artifact" >&2
  exit 1
}

actual_bytes=$(wc -c < "$artifact" | tr -d ' ')
if command -v sha256sum >/dev/null 2>&1; then
  actual_sha=$(sha256sum "$artifact" | awk '{print $1}')
else
  actual_sha=$(shasum -a 256 "$artifact" | awk '{print $1}')
fi

[ "$actual_bytes" = "$expected_bytes" ] || {
  echo "runtime byte count mismatch" >&2
  exit 1
}
[ "$actual_sha" = "$expected_sha" ] || {
  echo "runtime SHA-256 mismatch" >&2
  exit 1
}

description=$(file -b "$artifact")
case "$description" in
  *ELF*64-bit*ARM*aarch64*statically\ linked*) ;;
  *)
    echo "unexpected runtime ELF: $description" >&2
    exit 1
    ;;
esac

printf 'verified %s %s\n' "$actual_sha" "$artifact"
