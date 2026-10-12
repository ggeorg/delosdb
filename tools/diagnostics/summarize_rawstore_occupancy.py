#!/usr/bin/env python3
"""Validate and summarize bounded F08 evidence; never infer a win from instrumentation."""
import argparse
import csv
import json
import statistics
from pathlib import Path

PARTITION = ["self_ns", "child_wait_ns", "alloc_wait_ns", "alloc_hold_ns",
             "log_wait_ns", "log_hold_ns", "switch_buffer_ns", "dirty_flush_ns", "switch_file_ns"]


def read_window(path):
    with path.open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    if len(rows) != 1:
        raise ValueError(f"Expected one measurement window: {path}")
    row = rows[0]
    row["elapsed_ns"] = int(row["elapsed_ns"])
    row["inserted_rows"] = int(row["inserted_rows"])
    if min(row["elapsed_ns"], row["inserted_rows"]) <= 0:
        raise ValueError(f"Invalid measurement window: {path}")
    return row


def ledger(path, window):
    with path.open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    resources = {}
    for row in rows:
        if row["resource"].startswith("INVALID"):
            raise ValueError(f"Ownership tracking invalid: {path}")
        numeric = {key: int(value) for key, value in row.items()
                   if key not in ("resource", "context")}
        if any(value < 0 for value in numeric.values()):
            raise ValueError(f"Negative counter: {path}: {row['resource']}")
        hold = numeric["hold_ns"]
        if sum(numeric[key] for key in PARTITION) != hold:
            raise ValueError(f"Overlapping or incomplete partition: {path}: {row['resource']}")
        resource = resources.setdefault(row["resource"], {
            "resource": row["resource"], "contexts": [], "acquires": 0, "hold_ns": 0,
            "wait_ns": 0, "first_acquire_ns": None, "last_release_ns": 0,
            "partition_ns": {key: 0 for key in PARTITION},
            "sample_every": numeric.get("sample_every", 1)})
        if resource["sample_every"] != numeric.get("sample_every", 1):
            raise ValueError(f"Mixed sampling weights: {path}: {row['resource']}")
        resource["contexts"].append(row["context"])
        for key in ("acquires", "hold_ns", "wait_ns"):
            resource[key] += numeric[key]
        for key in PARTITION:
            resource["partition_ns"][key] += numeric[key]
        if numeric["first_acquire_ns"]:
            begin = resource["first_acquire_ns"]
            resource["first_acquire_ns"] = min(begin or numeric["first_acquire_ns"],
                                               numeric["first_acquire_ns"])
        resource["last_release_ns"] = max(resource["last_release_ns"], numeric["last_release_ns"])
    for resource in resources.values():
        hold = resource["hold_ns"]
        weight = resource["sample_every"]
        resource["run_utilization"] = hold / window["elapsed_ns"]
        resource["estimated_run_utilization"] = weight * hold / window["elapsed_ns"]
        resource["estimated_acquires"] = weight * resource["acquires"]
        resource["estimated_hold_ns_per_inserted_row"] = weight * hold / window["inserted_rows"]
        resource["estimated_wait_ns_per_inserted_row"] = weight * resource["wait_ns"] / window["inserted_rows"]
        resource["estimated_partition_ns"] = {key: value * weight for key, value in resource["partition_ns"].items()}
        resource["hold_ns_per_inserted_row"] = hold / window["inserted_rows"]
        resource["wait_ns_per_inserted_row"] = resource["wait_ns"] / window["inserted_rows"]
        resource["saturated_capacity_rows_per_second"] = (
            1e9 * window["inserted_rows"] / (weight * hold) if hold else None)
        span = resource["last_release_ns"] - (resource["first_acquire_ns"] or 0)
        resource["observed_span_ns"] = span
        resource["observed_span_utilization"] = hold / span if span > 0 else None
        if resource["run_utilization"] > 1.005:
            raise ValueError(f"Exclusive instance occupancy exceeds wall time: {path}: {resource}")
    return sorted(resources.values(), key=lambda resource: resource["hold_ns"], reverse=True)


