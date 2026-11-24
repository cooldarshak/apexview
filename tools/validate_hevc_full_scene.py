#!/usr/bin/env python3
"""Encode and independently decode a complete synthetic Pitwall video scene."""

import argparse
import json
from pathlib import Path
import subprocess


WIDTH, HEIGHT, FPS = 960, 540, 25
FEEDS = (
    ("0x24599c", 640, 360, 0, 90),
    ("0x8f2449", 160, 90, 640, 340),
    ("0x227747", 160, 90, 800, 340),
    ("0x8a5128", 160, 90, 640, 430),
)


def run(program, *args):
    return subprocess.check_output([program, *map(str, args)])


def encode(output):
    args = ["-v", "error", "-y", "-filter_complex_threads", "1"]
    filters = []
    for index, (color, width, height, _, _) in enumerate(FEEDS):
        args += ["-f", "lavfi", "-i",
                 f"color={color}:size={width}x{height}:rate={FPS}:duration=2"]
        filters.append(f"[{index}:v]drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='FEED {index + 1}':"
                       f"x=12:y=12:fontsize=18:fontcolor=white[feed{index}]")
    layout = "|".join(f"{x}_{y}" for _, _, _, x, y in FEEDS)
    inputs = "".join(f"[feed{i}]" for i in range(len(FEEDS)))
    filters += [
        f"{inputs}xstack=inputs=4:layout={layout}:fill=black[feeds]",
        "[feeds]pad=960:540:0:0,drawbox=x=640:y=0:w=320:h=340:color=0x181818:t=fill,"
        "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='TIMING TOWER':x=654:y=14:fontsize=20:fontcolor=white[tower]",
    ]
    table = "tower"
    for row, driver in enumerate(("VER", "NOR", "LEC", "PIA")):
        y = 52 + 45 * row
        filters.append(
            f"[{table}]drawbox=x=650:y={y}:w=290:h=37:color=0x6630bc:t=fill,"
            f"drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='{row + 1}  {driver}    S1  S2  S3':"
            f"x=658:y={y + 8}:fontsize=18:fontcolor=white[row{row}]"
        )
        table = f"row{row}"
    args += ["-f", "lavfi", "-i", f"color=yellow:size=12x12:rate={FPS}:duration=2"]
    filters += [
        f"[{table}]drawbox=x=423:y=103:w=205:h=110:color=0x161616:t=fill,"
        "drawbox=x=443:y=121:w=165:h=72:color=white:t=3,"
        "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='TRACKER':x=460:y=152:fontsize=16:fontcolor=white[track]",
        "[track][4:v]overlay=x='443+60*t':y=117:shortest=1[scene]",
    ]
    run("ffmpeg", *args, "-filter_complex", ";".join(filters), "-map", "[scene]",
        "-c:v", "libx265", "-preset", "ultrafast", "-x265-params",
        "log-level=error:pools=none:frame-threads=2", "-tag:v", "hvc1",
        "-movflags", "+faststart", output)


def verify(output):
    info = json.loads(run("ffprobe", "-v", "error", "-show_streams", "-of", "json", output))
    streams = info["streams"]
    if len(streams) != 1 or (streams[0]["codec_name"], streams[0]["width"], streams[0]["height"]) != (
        "hevc", WIDTH, HEIGHT
    ):
        raise RuntimeError("Expected exactly one 960x540 HEVC stream")
    decoded = run("ffmpeg", "-v", "error", "-threads", "2", "-i", output,
                  "-f", "rawvideo", "-pix_fmt", "rgb24", "-threads", "1", "-")
    frame_size = WIDTH * HEIGHT * 3
    if len(decoded) != FPS * 2 * frame_size:
        raise RuntimeError("Unexpected decoded frame count")

    def pixel(frame, x, y):
        offset = frame * frame_size + (y * WIDTH + x) * 3
        return decoded[offset:offset + 3]

    def check(frame, x, y, color):
        actual = pixel(frame, x, y)
        expected = bytes.fromhex(color)
        if max(abs(a - b) for a, b in zip(actual, expected)) > 25:
            raise RuntimeError(f"Unexpected decoded pixel at frame {frame}, ({x}, {y}): {actual.hex()}")

    for color, width, height, x, y in FEEDS:
        check(0, x + width // 2, y + height // 2, color[2:])
    check(0, 929, 76, "6630bc")   # Timing tower row
    check(0, 590, 121, "ffffff")  # Circuit line over the main feed
    check(0, 449, 123, "ffff00")  # Initial marker
    check(FPS, 509, 123, "ffff00")  # Marker moves along the track
    check(FPS, 449, 123, "ffffff")  # Old marker position exposes the track
    print("FULL_SCENE_HEVC_PASS: four feeds, tower and moving tracker decoded from one video stream")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    output = parser.parse_args().output
    output.parent.mkdir(parents=True, exist_ok=True)
    encode(output)
    verify(output)
