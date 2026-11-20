#!/usr/bin/env python3
"""Compare exported Perfetto FrameTimeline CSVs from matched playback intervals.

Export docs/perfetto/frame_timeline_export.sql from ui.perfetto.dev. The physical
display frames here are NOT decoded video frames. To verify a 50fps feed, compare
the companion SINGLE_DECODER_SCENE codec/texture/Choreographer cadence logs too.
"""

import argparse
import csv
import math
from pathlib import Path
from statistics import median


def read_timeline(path):
    required = {"ts", "dur", "display_frame_token", "surface_frame_token",
                "present_type", "jank_type", "layer_name", "process_name"}
    with Path(path).open(newline="", encoding="utf-8-sig") as stream:
        rows = csv.DictReader(stream)
        if rows.fieldnames is None or not required.issubset(rows.fieldnames):
            raise ValueError(f"{path}: Perfetto CSV is missing: {sorted(required - set(rows.fieldnames or []))}")
        result = []
        for line, row in enumerate(rows, 2):
            try:
                ts = int(row["ts"])
                duration = int(row["dur"])
                if duration < 0:
                    continue
            except (TypeError, ValueError) as exc:
                raise ValueError(f"{path}:{line}: invalid timeline timestamp/duration") from exc
            result.append({**row, "ts": ts, "dur": duration})
    if not result:
        raise ValueError(f"{path}: empty FrameTimeline, verify android.surfaceflinger.frametimeline was captured")
    return result


def percentile(values, percentile_value):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * percentile_value / 100) - 1)]


def summary(rows):
    def classify(layer):
        present = [row for row in layer if row["present_type"] != ""]
        late = sum("late" in row["present_type"].lower() for row in present)
        dropped = sum("drop" in row["present_type"].lower() for row in present)
        return {"frames": len(present), "late": late, "dropped": dropped,
                "late_pct": late * 100 / len(present) if present else None,
                "drop_pct": dropped * 100 / len(present) if present else None}

    display = [row for row in rows if row["process_name"] == "/system/bin/surfaceflinger"
               and row["surface_frame_token"] in ("", "0", None)]
    app = [row for row in rows if row["process_name"] == "app.apexview_custom"]
    # Some traces lack a process association for the TextureView layer; the
    # explicit app layer name is an independently identifiable fallback.
    if not app:
        app = [row for row in rows if "app.apexview_custom" in row["layer_name"]]

    # An SF frame can appear more than once in a trace. Use its token to
    # calculate display slots, never count app surfaces as display frames.
    unique_display = {}
    unique_display_rows = {}
    for row in display:
        token = row["display_frame_token"]
        if token not in ("", None):
            unique_display.setdefault(token, row["ts"] + row["dur"])
            unique_display_rows.setdefault(token, row)
    times = sorted(unique_display.values())
    gaps = [(b - a) / 1e6 for a, b in zip(times, times[1:]) if b >= a]
    # This histogram describes display vsync periods, NOT decoded 50fps pictures.
    bins = {"<13ms": 0, "13-19ms": 0, "19-23ms": 0, "23-30ms": 0,
            "30-45ms": 0, ">=45ms": 0}
    for ms in gaps:
        name = next((label for limit, label in
                     [(13, "<13ms"), (19, "13-19ms"), (23, "19-23ms"),
                      (30, "23-30ms"), (45, "30-45ms")] if ms < limit), ">=45ms")
        bins[name] += 1
    return {"display": classify(list(unique_display_rows.values())), "app": classify(app),
            "display_tokens": len(unique_display), "display_interval_bins": bins,
            "display_gap_median_ms": median(gaps) if gaps else None,
            "display_gap_p95_ms": percentile(gaps, 95),
            "sample_span_s": (max(row["ts"] for row in rows) - min(row["ts"] for row in rows)) / 1e9}


def show(label, metrics):
    print(f"{label}: captured {metrics['sample_span_s']:.1f}s; unique SF display tokens={metrics['display_tokens']}")
    for category in ("display", "app"):
        entry = metrics[category]
        if entry["frames"]:
            print(f"  {category}: frames={entry['frames']}, late={entry['late']} "
                  f"({entry['late_pct']:.1f}%), dropped={entry['dropped']} "
                  f"({entry['drop_pct']:.1f}%)")
        else:
            print(f"  {category}: no matching FrameTimeline rows (not measured)")
    print("  SF display slot intervals (not unique video frames):", metrics["display_interval_bins"])
    print(f"  SF slot gap median/p95 ms: {metrics['display_gap_median_ms']}, {metrics['display_gap_p95_ms']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, help="CSV exported from older build")
    parser.add_argument("--candidate", required=True, help="CSV exported from new build")
    args = parser.parse_args()
    old = summary(read_timeline(args.baseline))
    new = summary(read_timeline(args.candidate))
    show("BASELINE", old)
    show("CANDIDATE", new)
    for category in ("display", "app"):
        a, b = old[category], new[category]
        if a["late_pct"] is not None and b["late_pct"] is not None:
            print(f"  {category} late-present change: {b['late_pct'] - a['late_pct']:+.1f} percentage points")
    print("IMPORTANT: match device, source renditions, refresh Hz, warmup and interval; "
          "50fps video presentation and A/V sync are NOT proven by display timelines alone.")


if __name__ == "__main__":
    main()
