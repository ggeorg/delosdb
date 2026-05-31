#!/usr/bin/env python3
"""Surgical DelosDB Java 21 cleanup for low-risk ij tooling collections.

This updater intentionally patches the current working tree instead of
replacing whole files, so it preserves local DelosDB edits already applied in
this modernization branch.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path.cwd()

SESSION = ROOT / "java/org.apache.derby.tools/org/apache/derby/impl/tools/ij/Session.java"
IJ_VECTOR_RESULT = ROOT / "java/org.apache.derby.tools/org/apache/derby/impl/tools/ij/ijVectorResult.java"
XA_HELPER = ROOT / "java/org.apache.derby.tools/org/apache/derby/impl/tools/ij/xaHelper.java"


def read(path: Path) -> str:
    if not path.exists():
        raise SystemExit(f"Missing expected file: {path}")
    return path.read_text(encoding="utf-8")


def write_if_changed(path: Path, before: str, after: str) -> bool:
    if before == after:
        print(f"unchanged: {path}")
        return False
    path.write_text(after, encoding="utf-8")
    print(f"updated:   {path}")
    return True


def remove_import(text: str, fqcn: str) -> str:
    return re.sub(rf"^import\s+{re.escape(fqcn)};\s*\n", "", text, flags=re.MULTILINE)


def add_import(text: str, fqcn: str) -> str:
    line = f"import {fqcn};"
    if line in text:
        return text
    imports = list(re.finditer(r"^import\s+[^;]+;\s*$", text, flags=re.MULTILINE))
    if not imports:
        # Fallback: insert after package line.
        return re.sub(r"^(package\s+[^;]+;\s*)$", r"\1\n" + line, text, count=1, flags=re.MULTILINE)
    insert_at = imports[-1].end()
    return text[:insert_at] + "\n" + line + text[insert_at:]


def replace_required(text: str, pattern: str, replacement: str, label: str, flags: int = 0) -> tuple[str, int]:
    new_text, count = re.subn(pattern, replacement, text, flags=flags)
    if count:
        print(f"  {label}: {count}")
    return new_text, count


def patch_session() -> bool:
    path = SESSION
    original = read(path)
    text = original

    text = remove_import(text, "java.util.Hashtable")
    text = add_import(text, "java.util.HashMap")
    text = add_import(text, "java.util.Map")

    replacements = [
        (
            r"\bHashtable\s*<\s*String\s*,\s*PreparedStatement\s*>\s+prepStmts\s*=\s*new\s+Hashtable\s*<\s*String\s*,\s*PreparedStatement\s*>\s*\(\s*\)\s*;",
            "Map<String,PreparedStatement> prepStmts = new HashMap<String,PreparedStatement>();",
            "prepared statement map",
        ),
        (
            r"\bHashtable\s+prepStmts\s*=\s*new\s+Hashtable\s*\(\s*\)\s*;",
            "Map<String,PreparedStatement> prepStmts = new HashMap<String,PreparedStatement>();",
            "raw prepared statement map",
        ),
        (
            r"\bHashtable\s*<\s*String\s*,\s*Statement\s*>\s+cursorStmts\s*=\s*new\s+Hashtable\s*<\s*String\s*,\s*Statement\s*>\s*\(\s*\)\s*;",
            "Map<String,Statement> cursorStmts = new HashMap<String,Statement>();",
            "cursor statement map",
        ),
        (
            r"\bHashtable\s+cursorStmts\s*=\s*new\s+Hashtable\s*\(\s*\)\s*;",
            "Map<String,Statement> cursorStmts = new HashMap<String,Statement>();",
            "raw cursor statement map",
        ),
        (
            r"\bHashtable\s*<\s*String\s*,\s*ResultSet\s*>\s+cursors\s*=\s*new\s+Hashtable\s*<\s*String\s*,\s*ResultSet\s*>\s*\(\s*\)\s*;",
            "Map<String,ResultSet> cursors = new HashMap<String,ResultSet>();",
            "cursor result set map",
        ),
        (
            r"\bHashtable\s+cursors\s*=\s*new\s+Hashtable\s*\(\s*\)\s*;",
            "Map<String,ResultSet> cursors = new HashMap<String,ResultSet>();",
            "raw cursor result set map",
        ),
        (
            r"\bHashtable\s*<\s*String\s*,\s*AsyncStatement\s*>\s+asyncStmts\s*=\s*new\s+Hashtable\s*<\s*String\s*,\s*AsyncStatement\s*>\s*\(\s*\)\s*;",
            "Map<String,AsyncStatement> asyncStmts = new HashMap<String,AsyncStatement>();",
            "async statement map",
        ),
        (
            r"\bHashtable\s+asyncStmts\s*=\s*new\s+Hashtable\s*\(\s*\)\s*;",
            "Map<String,AsyncStatement> asyncStmts = new HashMap<String,AsyncStatement>();",
            "raw async statement map",
        ),
    ]

    total = 0
    for pattern, replacement, label in replacements:
        text, count = replace_required(text, pattern, replacement, label)
        total += count

    if "Hashtable" in text and total > 0:
        # This file should no longer need Hashtable after the four maps move.
        # Leave comments alone, but fail on code/import references.
        code_without_comments = re.sub(r"/\*.*?\*/|//.*", "", text, flags=re.DOTALL)
        if "Hashtable" in code_without_comments:
            raise SystemExit(f"Session.java still contains Hashtable in code after patch; inspect manually: {path}")

    if total == 0 and "HashMap" not in text:
        raise SystemExit("No Session.java map replacements were applied; file shape was not recognized.")

    return write_if_changed(path, original, text)


def patch_ij_vector_result() -> bool:
    path = IJ_VECTOR_RESULT
    original = read(path)
    text = original

    text = remove_import(text, "java.util.Vector")
    text = add_import(text, "java.util.ArrayList")
    text = add_import(text, "java.util.List")

    total = 0
    patterns = [
        (r"\bVector\s*<\s*Object\s*>\s+vec\s*;", "private final List<Object> vec;", "typed vector field"),
        (r"\bVector\s+vec\s*;", "private final List<Object> vec;", "raw vector field"),
        (r"ijVectorResult\s*\(\s*Vector\s*<\s*Object\s*>\s+v\s*,\s*SQLWarning\s+w\s*\)", "ijVectorResult(List<Object> v, SQLWarning w)", "typed vector constructor"),
        (r"ijVectorResult\s*\(\s*Vector\s+v\s*,\s*SQLWarning\s+w\s*\)", "ijVectorResult(List<Object> v, SQLWarning w)", "raw vector constructor"),
        (r"this\s*\(\s*new\s+Vector\s*<\s*Object\s*>\s*\(\s*1\s*\)\s*,\s*w\s*\)\s*;", "this(new ArrayList<Object>(1), w);", "typed single-value vector init"),
        (r"this\s*\(\s*new\s+Vector\s*\(\s*1\s*\)\s*,\s*w\s*\)\s*;", "this(new ArrayList<Object>(1), w);", "raw single-value vector init"),
    ]
    for pattern, replacement, label in patterns:
        text, count = replace_required(text, pattern, replacement, label)
        total += count

    get_vector_replacement = (
        "@SuppressWarnings({ \"rawtypes\", \"unchecked\" })\n"
        "    public java.util.Vector getVector() {\n"
        "        return new java.util.Vector(vec);\n"
        "    }"
    )
    text, count = replace_required(
        text,
        r"public\s+(?:java\.util\.)?Vector(?:\s*<\s*Object\s*>)?\s+getVector\s*\(\s*\)\s*\{\s*return\s+vec\s*;\s*\}",
        get_vector_replacement,
        "Vector-returning compatibility method",
        flags=re.DOTALL,
    )
    total += count

    if total == 0 and "List<Object>" not in text:
        raise SystemExit("No ijVectorResult.java replacements were applied; file shape was not recognized.")

    return write_if_changed(path, original, text)


def patch_xa_helper() -> bool:
    path = XA_HELPER
    original = read(path)
    text = original

    text = remove_import(text, "java.util.Vector")
    text = add_import(text, "java.util.ArrayList")
    text = add_import(text, "java.util.List")

    total = 0
    replacements = [
        (
            r"\bVector\s*<\s*String\s*>\s+v\s*=\s*new\s+Vector\s*<\s*String\s*>\s*\(\s*\)\s*;",
            "List<Object> v = new ArrayList<Object>();",
            "XA recovery vector to list",
        ),
        (
            r"\bVector\s+v\s*=\s*new\s+Vector\s*\(\s*\)\s*;",
            "List<Object> v = new ArrayList<Object>();",
            "raw XA recovery vector to list",
        ),
    ]
    for pattern, replacement, label in replacements:
        text, count = replace_required(text, pattern, replacement, label)
        total += count

    text, count = replace_required(text, r"\bv\.addElement\s*\(", "v.add(", "XA recovery addElement to add")
    total += count

    if total == 0 and "ArrayList<Object>" not in text:
        raise SystemExit("No xaHelper.java replacements were applied; file shape was not recognized.")

    return write_if_changed(path, original, text)


def main() -> None:
    changed = 0
    changed += patch_session()
    changed += patch_ij_vector_result()
    changed += patch_xa_helper()
    print(f"\nDone. Files changed: {changed}")
    print("Run: ./gradlew clean build && ./gradlew fullVerification && ./dev/modernization-audit.sh --verify")


if __name__ == "__main__":
    main()
