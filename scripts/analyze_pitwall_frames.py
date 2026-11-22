#!/usr/bin/env python3
"""Distinguish Android app UI, SurfaceView video and SF display FrameTimeline.

A late MainActivity window frame does not establish independent video jank.
Video layer frames do not establish which F1 camera pixels were displayed;
correlate actual layer cadence with decoder/latch and audio clocks.
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
    # The independent SurfaceView has its own queue. TextureView clear playback
    # is part of HWUI and must not be fabricated into a separate video layer.
    def is_video(row):
        name = row["layer_name"].lower()
        identified = "surfaceview" in name or "pitwallsecurescene" in name
        owned = (row["process_name"] == "app.apexview_custom" or
                 "app.apexview_custom" in name or "pitwall" in name)
        return identified and owned and row["surface_frame_token"] not in ("", "0", None)

    video = {}
    for row in rows:
        if is_video(row):
            video.setdefault((row["layer_name"], row["surface_frame_token"]), row)
    video_rows = list(video.values())
    app = [row for row in rows if row["process_name"] == "app.apexview_custom"
           and not is_video(row)]
    if not app:
        app = [row for row in rows if "app.apexview_custom" in row["layer_name"]
               and not is_video(row)]

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
    video_times = sorted(row["ts"] + row["dur"] for row in video_rows)
    video_gaps = [(b - a) / 1e6 for a, b in zip(video_times, video_times[1:]) if b >= a]
    return {"display": classify(list(unique_display_rows.values())), "app": classify(app),
            "video": classify(video_rows),
            "video_layer_names": sorted({row["layer_name"] for row in video_rows}),
            "video_gap_p95_ms": percentile(video_gaps, 95),
            "video_gap_max_ms": max(video_gaps) if video_gaps else None,
            "display_tokens": len(unique_display), "display_interval_bins": bins,
            "display_gap_median_ms": median(gaps) if gaps else None,
            "display_gap_p95_ms": percentile(gaps, 95),
            "sample_span_s": (max(row["ts"] for row in rows) - min(row["ts"] for row in rows)) / 1e9}


def show(label, metrics):
    print(f"{label}: captured {metrics['sample_span_s']:.1f}s; unique SF display tokens={metrics['display_tokens']}")
    for category in ("display", "app", "video"):
        entry = metrics[category]
        if entry["frames"]:
            print(f"  {category}: frames={entry['frames']}, late={entry['late']} "
                  f"({entry['late_pct']:.1f}%), dropped={entry['dropped']} "
                  f"({entry['drop_pct']:.1f}%)")
        else:
            print(f"  {category}: no matching FrameTimeline rows (not measured)")
    if metrics["video_layer_names"]:
        print("  independent video SurfaceView layers:", ", ".join(metrics["video_layer_names"]))
        print("  video layer frame gap p95/max ms:",
              metrics["video_gap_p95_ms"], metrics["video_gap_max_ms"])
    else:
        print("  video SurfaceView NOT_MEASURED; clear TextureView is in HWUI")
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
    for category in ("display", "app", "video"):
        a, b = old[category], new[category]
        if a["late_pct"] is not None and b["late_pct"] is not None:
            print(f"  {category} late-present change: {b['late_pct'] - a['late_pct']:+.1f} percentage points")
    print("IMPORTANT: match device, renditions, refresh and steady intervals. "
          "SurfaceView and app HWUI are distinct, while TextureView belongs to HWUI. "
          "Camera identity and A/V sync still need decoder/latch/audio evidence.")


if __name__ == "__main__":
    main()
