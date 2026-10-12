#!/usr/bin/env python3
"""Align JVM and host observations with the fixed ledger-OFF BARE/4-client window."""
import argparse
import csv
import datetime as dt
import json
import re
import statistics
from pathlib import Path

LOG = re.compile(r'^\[([\d.]+)s\]\[\w+\]\[([^]]+)\]\s*(.*)$')
GC_PAUSE = re.compile(r'Pause.*\s([\d.]+)ms$')
SAFEPOINT = re.compile(r'Total:\s*(\d+) ns')
VM_COUNTERS = re.compile(r'(Pageins|Pageouts|Swapins|Swapouts|Pages occupied by compressor|Pages stored in compressor):\s*(\d+)\.')


def timestamp(text):
    return dt.datetime.fromisoformat(text.replace('Z', '+00:00'))


def overlap(begin, end, event_end, duration):
    return max(0.0, min(end, event_end) - max(begin, event_end - duration))


def jvm_events(lines, begin, end):
    gc_ms = 0.0
    safepoint_ms = 0.0
    gc_count = safepoints = compilations = invalidations = drops = 0
    for line in lines:
        match = LOG.match(line)
        if not match:
            continue
        time = float(match[1])
        tags = match[2].strip()
        text = match[3]
        if tags == 'logging' and 'drop' in text.lower():
            drops += 1
        if tags == 'gc':
            pause = GC_PAUSE.search(text)
            if pause:
                duration = float(pause[1])
                included = overlap(begin, end, time, duration / 1000) * 1000
                if included > 0:
                    gc_ms += included
                    gc_count += 1
        if tags == 'safepoint':
            pause = SAFEPOINT.search(text)
            if pause:
                included = overlap(begin, end, time, int(pause[1]) / 1e9) * 1000
                if included > 0:
                    safepoint_ms += included
                    safepoints += 1
        if tags == 'jit,compilation' and begin <= time <= end:
            if 'made not entrant' in text or 'made zombie' in text:
                invalidations += 1
            else:
                compilations += 1
    return dict(gc_pause_ms=gc_ms, gc_pause_events=gc_count,
                safepoint_total_ms=safepoint_ms, safepoint_events=safepoints,
                compilation_events=compilations, invalidation_events=invalidations,
                dropped_log_warnings=drops)


def one_csv(path):
    with path.open(newline='') as stream:
        rows = list(csv.DictReader(stream))
    if len(rows) != 1:
        raise ValueError(f'Expected one measurement window: {path}')
    return rows[0]


def host_samples(path, target, run, begin, end):
    with path.open(newline='') as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    selected = [row for row in rows if row['target'] == target and int(row['run']) == run
                and begin <= timestamp(row['capturedAtUtc']) <= end]
    result = dict(sample_count=len(selected))
    if not selected:
        return result
    result['system_cpu_load_min'] = min(float(row['systemCpuLoad']) for row in selected)
    result['system_cpu_load_max'] = max(float(row['systemCpuLoad']) for row in selected)
    result['free_memory_min_bytes'] = min(int(row['freeMemoryBytes']) for row in selected)
    first = dict((key, int(value)) for key, value in VM_COUNTERS.findall(selected[0]['vmStat'].replace('\\n', '\n')))
    last = dict((key, int(value)) for key, value in VM_COUNTERS.findall(selected[-1]['vmStat'].replace('\\n', '\n')))
    if len(selected) > 1:
        result['vm_counter_delta_between_samples'] = {key: last[key] - first[key] for key in first.keys() & last.keys()}
    other_java_peak = 0.0
    worker_states = []
    for row in selected:
        for line in row['topProcesses'].replace('\\n', '\n').splitlines():
            fields = line.split(maxsplit=6)
            if len(fields) < 7:
                continue
            if fields[0] == row['workerPid']:
                worker_states.append(fields[4])
            elif fields[6].endswith('/java'):
                other_java_peak = max(other_java_peak, float(fields[2]))
    result['other_java_peak_cpu_percent'] = other_java_peak
    result['worker_observed_process_states'] = worker_states
    return result


def summarize(directory):
    windows = sorted((directory / 'workers').glob('occupancy-*-window.csv'))
    if len(windows) != 8:
        raise ValueError('Expected two engines × four OFF runs at four clients')
    cells = []
    seen = set()
    for path in windows:
        window = one_csv(path)
        target = window['target']
        run = int(path.name.split('-run-')[1].split('-')[0])
        key = (target, run)
        if key in seen or target not in ('delos_heap', 'upstream_derby') or run not in range(1, 5):
            raise ValueError(f'Invalid or duplicate stability window: {path}')
        seen.add(key)
        if window['clients'] != '4' or window['ledger_enabled'] != 'false':
            raise ValueError(f'Stability window must use four clients and ledger OFF: {path}')
        stem = path.name.removesuffix('-window.csv')
        jvm = one_csv(path.with_name(stem + '-jvm.csv'))
        elapsed = int(window['elapsed_ns'])
        rows = int(window['inserted_rows'])
        if elapsed <= 0 or rows <= 0 or int(jvm['elapsed_ns']) != elapsed or int(jvm['inserted_rows']) != rows:
            raise ValueError(f'Mismatched JVM/throughput boundaries: {path}')
        begin = float(jvm['start_uptime_ms']) / 1000
        end = float(jvm['end_uptime_ms']) / 1000
        if abs((end - begin) - elapsed / 1e9) > 1e-5:
            raise ValueError(f'Invalid JVM uptime interval: {path}')
        log_path = directory / 'profiles' / f'jvm-{run}-{target}.log'
        events = jvm_events(log_path.read_text().splitlines(), begin, end)
        if events['dropped_log_warnings']:
            raise ValueError(f'Incomplete JVM diagnostic log: {log_path}')
        cells.append(dict(target=target, run=run, rows_per_second=rows * 1e9 / elapsed,
                          elapsed_seconds=elapsed / 1e9,
                          process_cpu_average_cores=int(jvm['process_cpu_ns']) / elapsed
                          if int(jvm['process_cpu_ns']) >= 0 else None,
                          compilation_ms=int(jvm['compilation_ms']),
                          gc_collection_ms=int(jvm['gc_collection_ms']), **events,
                          host=host_samples(directory / 'host-process-diagnostics.tsv', target, run,
                                            timestamp(jvm['captured_before_utc']), timestamp(jvm['captured_after_utc']))))
    dispersion = []
    for target in ('delos_heap', 'upstream_derby'):
        rates = [cell['rows_per_second'] for cell in cells if cell['target'] == target]
        median = statistics.median(rates)
        dispersion.append(dict(target=target, median_rows_per_second=median,
                               min_rows_per_second=min(rates), max_rows_per_second=max(rates),
                               range_over_median=(max(rates) - min(rates)) / median))
    return dict(cells=cells, dispersion=dispersion,
                interpretation='GC pause time is contained in safepoint time: do not add them. '
                'Compilation time is aggregated across compiler threads and overlaps application execution. '
                'Host samples are sparse; compression counter deltas are host-wide, not worker attribution. '
                'Historical swap allocation is not proof of active swapping. '
                'These profiled OFF runs diagnose instability and cannot qualify ledger overhead.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = summarize(args.directory)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    for item in result['dispersion']:
        print(item['target'], round(item['median_rows_per_second']),
              f"range/median={item['range_over_median']:.1%}")


if __name__ == '__main__':
    main()
