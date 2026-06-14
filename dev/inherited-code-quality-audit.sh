#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT_DIR/build/reports/inherited-code-quality"
REPORT_FILE="$REPORT_DIR/inherited-code-quality-audit.md"
VERIFY=false

if [[ "${1:-}" == "--verify" ]]; then
  VERIFY=true
fi

mkdir -p "$REPORT_DIR"

PRODUCTION_ROOTS=(
  "$ROOT_DIR/delosdb-client/src/main/java"
  "$ROOT_DIR/delosdb-commons/src/main/java"
  "$ROOT_DIR/delosdb-engine/src/main/java"
  "$ROOT_DIR/delosdb-optionaltools/src/main/java"
  "$ROOT_DIR/delosdb-runner/src/main/java"
  "$ROOT_DIR/delosdb-server/src/main/java"
  "$ROOT_DIR/delosdb-tools/src/main/java"
)

TEST_AND_SUPPORT_ROOTS=(
  "$ROOT_DIR/delosdb-tests/src/test/java"
  "$ROOT_DIR/delosdb-pptesting/src/test/java"
  "$ROOT_DIR/delosdb-storeless/src/main/java"
  "$ROOT_DIR/delosdb-buildtools/src/main/java"
  "$ROOT_DIR/delosdb-demos/src/main/demo"
)

existing_roots() {
  local root
  for root in "$@"; do
    [[ -d "$root" ]] && printf '%s\0' "$root"
  done
}

all_java_roots=()
for root in "${PRODUCTION_ROOTS[@]}" "${TEST_AND_SUPPORT_ROOTS[@]}"; do
  [[ -d "$root" ]] && all_java_roots+=("$root")
done

