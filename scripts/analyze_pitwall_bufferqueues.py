#!/usr/bin/env python3
"""BufferQueue ownership / nonblocking-pressure analysis of real Pitwall log exports.

These are *platform diagnostic strings*, not physical frame-loss measurements.
A BufferQueueProducer [MediaCodec.release] queue is not automatically the scene
SurfaceTexture queue. mDequeueTimeout=0 means immediate/nonblocking refusal.
Correlate with independent GL/callback/SurfaceFlinger evidence before a stall claim.
"""
import argparse
import json
import re
from collections import defaultdict
from datetime import datetime
from pathlib import Path

STAMP = re.compile(r"(?<!\d)(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)")
QUEUE = re.compile(r"BufferQueueProducer:\s*\[([^\]]*)\](?:\(id:([^,)]*))?")
OWNERSHIP = ("SCENE_CODEC_INPUT_ATTACH", "SCENE_CODEC_INPUT_DETACH", "SCENE_CODEC_INPUT_RELEASE")
ISSUES = ("dequeueBuffer: waitForFreeSlotThenRelock: timeout",
          "detachBuffer: slot", "cancelBuffer: slot")


def at_ms(line):
    match = STAMP.search(line)
    if not match:
        return None
    month, day, hour, minute, second, ms = map(int, match.groups())
    try:
        return int(datetime(2026, month, day, hour, minute, second, ms * 1000).timestamp() * 1000)
    except ValueError:
        return None


def analyze(text):
    owners = []
    events = []
    for line in text.splitlines():
        moment = at_ms(line)
        for marker in OWNERSHIP:
            if marker in line:
                owners.append((moment, marker, line))
                break
        if "BufferQueueProducer:" not in line or not any(issue in line for issue in ISSUES):
            continue
        match = QUEUE.search(line)
        if not match:
            continue
        name, identity = match.groups()
        if "dequeueBuffer:" in line:
            issue = "dequeue_timeout"
        elif "detachBuffer:" in line:
            issue = "detach_not_owned"
        else:
            issue = "cancel_not_owned"
        repeats = re.search(r"BUFFER_QUEUE_TIMEOUT repeats=(\d+)", line)
        count = int(repeats.group(1)) if repeats and issue == "dequeue_timeout" else 1
        if not count:
            continue
        nonblocking = "mDequeueTimeout: 0" in line
        events.append((moment, name, identity or "unknown", issue, count, nonblocking))
    rows = defaultdict(lambda: {
        "dequeue_timeout": 0, "nonblocking_dequeue_timeout": 0,
        "blocking_or_unknown_dequeue_timeout": 0,
        "detach_not_owned": 0, "cancel_not_owned": 0,
        "near_app_detach_or_release": 0,
    })
    release_stamps = [moment for moment, kind, _ in owners
                      if moment is not None and kind != "SCENE_CODEC_INPUT_ATTACH"]
    for moment, name, identity, issue, count, nonblocking in events:
        # Name and queue id are essential. Never group unrelated 'SurfaceTexture'
        # producers into one assumed codec output.
        key = name + "#" + identity
        row = rows[key]
        row[issue] += count
        if issue == "dequeue_timeout":
            row["nonblocking_dequeue_timeout" if nonblocking
                else "blocking_or_unknown_dequeue_timeout"] += count
        if moment is not None and any(abs(moment - stamp) <= 3000 for stamp in release_stamps):
            row["near_app_detach_or_release"] += count
    return {
        "queue_events": sum(row["dequeue_timeout"] + row["detach_not_owned"] +
                            row["cancel_not_owned"] for row in rows.values()),
        "nonblocking_timeouts": sum(row["nonblocking_dequeue_timeout"] for row in rows.values()),
        "blocking_or_unknown_timeouts": sum(row["blocking_or_unknown_dequeue_timeout"] for row in rows.values()),
        "app_attach_events": sum(kind == "SCENE_CODEC_INPUT_ATTACH" for _, kind, _ in owners),
        "app_detach_events": sum(kind == "SCENE_CODEC_INPUT_DETACH" for _, kind, _ in owners),
        "app_release_events": sum(kind == "SCENE_CODEC_INPUT_RELEASE" for _, kind, _ in owners),
        "queue_by_identity": dict(sorted(rows.items())),
        "physical_queue_depth": "NOT_MEASURED",
        "physical_video_frames_dropped": "NOT_MEASURED",
        "teardown_nearby_is_correlation_not_proof": True,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    print(json.dumps(analyze(args.log.read_text(encoding="utf-8", errors="replace")),
                     indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
