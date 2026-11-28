#!/usr/bin/env python3
"""M51/M52: summarize actual queue refusals and graphics ownership, not inferred allocation bytes."""
import argparse
import json
import re
from pathlib import Path

QUEUE = re.compile(r"\[([^\]]+)\]\(id:([^,]+),[^)]*\).*?dequeuedCount:\s*(\d+),\s*acquiredCount:\s*(\d+),\s*mDequeueTimeout:\s*(-?\d+)")
REPEAT = re.compile(r"^BUFFER_QUEUE_TIMEOUT repeats=(\d+) last=")
FIELDS = re.compile(r"(\w+)=([^\s]+)")

def analyze(lines):
    queues = {}
    owners = {}
    released = 0
    created = 0
    owner_peak = 0
    max_contexts = 0
    max_textures = 0
    fence_max = 0
    fence_count = 0
    ingress = 0
    coalesced = 0
    samples = []
    for line in lines:
        q = QUEUE.search(line)
        if q and "BufferQueueProducer" in line and "waitForFreeSlotThenRelock" in line:
            name, identity, dequeued, acquired, timeout = q.groups()
            repeat = REPEAT.match(line)
            count = int(repeat.group(1)) if repeat else 1
            key = (name, identity)
            row = queues.setdefault(key, {"name": name, "id": identity, "events": 0,
                "nonblocking": 0, "max_dequeued": 0, "max_acquired": 0})
            row["events"] += count
            row["nonblocking"] += count if int(timeout) == 0 else 0
            row["max_dequeued"] = max(row["max_dequeued"], int(dequeued))
            row["max_acquired"] = max(row["max_acquired"], int(acquired))
        f = dict(FIELDS.findall(line))
        if "GRAPHICS_OWNER_EVENT " in line and "owner" in f:
            owner = f["owner"]
            event = f.get("event")
            if event == "RELEASE":
                released += 1
                owners.pop(owner, None)
            elif event in ("CREATE", "CHANGE"):
                created += event == "CREATE"
                owners[owner] = (int(f.get("contexts", 0)), int(f.get("decoderTextures", 0)))
                owner_peak = max(owner_peak, len(owners))
                max_contexts = max(max_contexts, sum(v[0] for v in owners.values()))
                max_textures = max(max_textures, sum(v[1] for v in owners.values()))
        elif "SCENE_ASYNC_FENCE_WAIT " in line:
            fence_max = max(fence_max, int(f.get("maxNs", 0)))
            fence_count += int(f.get("waits", 0))
        elif "SCENE_DECODER_INGRESS " in line:
            ingress += int(f.get("notifications", 0))
            coalesced += int(f.get("coalesced", 0))
        elif "platformGraphicsKiB" in f:
            try:
                samples.append((int(f["platformGraphicsKiB"]), int(f.get("sceneGraphicsOwners", -1))))
            except ValueError:
                pass
    return {"queues": sorted(queues.values(), key=lambda q: -q["events"]),
        "owner_creates": created, "owner_releases": released, "owners_at_end": len(owners),
        "owner_peak": owner_peak, "egl_context_peak": max_contexts,
        "decoder_texture_peak": max_textures, "fence_waits": fence_count,
        "max_fence_wait_ns": fence_max, "notifications": ingress, "coalesced": coalesced,
        "graphics_samples": len(samples),
        "peak_graphics_kb": max((v[0] for v in samples), default=None),
        "peak_graphics_with_no_scene_kb": max((v[0] for v in samples if v[1] == 0), default=None),
        "vendor_allocation_bytes": "NOT_MEASURED",
        "physical_video_presentation": "NOT_MEASURED"}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    with args.log.open(errors="replace") as log:
        print(json.dumps(analyze(log), indent=2))
