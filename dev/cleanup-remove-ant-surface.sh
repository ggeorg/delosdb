#!/usr/bin/env bash
set -euo pipefail

rm -f build.xml
rm -rf tools/ant

printf '%s\n' 'Removed legacy Ant build surface:'
printf '%s\n' '  - build.xml'
printf '%s\n' '  - tools/ant/'
printf '%s\n' 'Gradle remains the supported DelosDB build path.'
