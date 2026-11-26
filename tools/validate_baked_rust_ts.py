#!/usr/bin/env python3
"""Synthetic AVC/AAC -> production Rust mux -> independent TS/PES parser + FFmpeg."""
import argparse
import json
from pathlib import Path
import re
import struct
import subprocess

MASK = (1 << 33) - 1

def run(*args):
    subprocess.run([str(a) for a in args], check=True)

def access_units(data):
    starts = list(re.finditer(rb"\x00\x00\x00\x01|\x00\x00\x01", data))
    auds = [m.start() for m in starts if data[m.end()] & 31 == 9]
    assert len(auds) == 50 and auds[0] == 0
    return [data[a:b] for a,b in zip(auds, auds[1:] + [len(data)])]

def adts(data):
    pos = 0
    while pos < len(data):
        h = data[pos:pos+7]
        assert len(h) == 7 and h[0] == 255 and h[1] & 0xf6 == 0xf0
        n = ((h[3] & 3) << 11) | h[4] << 3 | h[5] >> 5
        assert 7 <= n <= len(data)-pos
        yield data[pos:pos+n]
        pos += n
    assert pos == len(data)

def fixture(directory):
    video, audio = directory/"source.h264", directory/"source.aac"
    run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
        "-i", "testsrc2=size=160x96:rate=25", "-t", "2", "-an", "-c:v", "libx264",
        "-threads", "1", "-pix_fmt", "yuv420p", "-preset", "ultrafast", "-bf", "0",
        "-x264-params", "aud=1:keyint=25:min-keyint=25:scenecut=0:repeat-headers=1",
        "-f", "h264", video)
    run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
        "-i", "sine=frequency=700:sample_rate=48000", "-t", "2", "-c:a", "aac",
        "-b:a", "96k", "-f", "adts", audio)
    samples = []
    for i, data in enumerate(access_units(video.read_bytes())):
        key = any(data[m.end()] & 31 == 5 for m in re.finditer(rb"\x00\x00\x00\x01|\x00\x00\x01", data))
        assert key == (i in (0,25))
        samples.append((i*40000, 3 if key else 1, data))
    for i, data in enumerate(adts(audio.read_bytes())):
        pts = i*1024*1000000//48000
        if pts < 2000000:
            samples.append((pts, 0, data))
    groups = []
    for n in range(2):
        group = sorted((x for x in samples if n*1000000 <= x[0] < (n+1)*1000000),
                       key=lambda x: (x[0], -(x[1]&1)))
        (directory/f"segment-{n}.samples").write_bytes(b"".join(
            struct.pack(">qIB", pts, len(data), flags) + data for pts,flags,data in group))
        groups.append(group)
    return groups

def inspect_table(pid, payload):
    # Independently parse the advertised programme and elementary streams,
    # including MPEG-2 CRC (not the reflected zlib/IEEE CRC).
    pointer = payload[0]
    assert pointer == 0
    body = payload[1 + pointer:]
    length = ((body[1] & 15) << 8) | body[2]
    section = body[:3 + length]
    assert len(section) == 3 + length
    assert section[1] & 0xf0 == 0xb0
    crc = 0xffffffff
    for byte in section:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04c11db7 if crc & 0x80000000 else 0)) & 0xffffffff
    assert crc == 0
    assert section[3:8] == bytes([0, 1, 0xc1, 0, 0])
    if pid == 0:
        assert section[0] == 0 and length == 13
        assert section[8:-4] == bytes([0, 1, 0xf0, 0])
    else:
        assert section[0] == 2 and length == 23
        assert section[8:12] == bytes([0xe1, 0, 0xf0, 0])
        assert section[12:-4] == bytes([0x1b, 0xe1, 0, 0xf0, 0, 0x0f, 0xe1, 1, 0xf0, 0])

def inspect(data, group, counters):
    assert len(data) % 188 == 0
    expected = {256: [],257: []}
    for pts, flags, payload in group:
        expected[256 if flags&1 else 257].append(((pts*9//100 + 9000)&MASK, flags, payload))
    actual = {256: [],257: []}
    open_pes = {}
    tables = []
    def finish(pid):
        if pid in open_pes:
            raw = open_pes.pop(pid)
            assert raw[:3] == b"\0\0\1"
            assert raw[3] == (0xe0 if pid == 256 else 0xc0)
            declared = int.from_bytes(raw[4:6], "big")
            assert raw[7] == 0x80 and raw[8] == 5
            p = raw[9:14]
            assert p[0]&0xf1 == 0x21 and p[2]&1 and p[4]&1
            pts = ((p[0]&14)<<29) | (p[1]<<22) | ((p[2]&254)<<14) | (p[3]<<7) | (p[4]>>1)
            assert declared == (0 if pid == 256 else len(raw)-6)
            actual[pid].append((pts,bytes(raw[14:])))
    for offset in range(0,len(data),188):
        packet = data[offset:offset+188]
        assert packet[0] == 0x47 and not packet[1]&0x80
        pid = (packet[1]&31)<<8 | packet[2]
        assert pid in (0,4096,256,257)
        assert packet[3]&15 == counters.get(pid,0)
        counters[pid] = (packet[3]+1)&15
        adaptation = (packet[3]>>4)&3
        assert adaptation in (1,3)
        start = bool(packet[1]&64)
        at = 4 if adaptation == 1 else 5+packet[4]
        assert at < 188
        payload = packet[at:]
        if pid in (0,4096):
            assert start and payload[0] == 0
            inspect_table(pid, payload)
            tables.append(pid)
            continue
        if start:
            finish(pid)
            if pid == 256:
                assert adaptation == 3 and packet[4] >= 7 and packet[5]&16
                sample = expected[pid][len(actual[pid])]
                assert bool(packet[5]&64) == bool(sample[1]&2)
                c = packet[6:12]
                pcr = c[0]<<25 | c[1]<<17 | c[2]<<9 | c[3]<<1 | c[4]>>7
                assert (sample[0]-pcr)&MASK == 9000
            open_pes[pid] = bytearray()
        assert pid in open_pes
        open_pes[pid].extend(payload)
    for pid in actual:
        finish(pid)
        assert actual[pid] == [(pts,payload) for pts,_,payload in expected[pid]], pid
    assert tables == [0,4096]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--writer", type=Path)
    args = parser.parse_args()
    directory = args.output_dir.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    groups = fixture(directory)
    if args.writer is None:
        return  # local generation-only check; CI always supplies production writer
    run(args.writer.resolve(),directory)
    counters = {}
    streams = []
    for n,group in enumerate(groups):
        data = (directory/f"segment-{n}.ts").read_bytes()
        inspect(data, group, counters)
        streams.append(data)
    output = directory/"baked.ts"
    output.write_bytes(b"".join(streams))
    run("ffmpeg","-hide_banner","-loglevel","error","-xerror","-i",output,
        "-map","0:v:0","-map","0:a:0","-f","null","-")
    result = json.loads(subprocess.check_output(["ffprobe","-v","error","-count_frames",
        "-show_entries","stream=codec_name,nb_read_frames","-of","json",str(output)]))
    frames = {s["codec_name"]:int(s["nb_read_frames"]) for s in result["streams"]}
    assert frames["h264"] == 50
    assert frames["aac"] == sum(1 for group in groups for _,f,_ in group if not f&1)
    print("Rust TS: independent PES, continuity, PCR, source-byte and FFmpeg decode checks passed",frames)

if __name__ == "__main__":
    main()
