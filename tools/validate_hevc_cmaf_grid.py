#!/usr/bin/env python3
"""Independent FFmpeg validation for the Kotlin Tizen-style clear CMAF grid composer."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def run(args):
    return subprocess.run(args, check=True, text=True, capture_output=True)


def hashes(args, map_stream="0:v:0"):
    result = run(["ffmpeg", "-hide_banner", "-loglevel", "error", *args,
                  "-map", map_stream, "-f", "framemd5", "-"])
    return [line.split(",")[-1].strip() for line in result.stdout.splitlines()
            if line and not line.startswith("#")]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    fixtures, output = Path(args.fixture_dir), Path(args.output_dir)
    cmaf = output / "hevc_tizen_cmaf_grid.mp4"
    a, b = fixtures / "feed_a.hevc", fixtures / "feed_b.hevc"
    if not cmaf.is_file() or not a.is_file() or not b.is_file():
        raise SystemExit("Missing synthetic feed or Kotlin CMAF output")

    probe = run(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                 "-show_entries", "stream=codec_name,codec_tag_string,width,height,nb_read_frames",
                 "-of", "json", str(cmaf)])
    streams = json.loads(probe.stdout).get("streams", [])
    if len(streams) != 1:
        raise SystemExit(f"Expected one video stream, got {len(streams)}")
    stream = streams[0]
    if (stream.get("codec_name"), stream.get("codec_tag_string"),
        stream.get("width"), stream.get("height"), int(stream.get("nb_read_frames") or 0)) != (
            "hevc", "hvc1", 1920, 540, 50):
        raise SystemExit(f"Unexpected composed CMAF stream metadata: {stream}")

    expected = hashes(["-f", "hevc", "-i", str(a), "-f", "hevc", "-i", str(b),
                       "-filter_complex", "[0:v][1:v]hstack=inputs=2[grid]"],
                      map_stream="[grid]")
    actual = hashes(["-i", str(cmaf)])
    if len(expected) != 50 or actual != expected:
        mismatch = next((i for i, pair in enumerate(zip(expected, actual))
                         if pair[0] != pair[1]), None)
        raise SystemExit(f"Composed CMAF frame hashes differ from hstack reference at frame {mismatch}; "
                         f"expected={len(expected)} actual={len(actual)}")

    result = {
        "result": "TIZEN_STYLE_CLEAR_CMAF_GRID_PASS",
        "codec": stream["codec_name"],
        "sample_entry": stream["codec_tag_string"],
        "width": stream["width"],
        "height": stream["height"],
        "frames": 50,
        "single_video_track": True,
        "frame_hashes_match_hstack_reference": True,
        "encrypted": False,
        "sha256": hashlib.sha256(cmaf.read_bytes()).hexdigest(),
        "file_size_bytes": cmaf.stat().st_size,
    }
    (output / "hevc_tizen_cmaf_grid_validation.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
