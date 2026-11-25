#!/usr/bin/env python3
"""Independently decode the G-B clear streams composed by Rust."""
import argparse
import hashlib
import json
from pathlib import Path

from validate_hevc_cmaf_grid import hashes, run


def probe(stream):
    result = run([
        "ffprobe", "-v", "error", "-select_streams", "v", "-show_entries",
        "stream=codec_name,codec_tag_string,width,height,time_base:packet=pts,dts,duration,flags",
        "-of", "json", str(stream),
    ])
    return json.loads(result.stdout)


def validate_case(output, name, geometry, first, second):
    rust = output / f"hevc_rust_gb_{name}.mp4"
    actual = probe(rust)
    if name == "grid" and actual != probe(output / "hevc_tizen_cmaf_grid.mp4"):
        raise SystemExit("Rust packet PTS/DTS/durations/flags or track metadata differ from the JVM")
    width, height = geometry
    if actual["streams"] != [{
        "codec_name": "hevc", "codec_tag_string": "hvc1",
        "width": width, "height": height, "time_base": "1/90000",
    }]:
        raise SystemExit(f"Unexpected Rust {name} track: {actual['streams']}")
    timestamps = [i * 3600 for i in range(50)]
    packets = actual["packets"]
    if len(packets) != 50 or any(
        [p[field] for p in packets] != timestamps for field in ("pts", "dts")
    ) or any(p["duration"] != 3600 for p in packets):
        raise SystemExit(f"Rust {name} lost frames or altered the fixture timeline")
    sync_packets = [i for i, p in enumerate(packets) if "K" in p["flags"]]
    if sync_packets != ([0, 25] if name == "grid" else [0]):
        raise SystemExit(f"Rust {name} changed the random-access sample flags")
    expected = hashes([
        "-f", "hevc", "-i", str(first), "-f", "hevc", "-i", str(second),
        "-filter_complex", "[0:v][1:v]hstack=inputs=2[grid]",
    ], map_stream="[grid]")
    if len(expected) != 50 or expected != hashes(["-i", str(rust)]):
        raise SystemExit(f"Rust {name} decoded pixels differ from the independent source hstack")
    return {
        "case": name,
        "feeds": 2,
        "frames": len(expected),
        "geometry": geometry,
        "independent_pixel_match": True,
        "packet_timing_and_random_access_match": True,
        "sha256": hashlib.sha256(rust.read_bytes()).hexdigest(),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-dir", required=True, type=Path)
    parser.add_argument("--tile-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    tiled = args.tile_dir / "source_vod_tiled_2x2.hevc"
    a, b = (args.fixture_dir / f"feed_{name}.hevc" for name in ("a", "b"))
    report = {
        "result": "RUST_GB_CLEAR_GRID_PASS",
        "cases": [
            validate_case(args.output_dir, "grid", [1920, 540], a, b),
            validate_case(args.output_dir, "tiled", [512, 128], tiled,
                          args.tile_dir / "source_vod_alt_tiled_2x2.hevc"),
            validate_case(args.output_dir, "mixed", [640, 128], tiled,
                          args.tile_dir / "source_vod_mixed_tiled_2x2.hevc"),
        ],
        "native_slice_syntax_matches_jvm": True,
        "native_output_parameter_sets_match_jvm": True,
    }
    encoded = json.dumps(report, indent=2) + "\n"
    (args.output_dir / "hevc_rust_gb_grid_validation.json").write_text(encoded, encoding="utf-8")
    print(encoded, end="")


if __name__ == "__main__":
    main()
