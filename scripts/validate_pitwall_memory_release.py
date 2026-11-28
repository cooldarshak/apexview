#!/usr/bin/env python3
"""Replay the owner-reviewed physical memory bundle before official signing.

The closed labelled issue pins the baseline run and repository asset digest.
The workflow independently downloads both successful debug APK artifacts.
This is an additional gate, never a replacement for the existing 16 gates.
"""
import argparse
import hashlib
import json
import re
import stat
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

from pitwall_memory_gate import evaluate, require, SHA, DIGEST

MAX_BUNDLE_BYTES = 512 * 1024 * 1024
MAX_FILES = 4096


def metadata(body):
    def field(label, pattern):
        rows = re.findall(r"^" + re.escape(label) + r":\s*([^\r\n]+)$", body, re.M)
        require(len(rows) == 1 and re.fullmatch(pattern, rows[0].strip()),
                "Missing or invalid " + label)
        return rows[0].strip()
    return {
        "baseline_run": field("Memory baseline debug run", r"[1-9][0-9]*"),
        "baseline_commit": field("Memory baseline commit", r"[0-9a-f]{40}"),
        "asset_id": field("Memory evidence asset ID", r"[1-9][0-9]*"),
        "bundle_sha256": field("Memory evidence SHA256", r"[0-9a-f]{64}"),
    }


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def apk_digest(folder):
    apks = list(folder.rglob("*.apk"))
    require(len(apks) == 1 and apks[0].is_file(), "Expected one exact debug APK")
    return digest(apks[0])


def extract_bundle(bundle, root):
    require(0 < bundle.stat().st_size <= MAX_BUNDLE_BYTES, "Invalid compressed evidence size")
    with zipfile.ZipFile(bundle) as archive:
        infos = archive.infolist()
        require(0 < len(infos) <= MAX_FILES and
                sum(i.file_size for i in infos) <= MAX_BUNDLE_BYTES,
                "Evidence bundle exceeds bounded extraction budget")
        names = set()
        for item in infos:
            path = PurePosixPath(item.filename)
            mode = item.external_attr >> 16
            require(not path.is_absolute() and ".." not in path.parts and
                    "\\" not in item.filename and path.parts and
                    not stat.S_ISLNK(mode) and not item.flag_bits & 1,
                    "Unsafe evidence archive member")
            name = str(path)
            require(name not in names, "Duplicate evidence member")
            names.add(name)
            dest = root.joinpath(*path.parts)
            if item.is_dir():
                dest.mkdir(parents=True, exist_ok=True)
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(item) as source, dest.open("xb") as target:
                    while chunk := source.read(1024 * 1024):
                        target.write(chunk)


def require_matrix(report):
    routes = [r["scenario"] for r in report["routes"]]
    for delivery in ("PHONE", "ANDROID_TV"):
        for mode in ("SINGLE", "MULTIPLE"):
            for protection in ("CLEAR_HEVC", "WIDEVINE_HEVC"):
                require(any(r["delivery"] == delivery and r["decoder_mode"] == mode
                            and r["protection"] == protection for r in routes),
                        f"Missing paired route {delivery}/{mode}/{protection}")
    for delivery in ("GOOGLE_CAST", "PITWALL_CAST"):
        for screen in ("ON", "OFF"):
            for multiple in (False, True):
                require(any(r["delivery"] == delivery and r["screen_state"] == screen
                            and (r["feed_count"] > 1) == multiple and r["overlay"] == "BOTH"
                            and r["protection"] == "CLEAR_HEVC" and r["speed"] == 1
                            for r in routes), f"Missing paired clear overlay Cast route {delivery}/{screen}/multi={multiple}")
    # Protected pixels never need to enter an unprotected baked encoder.
    require(any(r["delivery"] == "PITWALL_CAST" and r["protection"] == "WIDEVINE_HEVC"
                and r["overlay"] == "BOTH" for r in routes), "Missing protected native Pitwall Cast evidence")
    for delivery in ("PHONE", "ANDROID_TV"):
        for multiple in (False, True):
            for overlay in ("OFF", "BOTH"):
                require(any(r["delivery"] == delivery and (r["feed_count"] > 1) == multiple
                            and r["overlay"] == overlay and r["speed"] == 1 for r in routes),
                        f"Missing paired local route {delivery}/{overlay}/multi={multiple}")
    for speed in (1, 1.5):
        for overlay in ("OFF", "MAP", "TOWER", "BOTH"):
            require(any(r["delivery"] == "PHONE" and r["device"] == "SM-S908E" and
                        r["speed"] == speed and r["overlay"] == overlay for r in routes),
                    f"Missing S22 paired speed/overlay {speed}/{overlay}")


def verify(meta, bundle, baseline_dir, candidate_dir, candidate_commit):
    require(SHA.fullmatch(candidate_commit) and SHA.fullmatch(meta["baseline_commit"]) and
            DIGEST.fullmatch(meta["bundle_sha256"]), "Invalid pinned evidence identity")
    require(digest(bundle) == meta["bundle_sha256"], "Evidence archive digest mismatch")
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        extract_bundle(bundle, root)
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        require(manifest["baseline_commit"] == meta["baseline_commit"] and
                manifest["candidate_commit"] == candidate_commit, "Wrong measured source commit")
        require(manifest["baseline_apk_sha256"] == apk_digest(baseline_dir) and
                manifest["candidate_apk_sha256"] == apk_digest(candidate_dir),
                "Measured APK does not match workflow artifact")
        report = evaluate(manifest, root)
        require(report["issues"] == 0, "Paired physical memory regression or target failure")
        require_matrix(report)
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("metadata")
    check = sub.add_parser("verify")
    for name in ("metadata", "bundle", "baseline-dir", "candidate-dir"):
        check.add_argument("--" + name, type=Path, required=True)
    check.add_argument("--candidate-commit", required=True)
    args = parser.parse_args()
    try:
        if args.command == "metadata":
            result = metadata(json.load(sys.stdin).get("body") or "")
        else:
            result = verify(json.loads(args.metadata.read_text()), args.bundle,
                            args.baseline_dir, args.candidate_dir, args.candidate_commit)
    except (ValueError, TypeError, KeyError, OSError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
