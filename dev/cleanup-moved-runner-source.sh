#!/usr/bin/env bash
set -euo pipefail

old_root="java/org.apache.derby.runner"
new_root="delosdb-runner/src/main/java"

if [[ ! -d "$new_root" ]]; then
  echo "New runner source root is missing: $new_root" >&2
  exit 1
fi

if [[ -d "$old_root" ]]; then
  rm -rf "$old_root"
  echo "Removed moved runner source root: $old_root"
else
  echo "Old runner source root already absent: $old_root"
fi
