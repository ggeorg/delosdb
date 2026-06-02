#!/usr/bin/env bash
set -euo pipefail
old_root="java/build"
if [[ -d "$old_root" ]]; then
  rm -rf "$old_root"
fi
