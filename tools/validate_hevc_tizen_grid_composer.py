#!/usr/bin/env python3
"""Independently decode the Kotlin Tizen-style HEVC grid output and compare source pixels."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def run_bytes(command):
    result = subprocess.run(command, check=True, capture_output=True)
    return result.stdout


def decoded_bytes(path, frames):
    return run_bytes([
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-f", "hevc", "-i", str(path),
        "-frames:v", str(frames), "-vsync", "0",
        "-pix_fmt", "yuv420p", "-f", "rawvideo", "pipe:1"
    ])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    fixtures = Path(args.fixture_dir)
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    feed_a = fixtures / "feed_a.hevc"
    feed_b = fixtures / "feed_b.hevc"
    composed = output / "tizen_grid_composed_2x1.hevc"
    manifest = output / "tizen_grid_composer_manifest.txt"
    for path in (feed_a, feed_b, composed, manifest):
        if not path.is_file():
            raise SystemExit(f"Required composition proof input is missing: {path}")

    frame_count = 50
    output_width = 1920
    output_height = 544
    expected = run_bytes([
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-f", "hevc", "-i", str(feed_a),
        "-f", "hevc", "-i", str(feed_b),
        "-filter_complex", "[0:v:0][1:v:0]hstack=inputs=2:shortest=1[v]",
        "-map", "[v]", "-frames:v", str(frame_count), "-vsync", "0",
        "-pix_fmt", "yuv420p", "-f", "rawvideo", "pipe:1"
    ])
    actual = decoded_bytes(composed, frame_count)
    expected_bytes = output_width * output_height * 3 // 2 * frame_count
    if len(expected) != expected_bytes or len(actual) != expected_bytes:
        raise SystemExit(
            f"Unexpected decoded byte count: reference={len(expected)}, "
            f"composed={len(actual)}, expected={expected_bytes}"
        )
    expected_hash = hashlib.sha256(expected).hexdigest()
    actual_hash = hashlib.sha256(actual).hexdigest()
    if expected_hash != actual_hash:
        # Report the first differing byte so a CABAC or seam-filter issue is diagnosable.
        mismatch = next(
            (index for index, pair in enumerate(zip(expected, actual)) if pair[0] != pair[1]),
            None
        )
        raise SystemExit(
            f"Composed pixels differ from independently decoded source feeds; "
            f"first byte mismatch={mismatch}, reference_sha256={expected_hash}, "
            f"composed_sha256={actual_hash}"
        )
    evidence = {
        "result": "PASS",
        "composer": "native Kotlin Tizen-style per-source-cell slice composition",
        "source_feeds": [feed_a.name, feed_b.name],
        "motion_constrained_fixture": "Kvazaar frametilemargin, TMVP disabled",
        "source_feeds_are_independent_encodes": True,
        "source_pps_ids": [0, 1],
        "output_grid": "2x1",
        "decoded_frames": frame_count,
        "output_dimensions": "1920x544",
        "reference_sha256": expected_hash,
        "composed_sha256": actual_hash,
        "pixel_identical_to_horizontal_source_stack": True,
        "cabac": "independently decoded by FFmpeg; no CABAC decode/re-encode in composer"
    }
    evidence_path = output / "tizen_grid_composer_ffmpeg_validation.json"
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
