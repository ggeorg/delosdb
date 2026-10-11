import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import contextlib
import io

spec = importlib.util.spec_from_file_location("insert_jfr", Path(__file__).parent / "insert-probe/jfr_insert_attribution.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class InsertAttributionTest(unittest.TestCase):
    def test_heap_and_index_use_the_same_page_api(self):
        base = "org.apache.derby.impl.store.raw.data.BasePage.insertAtSlot"
        self.assertEqual("heap", module.insert_page_kind([base, "HeapController.doInsert"]))
        self.assertEqual("B-tree", module.insert_page_kind([base, "BTreeController.doInsert"]))
        self.assertEqual("B-tree", module.insert_page_kind([base, "BTreeController.doInsert", "HeapController.insert"]))
        self.assertEqual("unclassified", module.insert_page_kind([base]))
        self.assertIsNone(module.insert_page_kind(["HeapController.insert"]))

    def test_report_keeps_index_samples_out_of_heap_denominator(self):
        base = "BasePage.insertAtSlot"
        events = [
            {"thread": "writer-0", "frames": [base, "HeapController.doInsert"]},
            {"thread": "writer-1", "frames": [base, "BTreeController.doInsert"]},
            {"thread": "writer-2", "frames": [base, "BTreeController.doInsert"]},
        ]
        output = io.StringIO()
        with patch.object(module, "jfr_events", return_value=iter(events)):
            with contextlib.redirect_stdout(output):
                module.latched_work("mixed-recording")
        self.assertIn("heap page insertion: 1 (33%", output.getvalue())
        self.assertIn("B-tree page insertion: 2 (67%", output.getvalue())
