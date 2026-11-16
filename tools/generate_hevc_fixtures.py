#!/usr/bin/env python3
"""Generate independent, synchronised, motion-constrained HEVC source feeds.

These synthetic inputs model the F1 tiled-feed precondition rather than ordinary x265 full-frame
encodes: each source is one 960x544 tile, encoded with Kvazaar's frametilemargin motion-vector
constraint and TMVP disabled. The Kotlin composer must preserve each feed's temporal independence
when the two source pictures become adjacent output tiles.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


FRAME_WIDTH = 960
FRAME_HEIGHT = 540
FRAME_RATE = 25
FRAME_COUNT = 50


def run(args):
    result = subprocess.run(args, check=False, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {' '.join(map(str, args))}\n"
            f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    return result


def probe(path):
    result = run([
        "ffprobe", "-v", "error", "-f", "hevc", "-count_frames",
        "-select_streams", "v:0", "-show_entries",
        "stream=codec_name,profile,width,height,pix_fmt,r_frame_rate,nb_read_frames",
        "-of", "json", str(path),
    ])
    streams = json.loads(result.stdout).get("streams", [])
    if len(streams) != 1:
        raise RuntimeError(f"Expected one video stream in {path.name}")
    return streams[0]


def encode_feed(source, raw_yuv, destination):
    run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", source,
        "-frames:v", str(FRAME_COUNT), "-pix_fmt", "yuv420p",
        "-f", "rawvideo", str(raw_yuv),
    ])
    expected_yuv_bytes = FRAME_WIDTH * FRAME_HEIGHT * 3 // 2 * FRAME_COUNT
    actual_yuv_bytes = raw_yuv.stat().st_size
    if actual_yuv_bytes != expected_yuv_bytes:
        raise RuntimeError(
            f"Unexpected raw YUV size for {source}: "
            f"{actual_yuv_bytes} != {expected_yuv_bytes}"
        )

    run([
        "kvazaar",
        "--input", str(raw_yuv),
        "--input-res", f"{FRAME_WIDTH}x{FRAME_HEIGHT}",
        "--input-fps", str(FRAME_RATE),
        "--input-format", "P420",
        "--frames", str(FRAME_COUNT),
        "--output", str(destination),
        "--period", "25",
        "--vps-period", "0",
        "--gop", "0",
        "--no-open-gop",
        "--tiles", "1x1",
        "--slices", "tiles",
        "--no-wpp",
        "--mv-constraint", "frametilemargin",
        "--no-tmvp",
        "--threads", "1",
        "--no-info",
        "--no-psnr",
        "--hash", "none",
    ])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", help="Output directory. Defaults to a temporary directory.")
    parser.add_argument("--keep", action="store_true", help="Keep generated .hevc files when using a temporary directory.")
    args = parser.parse_args()

    if not shutil.which("ffmpeg") or not shutil.which("ffprobe") or not shutil.which("kvazaar"):
        raise SystemExit("ffmpeg, ffprobe and kvazaar are required")
    if args.output_dir:
        output = Path(args.output_dir)
        output.mkdir(parents=True, exist_ok=True)
        temporary = False
    else:
        output = Path(tempfile.mkdtemp(prefix="apexview-hevc-poc-"))
        temporary = not args.keep

    sources = {
        "feed_a.hevc": "testsrc2=size=960x540:rate=25",
        "feed_b.hevc": "testsrc=size=960x540:rate=25",
    }
    metadata = {}
    with tempfile.TemporaryDirectory(prefix="apexview-motion-constrained-") as temporary_dir:
        temp = Path(temporary_dir)
        for filename, source in sources.items():
            raw_yuv = temp / (filename + ".yuv")
            destination = output / filename
            encode_feed(source, raw_yuv, destination)
            metadata[filename] = probe(destination)

    first, second = metadata.values()
    for key in ("codec_name", "width", "height", "r_frame_rate", "nb_read_frames"):
        if first.get(key) != second.get(key):
            raise SystemExit(f"Feed mismatch for {key}: {first.get(key)} != {second.get(key)}")
    if (
        first.get("codec_name") != "hevc"
        or first.get("width") != FRAME_WIDTH
        or first.get("height") != FRAME_HEIGHT
        or first.get("nb_read_frames") != str(FRAME_COUNT)
    ):
        raise SystemExit(f"Unexpected motion-constrained synthetic stream metadata: {first}")

    feed_a_bytes = (output / "feed_a.hevc").read_bytes()
    feed_b_bytes = (output / "feed_b.hevc").read_bytes()
    hashes = {
        "feed_a_sha256": hashlib.sha256(feed_a_bytes).hexdigest(),
        "feed_b_sha256": hashlib.sha256(feed_b_bytes).hexdigest(),
    }
    if hashes["feed_a_sha256"] == hashes["feed_b_sha256"]:
        raise SystemExit("Independent source encodes unexpectedly produced identical bitstreams")

    manifest = {
        "result": "PASS",
        "encoder": "Kvazaar",
        "encoder_license": "BSD-3-Clause",
        "independent_source_encodes": True,
        "source_picture_layout": "one 960x540 display tile (544 coded lines with conformance crop) and one independent slice per picture",
        "motion_vector_constraint": "frametilemargin",
        "temporal_motion_vector_prediction": "disabled",
        "wpp": "disabled",
        "frame_rate": FRAME_RATE,
        "frame_count": FRAME_COUNT,
        "feeds": metadata,
        "independent_bitstreams": hashes,
        "note": (
            "The source feeds are motion-constrained at their picture/tile boundaries. "
            "Ordinary unconstrained full-picture HEVC streams are not a valid fixture for "
            "pixel-identical cell stitching because inter-picture motion vectors may reference "
            "outside the future output cell."
        ),
    }
    (output / "feed_pair_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))

    if temporary:
        shutil.rmtree(output)


if __name__ == "__main__":
    main()