count_matches() {
  local pattern="$1"
  shift
  local roots=("$@")
  if [[ ${#roots[@]} -eq 0 ]]; then
    echo 0
    return
  fi
  {
    grep -R --include='*.java' -E "$pattern" "${roots[@]}" 2>/dev/null \
      | grep -Ev '^[^:]+:[[:space:]]*(//|/\*|\*)|^[[:space:]]*(//|/\*|\*)' \
      || true
  } | wc -l | tr -d ' '
}

write_matches() {
  local title="$1"
  local pattern="$2"
  shift 2
  local roots=("$@")
  {
    echo
    echo "## $title"
    echo
    if [[ ${#roots[@]} -eq 0 ]]; then
      echo "No source roots found."
      return
    fi
    grep -R --include='*.java' -n -E "$pattern" "${roots[@]}" 2>/dev/null \
      | sed "s#${ROOT_DIR}/##" \
      | grep -Ev '^[^:]+:[0-9]+:[[:space:]]*(//|/\*|\*)' \
      | head -200 \
      || true
  } >> "$REPORT_FILE"
}

write_file_list() {
  local title="$1"
  shift
  {
    echo
    echo "## $title"
    echo
    if [[ "$#" -eq 0 ]]; then
      echo "None found."
      return
    fi
    printf '%s\n' "$@" | sed "s#${ROOT_DIR}/##" | sort | head -200
  } >> "$REPORT_FILE"
}

find_single_file() {
  local relative_path="$1"
  if [[ -f "$ROOT_DIR/$relative_path" ]]; then
    printf '%s' "$ROOT_DIR/$relative_path"
  fi
}

vm_descriptor="$(find_single_file 'delosdb-engine/src/main/java/org/apache/derby/iapi/services/classfile/VMDescriptor.java')"
reflect_generated_class="$(find_single_file 'delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectGeneratedClass.java')"
reflect_method="$(find_single_file 'delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectMethod.java')"
legacy_cost_bridge="$(find_single_file 'delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/index/IndexProviderCostBridge.java')"

production_finalizers="$(count_matches 'protected[[:space:]]+void[[:space:]]+finalize[[:space:]]*\(' "${PRODUCTION_ROOTS[@]}")"
all_finalizers="$(count_matches 'protected[[:space:]]+void[[:space:]]+finalize[[:space:]]*\(' "${all_java_roots[@]}")"
string_buffer_input_stream="$(count_matches 'StringBufferInputStream' "${PRODUCTION_ROOTS[@]}")"
thread_stop_suspend_resume="$(count_matches '\.stop[[:space:]]*\(|\.suspend[[:space:]]*\(|\.resume[[:space:]]*\(' "${PRODUCTION_ROOTS[@]}")"
xml_factory_usage="$(count_matches 'DocumentBuilderFactory|SAXParserFactory|XMLInputFactory|TransformerFactory' "${PRODUCTION_ROOTS[@]}")"
reflection_invocation_usage="$(count_matches '\.invoke[[:space:]]*\(|getMethod[[:space:]]*\(|getDeclaredMethod[[:space:]]*\(' "${PRODUCTION_ROOTS[@]}")"
xplain_descriptor_constructors="$(count_matches 'new[[:space:]]+XPLAINResultSetDescriptor[[:space:]]*\(' "$ROOT_DIR/delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/rts")"

harness_files=()
if [[ -d "$ROOT_DIR/delosdb-tests/src/test/java/org/apache/derbyTesting/functionTests/harness" ]]; then
  while IFS= read -r -d '' file; do
    harness_files+=("$file")
  done < <(find "$ROOT_DIR/delosdb-tests/src/test/java/org/apache/derbyTesting/functionTests/harness" \
    -maxdepth 1 -type f \( -name 'jdk*.java' -o -name 'RunSuite.java' -o -name 'RunTest.java' \) -print0 | sort -z)
fi
legacy_harness_count="${#harness_files[@]}"

module_info_files=()
while IFS= read -r -d '' file; do
  module_info_files+=("$file")
done < <(find "$ROOT_DIR" \
  -path "$ROOT_DIR/.git" -prune -o \
  -path "$ROOT_DIR/.gradle" -prune -o \
  -path "$ROOT_DIR/.idea" -prune -o \
  -path "$ROOT_DIR/build" -prune -o \
  -name 'module-info.java' -type f -print0 | sort -z)

module_export_count=0
internal_export_count=0
for file in "${module_info_files[@]}"; do
  exports_in_file="$({ grep -E '^[[:space:]]*exports[[:space:]]+' "$file" 2>/dev/null || true; } | wc -l | tr -d ' ')"
  module_export_count=$((module_export_count + exports_in_file))
  internal_in_file="$({ grep -E '^[[:space:]]*exports[[:space:]]+.*(\.impl|\.iapi|\.catalog|\.diag|\.vti)' "$file" 2>/dev/null || true; } | wc -l | tr -d ' ')"
  internal_export_count=$((internal_export_count + internal_in_file))
done

classfile_lines=""
if [[ -n "$vm_descriptor" ]]; then
  classfile_lines="$(grep -n -E 'JAVA_CLASS_FORMAT_MAJOR_VERSION|JAVA_CLASS_FORMAT_MINOR_VERSION|major_version|minor_version|45|3' "$vm_descriptor" | sed "s#${ROOT_DIR}/##" || true)"
fi

cat > "$REPORT_FILE" <<EOF_REPORT
# DelosDB Inherited Code Quality Audit

Generated by \`dev/inherited-code-quality-audit.sh\`.

This is a guardrail report for inherited Apache Derby implementation code. It does not claim that a match is automatically wrong. It identifies cleanup and JVM 21 modernization candidates that must be handled with compatibility tests before behavior changes.

## Summary

| Area | Count |
|---|---:|
| Production Object finalizer overrides | ${production_finalizers} |
| All-tree Object finalizer overrides | ${all_finalizers} |
| Production StringBufferInputStream references | ${string_buffer_input_stream} |
| Production lifecycle stop/suspend/resume-looking calls | ${thread_stop_suspend_resume} |
| Production XML factory references | ${xml_factory_usage} |
| Production reflection method lookup/invoke references | ${reflection_invocation_usage} |
| Runtime statistics XPLAIN descriptor constructors | ${xplain_descriptor_constructors} |
| Legacy Derby harness launcher/JVM files | ${legacy_harness_count} |
| module-info.java files | ${#module_info_files[@]} |
| JPMS exports | ${module_export_count} |
| JPMS internal-looking exports | ${internal_export_count} |

## Generated bytecode version landmark

Expected source file:

\`delosdb-engine/src/main/java/org/apache/derby/iapi/services/classfile/VMDescriptor.java\`

\`\`\`text
${classfile_lines:-VMDescriptor.java not found.}
\`\`\`

Modernization note: do not bump Derby generated classfile versions blindly. First prove generated activation loading and verifier behavior on Java 21.

EOF_REPORT

write_matches "Production StringBufferInputStream references" 'StringBufferInputStream' "${PRODUCTION_ROOTS[@]}"
write_matches "Production Object finalizer overrides" 'protected[[:space:]]+void[[:space:]]+finalize[[:space:]]*\(' "${PRODUCTION_ROOTS[@]}"
write_matches "Production lifecycle stop/suspend/resume-looking calls" '\.stop[[:space:]]*\(|\.suspend[[:space:]]*\(|\.resume[[:space:]]*\(' "${PRODUCTION_ROOTS[@]}"
write_matches "Production XML factory references" 'DocumentBuilderFactory|SAXParserFactory|XMLInputFactory|TransformerFactory' "${PRODUCTION_ROOTS[@]}"
write_matches "Production reflection method lookup/invoke references" '\.invoke[[:space:]]*\(|getMethod[[:space:]]*\(|getDeclaredMethod[[:space:]]*\(' "${PRODUCTION_ROOTS[@]}"
write_matches "Runtime statistics XPLAIN descriptor constructors" 'new[[:space:]]+XPLAINResultSetDescriptor[[:space:]]*\(' "$ROOT_DIR/delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/rts"
write_file_list "Legacy Derby harness launcher/JVM files" "${harness_files[@]}"

{
  echo
  echo "## JPMS exports"
  echo
  if [[ ${#module_info_files[@]} -eq 0 ]]; then
    echo "No module-info.java files found."
  else
    for file in "${module_info_files[@]}"; do
      rel="${file#${ROOT_DIR}/}"
      echo "### \`$rel\`"
      echo
      grep -n -E '^[[:space:]]*exports[[:space:]]+' "$file" | sed "s#^#${rel}:#" || true
      echo
    done
  fi

  echo
  echo "## Generated execution dispatch landmarks"
  echo
  for file in "$reflect_generated_class" "$reflect_method"; do
    if [[ -n "$file" ]]; then
      rel="${file#${ROOT_DIR}/}"
      echo
      echo "### \`$rel\`"
      echo
      grep -n -E 'GeneratedMethod|Method|invoke|getMethod|getDeclaredMethod' "$file" | head -120 | sed "s#^#${rel}:#" || true
    fi
  done

  echo
  echo "## Legacy cost bridge landmark"
  echo
  if [[ -n "$legacy_cost_bridge" ]]; then
    rel="${legacy_cost_bridge#${ROOT_DIR}/}"
    echo
    echo "\`$rel\` exists. Keep it diagnostic-only unless a later cleanup removes it with tests."
    grep -n -E 'legacy|diagnostic|CostModelProvider|IndexProviderCostBridge|estimate' "$legacy_cost_bridge" | head -120 | sed "s#^#${rel}:#" || true
  else
    echo "IndexProviderCostBridge.java not found."
  fi
} >> "$REPORT_FILE"

if [[ "$VERIFY" == true ]]; then
  failed=false

  required_files=(
    "$ROOT_DIR/delosdb-engine/src/main/java/org/apache/derby/iapi/services/classfile/VMDescriptor.java"
    "$ROOT_DIR/delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectGeneratedClass.java"
    "$ROOT_DIR/delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectMethod.java"
    "$ROOT_DIR/delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/rts/RealTableScanStatistics.java"
  )

  for file in "${required_files[@]}"; do
    if [[ ! -f "$file" ]]; then
      echo "Inherited code quality audit failed: required landmark file is missing: ${file#${ROOT_DIR}/}" >&2
      failed=true
    fi
  done

  if [[ "$xplain_descriptor_constructors" == "0" ]]; then
    echo "Inherited code quality audit failed: no XPLAIN descriptor constructors were found; update the audit pattern." >&2
    failed=true
  fi

  if [[ "$module_export_count" == "0" ]]; then
    echo "Inherited code quality audit failed: no JPMS exports were found; update the audit pattern." >&2
    failed=true
  fi

  if [[ "$failed" == true ]]; then
    echo "See $REPORT_FILE" >&2
    exit 1
  fi
fi

echo "Inherited code quality audit written to $REPORT_FILE"
