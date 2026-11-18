"""Build and verify a clear, precomposited Cast fixture using FFmpeg."""

import argparse
import json
from pathlib import Path
import subprocess


WIDTH, HEIGHT = 1280, 720
FEEDS = (
    ("red", 800, 540, 0, 0),
    ("lime", 480, 180, 800, 0),
    ("blue", 480, 180, 800, 180),
    ("yellow", 480, 180, 800, 360),
)


def encode(output):
    command = ["ffmpeg", "-v", "error", "-y", "-filter_complex_threads", "1"]
    for color, width, height, _, _ in FEEDS:
        command += ["-f", "lavfi", "-i", f"color={color}:size={width}x{height}:rate=30"]
    command += [
        "-f", "lavfi", "-i", f"color=black:size={WIDTH}x{HEIGHT}:rate=30",
        "-f", "lavfi", "-i", "color=white:size=16x16:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
    ]

    layout = ["[4:v]null[base]"]
    previous = "base"
    for index, (_, _, _, x, y) in enumerate(FEEDS):
        current = f"feed{index}"
        layout.append(f"[{previous}][{index}:v]overlay={x}:{y}[{current}]")
        previous = current
    # Synthetic timing rows and a track with a marker moving along the top straight.
    layout += [
        f"[{previous}]drawbox=x=840:y=565:w=400:h=125:c=gray:t=fill,"
        "drawbox=x=860:y=580:w=350:h=12:c=cyan:t=fill,"
        "drawbox=x=860:y=610:w=285:h=12:c=cyan:t=fill,"
        "drawbox=x=860:y=640:w=320:h=12:c=cyan:t=fill,"
        "drawbox=x=40:y=568:w=720:h=110:c=gray:t=4[panels]",
        "[panels][5:v]overlay=x='60+160*t':y=563[video]",
    ]
    command += [
        "-filter_complex", ";".join(layout),
        "-map", "[video]", "-map", "6:a", "-t", "3",
        "-c:v", "libx264", "-preset", "fast", "-profile:v", "main",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart",
        str(output),
    ]
    subprocess.run(command, check=True)


def verify(output):
    streams = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_streams", "-of", "json", str(output),
    ]))["streams"]
    tracks = sorted((stream["codec_type"], stream["codec_name"]) for stream in streams)
    if tracks != [("audio", "aac"), ("video", "h264")]:
        raise ValueError(f"Expected one AVC video and one AAC audio track, got {tracks}")

    for timestamp in (0.5, 1.5):
        frame = subprocess.check_output([
            "ffmpeg", "-v", "error", "-ss", str(timestamp), "-i", str(output),
            "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", "-",
        ])
        expected = {
            (100, 100): (255, 0, 0),
            (1000, 100): (0, 255, 0),
            (1000, 280): (0, 0, 255),
            (1000, 460): (255, 255, 0),
            (900, 585): (0, 255, 255),
            (40, 620): (128, 128, 128),
            (68 + int(160 * timestamp), 570): (255, 255, 255),
            (68 + int(160 * (2 - timestamp)), 570): (128, 128, 128),
        }
        if len(frame) != WIDTH * HEIGHT * 3:
            raise ValueError(f"Unexpected decoded frame size: {len(frame)} bytes")
        for (x, y), color in expected.items():
            offset = (y * WIDTH + x) * 3
            actual = frame[offset:offset + 3]
            if any(abs(value - target) > 32 for value, target in zip(actual, color)):
                raise ValueError(f"Frame {timestamp}s pixel {x},{y}: {tuple(actual)} != {color}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    output = parser.parse_args().output
    output.parent.mkdir(parents=True, exist_ok=True)
    encode(output)
    verify(output)
    print(f"Verified one video track with four regions, timing rows and a moving marker: {output}")


if __name__ == "__main__":
    main()
