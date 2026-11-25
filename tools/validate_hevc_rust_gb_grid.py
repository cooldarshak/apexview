#!/usr/bin/env python3
"""Independently decode the actual G-B Rust fixture stream."""
import argparse
import hashlib
import json
from pathlib import Path

from validate_hevc_cmaf_grid import hashes, run


def probe(stream):
    result = run([
        "ffprobe", "-v", "error", "-select_streams", "v",
        "-show_entries",
        "stream=codec_name,codec_tag_string,width,height,time_base:packet=pts,dts,duration,flags",
        "-of", "json", str(stream),
    ])
    return json.loads(result.stdout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    target = args.output_dir
    rust = target / "hevc_rust_gb_grid.mp4"
    jvm = target / "hevc_tizen_cmaf_grid.mp4"
    actual = probe(rust)
    if actual != probe(jvm):
        raise SystemExit("Rust packet PTS/DTS/durations/flags or track metadata differ from the JVM")
    if actual["streams"] != [{
        "codec_name": "hevc", "codec_tag_string": "hvc1",
        "width": 1920, "height": 540, "time_base": "1/90000",
    }]:
        raise SystemExit(f"Unexpected Rust video track: {actual['streams']}")
    packets = actual["packets"]
    if len(packets) != 50 or [p["pts"] for p in packets] != [i * 3600 for i in range(50)]:
        raise SystemExit("Rust grid lost frames or changed the 25 fps presentation timeline")
    a, b = (args.fixture_dir / f"feed_{name}.hevc" for name in ("a", "b"))
    expected = hashes([
        "-f", "hevc", "-i", str(a), "-f", "hevc", "-i", str(b),
        "-filter_complex", "[0:v][1:v]hstack=inputs=2[grid]",
    ], map_stream="[grid]")
    if len(expected) != 50 or expected != hashes(["-i", str(rust)]):
        raise SystemExit("Rust grid decode differs from the independently decoded input pixels")
    report = {
        "result": "RUST_GB_CLEAR_GRID_PASS",
        "feeds": 2,
        "frames": len(expected),
        "geometry": [1920, 540],
        "jvm_packet_metadata_match": True,
        "independent_pixel_match": True,
        "jvm_validated_slice_syntax_and_output_parameter_sets": True,
        "sha256": hashlib.sha256(rust.read_bytes()).hexdigest(),
    }
    encoded = json.dumps(report, indent=2) + "\n"
    (target / "hevc_rust_gb_grid_validation.json").write_text(encoded, encoding="utf-8")
    print(encoded, end="")


if __name__ == "__main__":
    main()
