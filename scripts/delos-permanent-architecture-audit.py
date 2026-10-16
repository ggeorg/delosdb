#!/usr/bin/env python3
"""Permanent DelosDB module and architecture verification.

This executable is intentionally run only by verification tasks. Gradle
configuration remains limited to task registration and input/output wiring.
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path
from typing import Iterable


REQUIRED_SECTIONS = {
    "target-modules",
    "deferred-modules",
    "retired-modules",
    "retired-artifacts",
    "required-provider-artifacts",
    "deferred-provider-artifacts",
    "build-only-artifacts",
    "forbidden-production-edges",
}


def code_without_comments(source: str) -> str:
    out: list[str] = []
    i = 0
    state = "code"
    escaped = False
    while i < len(source):
        current = source[i]
        next_char = source[i + 1] if i + 1 < len(source) else ""
        triple = source.startswith('"""', i)

        if state == "line_comment":
            if current == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue

        if state == "block_comment":
            if current == "*" and next_char == "/":
                out.extend((" ", " "))
                state = "code"
                i += 2
            else:
                out.append("\n" if current == "\n" else " ")
                i += 1
            continue

        if state == "text_block":
            if triple:
                out.extend(('"', '"', '"'))
                state = "code"
                i += 3
            else:
                out.append(current)
                i += 1
            continue

        if state in {"single_quote", "double_quote"}:
            out.append(current)
            if escaped:
                escaped = False
            elif current == "\\":
                escaped = True
            elif state == "single_quote" and current == "'":
                state = "code"
            elif state == "double_quote" and current == '"':
                state = "code"
            i += 1
            continue

        if triple:
            out.extend(('"', '"', '"'))
            state = "text_block"
            i += 3
        elif current == "'":
            out.append(current)
            state = "single_quote"
            i += 1
        elif current == '"':
            out.append(current)
            state = "double_quote"
            i += 1
        elif current == "/" and next_char == "/":
            out.extend((" ", " "))
            state = "line_comment"
            i += 2
        elif current == "/" and next_char == "*":
            out.extend((" ", " "))
            state = "block_comment"
            i += 2
        else:
            out.append(current)
            i += 1
    return "".join(out)


def read_tsv(path: Path) -> list[list[str]]:
    if not path.is_file():
        raise ValueError(f"Missing architecture evidence file: {path}")
    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.reader(handle, delimiter="\t"))
    if not rows:
        raise ValueError(f"Empty architecture evidence file: {path}")
    return [row for row in rows[1:] if any(cell.strip() for cell in row)]


def load_sections(path: Path, required: set[str] = REQUIRED_SECTIONS) -> dict[str, list[str]]:
    if not path.is_file():
        raise ValueError(f"Missing architecture target: {path}")
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"\[([^]]+)]", line)
        if match:
            current = match.group(1)
            if current in sections:
                raise ValueError(f"Duplicate architecture target section [{current}]")
            sections[current] = []
        elif current is None:
            raise ValueError(
                f"Architecture target value outside a section at line {line_number}: {line}"
            )
        elif line in sections[current]:
            raise ValueError(
                f"Duplicate architecture target value in [{current}]: {line}"
            )
        else:
            sections[current].append(line)
    missing = sorted(required - sections.keys())
    unexpected = sorted(sections.keys() - required)
    if missing or unexpected:
        raise ValueError(
            "Architecture target sections differ from the permanent schema; "
            f"missing={missing}, unexpected={unexpected}"
        )
    return sections


def parse_edges(rows: Iterable[str], section: str) -> set[str]:
    edges: set[str] = set()
    for row in rows:
        match = re.fullmatch(r"([a-z0-9-]+)\s*->\s*([a-z0-9-]+)", row)
        if not match:
            raise ValueError(f"Invalid edge in [{section}]: {row}")
        edges.add(f"{match.group(1)} -> {match.group(2)}")
    return edges


PACKAGE_BOUNDARY_SECTIONS = {
    "module-projects",
    "package-groups",
    "allowed-group-edges",
    "allowed-package-edges",
}
JAVA_PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
STRING_LITERAL_RE = re.compile(r'"(?:\\.|[^"\\\n])*"')
DERBY_REFERENCE_RE = re.compile(r"\b(org\.apache\.derby\.[A-Za-z0-9_.]+)")


def package_group(package: str, groups: list[tuple[str, str]]) -> str:
    for group, prefix in groups:
        if prefix == "*" or package == prefix or package.startswith(prefix + "."):
            return group
    raise ValueError(f"No package group matches {package}")


