#!/usr/bin/env bash
set -euo pipefail
old_root="java/stubs/felix"
if [[ -d "$old_root" ]]; then
  rm -rf "$old_root"
  echo "Removed moved OSGi stub source root: $old_root"
fi
