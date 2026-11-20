#!/usr/bin/env python3
"""Compare matched OFF, MAP, TOWER and BOTH windows in a Perfetto trace.

Input windows are trace-relative seconds from the first `sched` event (not wall
clock or player time). Requires `pip install perfetto` on the analysis computer.
This measures Android app FrameTimeline / CPU and named render work. A frame
deadline miss is NOT proof of a dropped decoded or physically presented picture.
"""

import argparse
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Window:
    label: str
    start: float
    end: float

    @staticmethod
    def parse(value):
        try:
            name, start, end = value.split(":")
            result = Window(name.upper(), float(start), float(end))
        except (ValueError, TypeError) as error:
            raise ValueError("window must be LABEL:START:END (trace-relative seconds)") from error
        if result.label not in {"OFF", "MAP", "TOWER", "BOTH"} or not 0 <= result.start < result.end:
            raise ValueError("window label must be OFF/MAP/TOWER/BOTH with increasing nonnegative seconds")
        return result


def scalar(processor, sql):
    result = list(processor.query(sql))
    return vars(result[0]) if result else {}


def collect(processor, window, trace_origin_ns):
    low = trace_origin_ns + int(window.start * 1e9)
    high = trace_origin_ns + int(window.end * 1e9)
    # The process name is a fixed project constant, never an untrusted CLI SQL identifier.
    process = scalar(processor, "SELECT upid FROM process WHERE name='app.apexview_custom' "
                     "ORDER BY start_ts DESC LIMIT 1")
    if not process.get("upid"):
        raise ValueError("app.apexview_custom process is missing from the trace")
    upid = int(process["upid"])
    timeline = scalar(processor, f"""
        SELECT COUNT(*) frames,
               SUM(INSTR(jank_type, 'App Deadline Missed') > 0) missed,
               SUM(present_type = 'Late Present') late,
               SUM(present_type = 'Dropped Frame') dropped,
               ROUND(AVG(dur) / 1e6, 2) mean_duration_ms
          FROM actual_frame_timeline_slice
         WHERE upid={upid} AND ts >= {low} AND ts < {high}
           AND layer_name LIKE '%app.apexview.MainActivity%'
    """)
    cpu = scalar(processor, f"""
        SELECT ROUND(COALESCE(SUM(s.dur), 0) / 1e9, 3) cpu_seconds
          FROM sched s JOIN thread t USING (utid)
          JOIN process p USING (upid)
         WHERE p.upid={upid} AND s.ts >= {low} AND s.ts < {high} AND s.dur > 0
    """)
    rt = scalar(processor, f"""
        SELECT ROUND(COALESCE(SUM(s.dur), 0) / 1e9, 3) cpu_seconds
          FROM sched s JOIN thread t USING (utid)
          JOIN process p USING (upid)
         WHERE p.upid={upid} AND t.name LIKE 'RenderThread%'
           AND s.ts >= {low} AND s.ts < {high} AND s.dur > 0
    """)
    slices = list(processor.query(f"""
        SELECT name, COUNT(*) calls, ROUND(SUM(dur) / 1e6, 2) total_ms,
               ROUND(AVG(dur) / 1e6, 3) average_ms
          FROM slice
         WHERE ts >= {low} AND ts < {high}
           AND name IN ('PitwallMapCars', 'PitwallEdgeCars', 'PitwallTowerRowDraw',
                        'PitwallTowerSampleBuild', 'PitwallGlDraw', 'PitwallEglSwap',
                        'flush commands')
         GROUP BY name
    """))
    measures = {entry.name: {"calls": entry.calls, "total_ms": entry.total_ms,
                              "mean_ms": entry.average_ms} for entry in slices}
    return {"label": window.label, "seconds": window.end - window.start,
            "frames": timeline.get("frames") or 0, "missed": timeline.get("missed") or 0,
            "late": timeline.get("late") or 0, "dropped": timeline.get("dropped") or 0,
            "mean_duration_ms": timeline.get("mean_duration_ms"),
            "cpu_seconds": cpu.get("cpu_seconds") or 0,
            "render_cpu_seconds": rt.get("cpu_seconds") or 0, "slices": measures}


def percentages(record):
    frames = record["frames"]
    return {key: 100 * record[key] / frames if frames else None
            for key in ("missed", "late", "dropped")}


def compare(records):
    off = next((row for row in records if row["label"] == "OFF"), None)
    for item in records:
        pct = percentages(item)
        frame_info = (f"appFrames={item['frames']:4} missed={pct['missed']:.1f}%"
                      if pct["missed"] is not None else "app FrameTimeline NOT_MEASURED")
        print(f"{item['label']:5} {item['seconds']:5.1f}s {frame_info}", end="")
        print(f"  renderCpu={item['render_cpu_seconds']/item['seconds']:.2f}core "
              f"totalCpu={item['cpu_seconds']/item['seconds']:.2f}core "
              f"avgAppFrame={item['mean_duration_ms']}ms")
        for key, value in sorted(item["slices"].items()):
            print(f"      {key}: {value['calls']} calls / {value['total_ms']}ms, "
                  f"mean={value['mean_ms']}ms")
        if off is not None and off is not item and pct["missed"] is not None:
            base = percentages(off)["missed"]
            if base is not None:
                print(f"      Δ app deadline misses vs OFF: {pct['missed']-base:+.1f} percentage points")
    print("LIMIT: app FrameTimeline is not individual 50fps video frame presentation. "
          "Compare decoder timestamps and SurfaceFlinger actual FrameTimeline separately.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--window", action="append", required=True,
                        metavar="LABEL:START:END", help="e.g. OFF:15:35, repeat per overlay state")
    args = parser.parse_args()
    try:
        windows = [Window.parse(value) for value in args.window]
    except ValueError as error:
        parser.error(str(error))
    if len({window.label for window in windows}) != len(windows):
        parser.error("each overlay label may appear once per comparison")
    try:
        from perfetto.trace_processor import TraceProcessor
    except ImportError as error:
        parser.error(f"Install Perfetto parser: python -m pip install perfetto ({error})")
    with TraceProcessor(trace=str(args.trace)) as processor:
        origin = scalar(processor, "SELECT MIN(ts) origin FROM sched").get("origin")
        if origin is None:
            parser.error("no sched timestamps in trace")
        compare([collect(processor, window, int(origin)) for window in windows])


if __name__ == "__main__":
    main()
