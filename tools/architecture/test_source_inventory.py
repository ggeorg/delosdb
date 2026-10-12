"""Checks package cycles and contract-to-implementation edges on a small source tree."""
import tempfile
import unittest
from pathlib import Path
import source_inventory as inventory


def write(root, owner, package, name, imports=()):
    directory = root / owner / 'src/main/java' / package.replace('.', '/')
    directory.mkdir(parents=True, exist_ok=True)
    lines = [f'package {package};'] + [f'import {i};' for i in imports] + [f'public class {name} {{}}']
    (directory / f'{name}.java').write_text('\n'.join(lines) + '\n')


class SourceInventoryTest(unittest.TestCase):
    def test_cycles_and_contract_edges(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # a.iapi -> a.impl.x -> a.impl.y -> a.iapi is one cycle; the wildcard closes it.
            write(root, 'delosdb-a', 'a.iapi', 'Contract', ['a.impl.x.Worker'])
            write(root, 'delosdb-a', 'a.impl.x', 'Worker', ['a.impl.y.*'])
            write(root, 'delosdb-a', 'a.impl.y', 'Helper', ['a.iapi.Contract'])
            # a.impl.z only depends on the cycle and stays outside it.
            write(root, 'delosdb-a', 'a.impl.z', 'Leaf', ['a.impl.x.Worker'])
            # Another owner's imports are cross-owner edges, not package edges.
            write(root, 'delosdb-b', 'b.iapi', 'Other', ['a.impl.x.Worker'])

            result = inventory.inventory(root)

        self.assertEqual(
            [dict(owner='delosdb-a', packages=['a.iapi', 'a.impl.x', 'a.impl.y'])],
            result['intra_owner_package_cycles'])
        self.assertEqual(
            [dict(owner='delosdb-a', source='a.iapi', target='a.impl.x', imports=1)],
            result['contract_to_implementation_package_edges'])
        self.assertEqual(
            [('delosdb-b', 'delosdb-a', 1)],
            [(e['source'], e['target'], e['imports']) for e in result['source_edges']])

    def test_deep_chain_does_not_recurse(self):
        chain = {f'p{i}': {f'p{i + 1}'} for i in range(5000)}
        chain['p5000'] = {'p0'}
        components = inventory.strongly_connected(chain)
        self.assertEqual([5001], [len(c) for c in components])


if __name__ == '__main__':
    unittest.main()
