#!/usr/bin/env bash
set -euo pipefail
old_root="java/pptesting"
new_root="delosdb-pptesting/src/test/java/org"
legacy_build="delosdb-pptesting/src/test/legacy/build.xml"

if [[ ! -d "$new_root" ]]; then
  echo "Missing moved package-private test source root: $new_root" >&2
  exit 1
fi

if [[ ! -f "$legacy_build" ]]; then
  echo "Missing moved package-private legacy build file: $legacy_build" >&2
  exit 1
fi

rm -rf "$old_root"
