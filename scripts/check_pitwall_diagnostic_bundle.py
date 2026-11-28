#!/usr/bin/env python3
"""Verify manual capture integrity and actual producer coverage, not physical acceptance."""
import argparse
import hashlib
import pathlib
import re
import subprocess
import sys

from check_pitwall_memory_trace import inspect_heap_quality, heap_quality_ok

PACKAGE = "app.apexview_custom"
HASH = re.compile(r"^[0-9a-f]{64}$")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def hash_record(path):
    fields = path.read_text().split()
    if not fields or not HASH.fullmatch(fields[0]):
        raise ValueError("Missing or invalid digest: " + path.name)
    return fields[0]


def manifest(path):
    result = {}
    for line in path.read_text().splitlines():
        key, sep, value = line.partition("=")
        if not sep or key in result:
            raise ValueError("Malformed or duplicate manifest key")
        result[key] = value
    return result


def count_sql(processor, trace, query):
    result = subprocess.run([str(processor), "-Q", query, str(trace)],
                            capture_output=True, text=True, timeout=120, check=True)
    # Query returns a single integer; reject missing/malformed output.
    rows = [line.strip().strip('"') for line in result.stdout.splitlines()
            if line.strip()]
    if len(rows) != 2 or rows[0] != "n" or not rows[1].isdigit():
        raise ValueError("Unexpected trace processor result")
    return int(rows[1])


def verify(root, processor=None):
    m = manifest(root / "manifest.txt")
    if m.get("package") != PACKAGE or not re.fullmatch(r"[1-9][0-9]*", m.get("pid", "")):
        raise ValueError("Invalid target identity")
    pid = int(m["pid"])
    if m.get("end_pid") != m["pid"] or m.get("export_status") != "0":
        raise ValueError("Changed PID or failed export")
    if int(m["end_epoch"]) <= int(m["start_epoch"]):
        raise ValueError("Invalid capture interval")
    if hash_record(root / "apk-start.sha256") != hash_record(root / "apk-end.sha256"):
        raise ValueError("APK changed during capture")
    mode = m["mode"]
    if mode == "memory":
        if (m["start_status"], m["stop_status"]) != ("UNPROFILED", "UNPROFILED"):
            raise ValueError("Invalid unprofiled capture")
        for name in ("meminfo-start.txt", "meminfo-end.txt"):
            if "TOTAL PSS" not in (root / name).read_text():
                raise ValueError("Missing meminfo PSS")
        return "UNPROFILED_SNAPSHOTS_ONLY; validate rolling app log separately"
    if mode not in ("system", "native", "java"):
        raise ValueError("Unknown capture mode")
    if (m["start_status"], m["stop_status"]) != ("DETACHED", "STOPPED"):
        raise ValueError("Capture startup or stop not verified")
    trace = root / "trace.pftrace"
    if not trace.stat().st_size or digest(trace) != hash_record(root / "trace.sha256"):
        raise ValueError("Missing or changed trace")
    if mode == "native":
        with trace.open("rb") as stream:
            counts = inspect_heap_quality(stream, pid)
        if not heap_quality_ok(counts, 3):
            raise ValueError("Incomplete native heap snapshots: " + str(counts))
        return "NATIVE_PACKET_COVERAGE; symbol attribution still required"
    if processor is None:
        raise ValueError("Trace processor required for system or Java coverage")
    process = f"SELECT upid FROM process WHERE pid={pid} AND name='{PACKAGE}'"
    queries = {
        "system": {
            "app_scheduler": f"SELECT count(*) AS n FROM sched JOIN thread USING(utid) WHERE upid IN ({process})",
            "app_frame_timeline": f"SELECT count(*) AS n FROM actual_frame_timeline_slice WHERE upid IN ({process})",
        },
        "java": {
            "app_heap_objects": f"SELECT count(*) AS n FROM heap_graph_object WHERE upid IN ({process})",
        },
    }[mode]
    for name, query in queries.items():
        if count_sql(processor, trace, query) == 0:
            raise ValueError("Missing actual coverage: " + name)
    return "TARGET_TABLE_COVERAGE; not physical acceptance"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("bundle", type=pathlib.Path)
    p.add_argument("--trace-processor", type=pathlib.Path)
    a = p.parse_args()
    try:
        print(verify(a.bundle, a.trace_processor))
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as exc:
        print("PARTIAL_EVIDENCE:", exc, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
