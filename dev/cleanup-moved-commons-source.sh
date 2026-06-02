#!/usr/bin/env bash
set -euo pipefail

new_root="delosdb-commons/src/main/java/org/apache/derby/shared"
old_root="java/org.apache.derby.commons"

if [[ ! -d "$new_root" ]]; then
  echo "Refusing to remove $old_root because $new_root was not found." >&2
  exit 1
fi

rm -rf "$old_root"
echo "Removed moved source root: $old_root"