def check_engine_package_boundaries(
    root: Path, report_dir: Path, manifest: Path
) -> tuple[list[str], list[str]]:
    """Returns evidence lines and failures for the engine's package groups."""
    sections = load_sections(manifest, PACKAGE_BOUNDARY_SECTIONS)
    module_projects = set(sections["module-projects"])
    groups = [tuple(row.split()) for row in sections["package-groups"]]
    if any(len(row) != 2 for row in groups):
        raise ValueError("Invalid [package-groups] row; expected: group package-prefix")
    allowed_groups = parse_edges(sections["allowed-group-edges"], "allowed-group-edges")
    allowed_packages: set[tuple[str, str]] = set()
    for row in sections["allowed-package-edges"]:
        match = re.fullmatch(r"([a-z0-9-]+)\s*->\s*([\w.]+)", row)
        if not match:
            raise ValueError(f"Invalid edge in [allowed-package-edges]: {row}")
        allowed_packages.add((match.group(1), match.group(2)))

    owners = {row[0]: set(row[1].split(",")) for row in read_tsv(report_dir / "package-owners.tsv")}

    def owning_package(name: str) -> str | None:
        parts = name.split(".")
        for end in range(len(parts), 0, -1):
            candidate = ".".join(parts[:end])
            if candidate in owners:
                return candidate
        return None

    seen: dict[tuple[str, str], int] = {}
    failures: list[str] = []
    for project in sorted(module_projects):
        for source in sorted((root / project / "src/main/java").rglob("*.java")):
            if source.name == "module-info.java":
                continue
            code = STRING_LITERAL_RE.sub('""', code_without_comments(source.read_text(encoding="utf-8")))
            declared = JAVA_PACKAGE_RE.search(code)
            if not declared:
                continue
            from_package = declared.group(1)
            from_group = package_group(from_package, groups)
            for package in sorted({owning_package(name) for name in DERBY_REFERENCE_RE.findall(code)} - {None}):
                if package == from_package or not owners[package] & module_projects:
                    continue
                to_group = package_group(package, groups)
                seen[(from_group, to_group)] = seen.get((from_group, to_group), 0) + 1
                if (
                    from_group != to_group
                    and f"{from_group} -> {to_group}" not in allowed_groups
                    and (from_group, package) not in allowed_packages
                ):
                    failures.append(
                        f"{from_group} -> {to_group}: {source.relative_to(root)} uses {package}"
                    )
    evidence = [f"Package group edges (files): {a} -> {b}: {n}" for (a, b), n in sorted(seen.items())]
    return evidence, failures


