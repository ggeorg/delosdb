"""Evidence-contract checks for the occupancy report validator."""
import csv
import tempfile
import unittest
from pathlib import Path
import summarize_rawstore_occupancy as summary


class OccupancySummaryTest(unittest.TestCase):
    def test_partition_and_instance_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'ledger.csv'
            keys = ['resource', 'context', 'acquires', 'hold_ns', 'wait_ns',
                    'first_acquire_ns', 'last_release_ns'] + summary.PARTITION
            row = dict.fromkeys(keys, 0)
            row.update(resource='PAGE:1', context='BTREE_ROOT', acquires=4,
                       hold_ns=600, wait_ns=80, first_acquire_ns=1000,
                       last_release_ns=1900, self_ns=100, log_wait_ns=200,
                       log_hold_ns=300)
            window = {'elapsed_ns': 1000, 'inserted_rows': 10}
            self.write(path, keys, row)
            resource = summary.ledger(path, window)[0]
            self.assertEqual(.6, resource['run_utilization'])
            self.assertEqual(60, resource['hold_ns_per_inserted_row'])
            row['log_hold_ns'] += 1
            self.write(path, keys, row)
            with self.assertRaisesRegex(ValueError, 'partition'):
                summary.ledger(path, window)
            row['log_hold_ns'] -= 1
            self.write(path, keys, row)
            window['elapsed_ns'] = 100
            with self.assertRaisesRegex(ValueError, 'occupancy exceeds'):
                summary.ledger(path, window)

    def test_incomplete_campaign_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, 'Expected 2 engines'):
                summary.summarize(Path(temporary))

    def test_disabled_window_propagation_is_explicit(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'window.csv'
            keys = ['target', 'clients', 'inserted_rows', 'elapsed_ns', 'ledger_enabled']
            self.write(path, keys, dict(target='upstream_derby', clients=8,
                                       inserted_rows=100, elapsed_ns=1000,
                                       ledger_enabled='false'))
            window = summary.read_window(path)
            self.assertEqual(100, window['inserted_rows'])
            self.assertEqual('false', window['ledger_enabled'])

    def test_complete_campaign_uses_worker_evidence_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            window_keys = ['target', 'clients', 'inserted_rows', 'elapsed_ns', 'ledger_enabled']
            ledger_keys = ['resource', 'context', 'acquires', 'hold_ns', 'wait_ns',
                           'first_acquire_ns', 'last_release_ns'] + summary.PARTITION
            for shape in ('BARE', 'PRIMARY_KEY_ONLY'):
                for mode in ('false', 'true'):
                    workers = root / shape / mode / 'workers'
                    workers.mkdir(parents=True)
                    for target in ('delos_heap', 'upstream_derby'):
                        for clients in (4, 8):
                            for run in range(1, 5):
                                enabled = mode == 'true' and target == 'delos_heap'
                                stem = f'occupancy-{target}-{clients}-run-{run}'
                                self.write(workers / (stem + '-window.csv'), window_keys,
                                           dict(target=target, clients=clients, inserted_rows=100,
                                                elapsed_ns=1000, ledger_enabled=str(enabled).lower()))
                                if enabled:
                                    row = dict.fromkeys(ledger_keys, 0)
                                    row.update(resource='PAGE:1', context='HEAP_INSERT_PAGE',
                                               acquires=100, hold_ns=600, self_ns=400,
                                               log_hold_ns=200, first_acquire_ns=1000,
                                               last_release_ns=1900)
                                    self.write(workers / (stem + '.csv'), ledger_keys, row)
            result = summary.summarize(root)
            self.assertEqual(64, len(result['cells']))
            self.assertEqual(4, len(result['calibration']))
            self.assertTrue(all(cell['valid_within_five_percent']
                                and cell['reference_stable_within_five_percent']
                                for cell in result['calibration']))

    def test_sampled_raw_partition_is_separate_from_weighted_estimate(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'sampled.csv'
            keys = ['resource', 'context', 'acquires', 'hold_ns', 'wait_ns',
                    'first_acquire_ns', 'last_release_ns', 'sample_every'] + summary.PARTITION
            row = dict.fromkeys(keys, 0)
            row.update(resource='LOG:1', context='OTHER', acquires=4, hold_ns=100,
                       self_ns=40, dirty_flush_ns=60, wait_ns=50,
                       first_acquire_ns=1000, last_release_ns=2000, sample_every=32)
            self.write(path, keys, row)
            resource = summary.ledger(path, {'elapsed_ns': 10000, 'inserted_rows': 100})[0]
            self.assertEqual(100, resource['hold_ns'])
            self.assertEqual(.01, resource['run_utilization'])
            self.assertEqual(.32, resource['estimated_run_utilization'])
            self.assertEqual(32, resource['estimated_hold_ns_per_inserted_row'])
            self.assertEqual(3200, sum(resource['estimated_partition_ns'].values()))

    def test_paired_off_on_on_off_windows_keep_reference_modes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workers = root / 'BARE' / 'paired' / 'workers'
            workers.mkdir(parents=True)
            window_keys = ['target', 'clients', 'inserted_rows', 'elapsed_ns',
                           'ledger_enabled', 'calibration_mode']
            ledger_keys = ['resource', 'context', 'acquires', 'hold_ns', 'wait_ns',
                           'first_acquire_ns', 'last_release_ns', 'sample_every'] + summary.PARTITION
            for target in ('delos_heap', 'upstream_derby'):
                for run in range(1, 9):
                    mode = (run - 1) % 4 in (1, 2)
                    enabled = mode and target == 'delos_heap'
                    stem = f'occupancy-{target}-4-run-{run}'
                    self.write(workers / (stem + '-window.csv'), window_keys,
                               dict(target=target, clients=4, inserted_rows=100, elapsed_ns=1000,
                                    ledger_enabled=str(enabled).lower(), calibration_mode=str(mode).lower()))
                    if enabled:
                        row = dict.fromkeys(ledger_keys, 0)
                        row.update(resource='PAGE:1', context='HEAP_INSERT_PAGE', acquires=1,
                                   hold_ns=10, self_ns=5, log_hold_ns=5,
                                   first_acquire_ns=1000, last_release_ns=2000, sample_every=32)
                        self.write(workers / (stem + '.csv'), ledger_keys, row)
            result = summary.summarize(root, ('BARE',), ('4',), paired=True)
            self.assertEqual(16, len(result['cells']))
            self.assertEqual(4, len(result['paired_comparisons']))
            self.assertTrue(all(pair['heap_ratio'] == 1 for pair in result['paired_comparisons']))
            self.assertEqual(8, sum(cell['mode'] == 'true' for cell in result['cells']))
            self.assertTrue(result['calibration'][0]['valid_within_five_percent'])
            self.assertTrue(result['calibration'][0]['reference_stable_within_five_percent'])

    @staticmethod
    def write(path, keys, row):
        with path.open('w', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=keys)
            writer.writeheader()
            writer.writerow(row)


if __name__ == '__main__':
    unittest.main()
