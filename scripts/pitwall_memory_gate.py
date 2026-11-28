#!/usr/bin/env python3
"""M59 paired physical memory gate. Read actual GC_PROCESS_MEMORY samples.
Never treat missing vendor counters as zero or sum overlapping PSS categories.
The manifest is operator-observed metadata, not a substitute for human review.
"""
import argparse
import hashlib
import json
import math
import re
import statistics
from pathlib import Path

MIB = 1024 * 1024
FIELDS = ("pssKiB", "platformGraphicsKiB", "media3InUseBytes",
          "media3FreePoolBytes", "nativeFragmentCapacityBytes",
          "sceneGraphicsOwners", "sceneUnverifiedRetirements",
          "media3ParkedInUseBytes", "nativeOwnedOutputAllocations",
          "nativeOwnedOutputReuses")
MATCH = ("device", "firmware", "content_id", "source_profile", "decoder_mode",
         "protection", "delivery", "feed_count", "overlay", "speed",
         "display_policy", "display_hz", "cache_state", "network_profile",
         "renderer", "software_drm", "screen_state", "codec", "thermal_start")
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^[0-9a-f]{64}$")
FIELD = re.compile(r"\b(\w+)=([^\s]+)")
REF = {"pss_peak_mib": 550, "graphics_peak_mib": 226.9,
       "media3_peak_mib": 55, "graphics_residual_mib": 20,
       "native_reuse_pct": 85}
LOWER = ("pss_p95_mib", "graphics_p95_mib", "media3_peak_mib",
         "native_capacity_peak_mib", "media3_free_peak_mib")


def require(ok, message):
    if not ok:
        raise ValueError(message)


def number(value):
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0,
            "Invalid numeric evidence")
    return value


def artifact(root, item):
    require(isinstance(item, dict) and isinstance(item.get("path"), str) and
            DIGEST.fullmatch(str(item.get("sha256", ""))), "Invalid evidence artifact")
    path = (root / item["path"]).resolve()
    require(path.is_relative_to(root.resolve()) and path.is_file() and path.stat().st_size > 0,
            "Missing or outside-bundle evidence artifact")
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    require(digest == item["sha256"], "Evidence digest mismatch")
    return path


def read_samples(path, pid):
    rows = []
    with path.open(encoding="utf-8", errors="strict") as source:
        for line in source:
            if "GC_PROCESS_MEMORY " not in line:
                continue
            fields = dict(FIELD.findall(line.split("GC_PROCESS_MEMORY ", 1)[1]))
            if fields.get("pid") != str(pid):
                continue
            try:
                row = {key: int(fields[key]) for key in ("uptimeMs", *FIELDS)}
            except (KeyError, ValueError) as error:
                raise ValueError("Mandatory memory counter unavailable") from error
            require(all(v >= 0 for v in row.values()), "Negative memory counter")
            if rows:
                require(row["uptimeMs"] > rows[-1]["uptimeMs"], "Mixed or duplicate process samples")
            rows.append(row)
    require(rows, "No memory samples for specified process")
    return rows


def p95(values):
    return sorted(values)[math.ceil(len(values) * .95) - 1]


def window(rows, begin, end):
    require(type(begin) is int and type(end) is int and 0 <= begin < end,
            "Invalid observation interval")
    part = [r for r in rows if begin <= r["uptimeMs"] <= end]
    require(len(part) >= 2 and part[0]["uptimeMs"] - begin <= 15000 and
            end - part[-1]["uptimeMs"] <= 15000 and
            all(b["uptimeMs"] - a["uptimeMs"] <= 15000 for a, b in zip(part, part[1:])),
            "Incomplete or sparse memory observation window")
    return part


