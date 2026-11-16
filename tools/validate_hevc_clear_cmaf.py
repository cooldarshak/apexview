#!/usr/bin/env python3
"""Independently validate the clear HEVC fragmented-MP4 WP5 output."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


EXPECTED_WIDTH = 256
EXPECTED_HEIGHT = 128
EXPECTED_FRAMES = 50


def run(args):
    return subprocess.run(args, check=True, text=True, capture_output=True)


def frame_hashes(input_args):
    result = run([
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        *input_args,
        "-map", "0:v:0", "-f", "framemd5", "-"
    ])
    hashes = []
    for line in result.stdout.splitlines():
        if not line or line.startswith("#"):
            continue
        fields = [part.strip() for part in line.split(",")]
        if fields:
            hashes.append(fields[-1])
    return hashes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()

    fixture_dir = Path(args.fixture_dir)
    output_dir = Path(args.output_dir)
    source = fixture_dir / "reassembled.hevc"
    cmaf = output_dir / "hevc_clear_cmaf.mp4"
    evidence = output_dir / "hevc_clear_cmaf_validation.json"

    if not source.is_file():
        raise SystemExit(f"Missing synthetic HEVC reference stream: {source}")
    if not cmaf.is_file():
        raise SystemExit(f"Missing Kotlin-generated clear CMAF file: {cmaf}")

    probe = run([
        "ffprobe", "-v", "error", "-count_frames",
        "-select_streams", "v:0",
        "-show_entries",
        "stream=codec_name,codec_tag_string,width,height,time_base,nb_read_frames",
        "-of", "json", str(cmaf)
    ])
    streams = json.loads(probe.stdout).get("streams", [])
    if len(streams) != 1:
        raise SystemExit(f"Expected exactly one video stream, found {len(streams)}")
    stream = streams[0]
    if stream.get("codec_name") != "hevc":
        raise SystemExit(f"Unexpected codec: {stream.get('codec_name')}")
    if stream.get("codec_tag_string") != "hvc1":
        raise SystemExit(f"Unexpected HEVC sample entry: {stream.get('codec_tag_string')}")
    if stream.get("width") != EXPECTED_WIDTH or stream.get("height") != EXPECTED_HEIGHT:
        raise SystemExit(f"Unexpected decoded dimensions: {stream.get('width')}x{stream.get('height')}")
    frame_count = int(stream.get("nb_read_frames") or 0)
    if frame_count != EXPECTED_FRAMES:
        raise SystemExit(f"Expected {EXPECTED_FRAMES} CMAF frames, decoded {frame_count}")

    source_hashes = frame_hashes(["-f", "hevc", "-i", str(source)])
    cmaf_hashes = frame_hashes(["-i", str(cmaf)])
    if len(source_hashes) != EXPECTED_FRAMES:
        raise SystemExit(f"Reference HEVC decoded to {len(source_hashes)} frames")
    if len(cmaf_hashes) != EXPECTED_FRAMES:
        raise SystemExit(f"CMAF output decoded to {len(cmaf_hashes)} frames")
    if source_hashes != cmaf_hashes:
        mismatch = next(
            (index for index, pair in enumerate(zip(source_hashes, cmaf_hashes))
             if pair[0] != pair[1]),
            None
        )
        raise SystemExit(f"CMAF frame hashes differ from reference; first mismatch: {mismatch}")

    digest = hashlib.sha256(cmaf.read_bytes()).hexdigest()
    result = {
        "result": "CLEAR_CMAF_INDEPENDENT_DECODE_PASS",
        "container": "fragmented ISO-BMFF",
        "codec": stream["codec_name"],
        "sample_entry": stream["codec_tag_string"],
        "width": stream["width"],
        "height": stream["height"],
        "time_base": stream.get("time_base"),
        "frame_count": frame_count,
        "reference_frame_hashes_match": True,
        "encrypted": False,
        "file_size_bytes": cmaf.stat().st_size,
        "sha256": digest,
    }
    evidence.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
