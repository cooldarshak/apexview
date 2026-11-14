#!/usr/bin/env python3
"""Generate two disposable, synchronised HEVC test feeds. No F1 media is used."""
import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


def run(args):
    return subprocess.run(args, check=True, text=True, capture_output=True)


def probe(path):
    result = run([
        "ffprobe", "-v", "error", "-f", "hevc", "-count_frames",
        "-select_streams", "v:0", "-show_entries",
        "stream=codec_name,width,height,r_frame_rate,nb_read_frames",
        "-of", "json", str(path),
    ])
    streams = json.loads(result.stdout).get("streams", [])
    if len(streams) != 1:
        raise RuntimeError(f"Expected one video stream in {path.name}")
    return streams[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", help="Output directory. Defaults to a temporary directory.")
    parser.add_argument("--keep", action="store_true", help="Keep generated .hevc files when using a temporary directory.")
    args = parser.parse_args()

    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        raise SystemExit("ffmpeg and ffprobe are required")
    if args.output_dir:
        output = Path(args.output_dir)
        output.mkdir(parents=True, exist_ok=True)
        temporary = False
    else:
        output = Path(tempfile.mkdtemp(prefix="apexview-hevc-poc-"))
        temporary = not args.keep

    sources = {
        "feed_a.hevc": "testsrc2=size=640x360:rate=25",
        "feed_b.hevc": "smptebars=size=640x360:rate=25",
    }
    metadata = {}
    for filename, source in sources.items():
        destination = output / filename
        run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", source, "-t", "2", "-an",
            "-c:v", "libx265", "-preset", "ultrafast",
            "-x265-params",
            "log-level=error:tile-columns=1:tile-rows=0:keyint=50:min-keyint=50:scenecut=0",
            "-f", "hevc", str(destination),
        ])
        metadata[filename] = probe(destination)

    first, second = metadata.values()
    for key in ("codec_name", "width", "height", "r_frame_rate", "nb_read_frames"):
        if first.get(key) != second.get(key):
            raise SystemExit(f"Feed mismatch for {key}: {first.get(key)} != {second.get(key)}")
    if first.get("codec_name") != "hevc" or first.get("nb_read_frames") != "50":
        raise SystemExit(f"Unexpected synthetic stream metadata: {first}")

    print(json.dumps({
        "result": "PASS",
        "note": "Metadata/timing fixture only; not a stitched stream or proof of tile-slice compatibility.",
        "feeds": metadata,
    }, indent=2))
    if temporary:
        shutil.rmtree(output)


if __name__ == "__main__":
    main()
