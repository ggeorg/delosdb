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
                imported_package = imported[:-2]
                targets.update(packages.get(imported_package, ()))
                if owner in packages.get(imported_package, ()) and imported_package != source_package:
                    package_edges[(owner, source_package, imported_package)] += 1
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
    return dict(method='Explicit imports plus conservative wildcard imports from src/main/java only; generated, test and reflective/service-loading edges excluded. A source owner is not necessarily a runtime JPMS module. Packages without an .impl. segment are treated as contracts, following the inherited Derby iapi/impl convention.',
                modules=modules,
                source_edges=[dict(source=a, target=b, imports=n, examples=examples[(a, b)])
                              for (a, b), n in sorted(edges.items())],
                intra_owner_explicit_package_edges=[dict(owner=owner, source=a, target=b, imports=n)
                                                    for (owner, a, b), n in sorted(package_edges.items())],
                intra_owner_package_cycles=package_cycles(package_edges),
                contract_to_implementation_package_edges=[
                    dict(owner=owner, source=a, target=b, imports=n)
                    for (owner, a, b), n in sorted(package_edges.items())
                    if not is_implementation(a) and is_implementation(b)],
                split_source_packages={p: sorted(owners) for p, owners in sorted(packages.items()) if len(owners) > 1})


def is_implementation(package):
    return '.impl.' in package + '.'


def package_cycles(package_edges):
    """Strongly connected package groups (more than one package) per source owner."""
    graphs = collections.defaultdict(lambda: collections.defaultdict(set))
    for owner, source, target in package_edges:
        graphs[owner][source].add(target)
    cycles = []
    for owner, graph in sorted(graphs.items()):
        for component in strongly_connected(graph):
            if len(component) > 1:
                cycles.append(dict(owner=owner, packages=sorted(component)))
    return cycles


def strongly_connected(graph):
    """Iterative Tarjan, so a long package chain cannot exceed the recursion limit."""
    index, low, on_stack, stack, components = {}, {}, set(), [], []
    nodes = sorted(set(graph) | {t for targets in graph.values() for t in targets})
    for root in nodes:
        if root in index:
            continue
        work = [(root, iter(sorted(graph.get(root, ()))))]
        index[root] = low[root] = len(index)
        stack.append(root)
        on_stack.add(root)
        while work:
            node, successors = work[-1]
            advanced = False
            for successor in successors:
                if successor not in index:
                    index[successor] = low[successor] = len(index)
                    stack.append(successor)
                    on_stack.add(successor)
                    work.append((successor, iter(sorted(graph.get(successor, ())))))
                    advanced = True
                    break
                if successor in on_stack:
                    low[node] = min(low[node], index[successor])
            if advanced:
                continue
            work.pop()
            if work:
                low[work[-1][0]] = min(low[work[-1][0]], low[node])
            if low[node] == index[node]:
                component = []
                while True:
                    member = stack.pop()
                    on_stack.discard(member)
                    component.append(member)
                    if member == node:
                        break
                components.append(component)
    return components


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
