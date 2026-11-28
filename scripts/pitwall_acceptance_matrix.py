#!/usr/bin/env python3
"""Fail-closed real-device S22/TCL 10-minute Pitwall acceptance matrix.

A scenario is a physical, authorized app session, NOT a synthetic CI capture.
Manifest fields are operator-observed metadata; actual app metrics are read from
Pitwall's exported log, and FrameTimeline claims require a real Perfetto CSV.
Clear TextureView video is part of HWUI; it has no independently isolatable
video layer in this exporter. A successful tool run is not a release sign-off.
"""
import argparse
import json
from dataclasses import dataclass, MISSING
from pathlib import Path

from analyze_pitwall_runtime import app_session, summarize as app_summary
from analyze_pitwall_frames import read_timeline, summary as timeline_summary
from pitwall_memory_gate import evaluate as evaluate_memory

OVERLAYS = frozenset({"OFF", "MAP", "TOWER", "BOTH"})
SPEEDS = frozenset({1.0, 1.5})
MIN_ENDURANCE_S = 600
MIN_PERFETTO_S = 45


@dataclass(frozen=True)
class Case:
    label: str
    overlay: str
    speed: float
    device: str
    build: str
    content_id: str
    source_profile: str
    decoder_mode: str
    protection: str
    delivery: str
    feed_count: int
    display_hz: float
    thermal_start: int
    thermal_end: int
    app_log: Path
    timeline_csv: Path
    display_policy: str = "FIXED"  # FIXED benchmarks, ADAPTIVE compatibility

    @staticmethod
    def parse(data, root):
        required = {f.name for f in Case.__dataclass_fields__.values() if f.default is MISSING}
        missing = required - set(data)
        if missing:
            raise ValueError("Missing physical-run fields: " + ", ".join(sorted(missing)))
        # Never trust a manifest to grant physical sign-off: inspect the files.
        fields = dict(data)
        for path in ("app_log", "timeline_csv"):
            fields[path] = root / str(fields[path])
        fields["speed"] = float(fields["speed"])
        fields["display_hz"] = float(fields["display_hz"])
        fields["feed_count"] = int(fields["feed_count"])
        fields["thermal_start"] = int(fields["thermal_start"])
        fields["thermal_end"] = int(fields["thermal_end"])
        fields["overlay"] = str(fields["overlay"]).upper()
        case = Case(**fields)
        if (case.overlay not in OVERLAYS or case.speed not in SPEEDS or
            case.feed_count < 1 or case.display_hz <= 0 or
            case.display_policy not in {"FIXED", "ADAPTIVE"} or
            not case.device.strip() or not case.build.strip() or
            not case.content_id.strip() or not case.source_profile.strip() or
            case.decoder_mode not in {"SINGLE", "MULTIPLE"} or
            case.protection not in {"CLEAR_HEVC", "WIDEVINE_HEVC"} or
            case.delivery not in {"PHONE", "ANDROID_TV", "PITWALL_CAST", "GOOGLE_CAST"} or
            case.thermal_start not in range(0, 7) or case.thermal_end not in range(0, 7)):
            raise ValueError("Invalid playback/decoder/DRM/Cast/thermal scenario metadata")
        return case

    @property
    def comparable(self):
        return (self.device, self.build, self.content_id, self.source_profile,
                self.decoder_mode, self.protection, self.delivery,
                self.feed_count, self.display_hz, self.display_policy)


def validate_groups(cases):
    if not cases:
        raise ValueError("No physical playback runs supplied")
    groups = {}
    unique = set()
    for case in cases:
        key = (case.comparable, case.speed, case.overlay)
        if key in unique:
            raise ValueError("Duplicate device/speed/overlay session; choose one unambiguous capture")
        unique.add(key)
        groups.setdefault(case.comparable, []).append(case)
    # A matrix cannot be assembled by mixing different devices, quality
    # profiles, number of feeds or build versions within its overlay A/B.
    for comparable, group in groups.items():
        scenarios = {(case.speed, case.overlay) for case in group}
        missing = sorted((speed, label) for speed in SPEEDS
                         for label in OVERLAYS if (speed, label) not in scenarios)
        if missing:
            raise ValueError(f"Incomplete matching 1x/1.5x overlay matrix {comparable}: {missing}")
        # Recorded thermal status need not be equal, but large drift means
        # any computed A/B performance delta is confounded.
        if max(c.thermal_end for c in group) - min(c.thermal_start for c in group) > 2:
            raise ValueError("Thermal excursion across A/B samples is too large to attribute overlay effects")
    return groups


