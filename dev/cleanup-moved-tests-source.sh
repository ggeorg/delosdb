#!/usr/bin/env bash
set -euo pipefail
old_root="java/org.apache.derby.tests"
if [[ -d "$old_root" ]]; then
  rm -rf "$old_root"
  echo "Removed moved test source root: $old_root"
else
  echo "Already removed: $old_root"
fi
