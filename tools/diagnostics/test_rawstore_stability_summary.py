"""Checks window clipping and non-overlapping JVM instability attribution."""
import tempfile
import unittest
from pathlib import Path
import summarize_rawstore_stability as summary


class StabilitySummaryTest(unittest.TestCase):
    def test_gc_and_safepoints_are_clipped_and_not_added(self):
        lines = [
            '[1.000s][info][gc] GC(0) Pause Young 100M->1M(1024M) 20.000ms',
            '[2.020s][info][gc] GC(1) Pause Young 100M->1M(1024M) 30.000ms',
            '[3.010s][info][gc] GC(2) Pause Young 100M->1M(1024M) 20.000ms',
            '[2.010s][info][safepoint] Safepoint "G1CollectForAllocation", Total: 20000000 ns',
            '[2.500s][debug][jit,compilation] 100 4 example.Driver::insert (12 bytes)',
            '[2.600s][info][jit,compilation] 100 4 example.Driver::insert made not entrant',
            '[3.500s][debug][jit,compilation] 101 4 example.Driver::verify (12 bytes)',
        ]
        result = summary.jvm_events(lines, 2.0, 3.0)
        self.assertAlmostEqual(30, result['gc_pause_ms'])
        self.assertAlmostEqual(10, result['safepoint_total_ms'])
        self.assertEqual(2, result['gc_pause_events'])
        self.assertEqual(1, result['compilation_events'])
        self.assertEqual(1, result['invalidation_events'])
        self.assertNotIn('total_pause_ms', result)

    def test_dropped_logs_are_explicit(self):
        result = summary.jvm_events(['[1.0s][warning][logging] Dropped 5 messages'], 2.0, 3.0)
        self.assertEqual(1, result['dropped_log_warnings'])

    def test_partial_guarded_cohort_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'workers').mkdir()
            with self.assertRaisesRegex(ValueError, 'four OFF runs'):
                summary.summarize(root)


if __name__ == '__main__':
    unittest.main()
