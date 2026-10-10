#!/usr/bin/env python3
"""Attribute writer time in an insert-probe JFR recording.

usage: python3 -I tools/diagnostics/insert-probe/jfr_insert_attribution.py RECORDING.jfr

Prints, for threads named writer-*:
  1. monitor-wait time (page latches wait on the page monitor) by monitor class and
     first non-latch call site;
  2. CPU samples taken while a heap page is latched (BasePage.insert* on the stack),
     split into log-record construction, page apply, record locking and WAL append.

Streams `jfr print` text output; never expands the recording to JSON (multi-GB).
"""
import collections
import re
import shutil
import subprocess
import sys

UNITS = {"ns": 1e-6, "us": 1e-3, "ms": 1.0, "s": 1000.0}


def jfr_events(recording, event, depth):
    jfr = shutil.which("jfr") or "jfr"
    process = subprocess.Popen(
        [jfr, "print", "--events", event, "--stack-depth", str(depth), recording],
        stdout=subprocess.PIPE, text=True)
    current = None
    for raw in process.stdout:
        line = raw.strip()
        if line.startswith(event + " {"):
            current = {"frames": []}
        elif current is None:
            continue
        elif line.startswith("duration ="):
            match = re.match(r"([\d.]+)\s*(ns|us|ms|s)\b", line.split("=", 1)[1].strip())
            current["ms"] = float(match.group(1)) * UNITS[match.group(2)] if match else 0.0
        elif line.startswith("monitorClass ="):
            current["monitor"] = line.split("=", 1)[1].split("(")[0].strip().split(".")[-1]
        elif line.startswith("eventThread =") or line.startswith("sampledThread ="):
            current["thread"] = line.split('"')[1] if '"' in line else ""
        elif "line:" in line:
            current["frames"].append(line.split("(")[0])
        elif line == "}":
            yield current
            current = None
    if process.wait() != 0:
        sys.exit("jfr print failed for " + recording)


def short(frame):
    parts = frame.split(".")
    return ".".join(parts[-2:])


def monitor_waits(recording):
    totals = collections.defaultdict(lambda: [0, 0.0])
    for event in jfr_events(recording, "jdk.JavaMonitorWait", 8):
        if not event.get("thread", "").startswith("writer"):
            continue
        frames = [short(f) for f in event["frames"]]
        site = next((f for f in frames if not f.startswith(("Object.", "BasePage."))), "?")
        total = totals[(event.get("monitor", "?"), site)]
        total[0] += 1
        total[1] += event.get("ms", 0.0)
    print("writer monitor waits (s), by monitor and site:")
    print("  total %.2f s" % (sum(t[1] for t in totals.values()) / 1000))
    for (monitor, site), (count, ms) in sorted(totals.items(), key=lambda kv: -kv[1][1])[:10]:
        print("  %8d %8.2f s  %-24s %s" % (count, ms / 1000, monitor, site))


def latched_work(recording):
    def category(stack):
        if "LogToFile.appendLogRecord" in stack:
            return "WAL append (LogToFile monitor)"
        if "doMe" in stack:
            return "apply to page (doMe)"
        if any(k in stack for k in ("InsertOperation.<init>", "logRow", "LogRecord.setValue",
                                     "writeUTF", "FileLogger.logAndDo")):
            return "build log record (serialize row)"
        if any(k in stack for k in ("lockRecordForWrite", "LockSet", "lockObject")):
            return "record lock"
        return "other"

    writer = latched = 0
    categories = collections.Counter()
    for event in jfr_events(recording, "jdk.ExecutionSample", 64):
        if not event.get("thread", "").startswith("writer"):
            continue
        writer += 1
        stack = " ".join(event["frames"])
        if "BasePage.insert" in stack:
            latched += 1
            categories[category(stack)] += 1
    print("CPU samples while a heap page is latched:")
    print("  writer samples %d, under heap-page latch %d (%.0f%%)"
          % (writer, latched, 100.0 * latched / max(writer, 1)))
    for name, count in categories.most_common():
        print("  %5.1f%%  %s" % (100.0 * count / max(latched, 1), name))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    monitor_waits(sys.argv[1])
    latched_work(sys.argv[1])
