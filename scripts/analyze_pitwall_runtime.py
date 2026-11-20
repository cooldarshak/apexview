#!/usr/bin/env python3
"""Compare app-side playback/jank counters from matching Pitwall log intervals.

These are app-side counters, not a substitute for Perfetto physical FrameTimeline.
In-app exports may append several versions: --build limits the selected session.
Use logs from the same device, scene, feed resolutions, clock and sample duration.
"""

import argparse
import re
from pathlib import Path
from statistics import median


def fields(line):
    return dict(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=([^\s]+)", line))


def app_session(text, version):
    if version is None:
        return text
    # The diagnostic exporter appends a fresh capture with the next app version.
    marker = re.compile(r"^Apexview\s+(\d+(?:\.\d+)+)\s+", re.MULTILINE)
    matches = list(marker.finditer(text))
    selected = [i for i, match in enumerate(matches) if match.group(1) == version]
    if not selected:
        raise ValueError(f"no capture for Apexview {version}")
    i = selected[-1]
    start = matches[i].start()
    end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
    return text[start:end]


def as_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def summarize(text):
    frames = slow = frozen = total_ms = queue = 0
    queue_surfaces = {}
    scene_samples = latched = drawn = 0
    failures = 0
    active_decoders = set()
    four_feed_prepared = False
    segments = []
    for line in text.splitlines():
        f = fields(line)
        if "PitwallFrames:" in line and f.get("role") == "player":
            frames += as_int(f.get("frames"))
            slow += as_int(f.get("slow"))
            frozen += as_int(f.get("frozen700ms"))
            total_ms += as_int(f.get("intervalMs"))
        if "dequeueBuffer: waitForFreeSlotThenRelock: timeout" in line:
            if "BUFFER_QUEUE_TIMEOUT repeats=" in line:
                count = as_int(f.get("repeats"))
                source = line.split("BufferQueueProducer:", 1)[-1].split("dequeueBuffer:", 1)[0].strip()
            else:
                count = 1
                source = line.split("BufferQueueProducer:", 1)[-1].split("dequeueBuffer:", 1)[0].strip()
            queue += count
            queue_surfaces[source] = queue_surfaces.get(source, 0) + count
        if "SINGLE_DECODER_SCENE " in line and "pacingLatched=" in line:
            scene_samples += 1
            latched += as_int(f.get("pacingLatched"))
            drawn += as_int(f.get("pacingPresented"))
        if "ARCHIVE_VOD_PREPARED feeds=4" in line:
            four_feed_prepared = True
        if "ARCHIVE_VOD_SEGMENT " in line:
            segments.append({key: as_int(f.get(key)) for key in (
                "number", "processAllocBytesDuringSegment", "processGcDuringSegment",
                "parseMs", "rewriteMs", "packageMs", "composeMs", "outputBytes")})
        if "ARCHIVE_VOD_FAILED" in line or "event=load-error" in line:
            failures += 1
        if "F1_COMPOSITION_PLAYBACK" in line and "stage=ACTIVE" in line:
            active_decoders.add(f.get("activeVideoDecoders", "unknown"))
    # First three segments include player warmup, HTTP cache fill and codec startup.
    # Report them separately so they cannot distort steady-state allocation claims.
    steady = segments[3:]
    return {
        "frames": frames,
        "slow": slow,
        "slow_pct": round(100 * slow / frames, 1) if frames else None,
        "frozen": frozen,
        "covered_seconds": round(total_ms / 1000, 1),
        "queue_timeouts": queue,
        "queue_per_second": round(1000 * queue / total_ms, 2) if total_ms else None,
        "queue_surfaces": queue_surfaces,
        "scene_samples": scene_samples,
        "latched": latched,
        "drawn": drawn,
        "failures": failures,
        "active_decoders": sorted(active_decoders),
        "four_feed_prepared": four_feed_prepared,
        "archive_segments_total": len(segments),
        "archive_segments_steady": len(steady),
        "archive_alloc_median_bytes": int(median(s["processAllocBytesDuringSegment"] for s in steady)) if steady else None,
        "archive_gc_median": median(s["processGcDuringSegment"] for s in steady) if steady else None,
        "archive_compose_median_ms": median(s["composeMs"] for s in steady) if steady else None,
        "archive_rewrite_median_ms": median(s["rewriteMs"] for s in steady) if steady else None,
        "archive_output_median_bytes": int(median(s["outputBytes"] for s in steady)) if steady else None,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="Pitwall exported diagnostic text file")
    parser.add_argument("--build", help="For appended logs, use only this version, e.g. 1.0.573")
    args = parser.parse_args()
    report = summarize(app_session(args.log.read_text(encoding="utf-8", errors="replace"), args.build))
    for label, value in report.items():
        print(f"{label}: {value}")
    print("CAUTION: the summary includes all modes in the selected session. "
          "Use steady-state intervals for valid scene-to-scene comparisons; "
          "only Perfetto verifies physical display frames.")


if __name__ == "__main__":
    main()