def evaluate_run(run, root):
    require(SHA.fullmatch(str(run.get("commit", ""))) and
            DIGEST.fullmatch(str(run.get("apk_sha256", ""))), "Missing immutable build identity")
    require(type(run.get("pid")) is int and run["pid"] > 0, "Missing process ID")
    scenario = run["scenario"]
    require(all(k in scenario and scenario[k] is not None and scenario[k] != "" for k in MATCH),
            "Incomplete matched scenario")
    require(scenario["delivery"] in ("PHONE", "ANDROID_TV", "GOOGLE_CAST", "PITWALL_CAST") and
            scenario["decoder_mode"] in ("SINGLE", "MULTIPLE") and
            scenario["protection"] in ("CLEAR_HEVC", "WIDEVINE_HEVC") and
            scenario["overlay"] in ("OFF", "MAP", "TOWER", "BOTH") and
            scenario["speed"] in (1, 1.5) and type(scenario["feed_count"]) is int and
            scenario["feed_count"] > 0 and type(scenario["software_drm"]) is bool and
            scenario["screen_state"] in ("ON", "OFF") and
            scenario["display_policy"] in ("FIXED", "ADAPTIVE"), "Invalid scenario")
    require(type(scenario["thermal_start"]) is int and scenario["thermal_start"] in range(3) and
            type(run["thermal_end"]) is int and run["thermal_end"] in range(3) and
            abs(run["thermal_end"] - scenario["thermal_start"]) <= 1, "Thermally confounded run")
    require(number(scenario["display_hz"]) > 0, "Invalid display rate")
    require(run.get("profiled") is False, "Profiler sessions cannot establish PSS acceptance")
    log = artifact(root, run["memory_log"])
    # Separate evidence for actual output and functional checks, reviewed by owner.
    artifact(root, run["presentation_evidence"])
    require(run.get("quality_and_function_review") == "PASS", "Quality/function review not PASS")
    rows = read_samples(log, run["pid"])
    idle_begin, idle_end = run["idle_ms"]
    active_begin, active_end = run["active_ms"]
    stop = run["terminal_stop_ms"]
    require(type(stop) is int and idle_end < active_begin < active_end <= stop and
            active_end - active_begin == 600000 and idle_end - idle_begin >= 30000,
            "Expected ordered idle, fixed 10-minute active and terminal intervals")
    idle = window(rows, idle_begin, idle_end)
    active = window(rows, active_begin, active_end)
    after = window(rows, stop + 120000, stop + 150000)
    owners = ("sceneGraphicsOwners", "media3ParkedInUseBytes", "media3InUseBytes",
              "nativeFragmentCapacityBytes")
    require(all(r["sceneUnverifiedRetirements"] == idle[0]["sceneUnverifiedRetirements"]
                for r in active + after), "Unverified graphics retirement")
    require(all(r[key] == 0 for r in after for key in owners), "Terminal owner still retained")
    require(all(r["media3ParkedInUseBytes"] == 0 for r in active), "Parked media still allocated")
    def metric(key, divisor):
        values = [r[key] / divisor for r in active]
        return max(values), p95(values)
    pss, pss95 = metric("pssKiB", 1024)
    graphics, graphics95 = metric("platformGraphicsKiB", 1024)
    media3, _ = metric("media3InUseBytes", MIB)
    free, _ = metric("media3FreePoolBytes", MIB)
    native, _ = metric("nativeFragmentCapacityBytes", MIB)
    allocations = active[-1]["nativeOwnedOutputAllocations"] - active[0]["nativeOwnedOutputAllocations"]
    reuses = active[-1]["nativeOwnedOutputReuses"] - active[0]["nativeOwnedOutputReuses"]
    require(allocations >= 0 and reuses >= 0, "Native counters reset")
    return {"pss_peak_mib": pss, "pss_p95_mib": pss95,
            "graphics_peak_mib": graphics, "graphics_p95_mib": graphics95,
            "media3_peak_mib": media3, "media3_free_peak_mib": free,
            "native_capacity_peak_mib": native,
            "graphics_residual_mib": max(0, max(r["platformGraphicsKiB"] for r in after) / 1024
                                        - statistics.median(r["platformGraphicsKiB"] for r in idle) / 1024),
            "native_reuse_pct": 100 * reuses / (allocations + reuses) if allocations + reuses else None}


