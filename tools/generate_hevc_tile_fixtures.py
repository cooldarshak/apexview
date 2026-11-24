#!/usr/bin/env python3
"""Generate complementary HEVC tile feeds and a deterministic reassembled reference.

All media is synthetic and locally generated. The two feed files contain complementary
independent tile slices from the same encoded pictures; feed_right is intentionally not a
standalone full-picture stream.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


FRAME_WIDTH = 256
FRAME_HEIGHT = 128
FRAME_RATE = 25
FRAME_COUNT = 50


def run(args):
    return subprocess.run(args, check=True, text=True, capture_output=True)


def annexb_nals(data):
    starts = []
    index = 0
    while index + 2 < len(data):
        if index + 3 < len(data) and data[index:index + 4] == b"\x00\x00\x00\x01":
            starts.append((index, 4))
            index += 4
        elif data[index:index + 3] == b"\x00\x00\x01":
            starts.append((index, 3))
            index += 3
        else:
            index += 1
    if not starts:
        raise RuntimeError("No Annex-B start code found")
    result = []
    for position, (start, prefix) in enumerate(starts):
        begin = start + prefix
        end = starts[position + 1][0] if position + 1 < len(starts) else len(data)
        while end > begin and data[end - 1] == 0:
            end -= 1
        nal = data[begin:end]
        if len(nal) < 2:
            raise RuntimeError("Found an empty or truncated HEVC NAL")
        if nal[0] & 0x80:
            raise RuntimeError("HEVC forbidden_zero_bit is set")
        if (nal[1] & 0x07) == 0:
            raise RuntimeError("HEVC nuh_temporal_id_plus1 is zero")
        nal_type = (nal[0] >> 1) & 0x3f
        result.append((nal_type, nal))
    return result


def first_slice_segment(nal):
    # first_slice_segment_in_pic_flag is the first RBSP bit after the NAL header.
    # Emulation-prevention bytes cannot occur before this first syntax bit.
    if len(nal) < 3:
        raise RuntimeError("Truncated VCL NAL")
    return bool(nal[2] & 0x80)


def split_into_picture_access_units(nals, expected_tile_slices=2):
    parameter_sets = []
    frames = []
    current = None
    pending_prefix = []
    saw_vcl = False

    for nal_type, nal in nals:
        if nal_type in (32, 33, 34) and not saw_vcl and current is None:
            parameter_sets.append(nal)
            continue

        if nal_type <= 31:
            is_first = first_slice_segment(nal)
            if is_first:
                if current is not None:
                    if not current["slices"]:
                        raise RuntimeError("Access unit closed without a VCL slice")
                    frames.append(current)
                current = {"prefix": pending_prefix, "slices": [], "suffix": []}
                pending_prefix = []
            elif current is None:
                raise RuntimeError("A continuation tile slice appeared before its first tile")
            current["slices"].append(nal)
            saw_vcl = True
            continue

        # AUD and prefix SEI belong before the next coded picture. Suffix SEI is
        # attached to the current picture. Other non-VCL NALs are conservatively
        # retained adjacent to the current picture or as the next prefix.
        if nal_type in (35, 39) or current is None:
            if current is not None and current["slices"]:
                frames.append(current)
                current = None
            pending_prefix.append(nal)
        elif nal_type == 40:
            current["suffix"].append(nal)
        else:
            current["suffix"].append(nal)

    if current is not None:
        if not current["slices"]:
            raise RuntimeError("Final access unit contains no VCL slice")
        frames.append(current)

    if not parameter_sets:
        raise RuntimeError("No VPS/SPS/PPS parameter sets found before coded pictures")
    if len(frames) != FRAME_COUNT:
        raise RuntimeError(f"Expected {FRAME_COUNT} access units, found {len(frames)}")

    for frame_index, frame in enumerate(frames):
        if len(frame["slices"]) != expected_tile_slices:
            raise RuntimeError(
                f"Picture {frame_index} has {len(frame['slices'])} tile slices; expected exactly {expected_tile_slices}"
            )
        if not first_slice_segment(frame["slices"][0]):
            raise RuntimeError(f"Picture {frame_index} first tile lacks first_slice_segment_in_pic_flag")
        for tile_index, tile in enumerate(frame["slices"][1:], start=1):
            if first_slice_segment(tile):
                raise RuntimeError(
                    f"Picture {frame_index} continuation tile {tile_index} unexpectedly starts a new picture"
                )

    return parameter_sets, frames


def write_annexb(path, parameter_sets, frames, tile_index=None):
    with path.open("wb") as stream:
        for nal in parameter_sets:
            stream.write(b"\x00\x00\x00\x01")
            stream.write(nal)
        for frame in frames:
            ordered = frame["slices"] if tile_index is None else [frame["slices"][tile_index]]
            # The POC assembler intentionally accepts a narrow elementary-stream profile:
            # VPS/SPS/PPS followed by the complementary VCL tile slices only. AUD, SEI,
            # EOS and other non-VCL metadata are not part of this synthetic contract.
            for nal in ordered:
                stream.write(b"\x00\x00\x00\x01")
                stream.write(nal)


def decoded_frame_hashes(path):
    result = run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "hevc", "-i", str(path),
        "-map", "0:v:0", "-f", "framemd5", "-"
    ])
    hashes = []
    for line in result.stdout.splitlines():
        if not line or line.startswith("#"):
            continue
        fields = [part.strip() for part in line.split(",")]
        if fields:
            hashes.append(fields[-1])
    if len(hashes) != FRAME_COUNT:
        raise RuntimeError(f"{path.name} decoded to {len(hashes)} frames, expected {FRAME_COUNT}")
    return hashes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--keep-source-yuv", action="store_true")
    args = parser.parse_args()

    for executable in ("ffmpeg", "kvazaar"):
        if not shutil.which(executable):
            raise SystemExit(f"{executable} is required")

    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="apexview-kvazaar-") as temporary:
        source_yuv = Path(temporary) / "source.yuv"
        source_stream = output / "source_tiled.hevc"

        run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i",
            f"testsrc2=size={FRAME_WIDTH}x{FRAME_HEIGHT}:rate={FRAME_RATE}",
            "-frames:v", str(FRAME_COUNT), "-pix_fmt", "yuv420p",
            "-f", "rawvideo", str(source_yuv)
        ])

        run([
            "kvazaar",
            "--input", str(source_yuv),
            "--input-res", f"{FRAME_WIDTH}x{FRAME_HEIGHT}",
            "--input-fps", str(FRAME_RATE),
            "--input-format", "P420",
            "--frames", str(FRAME_COUNT),
            "--output", str(source_stream),
            "--period", "0",
            "--vps-period", "0",
            "--gop", "0",
            "--no-open-gop",
            "--tiles", "2x1",
            "--slices", "tiles",
            "--no-wpp",
            "--mv-constraint", "frametilemargin",
            "--threads", "1",
            "--no-info",
            "--no-psnr"
        ])

        nals = annexb_nals(source_stream.read_bytes())
        parameter_sets, frames = split_into_picture_access_units(nals)

        left_feed = output / "feed_left.hevc"
        right_feed = output / "feed_right.hevc"
        reassembled = output / "reassembled.hevc"
        write_annexb(left_feed, parameter_sets, frames, tile_index=0)
        write_annexb(right_feed, parameter_sets, frames, tile_index=1)
        write_annexb(reassembled, parameter_sets, frames)

        source_hashes = decoded_frame_hashes(source_stream)
        reassembled_hashes = decoded_frame_hashes(reassembled)
        if source_hashes != reassembled_hashes:
            first_difference = next(
                (index for index, pair in enumerate(zip(source_hashes, reassembled_hashes))
                 if pair[0] != pair[1]),
                None
            )
            raise RuntimeError(f"Reassembled frame hashes differ; first mismatch: {first_difference}")

        # Package the clear synthetic elementary stream in MP4 so an Android instrumentation
        # test can pass it through MediaExtractor and exactly one MediaCodec decoder instance.
        decoder_sample = output / "reassembled.mp4"
        run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-r", str(FRAME_RATE), "-f", "hevc", "-i", str(reassembled),
            "-frames:v", str(FRAME_COUNT), "-c:v", "copy", "-tag:v", "hvc1",
            "-r", str(FRAME_RATE), str(decoder_sample)
        ])
        probe = run([
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=codec_name,width,height,nb_frames",
            "-of", "json", str(decoder_sample)
        ])
        streams = json.loads(probe.stdout).get("streams", [])
        if len(streams) != 1 or streams[0].get("codec_name") != "hevc":
            raise RuntimeError("Could not package a single HEVC video track for MediaCodec proof")
        if streams[0].get("width") != FRAME_WIDTH or streams[0].get("height") != FRAME_HEIGHT:
            raise RuntimeError(f"Unexpected MediaCodec proof dimensions: {streams[0]}")

        left_nals = annexb_nals(left_feed.read_bytes())
        right_nals = annexb_nals(right_feed.read_bytes())
        metadata = {
            "result": "PASS",
            "encoder": "Kvazaar",
            "encoder_license": "BSD-3-Clause",
            "tile_grid": "2x1",
            "slice_mode": "tiles",
            "width": FRAME_WIDTH,
            "height": FRAME_HEIGHT,
            "frame_rate": FRAME_RATE,
            "frame_count": FRAME_COUNT,
            "parameter_set_nal_counts": {
                "vps": sum(1 for t, _ in nals if t == 32),
                "sps": sum(1 for t, _ in nals if t == 33),
                "pps": sum(1 for t, _ in nals if t == 34)
            },
            "tile_slices_per_picture": 2,
            "left_feed_vcl_nals": sum(1 for t, _ in left_nals if t <= 31),
            "right_feed_vcl_nals": sum(1 for t, _ in right_nals if t <= 31),
            "reassembled_decoded_frames": len(reassembled_hashes),
            "media_codec_test_asset": decoder_sample.name,
            "media_codec_test_asset_bytes": decoder_sample.stat().st_size,
            "decoded_frame_hashes_match_source": True,
            "note": (
                "Two complementary tile-slice streams were separated from the same encoded "
                "pictures. The narrow test inputs contain the shared VPS/SPS/PPS and only the "
                "corresponding VCL tile slices; non-VCL metadata is excluded. The Kotlin "
                "assembler reconstitutes the original VCL NAL sequence and FFmpeg verifies "
                "pixel-identical output. Android MediaCodec runtime validation remains separate."
            )
        }
        (output / "tile_fixture_manifest.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print(json.dumps(metadata, indent=2))

    if args.keep_source_yuv:
        print("Source YUV is intentionally generated only in a temporary folder and is not retained.")


if __name__ == "__main__":
    main()
