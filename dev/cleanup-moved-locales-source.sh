#!/usr/bin/env bash
set -euo pipefail
old_root="java/locales"
new_root="delosdb-locales/src/main/templates"
if [[ ! -d "$new_root" ]]; then
  echo "Missing moved locales template root: $new_root" >&2
  exit 1
fi
if [[ -d "$old_root" ]]; then
  rm -rf "$old_root"
  echo "Removed moved locales template root: $old_root"
fi
