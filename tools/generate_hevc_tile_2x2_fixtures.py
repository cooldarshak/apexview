#!/usr/bin/env python3
"""Generate four complementary HEVC tile streams from the same synthetic pictures.

The source is encoded once as a 2x2 uniform tile grid. Each output feed contains exactly
one independent tile slice per picture plus the shared VPS/SPS/PPS. No camera streams,
DRM material, or external media are used.
"""
import argparse
import json
import shutil
import tempfile
from pathlib import Path

import generate_hevc_tile_fixtures as base


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    for executable in ("ffmpeg", "kvazaar", "ffprobe"):
        if not shutil.which(executable):
            raise SystemExit(f"{executable} is required")

    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="apexview-kvazaar-2x2-") as temporary:
        source_yuv = Path(temporary) / "source.yuv"
        source = output / "source_tiled_2x2.hevc"
        base.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i",
            f"testsrc2=size={base.FRAME_WIDTH}x{base.FRAME_HEIGHT}:rate={base.FRAME_RATE}",
            "-frames:v", str(base.FRAME_COUNT), "-pix_fmt", "yuv420p",
            "-f", "rawvideo", str(source_yuv)
        ])
        base.run([
            "kvazaar",
            "--input", str(source_yuv),
            "--input-res", f"{base.FRAME_WIDTH}x{base.FRAME_HEIGHT}",
            "--input-fps", str(base.FRAME_RATE),
            "--input-format", "P420",
            "--frames", str(base.FRAME_COUNT),
            "--output", str(source),
            "--period", "0", "--vps-period", "0", "--gop", "0",
            "--no-open-gop", "--tiles", "2x2", "--slices", "tiles",
            "--no-wpp", "--mv-constraint", "frametilemargin",
            "--threads", "1", "--no-info", "--no-psnr"
        ])
        nals = base.annexb_nals(source.read_bytes())
        parameter_sets, frames = base.split_into_picture_access_units(nals, expected_tile_slices=4)

        feeds = []
        for tile_index in range(4):
            feed = output / f"feed_tile_{tile_index}.hevc"
            base.write_annexb(feed, parameter_sets, frames, tile_index=tile_index)
            feeds.append(feed)

        reassembled = output / "reassembled_2x2.hevc"
        base.write_annexb(reassembled, parameter_sets, frames)
        source_hashes = base.decoded_frame_hashes(source)
        output_hashes = base.decoded_frame_hashes(reassembled)
        if source_hashes != output_hashes:
            raise RuntimeError("2x2 reconstructed output differs from the original source frames")

        decoder_sample = output / "reassembled_2x2.mp4"
        base.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-r", str(base.FRAME_RATE), "-f", "hevc", "-i", str(reassembled),
            "-frames:v", str(base.FRAME_COUNT), "-c:v", "copy", "-tag:v", "hvc1",
            "-r", str(base.FRAME_RATE), str(decoder_sample)
        ])
        probe = base.run([
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=codec_name,width,height,nb_frames",
            "-of", "json", str(decoder_sample)
        ])
        streams = json.loads(probe.stdout).get("streams", [])
        if len(streams) != 1 or streams[0].get("codec_name") != "hevc":
            raise RuntimeError("Could not package one HEVC video track for the 2x2 decoder test")
        if streams[0].get("width") != base.FRAME_WIDTH or streams[0].get("height") != base.FRAME_HEIGHT:
            raise RuntimeError(f"Unexpected 2x2 decoder fixture dimensions: {streams[0]}")

        manifest = {
            "result": "PASS",
            "encoder": "Kvazaar",
            "encoder_license": "BSD-3-Clause",
            "tile_grid": "2x2",
            "slice_mode": "one independent slice per tile",
            "width": base.FRAME_WIDTH,
            "height": base.FRAME_HEIGHT,
            "frame_rate": base.FRAME_RATE,
            "frame_count": base.FRAME_COUNT,
            "tiles_per_picture": 4,
            "vcl_slices_per_feed": base.FRAME_COUNT,
            "reassembled_decoded_frames": len(output_hashes),
            "decoded_frame_hashes_match_source": True,
            "decoder_asset": decoder_sample.name,
            "note": "All four tile feeds were extracted from the same Kvazaar-encoded pictures. " +
                    "This validates 2x2 complementary-slice assembly only; it does not model " +
                    "F1 feed-to-feed tile selection or CMAF/Widevine delivery."
        }
        (output / "tile_fixture_2x2_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
