#!/usr/bin/env bash
set -euo pipefail
old_root="java/storeless"
if [[ -d "$old_root" ]]; then
  rm -rf "$old_root"
  echo "Removed moved storeless source root: $old_root"
fi
