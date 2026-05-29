#!/usr/bin/env bash
set -euo pipefail

repo_name="${1:-delosdb}"
remote_owner="${2:-ggeorg}"

if [ ! -d .git ]; then
  git init
fi

git add .
git commit -m "Bootstrap DelosDB fork" || true

echo "Create the GitHub repository, then run:"
echo "  git branch -M main"
echo "  git remote add origin git@github.com:${remote_owner}/${repo_name}.git"
echo "  git push -u origin main"