def write_report(path: Path, title: str, lines: Iterable[str], failures: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    content = [title, "=" * len(title), *lines, "Status: PASS" if not failures else "Status: FAIL"]
    content.extend(f"- {failure}" for failure in failures)
    path.write_text("\n".join(content) + "\n", encoding="utf-8")


def run_boundary(args: argparse.Namespace) -> None:
    root = args.root
    report_dir = args.dependency_report_dir
    failures: list[str] = []
    zero_row_files = {
        "missing-declared-project-dependencies.tsv": "missing declared production dependencies",
        "duplicate-resources.tsv": "duplicate resource paths",
        "output-dir-backdoors.tsv": "cross-module output-directory backdoors",
        "unresolved-imports.tsv": "unresolved project imports",
    }
    for name, description in zero_row_files.items():
        rows = read_tsv(report_dir / name)
        if rows:
            failures.append(
                f"Unexpected {description}: " + " | ".join("\t".join(row) for row in rows)
            )

    for row in read_tsv(report_dir / "package-owners.tsv"):
        if len(row) != 2:
            failures.append("Malformed package owner row: " + "\t".join(row))
        elif len([owner for owner in row[1].split(",") if owner]) != 1:
            failures.append("Package has multiple production owners: " + "\t".join(row))

    base_monitor = root / "delosdb-engine/src/main/java/org/apache/derby/impl/services/monitor/BaseMonitor.java"
    code = code_without_comments(base_monitor.read_text(encoding="utf-8"))
    if (
        "import org.apache.derby.iapi.services.loader.ClassInfo;" in code
        or "new ClassInfo(" in code
    ):
        failures.append("Registered-format construction must remain inside the engine module")

    package_evidence, package_failures = check_engine_package_boundaries(
        root, report_dir, args.package_boundaries
    )
    failures.extend(package_failures)

    write_report(
        args.output,
        "DelosDB module dependency boundary static analysis",
        [
            "Evidence: generated dependency/resource TSV reports and executable Java structure",
            "Documentation/comment authority: none",
            *package_evidence,
        ],
        failures,
    )
    if failures:
        raise ValueError(f"DelosDB module dependency boundary static analysis failed. Report: {args.output}")


def project_dependency_pattern(module: str) -> re.Pattern[str]:
    return re.compile(rf"project\s*\(\s*(?:path\s*:\s*)?['\"]:{re.escape(module)}['\"]")


def production_dependency_pattern(module: str) -> re.Pattern[str]:
    return re.compile(
        rf"(?m)^\s*(?:api|implementation|compileOnly|runtimeOnly)\s+"
        rf"project\s*\(\s*(?:path\s*:\s*)?['\"]:{re.escape(module)}['\"]"
    )


def run_v1(args: argparse.Namespace) -> None:
    root = args.root
    sections = load_sections(args.target)
    failures: list[str] = []

    target_modules = set(sections["target-modules"])
    deferred_modules = set(sections["deferred-modules"])
    retired_modules = set(sections["retired-modules"])
    retired_artifacts = set(sections["retired-artifacts"])
    required_provider_artifacts = set(sections["required-provider-artifacts"])
    deferred_provider_artifacts = set(sections["deferred-provider-artifacts"])
    build_only_artifacts = set(sections["build-only-artifacts"])
    forbidden_edges = parse_edges(
        sections["forbidden-production-edges"], "forbidden-production-edges"
    )

    if not deferred_modules <= target_modules:
        failures.append("Deferred modules must be members of target-modules")
    if not deferred_provider_artifacts <= required_provider_artifacts:
        failures.append(
            "Deferred provider artifacts must be members of required-provider-artifacts"
        )
    overlap = sorted(target_modules & retired_modules)
    if overlap:
        failures.append(f"Target and retired modules overlap: {overlap}")

    settings_code = code_without_comments((root / "settings.gradle").read_text(encoding="utf-8"))
    settings_modules = re.findall(r"(?m)^\s*include\s+['\"]([^'\"]+)['\"]\s*$", settings_code)
    settings_module_set = set(settings_modules)
    if len(settings_module_set) != len(settings_modules):
        failures.append("settings.gradle contains duplicate module declarations")
    extra_modules = sorted(settings_module_set - target_modules)
    missing_modules = target_modules - settings_module_set
    if extra_modules:
        failures.append(f"settings.gradle contains modules outside the v1 target: {extra_modules}")
    missing_active = sorted(missing_modules - deferred_modules)
    if missing_active:
        failures.append(f"settings.gradle is missing non-deferred target modules: {missing_active}")

    for module in sorted(retired_modules):
        if module in settings_module_set or (root / module).exists():
            failures.append(f"Retired module remains active or present: {module}")

    build_files = [root / "build.gradle", *sorted(root.glob("delosdb-*/build.gradle"))]
    build_code = {
        path.relative_to(root).as_posix(): code_without_comments(path.read_text(encoding="utf-8"))
        for path in build_files
        if path.is_file()
    }
    for module in sorted(retired_modules):
        pattern = project_dependency_pattern(module)
        for relative, code in build_code.items():
            if pattern.search(code):
                failures.append(f"Build wiring references retired module {module}: {relative}")

    runtime_artifacts = set(args.runtime_artifact)
    support_artifacts = set(args.support_artifact)
    assembled_artifacts = runtime_artifacts | support_artifacts
    active_required = required_provider_artifacts - deferred_provider_artifacts
    missing_required = sorted(active_required - assembled_artifacts)
    if missing_required:
        failures.append(f"Required provider artifacts are missing: {missing_required}")
    wrongly_runtime = sorted(active_required & runtime_artifacts)
    if wrongly_runtime:
        failures.append(f"Provider artifacts must remain support artifacts: {wrongly_runtime}")
    build_only_runtime = sorted(build_only_artifacts & runtime_artifacts)
    if build_only_runtime:
        failures.append(f"Build-only artifacts appear on the runtime model: {build_only_runtime}")
    retained_retired = sorted(retired_artifacts & assembled_artifacts)
    if retained_retired:
        failures.append(f"Retired artifacts remain assembled: {retained_retired}")

    edge_rows = read_tsv(args.dependency_report_dir / "module-source-edge-summary.tsv")
    observed_edges = {f"{row[0]} -> {row[1]}" for row in edge_rows if len(row) >= 2}
    forbidden_source_edges = sorted(forbidden_edges & observed_edges)
    if forbidden_source_edges:
        failures.append(f"Forbidden production source edges remain: {forbidden_source_edges}")

    for edge in sorted(forbidden_edges):
        source_module, target_module = edge.split(" -> ", 1)
        source_code = build_code.get(f"{source_module}/build.gradle", "")
        if production_dependency_pattern(target_module).search(source_code):
            failures.append(f"Forbidden production dependency remains: {edge}")

    write_report(
        args.output,
        "DelosDB v1 module architecture static analysis",
        [
            "Evidence: settings, generated dependency edges, build declarations, and runtime artifact model",
            "Documentation/comment authority: none",
            f"Current modules: {len(settings_modules)}",
            f"Deferred target modules: {sorted(missing_modules)}",
            f"Required active provider artifacts: {sorted(active_required)}",
        ],
        failures,
    )
    if failures:
        raise ValueError(f"DelosDB v1 module architecture static analysis failed. Report: {args.output}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("boundary", "v1"), required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--dependency-report-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target", type=Path)
    parser.add_argument("--package-boundaries", type=Path)
    parser.add_argument("--runtime-artifact", action="append", default=[])
    parser.add_argument("--support-artifact", action="append", default=[])
    args = parser.parse_args()
    args.root = args.root.resolve()
    args.dependency_report_dir = args.dependency_report_dir.resolve()
    args.output = args.output.resolve()
    if args.target is not None:
        args.target = args.target.resolve()
    if args.mode == "v1" and args.target is None:
        parser.error("--target is required for --mode v1")
    if args.mode == "boundary" and args.package_boundaries is None:
        parser.error("--package-boundaries is required for --mode boundary")
    if args.package_boundaries is not None:
        args.package_boundaries = args.package_boundaries.resolve()
    return args


def main() -> int:
    args = parse_args()
    try:
        if args.mode == "boundary":
            run_boundary(args)
        else:
            run_v1(args)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"{args.output.name}: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
