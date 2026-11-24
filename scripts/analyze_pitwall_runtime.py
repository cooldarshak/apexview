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


def nearest_rank_p95(values):
    """Exact nearest-rank p95 from observed samples; never interpolate or hide misses."""
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) * 95 + 99) // 100
    return ordered[rank - 1]



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
    protected_segments = []
    archive_requests = []
    archived_cached_bytes = []
    protected_retained_bytes = []
    ui_input_p95_upper = []
    ui_animation_p95_upper = []
    thermal_statuses = set()
    refresh_observed = set()
    refresh_unknown_windows = 0
    refresh_mixed_windows = 0
    protected_active_feeds = set()
    for line in text.splitlines():
        f = fields(line)
        if "PitwallFrames:" in line and f.get("role") == "player":
            frames += as_int(f.get("frames"))
            slow += as_int(f.get("slow"))
            frozen += as_int(f.get("frozen700ms"))
            total_ms += as_int(f.get("intervalMs"))
            try:
                low = float(f["refreshMinHz"])
                high = float(f["refreshMaxHz"])
                if not (0 < low <= high < 1000):
                    raise ValueError("invalid refresh range")
                refresh_observed.update((round(low, 2), round(high, 2)))
                if high - low > 1.0:
                    refresh_mixed_windows += 1
            except (KeyError, ValueError):
                refresh_unknown_windows += 1
            if "inputP95UpperMs" in f:
                ui_input_p95_upper.append(as_int(f["inputP95UpperMs"]))
            if "animationP95UpperMs" in f:
                ui_animation_p95_upper.append(as_int(f["animationP95UpperMs"]))
            if "thermalStatus" in f:
                thermal_statuses.add(as_int(f["thermalStatus"]))
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
        if "ARCHIVE_VOD_REQUEST " in line:
            if f.get("cached") in ("true", "false") and "waitMs" in f:
                archive_requests.append((f["cached"] == "true", as_int(f["waitMs"])))
        if "ARCHIVE_VOD_SEGMENT " in line:
            if "cachedBytes" in f:
                archived_cached_bytes.append(as_int(f["cachedBytes"]))
            row = {key: as_int(f.get(key)) for key in (
                "number", "processAllocBytesDuringSegment", "processGcDuringSegment",
                "parseMs", "rewriteMs", "packageMs", "composeMs", "outputBytes")}
            # New stage metrics were introduced after the baseline captures.
            # Absence is NOT a measured 0 ms; never fabricate a cost reduction.
            for key in ("cmafInspectorMs", "nalInspectorMs", "parseCpuMs",
                        "rewriteCpuMs", "packageCpuMs", "producerCpuMs"):
                if key in f:
                    row[key] = as_int(f[key])
            segments.append(row)
        if "PROTECTED_CMAF_SEGMENT " in line:
            if "retainedBytes" in f:
                protected_retained_bytes.append(as_int(f["retainedBytes"]))
            row = {key: as_int(f.get(key)) for key in (
                "feeds", "sampleCount", "processAllocBytesDuringSegment",
                "processGcDuringSegment", "composeMs", "outputBytes")}
            if "composerThreadCpuMs" in f:
                row["composerThreadCpuMs"] = as_int(f["composerThreadCpuMs"])
            protected_segments.append(row)
        if "ARCHIVE_VOD_FAILED" in line or "event=load-error" in line:
            failures += 1
        if "F1_COMPOSITION_PLAYBACK" in line and "stage=ACTIVE" in line:
            active_decoders.add(f.get("activeVideoDecoders", "unknown"))
            if "feeds" in f:
                protected_active_feeds.add(as_int(f["feeds"]))
    # First three segments include player warmup, HTTP cache fill and codec startup.
    # Report them separately so they cannot distort steady-state allocation claims.
    steady = segments[3:]
    protected_steady = protected_segments[3:]
    def observed_median(records, metric):
        measured = [record[metric] for record in records if metric in record]
        return median(measured) if measured else None
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
        "protected_active_feeds": sorted(protected_active_feeds),
        "input_p95_bucket_max_ms": max(ui_input_p95_upper) if ui_input_p95_upper else None,
        "animation_p95_bucket_max_ms": max(ui_animation_p95_upper) if ui_animation_p95_upper else None,
        "reported_thermal_statuses": sorted(thermal_statuses),
        "observed_refresh_hz": sorted(refresh_observed),
        "refresh_unknown_windows": refresh_unknown_windows,
        "refresh_mixed_windows": refresh_mixed_windows,
        "archive_requests_total": len(archive_requests),
        "archive_cache_hits": sum(hit for hit, _ in archive_requests),
        "archive_cache_hit_pct": (
            round(100 * sum(hit for hit, _ in archive_requests) / len(archive_requests), 1)
            if archive_requests else None
        ),
        "archive_request_p95_ms": nearest_rank_p95([duration for _, duration in archive_requests]),
        "archive_cache_hit_p95_ms": nearest_rank_p95(
            [duration for hit, duration in archive_requests if hit]
        ),
        "archive_cache_miss_p95_ms": nearest_rank_p95(
            [duration for hit, duration in archive_requests if not hit]
        ),
        "archive_cache_peak_bytes": max(archived_cached_bytes, default=None),
        "protected_history_peak_bytes": max(protected_retained_bytes, default=None),
        "archive_segments_total": len(segments),
        "archive_segments_steady": len(steady),
        "archive_alloc_median_bytes": int(median(s["processAllocBytesDuringSegment"] for s in steady)) if steady else None,
        "archive_gc_median": median(s["processGcDuringSegment"] for s in steady) if steady else None,
        "archive_compose_median_ms": median(s["composeMs"] for s in steady) if steady else None,
        "archive_rewrite_median_ms": median(s["rewriteMs"] for s in steady) if steady else None,
        "archive_cmaf_inspector_median_ms": observed_median(steady, "cmafInspectorMs"),
        "archive_nal_inspector_median_ms": observed_median(steady, "nalInspectorMs"),
        "archive_rewrite_cpu_median_ms": observed_median(steady, "rewriteCpuMs"),
        "archive_output_median_bytes": int(median(s["outputBytes"] for s in steady)) if steady else None,
        "protected_segments_total": len(protected_segments),
        "protected_segments_steady": len(protected_steady),
        "protected_alloc_median_bytes": int(median(s["processAllocBytesDuringSegment"] for s in protected_steady)) if protected_steady else None,
        "protected_gc_median": median(s["processGcDuringSegment"] for s in protected_steady) if protected_steady else None,
        "protected_compose_median_ms": median(s["composeMs"] for s in protected_steady) if protected_steady else None,
        "protected_composer_cpu_median_ms": observed_median(protected_steady, "composerThreadCpuMs"),
        "protected_output_median_bytes": int(median(s["outputBytes"] for s in protected_steady)) if protected_steady else None,
    }


