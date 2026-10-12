#!/usr/bin/env python3
"""Inventory handwritten Java ownership and imports; not a compiler or a gate."""
import argparse
import collections
import json
from pathlib import Path
import re

PACKAGE = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.M)
IMPORT = re.compile(r'^\s*import\s+(?:static\s+)?([\w.*]+)\s*;', re.M)


def inventory(root):
    modules = {}
    classes = {}
    class_packages = {}
    packages = collections.defaultdict(set)
    sources = []
    for module in sorted(root.glob('delosdb-*')):
        java = module / 'src/main/java'
        if not java.is_dir():
            continue
        count = collections.Counter()
        lines = 0
        for path in sorted(java.rglob('*.java')):
            if path.name == 'module-info.java':
                continue
            text = path.read_text(encoding='utf-8')
            match = PACKAGE.search(text)
            if not match:
                continue
            package = match[1]
            count[package] += 1
            lines += len(text.splitlines())
            classes[package + '.' + path.stem] = module.name
            class_packages[package + '.' + path.stem] = package
            packages[package].add(module.name)
            sources.append((module.name, package, path, text))
        build = module / 'build.gradle'
        build_text = build.read_text() if build.exists() else ''
        modules[module.name] = dict(java_files=sum(count.values()), java_lines=lines,
                                   packages=dict(count.most_common()),
                                   build_lines=len(build_text.splitlines()),
                                   declared_projects=sorted(set(re.findall(r"project\((?:path:\s*)?['\"]:([^'\"]+)", build_text))))
    edges = collections.Counter()
    package_edges = collections.Counter()
    examples = collections.defaultdict(list)
    for owner, source_package, path, text in sources:
        for imported in IMPORT.findall(text):
            targets = set()
            if imported.endswith('.*'):
                # Wildcard resolution is deliberately conservative.
                targets.update(packages.get(imported[:-2], ()))
            else:
                name = imported
                while '.' in name:
                    if name in classes:
                        targets.add(classes[name])
                        if classes[name] == owner and class_packages[name] != source_package:
                            package_edges[(owner, source_package, class_packages[name])] += 1
                        break
                    name = name.rsplit('.', 1)[0]
            for target in targets - {owner}:
                edges[(owner, target)] += 1
                if len(examples[(owner, target)]) < 5:
                    examples[(owner, target)].append(dict(file=str(path.relative_to(root)), imported=imported))
    return dict(method='Explicit imports plus conservative wildcard imports from src/main/java only; generated, test and reflective/service-loading edges excluded. A source owner is not necessarily a runtime JPMS module.',
                modules=modules,
                source_edges=[dict(source=a, target=b, imports=n, examples=examples[(a, b)])
                              for (a, b), n in sorted(edges.items())],
                intra_owner_explicit_package_edges=[dict(owner=owner, source=a, target=b, imports=n)
                                                    for (owner, a, b), n in sorted(package_edges.items())],
                split_source_packages={p: sorted(owners) for p, owners in sorted(packages.items()) if len(owners) > 1})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = inventory(args.root.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(f"Wrote {len(result['modules'])} source owners to {args.output}")


if __name__ == '__main__':
    main()
