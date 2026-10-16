#!/usr/bin/env python3
"""
Generate a source-level DelosDB module dependency map.

This is intentionally separate from Gradle's dependency report:
  * Gradle shows declared project dependencies.
  * This script shows actual Java import edges between DelosDB modules.
  * It also flags output-directory classpath backdoors in build.gradle files.
  * It separates source-classpath declarations from custom build-wiring
    configurations when source/package references are absent. Only the former
    are direct-removal review candidates; neither category is auto-deleted.
  * It reports resource/source-set patterns that are easy to miss during
    module-boundary cleanups.

The report is generic on purpose. It does not encode DelosDB ownership policy;
humans review the generated report and decide whether a dependency or package
owner split is acceptable.

Outputs are written under build/reports/delosdb-module-dependencies by default.
No external Python packages are required.
"""

from __future__ import annotations

import argparse
import os
import re
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

JAVA_PACKAGE_RE = re.compile(r"^\s*package\s+([a-zA-Z_][\w]*(?:\.[a-zA-Z_][\w]*)*)\s*;", re.MULTILINE)
JAVA_IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([a-zA-Z_][\w]*(?:\.[a-zA-Z_][\w]*)*(?:\.\*)?)\s*;", re.MULTILINE)
PROJECT_DEP_RE = re.compile(r"project\s*\(\s*(?:path\s*:\s*)?['\"]:([^'\"]+)['\"](?:\s*,[^)]*)?\)")
PROJECT_DEP_DECLARATION_RE = re.compile(
    r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(|\s+)\s*project\s*\(\s*(?:path\s*:\s*)?['\"]:([^'\"]+)['\"]"
)
STANDARD_SOURCE_CONFIGURATIONS = {
    "api",
    "implementation",
    "compileOnly",
    "compileOnlyApi",
    "testImplementation",
    "testCompileOnly",
}
# Only report build-output references that cross module boundaries. Ordinary
# per-module generated source/output directories are expected Gradle wiring and
# should not be counted as architecture backdoors.
OUTPUT_BACKDOOR_RE = re.compile(
    r"(rootProject\.file\(\s*['\"][^'\"]+/build/|file\(\s*['\"]\.\./[^'\"]+/build/|project\s*\([^)]*\)\s*\.\s*layout\s*\.\s*buildDirectory|[A-Za-z0-9_-]+/build/classes|[A-Za-z0-9_-]+/build/resources)"
)
# Package-private test support modules deliberately use production package names
# to test package-private Derby behavior. They are not production package owners
# for architecture dependency analysis.
PACKAGE_PRIVATE_TEST_SUPPORT_MODULES = {"delosdb-pptesting"}
RESOURCE_SOURCESET_RE = re.compile(
    r"(resources\s*\{|resources\s*\.\s*srcDir|resources\s*\.\s*srcDirs|processResources\s*\{|from\s*\(|src/main/resources|META-INF/services|duplicatesStrategy)"
)
PROJECT_PACKAGE_REFERENCE_RE = re.compile(r"\b(org\.apache\.derby\.[A-Za-z0-9_.$]+|io\.github\.ggeorg\.[A-Za-z0-9_.$]+)")

IGNORED_IMPORT_PREFIXES = (
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "com.sun.",
    "org.junit.",
    "junit.",
    "org.hamcrest.",
)

IGNORED_DIR_NAMES = {
    ".git",
    ".gradle",
    ".idea",
    "build",
    "out",
    "target",
    ".settings",
}

RESOURCE_ROOT_PARTS = {
    ("src", "main", "resources"),
    ("src", "test", "resources"),
}


@dataclass(frozen=True)
class JavaSource:
    module: str
    source_set: str
    path: Path
    package: str | None
    imports: tuple[str, ...]
    text: str


@dataclass(frozen=True)
class SourceEdge:
    from_module: str
    source_set: str
    to_module: str
    imported: str
    owner_package: str
    source_file: str


@dataclass(frozen=True)
class UnresolvedImport:
    from_module: str
    source_set: str
    imported: str
    source_file: str


@dataclass(frozen=True)
class BackdoorUse:
    module: str
    build_file: str
    line_no: int
    line: str


@dataclass(frozen=True)
class ResourceDeclaration:
    module: str
    build_file: str
    line_no: int
    line: str


@dataclass(frozen=True)
class ResourceFile:
    module: str
    source_set: str
    relative_resource_path: str
    source_file: str


@dataclass(frozen=True)
class ProjectPackageReference:
    from_module: str
    source_set: str
    referenced: str
    owner_package: str
    owner_modules: tuple[str, ...]
    source_file: str


@dataclass(frozen=True)
class DeclaredProjectDependency:
    module: str
    dependency: str
    configuration: str
    build_file: str
    line_no: int
    line: str

    @property
    def source_classpath_configuration(self) -> bool:
        return self.configuration in STANDARD_SOURCE_CONFIGURATIONS


def is_module_dir(path: Path) -> bool:
    if not path.is_dir():
        return False
    if path.name in IGNORED_DIR_NAMES:
        return False
    if (path / "build.gradle").exists() or (path / "build.gradle.kts").exists():
        return True
    return False


def discover_modules(root: Path) -> dict[str, Path]:
    modules: dict[str, Path] = {}
    for child in sorted(root.iterdir()):
        if is_module_dir(child):
            modules[child.name] = child
    return modules


def source_set_for(path: Path, module_dir: Path) -> str:
    try:
        rel = path.relative_to(module_dir)
    except ValueError:
        return "unknown"
    parts = rel.parts
    if len(parts) >= 3 and parts[0] == "src":
        if parts[1] == "main" and parts[2] == "demo":
            return "demo"
        return parts[1]
    if len(parts) >= 1 and parts[0] == "dev":
        return "dev"
    return "other"


def iter_java_files(module_dir: Path) -> Iterable[Path]:
    for dirpath, dirnames, filenames in os.walk(module_dir):
        dirnames[:] = [d for d in dirnames if d not in IGNORED_DIR_NAMES]
        base = Path(dirpath)
        for filename in filenames:
            if filename.endswith(".java"):
                yield base / filename


def iter_resource_files(module: str, module_dir: Path, root: Path) -> Iterable[ResourceFile]:
    for dirpath, dirnames, filenames in os.walk(module_dir):
        dirnames[:] = [d for d in dirnames if d not in IGNORED_DIR_NAMES]
        base = Path(dirpath)
        try:
            rel_dir = base.relative_to(module_dir)
        except ValueError:
            continue
        rel_parts = rel_dir.parts
        if len(rel_parts) < 3 or tuple(rel_parts[:3]) not in RESOURCE_ROOT_PARTS:
            continue
        source_set = rel_parts[1]
        resource_root = module_dir / rel_parts[0] / rel_parts[1] / rel_parts[2]
        for filename in filenames:
            path = base / filename
            rel_resource = path.relative_to(resource_root)
            yield ResourceFile(module, source_set, str(rel_resource), str(path.relative_to(root)))


def parse_java_file(path: Path, module: str, module_dir: Path) -> JavaSource:
    text = path.read_text(encoding="utf-8", errors="replace")
    package_match = JAVA_PACKAGE_RE.search(text)
    package = package_match.group(1) if package_match else None
    imports = tuple(m.group(1) for m in JAVA_IMPORT_RE.finditer(text))
    return JavaSource(
        module=module,
        source_set=source_set_for(path, module_dir),
        path=path,
        package=package,
        imports=imports,
        text=text,
    )


def build_package_owners(sources: list[JavaSource]) -> dict[str, set[str]]:
    owners: dict[str, set[str]] = defaultdict(set)

    # Production source sets establish package ownership first. Test-only
    # sources may contribute ownership only for packages that have no
    # production owner. This keeps ordinary test packages resolvable while
    # preventing package-private test patches from being mistaken for a
    # second production module owner.
    for src in sources:
        if src.module in PACKAGE_PRIVATE_TEST_SUPPORT_MODULES:
            continue
        if src.package and is_production_source_set(src.source_set):
            owners[src.package].add(src.module)

    production_packages = set(owners)
    for src in sources:
        if src.module in PACKAGE_PRIVATE_TEST_SUPPORT_MODULES:
            continue
        if (src.package
                and not is_production_source_set(src.source_set)
                and src.package not in production_packages):
            owners[src.package].add(src.module)

    return owners


def imported_package_candidates(import_name: str) -> list[str]:
    name = import_name[:-2] if import_name.endswith(".*") else import_name
    parts = name.split(".")
    candidates = []
    for i in range(len(parts), 0, -1):
        candidates.append(".".join(parts[:i]))
    return candidates


def resolve_import_owner(import_name: str, package_owners: dict[str, set[str]]) -> tuple[str, set[str]] | None:
    for candidate in imported_package_candidates(import_name):
        owners = package_owners.get(candidate)
        if owners:
            return candidate, owners
    return None


def is_ignored_import(import_name: str) -> bool:
    if import_name.startswith(IGNORED_IMPORT_PREFIXES):
        return True
    return False


def is_production_source_set(source_set: str) -> bool:
    return source_set == "main"


def compute_edges(
    sources: list[JavaSource],
    package_owners: dict[str, set[str]],
    root: Path,
) -> tuple[list[SourceEdge], list[UnresolvedImport]]:
    edges: list[SourceEdge] = []
    unresolved: list[UnresolvedImport] = []

    for src in sources:
        for imp in src.imports:
            if is_ignored_import(imp):
                continue
            resolved = resolve_import_owner(imp, package_owners)
            rel_source = str(src.path.relative_to(root))
            if resolved is None:
                # Keep only imports that look like project imports or common third-party imports we may care about.
                if imp.startswith("org.apache.derby.") or imp.startswith("io.github.ggeorg."):
                    unresolved.append(UnresolvedImport(src.module, src.source_set, imp, rel_source))
                continue
            owner_package, owners = resolved
            for owner in sorted(owners):
                if owner != src.module:
                    edges.append(SourceEdge(src.module, src.source_set, owner, imp, owner_package, rel_source))
    return edges, unresolved


def compute_project_package_references(
    sources: list[JavaSource],
    package_owners: dict[str, set[str]],
    root: Path,
) -> list[ProjectPackageReference]:
    refs: list[ProjectPackageReference] = []
    seen: set[tuple[str, str, str, str, str]] = set()
    for src in sources:
        rel_source = str(src.path.relative_to(root))
        imported_names = set(src.imports)
        for match in PROJECT_PACKAGE_REFERENCE_RE.finditer(src.text):
            name = match.group(1).rstrip(".;,)]}")
            # Imports are already represented by source-import-edges.tsv.
            if name in imported_names:
                continue
            resolved = resolve_import_owner(name, package_owners)
            if resolved is None:
                continue
            owner_package, owners = resolved
            for owner in sorted(owners):
                if owner == src.module:
                    continue
                key = (src.module, src.source_set, owner, name, rel_source)
                if key in seen:
                    continue
                seen.add(key)
                refs.append(
                    ProjectPackageReference(
                        src.module,
                        src.source_set,
                        name,
                        owner_package,
                        tuple(sorted(owners)),
                        rel_source,
                    )
                )
    return refs


def parse_declared_project_dependencies(
    module: str, module_dir: Path, root: Path
) -> list[DeclaredProjectDependency]:
    declarations: list[DeclaredProjectDependency] = []
    for build_name in ("build.gradle", "build.gradle.kts"):
        build_file = module_dir / build_name
        if not build_file.exists():
            continue
        for line_no, line in enumerate(
            build_file.read_text(encoding="utf-8", errors="replace").splitlines(), start=1
        ):
            match = PROJECT_DEP_DECLARATION_RE.search(line)
            if match is None:
                continue
            configuration, dependency = match.groups()
            if dependency == module:
                continue
            declarations.append(
                DeclaredProjectDependency(
                    module=module,
                    dependency=dependency,
                    configuration=configuration,
                    build_file=str(build_file.relative_to(root)),
                    line_no=line_no,
                    line=line.strip(),
                )
            )
    return declarations


def declared_dependency_names(
    declarations: dict[str, list[DeclaredProjectDependency]],
) -> dict[str, set[str]]:
    return {
        module: {declaration.dependency for declaration in module_declarations}
        for module, module_declarations in declarations.items()
    }


def declarations_without_source_references(
    declarations: dict[str, list[DeclaredProjectDependency]],
    used_deps: dict[str, set[str]],
    *,
    source_classpath: bool,
) -> list[DeclaredProjectDependency]:
    return [
        declaration
        for module, module_declarations in sorted(declarations.items())
        for declaration in module_declarations
        if declaration.source_classpath_configuration == source_classpath
        and declaration.dependency not in used_deps.get(module, set())
    ]


def find_backdoor_uses(module: str, module_dir: Path, root: Path) -> list[BackdoorUse]:
    out: list[BackdoorUse] = []
    for build_name in ("build.gradle", "build.gradle.kts"):
        build_file = module_dir / build_name
        if not build_file.exists():
            continue
        lines = build_file.read_text(encoding="utf-8", errors="replace").splitlines()
        for i, line in enumerate(lines, start=1):
            if OUTPUT_BACKDOOR_RE.search(line):
                out.append(BackdoorUse(module, str(build_file.relative_to(root)), i, line.strip()))
    return out


def find_resource_declarations(module: str, module_dir: Path, root: Path) -> list[ResourceDeclaration]:
    out: list[ResourceDeclaration] = []
    for build_name in ("build.gradle", "build.gradle.kts"):
        build_file = module_dir / build_name
        if not build_file.exists():
            continue
        lines = build_file.read_text(encoding="utf-8", errors="replace").splitlines()
        for i, line in enumerate(lines, start=1):
            if RESOURCE_SOURCESET_RE.search(line):
                out.append(ResourceDeclaration(module, str(build_file.relative_to(root)), i, line.strip()))
    return out


def used_module_dependencies(source_edges: list[SourceEdge], package_refs: list[ProjectPackageReference]) -> dict[str, set[str]]:
    used: dict[str, set[str]] = defaultdict(set)
    for edge in source_edges:
        used[edge.from_module].add(edge.to_module)
    for ref in package_refs:
        for owner in ref.owner_modules:
            if owner != ref.from_module:
                used[ref.from_module].add(owner)
    return used


def duplicate_resource_paths(resource_files: list[ResourceFile]) -> list[tuple[str, str, str, list[str]]]:
    by_key: dict[tuple[str, str, str], list[str]] = defaultdict(list)
    for item in resource_files:
        by_key[(item.module, item.source_set, item.relative_resource_path)].append(item.source_file)
    return [
        (module, source_set, rel_path, files)
        for (module, source_set, rel_path), files in sorted(by_key.items())
        if len(files) > 1
    ]


def write_tsv(path: Path, header: list[str], rows: Iterable[Iterable[object]]) -> None:
    with path.open("w", encoding="utf-8") as f:
        f.write("\t".join(header) + "\n")
        for row in rows:
            f.write("\t".join(str(item) for item in row) + "\n")


def write_dot(path: Path, module_edges: dict[tuple[str, str], int]) -> None:
    with path.open("w", encoding="utf-8") as f:
        f.write("digraph delosdb_modules {\n")
        f.write("  rankdir=LR;\n")
        for (src, dst), count in sorted(module_edges.items()):
            f.write(f'  "{src}" -> "{dst}" [label="{count}"];\n')
        f.write("}\n")


def write_markdown(
    path: Path,
    modules: dict[str, Path],
    source_edges: list[SourceEdge],
    unresolved: list[UnresolvedImport],
    declared_declarations: dict[str, list[DeclaredProjectDependency]],
    backdoor_uses: list[BackdoorUse],
    package_owners: dict[str, set[str]],
    package_refs: list[ProjectPackageReference],
    resource_declarations: list[ResourceDeclaration],
    resource_files: list[ResourceFile],
) -> None:
    module_edges: dict[tuple[str, str], int] = defaultdict(int)
    production_module_edges: dict[tuple[str, str], int] = defaultdict(int)
    module_edges_by_source_set: dict[tuple[str, str, str], int] = defaultdict(int)
    for edge in source_edges:
        module_edges[(edge.from_module, edge.to_module)] += 1
        if is_production_source_set(edge.source_set):
            production_module_edges[(edge.from_module, edge.to_module)] += 1
        module_edges_by_source_set[(edge.from_module, edge.source_set, edge.to_module)] += 1

    used_deps = used_module_dependencies(source_edges, package_refs)
    declared_deps = declared_dependency_names(declared_declarations)
    missing_declared: list[tuple[str, str, int]] = []
    for (src, dst), count in sorted(production_module_edges.items()):
        if dst not in declared_deps.get(src, set()):
            missing_declared.append((src, dst, count))

    declared_without_source_refs = declarations_without_source_references(
        declared_declarations, used_deps, source_classpath=True
    )
    build_wiring_without_source_refs = declarations_without_source_references(
        declared_declarations, used_deps, source_classpath=False
    )

    package_collisions = [(pkg, owners) for pkg, owners in sorted(package_owners.items()) if len(owners) > 1]
    duplicate_resources = duplicate_resource_paths(resource_files)
    duplicate_service_resources = [
        item for item in duplicate_resources if item[2].startswith("META-INF/services/")
    ]

    with path.open("w", encoding="utf-8") as f:
        f.write("# DelosDB module dependency tree\n\n")
        f.write("Generated from Java imports, Java project-package references, Gradle project declarations, and resource files.\n")
        f.write("This report is generic; it does not encode module ownership policy.\n")
        f.write("Source-classpath review candidates are separated from custom build-wiring edges; ")
        f.write("neither category is an automatic deletion list.\n\n")
        f.write("## Modules\n\n")
        for module in sorted(modules):
            f.write(f"- `{module}`\n")

        f.write("\n## Summary\n\n")
        f.write("| Metric | Count |\n")
        f.write("|---|---:|\n")
        f.write(f"| Modules | {len(modules)} |\n")
        f.write(f"| Source import edges | {len(source_edges)} |\n")
        f.write(f"| Project-package references | {len(package_refs)} |\n")
        f.write(f"| Missing declared production dependencies | {len(missing_declared)} |\n")
        f.write(f"| Source-classpath dependencies without source/package refs | {len(declared_without_source_refs)} |\n")
        f.write(f"| Build-wiring dependencies without source/package refs | {len(build_wiring_without_source_refs)} |\n")
        f.write(f"| Package owner collisions | {len(package_collisions)} |\n")
        f.write(f"| Resource source declarations | {len(resource_declarations)} |\n")
        f.write(f"| Duplicate resource paths | {len(duplicate_resources)} |\n")
        f.write(f"| Duplicate service resource paths | {len(duplicate_service_resources)} |\n")
        f.write(f"| Cross-module output-dir backdoor lines | {len(backdoor_uses)} |\n")
        f.write(f"| Unresolved project imports | {len(unresolved)} |\n")

        f.write("\n## Source import edge summary\n\n")
        f.write("| From | To | Import count | Declared Gradle dependency? |\n")
        f.write("|---|---|---:|---|\n")
        for (src, dst), count in sorted(module_edges.items()):
            declared = "yes" if dst in declared_deps.get(src, set()) else "NO"
            f.write(f"| `{src}` | `{dst}` | {count} | {declared} |\n")

        f.write("\n## Source-set edge summary\n\n")
        f.write("| From | Source set | To | Import count |\n")
        f.write("|---|---|---|---:|\n")
        for (src, source_set, dst), count in sorted(module_edges_by_source_set.items()):
            f.write(f"| `{src}` | `{source_set}` | `{dst}` | {count} |\n")

        f.write("\n## Missing declared production project dependencies\n\n")
        if missing_declared:
            f.write("| From | To | Import count |\n")
            f.write("|---|---|---:|\n")
            for src, dst, count in missing_declared:
                f.write(f"| `{src}` | `{dst}` | {count} |\n")
        else:
            f.write("None detected.\n")

        f.write("\n## Source-classpath project dependencies without source/package references\n\n")
        f.write("These are the direct-removal review candidates. Runtime behavior and classfile-signature use still require verification.\n\n")
        if declared_without_source_refs:
            f.write("| Module | Configuration | Declared dependency | Build file | Line |\n")
            f.write("|---|---|---|---|---:|\n")
            for declaration in declared_without_source_refs:
                f.write(
                    f"| `{declaration.module}` | `{declaration.configuration}` | "
                    f"`{declaration.dependency}` | `{declaration.build_file}` | {declaration.line_no} |\n"
                )
        else:
            f.write("None detected.\n")

        f.write("\n## Build-wiring project dependencies without source/package references\n\n")
        f.write("These custom-configuration edges are reported separately because they normally feed patching, packaging, or generated-artifact tasks rather than Java source compilation.\n\n")
        if build_wiring_without_source_refs:
            f.write("| Module | Configuration | Declared dependency | Build file | Line |\n")
            f.write("|---|---|---|---|---:|\n")
            for declaration in build_wiring_without_source_refs:
                f.write(
                    f"| `{declaration.module}` | `{declaration.configuration}` | "
                    f"`{declaration.dependency}` | `{declaration.build_file}` | {declaration.line_no} |\n"
                )
        else:
            f.write("None detected.\n")

        f.write("\n## Duplicate resource paths within a module/source set\n\n")
        if duplicate_resources:
            f.write("| Module | Source set | Resource path | Files |\n")
            f.write("|---|---|---|---|\n")
            for module, source_set, rel_path, files in duplicate_resources:
                f.write(f"| `{module}` | `{source_set}` | `{rel_path}` | `{', '.join(files)}` |\n")
        else:
            f.write("None detected.\n")

        f.write("\n## Resource-related Gradle declarations\n\n")
        if resource_declarations:
            f.write("| Module | File | Line | Text |\n")
            f.write("|---|---|---:|---|\n")
            for decl in resource_declarations[:500]:
                safe_line = decl.line.replace("|", "\\|")
                f.write(f"| `{decl.module}` | `{decl.build_file}` | {decl.line_no} | `{safe_line}` |\n")
            if len(resource_declarations) > 500:
                f.write("\nOnly first 500 resource declarations shown. Full list is in `resource-declarations.tsv`.\n")
        else:
            f.write("None detected.\n")

        f.write("\n## Cross-module output-directory classpath backdoors\n\n")
        if backdoor_uses:
            f.write("| Module | File | Line | Text |\n")
            f.write("|---|---|---:|---|\n")
            for use in backdoor_uses:
                safe_line = use.line.replace("|", "\\|")
                f.write(f"| `{use.module}` | `{use.build_file}` | {use.line_no} | `{safe_line}` |\n")
        else:
            f.write("None detected.\n")

        f.write("\n## Project imports that did not resolve to a local package owner\n\n")
        if unresolved:
            f.write("| From | Source set | Import | Source file |\n")
            f.write("|---|---|---|---|\n")
            for item in unresolved[:500]:
                f.write(f"| `{item.from_module}` | `{item.source_set}` | `{item.imported}` | `{item.source_file}` |\n")
            if len(unresolved) > 500:
                f.write("\nOnly first 500 unresolved imports shown. Full list is in `unresolved-imports.tsv`.\n")
        else:
            f.write("None detected.\n")

        f.write("\n## Package owner collisions\n\n")
        if package_collisions:
            f.write("| Package | Owners |\n")
            f.write("|---|---|\n")
            for pkg, owners in package_collisions:
                f.write(f"| `{pkg}` | `{', '.join(sorted(owners))}` |\n")
        else:
            f.write("None detected.\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate DelosDB module/source dependency reports.")
    parser.add_argument("--root", default=".", help="Repository root. Defaults to current directory.")
    parser.add_argument(
        "--out",
        default="build/reports/delosdb-module-dependencies",
        help="Output directory. Defaults to build/reports/delosdb-module-dependencies.",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()
    out_dir = (root / args.out).resolve() if not Path(args.out).is_absolute() else Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    modules = discover_modules(root)
    sources: list[JavaSource] = []
    resource_files: list[ResourceFile] = []
    for module, module_dir in modules.items():
        for java_file in iter_java_files(module_dir):
            sources.append(parse_java_file(java_file, module, module_dir))
        resource_files.extend(iter_resource_files(module, module_dir, root))

    package_owners = build_package_owners(sources)
    source_edges, unresolved = compute_edges(sources, package_owners, root)
    package_refs = compute_project_package_references(sources, package_owners, root)
    declared_declarations = {
        module: parse_declared_project_dependencies(module, module_dir, root)
        for module, module_dir in modules.items()
    }
    declared_deps = declared_dependency_names(declared_declarations)
    used_deps = used_module_dependencies(source_edges, package_refs)

    missing_declared: list[tuple[str, str, int]] = []
    module_edges: dict[tuple[str, str], int] = defaultdict(int)
    production_module_edges: dict[tuple[str, str], int] = defaultdict(int)
    for edge in source_edges:
        module_edges[(edge.from_module, edge.to_module)] += 1
        if is_production_source_set(edge.source_set):
            production_module_edges[(edge.from_module, edge.to_module)] += 1
    for (src, dst), count in sorted(production_module_edges.items()):
        if dst not in declared_deps.get(src, set()):
            missing_declared.append((src, dst, count))

    declared_without_source_refs = declarations_without_source_references(
        declared_declarations, used_deps, source_classpath=True
    )
    build_wiring_without_source_refs = declarations_without_source_references(
        declared_declarations, used_deps, source_classpath=False
    )

    backdoor_uses: list[BackdoorUse] = []
    resource_declarations: list[ResourceDeclaration] = []
    for module, module_dir in modules.items():
        backdoor_uses.extend(find_backdoor_uses(module, module_dir, root))
        resource_declarations.extend(find_resource_declarations(module, module_dir, root))

    duplicate_resources = duplicate_resource_paths(resource_files)

    write_tsv(
        out_dir / "package-owners.tsv",
        ["package", "owners"],
        ((pkg, ",".join(sorted(owners))) for pkg, owners in sorted(package_owners.items())),
    )
    write_tsv(
        out_dir / "declared-gradle-dependencies.tsv",
        ["module", "configuration", "declared_project_dependency", "build_file", "line_no"],
        (
            (
                declaration.module,
                declaration.configuration,
                declaration.dependency,
                declaration.build_file,
                declaration.line_no,
            )
            for module_declarations in declared_declarations.values()
            for declaration in module_declarations
        ),
    )
    write_tsv(
        out_dir / "source-import-edges.tsv",
        ["from_module", "source_set", "to_module", "import", "owner_package", "source_file"],
        ((e.from_module, e.source_set, e.to_module, e.imported, e.owner_package, e.source_file) for e in source_edges),
    )
    write_tsv(
        out_dir / "module-source-edge-summary.tsv",
        ["from_module", "to_module", "import_count", "declared_gradle_dependency"],
        (
            (src, dst, count, "yes" if dst in declared_deps.get(src, set()) else "NO")
            for (src, dst), count in sorted(module_edges.items())
        ),
    )
    write_tsv(
        out_dir / "missing-declared-project-dependencies.tsv",
        ["from_module", "to_module", "production_import_count"],
        missing_declared,
    )
    write_tsv(
        out_dir / "declared-project-dependencies-without-source-refs.tsv",
        ["module", "configuration", "declared_project_dependency", "build_file", "line_no"],
        (
            (
                declaration.module,
                declaration.configuration,
                declaration.dependency,
                declaration.build_file,
                declaration.line_no,
            )
            for declaration in declared_without_source_refs
        ),
    )
    write_tsv(
        out_dir / "build-wiring-project-dependencies-without-source-refs.tsv",
        ["module", "configuration", "declared_project_dependency", "build_file", "line_no"],
        (
            (
                declaration.module,
                declaration.configuration,
                declaration.dependency,
                declaration.build_file,
                declaration.line_no,
            )
            for declaration in build_wiring_without_source_refs
        ),
    )
    write_tsv(
        out_dir / "unresolved-imports.tsv",
        ["from_module", "source_set", "import", "source_file"],
        ((u.from_module, u.source_set, u.imported, u.source_file) for u in unresolved),
    )
    write_tsv(
        out_dir / "source-package-references.tsv",
        ["from_module", "source_set", "referenced", "owner_package", "owner_modules", "source_file"],
        ((r.from_module, r.source_set, r.referenced, r.owner_package, ",".join(r.owner_modules), r.source_file) for r in package_refs),
    )
    write_tsv(
        out_dir / "output-dir-backdoors.tsv",
        ["module", "build_file", "line_no", "line"],
        ((b.module, b.build_file, b.line_no, b.line) for b in backdoor_uses),
    )
    write_tsv(
        out_dir / "resource-files.tsv",
        ["module", "source_set", "resource_path", "source_file"],
        ((r.module, r.source_set, r.relative_resource_path, r.source_file) for r in resource_files),
    )
    write_tsv(
        out_dir / "duplicate-resources.tsv",
        ["module", "source_set", "resource_path", "files"],
        ((module, source_set, rel_path, ",".join(files)) for module, source_set, rel_path, files in duplicate_resources),
    )
    write_tsv(
        out_dir / "resource-declarations.tsv",
        ["module", "build_file", "line_no", "line"],
        ((r.module, r.build_file, r.line_no, r.line) for r in resource_declarations),
    )
    write_dot(out_dir / "module-dependencies.dot", module_edges)
    write_markdown(
        out_dir / "module-dependencies.md",
        modules,
        source_edges,
        unresolved,
        declared_declarations,
        backdoor_uses,
        package_owners,
        package_refs,
        resource_declarations,
        resource_files,
    )

    print(f"Wrote dependency reports to {out_dir.relative_to(root) if out_dir.is_relative_to(root) else out_dir}")
    print(f"Modules: {len(modules)}")
    print(f"Java sources: {len(sources)}")
    print(f"Inter-module source import edges: {len(source_edges)}")
    print(f"Project-package references: {len(package_refs)}")
    print(f"Missing declared production dependencies: {len(missing_declared)}")
    print(f"Source-classpath dependencies without source/package refs: {len(declared_without_source_refs)}")
    print(f"Build-wiring dependencies without source/package refs: {len(build_wiring_without_source_refs)}")
    print(f"Resource files: {len(resource_files)}")
    print(f"Duplicate resource paths: {len(duplicate_resources)}")
    print(f"Resource-related Gradle declarations: {len(resource_declarations)}")
    print(f"Unresolved project imports: {len(unresolved)}")
    print(f"Cross-module output-dir backdoor lines: {len(backdoor_uses)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
