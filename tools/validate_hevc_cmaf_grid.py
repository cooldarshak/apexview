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
    parser.add_argument("--tile-dir", required=True)
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

    native = output / "native_hls_archive_grid.mp4"
    if not native.is_file():
        raise SystemExit("Missing native HLS archive capture/composition evidence")
    native_hashes = hashes(["-i", str(native)])
    if native_hashes != expected:
        raise SystemExit("Native HLS archive output differs from independently decoded source hstack")
    native_manifest = (output / "native_hls_archive_grid.mpd").read_text()
    if "ContentProtection" in native_manifest or native_manifest.count("<Representation ") != 1:
        raise SystemExit("Native clear HLS output must contain one representation and no invented DRM")
    native_result = {"result": "NATIVE_HLS_ARCHIVE_CMAF_PASS", "frames": len(native_hashes),
                     "frame_hashes_match_hstack_reference": True,
                     "sha256": hashlib.sha256(native.read_bytes()).hexdigest()}
    (output / "native_hls_archive_grid_validation.json").write_text(json.dumps(native_result, indent=2) + "\n")
    print(json.dumps(native_result, indent=2))


    for count in (2, 3, 4):
        vod = output / f"native_vod_{count}_feeds.mp4"
        if not vod.is_file():
            raise SystemExit(f"Missing finite native VOD evidence for {count} feeds")
        input_args = []
        for i in range(count):
            input_args += ["-f", "hevc", "-i", str(a if i % 2 == 0 else b)]
        layout = "".join(f"[{i}:v]" for i in range(count)) + f"hstack=inputs={count}[grid]"
        wanted = hashes(input_args + ["-filter_complex", layout], map_stream="[grid]")
        decoded = hashes(["-i", str(vod)])
        if len(decoded) != 50 or decoded != wanted:
            raise SystemExit(f"Finite {count}-feed VOD pixels differ from full source pictures")
        mpd = (output / f"native_vod_{count}_feeds.mpd").read_text()
        if 'type="static"' not in mpd or mpd.count("<Representation ") != 1 or "ContentProtection" in mpd:
            raise SystemExit("Finite clear VOD must expose exactly one protected-data-free video representation")
        result = {"result": "FINITE_NATIVE_VOD_PASS", "feeds": count, "frames": len(decoded),
                  "full_feed_pixels_match_hstack": True, "one_video_track": True,
                  "sha256": hashlib.sha256(vod.read_bytes()).hexdigest()}
        (output / f"native_vod_{count}_validation.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))


    tiled_source = Path(args.tile_dir) / "source_vod_tiled_2x2.hevc"
    tiled_output = output / "native_vod_full_tiled_feeds.mp4"
    wanted = hashes(["-f", "hevc", "-i", str(tiled_source), "-f", "hevc", "-i", str(tiled_source),
                     "-filter_complex", "[0:v][1:v]hstack=inputs=2[grid]"], map_stream="[grid]")
    decoded = hashes(["-i", str(tiled_output)])
    if len(decoded) != 50 or decoded != wanted:
        raise SystemExit("Full tiled feeds differ from source pictures; selecting only source tile 0 is insufficient")
    result = {"result": "FULL_TILED_NATIVE_VOD_PASS", "feeds": 2, "source_tiles_per_feed": 4,
              "frames": len(decoded), "full_feed_pixels_match_hstack": True}
    (output / "native_vod_full_tiled_validation.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))

    five_output = output / "native_vod_five_tiled_feeds.mp4"
    inputs = []
    for _ in range(5):
        inputs += ["-f", "hevc", "-i", str(tiled_source)]
    wanted = hashes(inputs + ["-filter_complex", "[0:v][1:v][2:v][3:v][4:v]hstack=inputs=5[grid]"], map_stream="[grid]")
    decoded = hashes(["-i", str(five_output)])
    if len(decoded) != 50 or decoded != wanted:
        raise SystemExit("Five-feed output differs from independently decoded full tiled sources")
    result = {"result": "FIVE_TILED_NATIVE_VOD_PASS", "feeds": 5, "frames": len(decoded),
              "full_feed_pixels_match_hstack": True}
    (output / "native_vod_five_tiled_validation.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
