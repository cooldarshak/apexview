#!/usr/bin/env python3
"""Generate two disposable, synchronised HEVC test feeds. No F1 media is used."""
import argparse
import json
import hashlib
import re
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
        "stream=codec_name,profile,width,height,pix_fmt,r_frame_rate,nb_read_frames",
        "-of", "json", str(path),
    ])
    streams = json.loads(result.stdout).get("streams", [])
    if len(streams) != 1:
        raise RuntimeError(f"Expected one video stream in {path.name}")
    return streams[0]



def trace_frame_signatures(path):
    """Read frame POC and short-term RPS fields using FFmpeg's trace_headers bitstream filter."""
    result = run([
        "ffmpeg", "-hide_banner", "-loglevel", "trace", "-i", str(path),
        "-c:v", "copy", "-bsf:v", "trace_headers", "-f", "null", "-",
    ])
    field = re.compile(
        r"\[trace_headers @ [^]]+\]\s+\d+\s+([A-Za-z0-9_\[\]]+)\s+\S+\s+=\s+(-?\d+)"
    )
    blocks = []
    current = None
    for line in result.stderr.splitlines():
        match = field.search(line)
        if not match:
            continue
        name, value = match.group(1), int(match.group(2))
        if name == "nal_unit_type":
            if current is not None:
                blocks.append(current)
            current = {"nal_unit_type": value}
        elif current is not None:
            current[name] = value
    if current is not None:
        blocks.append(current)

    frames = []
    for block in blocks:
        nal_type = block["nal_unit_type"]
        if nal_type > 31 or not block.get("first_slice_segment_in_pic_flag", 0):
            continue
        if nal_type in (19, 20):
            poc_lsb = 0
            references = []
        else:
            if "slice_pic_order_cnt_lsb" not in block:
                raise RuntimeError(f"Missing POC LSB in VCL NAL type {nal_type} in {path.name}")
            poc_lsb = block["slice_pic_order_cnt_lsb"]
            if block.get("short_term_ref_pic_set_sps_flag") != 0:
                raise RuntimeError(
                    "Synthetic fixture unexpectedly selects an SPS RPS; "
                    "the fixture analyzer expects the explicit x265 RPS configuration"
                )
            negative_count = block.get("num_negative_pics", 0)
            positive_count = block.get("num_positive_pics", 0)
            references = []
            delta = 0
            for index in range(negative_count):
                delta -= block[f"delta_poc_s0_minus1[{index}]"] + 1
                used = block[f"used_by_curr_pic_s0_flag[{index}]"]
                references.append((delta, used))
            delta = 0
            for index in range(positive_count):
                delta += block[f"delta_poc_s1_minus1[{index}]"] + 1
                used = block[f"used_by_curr_pic_s1_flag[{index}]"]
                references.append((delta, used))
        frames.append({
            "poc_lsb": poc_lsb,
            "slice_type": block.get("slice_type"),
            "references": references,
        })
    return frames


def compare_frame_signatures(first, second):
    compared = min(len(first), len(second))
    mismatches = []
    for index in range(compared):
        if first[index] != second[index]:
            mismatches.append({
                "decode_index": index,
                "feed_a": first[index],
                "feed_b": second[index],
            })
    return {
        "feed_a_frames": len(first),
        "feed_b_frames": len(second),
        "compared_frames": compared,
        "poc_aligned": len(first) == len(second) and all(
            a["poc_lsb"] == b["poc_lsb"] for a, b in zip(first, second)
        ),
        "slice_and_reference_metadata_aligned": len(first) == len(second) and not mismatches,
        "mismatches": mismatches[:5],
    }

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
        "feed_a.hevc": "testsrc2=size=960x540:rate=25",
        "feed_b.hevc": "smptebars=size=960x540:rate=25",
    }
    metadata = {}
    for filename, source in sources.items():
        destination = output / filename
        run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", source, "-t", "2", "-an",
            "-c:v", "libx265", "-preset", "ultrafast",
            "-x265-params",
            "log-level=error:bframes=0:repeat-headers=1:keyint=25:min-keyint=25:scenecut=0",
            "-f", "hevc", str(destination),
        ])
        metadata[filename] = probe(destination)

    first, second = metadata.values()
    for key in ("codec_name", "width", "height", "r_frame_rate", "nb_read_frames"):
        if first.get(key) != second.get(key):
            raise SystemExit(f"Feed mismatch for {key}: {first.get(key)} != {second.get(key)}")
    if first.get("codec_name") != "hevc" or first.get("width") != 960 or first.get("height") != 540 or first.get("nb_read_frames") != "50":
        raise SystemExit(f"Unexpected synthetic stream metadata: {first}")

    feed_a_bytes = (output / "feed_a.hevc").read_bytes()
    feed_b_bytes = (output / "feed_b.hevc").read_bytes()
    hashes = {
        "feed_a_sha256": hashlib.sha256(feed_a_bytes).hexdigest(),
        "feed_b_sha256": hashlib.sha256(feed_b_bytes).hexdigest(),
    }
    if hashes["feed_a_sha256"] == hashes["feed_b_sha256"]:
        raise SystemExit("Independent source encodes unexpectedly produced identical bitstreams")

    signatures = {
        "feed_a": trace_frame_signatures(output / "feed_a.hevc"),
        "feed_b": trace_frame_signatures(output / "feed_b.hevc"),
    }
    alignment = compare_frame_signatures(signatures["feed_a"], signatures["feed_b"])
    if alignment["compared_frames"] != 50 or not alignment["poc_aligned"]:
        raise SystemExit(f"Synthetic HEVC POC alignment failed: {alignment}")
    if not alignment["slice_and_reference_metadata_aligned"]:
        raise SystemExit(f"Synthetic HEVC slice/reference alignment failed: {alignment}")

    print(json.dumps({
        "result": "PASS",
        "note": (
            "POC and RPS metadata align in two independent full-frame synthetic encodes. "
            "This is not a stitched stream and does not prove that these full-picture streams "
            "are complementary tile slices or can be reconstructed into one HEVC picture."
        ),
        "feeds": metadata,
        "independent_bitstreams": hashes,
        "source_picture_layout": "full-frame 1x1 PPS expected; no tile grid requested from encoder",
        "frame_alignment": alignment,
    }, indent=2))
    if temporary:
        shutil.rmtree(output)


if __name__ == "__main__":
    main()