def summarize(root, shapes=("BARE", "PRIMARY_KEY_ONLY"), clients=("4", "8"), paired=False):
    cells = []
    rates = {}
    for shape in shapes:
        for folder in (("paired",) if paired else ("false", "true")):
            directory = root / shape / folder
            paths = sorted((directory / "workers").glob("occupancy-*-window.csv"))
            expected_count = 2 * len(clients) * (8 if paired else 4)
            if len(paths) != expected_count:
                raise ValueError(f"Expected 2 engines × {len(clients)} client counts × "
                                 f"{8 if paired else 4} runs: {directory}")
            for path in paths:
                window = read_window(path)
                enabled = window.get("calibration_mode") if paired else folder
                if enabled not in ("false", "true"):
                    raise ValueError(f"Missing paired calibration mode: {path}")
                expected = enabled == "true" and window["target"] == "delos_heap"
                if (window["ledger_enabled"] == "true") != expected:
                    raise ValueError(f"Ledger propagation mismatch: {path}")
                run = int(path.name.split('-run-')[1].split('-window.csv')[0])
                cell = dict(window, shape=shape, mode=enabled, run=run, path=str(path))
                cell["rows_per_second"] = 1e9 * window["inserted_rows"] / window["elapsed_ns"]
                key = (shape, enabled, window["target"], window["clients"])
                rates.setdefault(key, []).append(cell["rows_per_second"])
                if expected:
                    raw = Path(str(path).replace("-window.csv", ".csv"))
                    resources = ledger(raw, window)
                    if not resources:
                        raise ValueError(f"Empty enabled ledger: {raw}")
                    cell["resource_count"] = len(resources)
                    cell["top_resources_by_run_occupancy"] = resources[:20]
                    heap = [resource for resource in resources
                            if "HEAP_INSERT_PAGE" in resource["contexts"] and resource["acquires"] >= 20]
                    cell["heap_pages_by_observed_span_utilization"] = sorted(heap,
                            key=lambda resource: resource["observed_span_utilization"] or 0, reverse=True)[:10]
                cells.append(cell)
    calibration = []
    for shape in shapes:
        for client in clients:
            for mode in ("false", "true"):
                for target in ("delos_heap", "upstream_derby"):
                    if len(rates.get((shape, mode, target, client), [])) != 4:
                        raise ValueError(f"Expected four observations per calibration cell: {shape}/{mode}/{target}/{client}")
            medians = {(mode, target): statistics.median(rates[(shape, mode, target, client)])
                       for mode in ("false", "true") for target in ("delos_heap", "upstream_derby")}
            ratio = medians[("true", "delos_heap")] / medians[("false", "delos_heap")]
            reference_ratio = medians[("true", "upstream_derby")] / medians[("false", "upstream_derby")]
            calibration.append({"shape": shape, "clients": int(client),
                                "ledger_on_off_ratio": ratio,
                                "derby_reference_on_off_ratio": reference_ratio,
                                "normalized_ratio": ratio / reference_ratio,
                                "valid_within_five_percent": abs(ratio - 1) <= .05,
                                "reference_stable_within_five_percent": abs(reference_ratio - 1) <= .05})
    paired_comparisons = []
    if paired:
        lookup = {(cell["shape"], cell["clients"], cell["target"], cell["run"]): cell
                  for cell in cells}
        for shape in shapes:
            for client in clients:
                for off_run, on_run in ((1, 2), (4, 3), (5, 6), (8, 7)):
                    ratios = {}
                    for target in ("delos_heap", "upstream_derby"):
                        off = lookup[(shape, client, target, off_run)]
                        on = lookup[(shape, client, target, on_run)]
                        if off["mode"] != "false" or on["mode"] != "true":
                            raise ValueError("Paired run order does not match OFF-ON-ON-OFF")
                        ratios[target] = on["rows_per_second"] / off["rows_per_second"]
                    paired_comparisons.append({"shape": shape, "clients": int(client),
                        "off_run": off_run, "on_run": on_run,
                        "heap_ratio": ratios["delos_heap"], "reference_ratio": ratios["upstream_derby"],
                        "normalized_ratio": ratios["delos_heap"] / ratios["upstream_derby"]})
    return {"calibration": calibration, "paired_comparisons": paired_comparisons, "cells": cells,
            "interpretation": "Sampled raw counts and times remain unscaled; estimated fields multiply by sample_every. "
            "Sampling estimates have variance and short-lived page lifetimes are only partially observed. "
            "Resource holds overlap: never add holds across different resources. "
            "Observed-span utilization for a short-lived Heap page is not run utilization. "
            "Capacity uses full-run occupancy per inserted row, not literal row latency. "
            "Reject architectural inference if calibration fails. "
            "U / occupancy_per_row is algebraically measured throughput, not an independent prediction."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--paired", action="store_true", help="Read interleaved OFF/ON workers")
    parser.add_argument("--shape", choices=("BARE", "PRIMARY_KEY_ONLY"))
    parser.add_argument("--clients", choices=("4", "4,8"), default="4,8")
    args = parser.parse_args()
    result = summarize(args.report_root,
                       (args.shape,) if args.shape else ("BARE", "PRIMARY_KEY_ONLY"),
                       tuple(args.clients.split(",")), args.paired)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    valid = all(cell["valid_within_five_percent"] and cell["reference_stable_within_five_percent"]
                for cell in result["calibration"])
    print(f"Evidence written to {args.output}; calibration {'valid' if valid else 'INVALID'}")
    return 0 if valid else 2


if __name__ == "__main__":
    raise SystemExit(main())
