#!/usr/bin/env bash
set -euo pipefail
old_root="java/org.apache.derby.optionaltools"
new_root="delosdb-optionaltools/src/main/java"

if [[ ! -d "$new_root" ]]; then
  echo "Missing moved optionaltools source root: $new_root" >&2
  exit 1
fi

if [[ -d "$old_root" ]]; then
  rm -rf "$old_root"
  echo "Removed moved optional tools source root: $old_root"
fi