def refresh_issues(app, expected_hz, display_policy="FIXED"):
    observed = app["observed_refresh_hz"]
    if not observed or app["refresh_unknown_windows"]:
        return ["display refresh observation incomplete"]
    if display_policy == "ADAPTIVE":
        # A 120Hz-capable phone may legitimately choose 60, 90, 96 or 120.
        # Its maximum is a ceiling, NOT a claim every playback frame ran at 120.
        # Still fail unknown and implausible observations instead of hiding them.
        if any(hz <= 0 or hz > expected_hz + 1.0 for hz in observed):
            return [f"adaptive display rate outside declared ceiling {expected_hz}: {observed}"]
        return []
    if display_policy != "FIXED":
        return ["unknown display refresh policy"]
    if app["refresh_mixed_windows"] or any(abs(hz - expected_hz) > 1.0 for hz in observed):
        return [f"display refresh mismatch or transition: expected {expected_hz}, observed {observed}"]
    return []


def evaluate(case):
    if not case.app_log.is_file() or not case.timeline_csv.is_file():
        raise ValueError(f"{case.label}: required app log or Perfetto CSV is missing")
    app = app_summary(app_session(case.app_log.read_text(encoding="utf-8", errors="replace"), case.build))
    timeline = timeline_summary(read_timeline(case.timeline_csv))
    reasons = refresh_issues(app, case.display_hz, case.display_policy)
    if app["covered_seconds"] < MIN_ENDURANCE_S:
        reasons.append(f"app-window observation {app['covered_seconds']}s < {MIN_ENDURANCE_S}s")
    if timeline["sample_span_s"] < MIN_PERFETTO_S:
        reasons.append(f"Perfetto capture {timeline['sample_span_s']:.1f}s < {MIN_PERFETTO_S}s")
    if app["slow_pct"] is None:
        reasons.append("app slow-frame percentage NOT_MEASURED")
    elif app["slow_pct"] >= 5.0:
        reasons.append(f"app slow UI frames {app['slow_pct']}% >= 5% target")
    if app["frozen"] != 0:
        reasons.append(f"{app['frozen']} frozen UI frames")
    if not timeline["app"]["frames"]:
        reasons.append("app-window FrameTimeline NOT_MEASURED")
    if case.decoder_mode == "SINGLE" and (not app["active_decoders"] or any(str(v) != "1" for v in app["active_decoders"])):
        reasons.append("single-decoder invariant not independently established")
    if max(case.thermal_start, case.thermal_end) >= 3:
        reasons.append("severe thermal status during endurance run")
    # SurfaceView has a distinct video layer; TextureView does not. A layer's
    # existence alone STILL cannot establish source-PTS-to-scanout identity.
    video_status = ("SURFACEVIEW_LAYER_PRESENT_SOURCE_CORRELATION_PENDING"
                    if timeline["video"]["frames"] else "NO_INDEPENDENT_VIDEO_LAYER")
    return {"case": case.label, "speed": case.speed, "overlay": case.overlay,
            "display_policy": case.display_policy,
            "observed_refresh_hz": app["observed_refresh_hz"],
            "refresh_transition_windows": app["refresh_mixed_windows"],
            "app_frames": app["frames"], "app_slow_pct": app["slow_pct"],
            "frozen": app["frozen"], "queue_timeouts": app["queue_timeouts"],
            "queue_per_second": app["queue_per_second"],
            "archive_request_p95_ms": app["archive_request_p95_ms"],
            "archive_alloc_median_bytes": app["archive_alloc_median_bytes"],
            "archive_gc_median": app["archive_gc_median"],
            "thermal_reported": app["reported_thermal_statuses"],
            "frame_timeline_app_late_pct": timeline["app"]["late_pct"],
            "frame_timeline_video_late_pct": timeline["video"]["late_pct"],
            "video_layer_evidence": video_status, "issues": reasons,
            "physical_release": "OPEN_NEEDS_VIDEO_SOURCE_CORRELATION_AND_DEVICE_MATRIX"}


def run(manifest):
    data = json.loads(manifest.read_text(encoding="utf-8"))
    memory = evaluate_memory(data["memory_evidence"], manifest.parent)
    cases = [Case.parse(obj, manifest.parent) for obj in data["runs"]]
    validate_groups(cases)
    records = [evaluate(case) for case in cases]
    # This report never signs a physical gate. Its job is to reject hidden
    # missing evidence and expose regressions for engineering triage.
    return {"scenarios": records, "memory": memory,
            "issues": sum(bool(r["issues"]) for r in records) + memory["issues"],
            "release_gate": "OPEN_PHYSICAL_EVIDENCE_REQUIRED"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="JSON file with 8 controlled captures per device/config")
    parser.add_argument("--json", action="store_true", help="Machine-readable report without secrets or URLs")
    args = parser.parse_args()
    try:
        report = run(args.manifest)
    except (KeyError, ValueError, OSError, TypeError) as error:
        parser.error(str(error))
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        for record in report["scenarios"]:
            print(f"{record['case']}: {record['speed']}x {record['overlay']} "
                  f"appSlow={record['app_slow_pct']}% frozen={record['frozen']} "
                  f"queue={record['queue_timeouts']} video={record['video_layer_evidence']} "
                  f"issues={record['issues'] or 'none measured'}")
        print("PHYSICAL_RELEASE_GATE=OPEN. Do not infer decoded video FPS from app-window FrameTimeline.")
    return 1 if report["issues"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
