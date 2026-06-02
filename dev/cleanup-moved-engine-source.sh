#!/usr/bin/env bash
set -euo pipefail

old_root="java/org.apache.derby.engine"
new_root="delosdb-engine/src/main/java"
required_file="$new_root/org/apache/derby/impl/sql/compile/sqlgrammar.jj"

if [[ ! -f "$required_file" ]]; then
  echo "Refusing to remove $old_root: expected moved engine file not found: $required_file" >&2
  exit 1
fi

if [[ -d "$old_root" ]]; then
  rm -rf "$old_root"
  echo "Removed moved engine source root: $old_root"
fi