def check_thresholds(report, ceilings, minimum_covered_seconds=None):
    """Return failures: absent real-device evidence is never a passing gate."""
    issues = []
    for metric, maximum in ceilings.items():
        if maximum is None:
            continue
        if maximum < 0:
            raise ValueError("Negative budget: " + metric)
        observed = report.get(metric)
        if observed is None:
            issues.append(f"{metric}: no observed evidence")
        elif observed > maximum:
            issues.append(f"{metric}: {observed} exceeds budget {maximum}")
    if minimum_covered_seconds is not None:
        if minimum_covered_seconds <= 0:
            raise ValueError("Minimum capture seconds must be positive")
        observed = report.get("covered_seconds", 0)
        if observed < minimum_covered_seconds:
            issues.append(f"covered_seconds: {observed} below {minimum_covered_seconds}")
    return issues


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="Pitwall exported diagnostic text file")
    parser.add_argument("--build", help="For appended logs, use only this version, e.g. 1.0.573")
    parser.add_argument("--max-archive-p95-ms", type=float,
                        help="Fail when real foreground archive request p95 exceeds ms")
    parser.add_argument("--max-archive-cache-mib", type=float,
                        help="Fail when retained clear cache peak exceeds MiB")
    parser.add_argument("--max-protected-history-mib", type=float,
                        help="Fail when composed protected history peak exceeds MiB")
    parser.add_argument("--max-ui-input-p95-ms", type=float,
                        help="Fail when UI input p95 bucket upper bound exceeds ms")
    parser.add_argument("--max-ui-animation-p95-ms", type=float,
                        help="Fail when UI animation p95 bucket upper bound exceeds ms")
    parser.add_argument("--min-covered-seconds", type=float,
                        help="Require this many seconds of real observed performance frames")
    args = parser.parse_args()
    report = summarize(app_session(args.log.read_text(encoding="utf-8", errors="replace"), args.build))
    for label, value in report.items():
        print(f"{label}: {value}")
    print("CAUTION: rotating captures can overlap and repeat the same timestamped lines; "
          "do not sum summaries from multiple files or confuse window p95 bucket maxima "
          "with input-to-photon latency. Protected segment sample windows include warmup. "
          "These are app-side observations, not SurfaceFlinger physical video presentation.")
    print("CAUTION: the summary includes all modes in the selected session. "
          "Use steady-state intervals for valid scene-to-scene comparisons; "
          "only Perfetto verifies physical display frames.")


    ceilings = {
        "archive_request_p95_ms": args.max_archive_p95_ms,
        "archive_cache_peak_bytes": (
            args.max_archive_cache_mib * 1024 * 1024 if args.max_archive_cache_mib is not None else None
        ),
        "protected_history_peak_bytes": (
            args.max_protected_history_mib * 1024 * 1024
            if args.max_protected_history_mib is not None else None
        ),
        "input_p95_bucket_max_ms": args.max_ui_input_p95_ms,
        "animation_p95_bucket_max_ms": args.max_ui_animation_p95_ms,
    }
    failures = check_thresholds(report, ceilings, args.min_covered_seconds)
    if failures:
        import sys
        for failure in failures:
            print("NOT ACCEPTED: " + failure, file=sys.stderr)
        raise SystemExit(2)
    if any(limit is not None for limit in ceilings.values()) or args.min_covered_seconds is not None:
        print("LOG METRICS PASS. Physical decoder, DRM and SurfaceFlinger gates remain separate.")


if __name__ == "__main__":
    main()