def reference(s):
    return (s["device"] == "SM-S908E" and s["delivery"] == "PHONE" and
            s["source_profile"] == "1080p+3x540p@50" and s["feed_count"] == 4 and
            s["protection"] == "CLEAR_HEVC" and s["decoder_mode"] == "SINGLE" and s["speed"] == 1)


def evaluate(manifest, root):
    require(manifest.get("schema_version") == 1, "Unknown memory evidence schema")
    pairs = manifest["pairs"]
    require(isinstance(pairs, list) and pairs, "No paired evidence")
    identity = tuple(manifest.get(k, "") for k in
                     ("baseline_commit", "baseline_apk_sha256", "candidate_commit", "candidate_apk_sha256"))
    require(all(isinstance(v, str) for v in identity) and SHA.fullmatch(identity[0]) and
            DIGEST.fullmatch(identity[1]) and SHA.fullmatch(identity[2]) and DIGEST.fullmatch(identity[3])
            and identity[0] != identity[2] and identity[1] != identity[3],
            "Missing distinct fixed baseline and candidate identities")
    groups = {}
    used_logs = set()
    for pair in pairs:
        a, b = pair["baseline"], pair["candidate"]
        require(a["scenario"] == b["scenario"], "Mismatched paired scenario")
        require((a["commit"], a["apk_sha256"], b["commit"], b["apk_sha256"]) == identity,
                "Run does not match fixed baseline and candidate identities")
        for run in (a, b):
            digest = run["memory_log"]["sha256"]
            require(digest not in used_logs, "Reused memory evidence")
            used_logs.add(digest)
        key = tuple(a["scenario"][k] for k in MATCH)
        group = groups.setdefault(key, [])
        group.append((a, b, evaluate_run(a, root), evaluate_run(b, root)))
    reports = []
    for group in groups.values():
        require(len(group) >= 3, "At least three independent paired repetitions required")
        require(len({(a["commit"], a["apk_sha256"], b["commit"], b["apk_sha256"])
                     for a, b, _, _ in group}) == 1, "Mixed APK identities within route")
        scenario = group[0][0]["scenario"]
        before = {k: statistics.median(x[2][k] for x in group) for k in LOWER}
        after = {k: statistics.median(x[3][k] for x in group) for k in LOWER}
        reasons = []
        for key in LOWER:
            # Fixed conservative initial tolerance; no ratcheting baseline.
            if after[key] > before[key] + max(5, before[key] * .02):
                reasons.append(key + " regressed")
        if reference(scenario):
            for _, _, _, result in group:
                for key, limit in REF.items():
                    actual = result[key]
                    if actual is None or (key == "native_reuse_pct" and actual < limit) or (
                            key != "native_reuse_pct" and (actual > limit or
                            (key in ("pss_peak_mib", "graphics_peak_mib") and actual == limit))):
                        reasons.append(key + " reference target failed")
        reports.append({"scenario": scenario, "pairs": len(group),
                        "baseline": before, "candidate": after, "issues": sorted(set(reasons))})
    require(any(reference(r["scenario"]) for r in reports), "Missing fixed S22 reference scenario")
    return {"baseline_commit": identity[0], "baseline_apk_sha256": identity[1],
            "candidate_commit": identity[2], "candidate_apk_sha256": identity[3],
            "routes": reports, "issues": sum(bool(r["issues"]) for r in reports),
            "physical_release": "OWNER_REVIEW_AND_FULL_DEVICE_MATRIX_REQUIRED"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        result = evaluate(json.loads(args.manifest.read_text()), args.manifest.parent)
    except (KeyError, TypeError, ValueError, OSError) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2, sort_keys=True))
    return int(bool(result["issues"]))


if __name__ == "__main__":
    raise SystemExit(main())
