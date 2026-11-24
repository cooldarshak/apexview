#!/usr/bin/env python3
"""Independently decode the Kotlin-generated HEVC output parameter sets with FFmpeg."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


START_CODE = b"\x00\x00\x00\x01"
PARAMETER_TYPES = {32, 33, 34}


def run(command):
    return subprocess.run(command, check=True, text=True, capture_output=True)


def split_annexb(data):
    starts = []
    index = 0
    while index + 2 < len(data):
        if index + 3 < len(data) and data[index:index + 4] == START_CODE:
            starts.append((index, 4))
            index += 4
        elif data[index:index + 3] == b"\x00\x00\x01":
            starts.append((index, 3))
            index += 3
        else:
            index += 1
    if not starts:
        raise RuntimeError("No Annex-B start code found")
    nals = []
    for position, (offset, prefix_size) in enumerate(starts):
        begin = offset + prefix_size
        end = starts[position + 1][0] if position + 1 < len(starts) else len(data)
        while end > begin and data[end - 1] == 0:
            end -= 1
        nal = data[begin:end]
        if len(nal) < 2:
            raise RuntimeError("Found an empty or truncated NAL")
        if nal[0] & 0x80 or (nal[1] & 0x07) == 0:
            raise RuntimeError("Invalid HEVC NAL header")
        nals.append((((nal[0] >> 1) & 0x3f), nal))
    return nals


def write_annexb(path, nals):
    with Path(path).open("wb") as stream:
        for _, nal in nals:
            stream.write(START_CODE)
            stream.write(nal)


def frame_hashes(path):
    result = run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "hevc", "-i", str(path),
        "-map", "0:v:0", "-f", "framemd5", "-"
    ])
    hashes = []
    for line in result.stdout.splitlines():
        if not line or line.startswith("#"):
            continue
        fields = [item.strip() for item in line.split(",")]
        if fields:
            hashes.append(fields[-1])
    return hashes


def stream_info(path):
    result = run([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,width,height,nb_read_frames",
        "-count_frames", "-of", "json", str(path)
    ])
    streams = json.loads(result.stdout).get("streams", [])
    if len(streams) != 1:
        raise RuntimeError(f"Expected exactly one video stream in {path.name}")
    return streams[0]


def splice_generated_parameter_sets(original_path, parameter_set_path, output_path):
    source_nals = split_annexb(Path(original_path).read_bytes())
    output_parameter_nals = split_annexb(Path(parameter_set_path).read_bytes())
    replacements = {}
    for nal_type, nal in output_parameter_nals:
        if nal_type not in PARAMETER_TYPES or nal_type in replacements:
            raise RuntimeError("Generated parameter-set file must contain exactly one VPS, SPS and PPS")
        replacements[nal_type] = nal
    if set(replacements) != PARAMETER_TYPES:
        raise RuntimeError("Generated output is missing one or more VPS/SPS/PPS NALs")

    output_nals = []
    inserted = set()
    for nal_type, nal in source_nals:
        if nal_type in PARAMETER_TYPES:
            if nal_type not in inserted:
                output_nals.append((nal_type, replacements[nal_type]))
                inserted.add(nal_type)
            continue
        output_nals.append((nal_type, nal))
    if inserted != PARAMETER_TYPES:
        raise RuntimeError("Input stream did not contain all three parameter-set NAL types")

    source_vcl = [nal for nal_type, nal in source_nals if nal_type <= 31]
    output_vcl = [nal for nal_type, nal in output_nals if nal_type <= 31]
    if source_vcl != output_vcl:
        raise RuntimeError("VCL NAL payloads changed while replacing output parameter sets")
    write_annexb(output_path, output_nals)


def verify_independent_feed(source_path, reidentified_path):
    original = frame_hashes(source_path)
    reidentified = frame_hashes(reidentified_path)
    if len(original) != 50 or len(reidentified) != 50:
        raise RuntimeError(
            f"Expected 50 decoded frames for {source_path.name}; got {len(original)} and {len(reidentified)}"
        )
    if original != reidentified:
        mismatch = next((i for i, pair in enumerate(zip(original, reidentified)) if pair[0] != pair[1]), None)
        raise RuntimeError(f"PPS identifier rewrite changed decoded picture content at frame {mismatch}")
    return {
        "source": source_path.name,
        "reidentified": reidentified_path.name,
        "decoded_frames": len(reidentified),
        "decoded_content_identical": True,
        "sha256": hashlib.sha256(Path(reidentified_path).read_bytes()).hexdigest()
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", required=True)
    parser.add_argument("--tile-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()

    source_dir = Path(args.source_dir)
    tile_dir = Path(args.tile_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    independent_feed_results = [
        verify_independent_feed(source_dir / "feed_a.hevc", source_dir / "feed_a_pps0.hevc"),
        verify_independent_feed(source_dir / "feed_b.hevc", source_dir / "feed_b_pps1.hevc")
    ]

    original_tile_stream = tile_dir / "reassembled.hevc"
    generated_sets = output_dir / "generated_parameter_sets.hevc"
    if not original_tile_stream.is_file() or not generated_sets.is_file():
        raise RuntimeError("Kotlin test did not produce the input stream or output parameter-set fixture")

    generated_stream = output_dir / "generated_output_picture.hevc"
    splice_generated_parameter_sets(original_tile_stream, generated_sets, generated_stream)

    original_info = stream_info(original_tile_stream)
    generated_info = stream_info(generated_stream)
    if (generated_info.get("codec_name"), generated_info.get("width"), generated_info.get("height")) != (
        "hevc", original_info.get("width"), original_info.get("height")
    ):
        raise RuntimeError(f"Independent ffprobe metadata mismatch: {original_info} vs {generated_info}")

    original_hashes = frame_hashes(original_tile_stream)
    generated_hashes = frame_hashes(generated_stream)
    if len(generated_hashes) != 50 or original_hashes != generated_hashes:
        mismatch = next((i for i, pair in enumerate(zip(original_hashes, generated_hashes)) if pair[0] != pair[1]), None)
        raise RuntimeError(
            f"FFmpeg decode of output VPS/SPS/PPS failed content comparison; "
            f"frames={len(generated_hashes)}, first_mismatch={mismatch}"
        )

    report = {
        "result": "PASS",
        "independent_decoder": "FFmpeg",
        "independent_parser": "ffprobe",
        "output_parameter_set_rewrite": {
            "decoded_frames": len(generated_hashes),
            "width": generated_info["width"],
            "height": generated_info["height"],
            "decoded_content_identical_to_reference": True,
            "vcl_nals_preserved_byte_for_byte": True
        },
        "independent_source_feeds": independent_feed_results
    }
    (output_dir / "independent_decode_validation.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
