"""Check whether a Perfetto capture actually includes native heap profiles.
No trace processor dependency; inspect top-level TracePacket field numbers.
This does NOT prove that a profile belongs to the app or has usable stacks.
Use Perfetto and exact-build symbols for allocation-site attribution.
"""
import argparse
import collections
import io
import pathlib
import zipfile

MAX_PACKET = 64 * 1024 * 1024

def varint(stream, eof_ok=False):
    value = 0
    for shift in range(0, 70, 7):
        b = stream.read(1)
        if not b:
            if shift == 0 and eof_ok:
                return None
            raise ValueError("truncated protobuf varint")
        if shift == 63 and b[0] > 1:
            raise ValueError("protobuf varint overflow")
        value |= (b[0] & 127) << shift
        if b[0] < 128:
            return value
    raise ValueError("invalid protobuf varint")

def fields(stream):
    while True:
        key = varint(stream, eof_ok=True)
        if key is None:
            return
        number, wire = key >> 3, key & 7
        if number == 0:
            raise ValueError("invalid protobuf field")
        if wire == 0:
            value = varint(stream)
        elif wire in (1, 2, 5):
            size = varint(stream) if wire == 2 else (8 if wire == 1 else 4)
            if size > MAX_PACKET:
                raise ValueError("packet exceeds inspection bound")
            value = stream.read(size)
            if len(value) != size:
                raise ValueError("truncated protobuf field")
        else:
            raise ValueError("unsupported protobuf wire type")
        yield number, wire, value

def inspect(stream):
    counts = collections.Counter()
    for number, wire, packet in fields(stream):
        if number != 1 or wire != 2:
            continue
        counts["packets"] += 1
        for field, kind, value in fields(io.BytesIO(packet)):
            if kind != 2:
                continue
            if field == 37 and value:
                counts["profile_packets"] += 1
            elif field == 50 and value:
                counts["compressed_packets"] += 1
    return counts

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=pathlib.Path)
    parser.add_argument("--require-heap", action="store_true")
    args = parser.parse_args()
    if zipfile.is_zipfile(args.trace):
        with zipfile.ZipFile(args.trace) as archive:
            traces = [n for n in archive.namelist() if n.endswith((".perfetto-trace", ".trace", ".pftrace"))]
            if len(traces) != 1:
                parser.error("ZIP must contain exactly one Perfetto trace")
            with archive.open(traces[0]) as stream:
                counts = inspect(stream)
    else:
        with args.trace.open("rb") as stream:
            counts = inspect(stream)
    print("packets=%d profile_packets=%d compressed_packets=%d" %
          tuple(counts[k] for k in ("packets", "profile_packets", "compressed_packets")))
    if counts["compressed_packets"]:
        print("Compressed packets not inspected; use Perfetto trace processor to establish heap coverage.")
    if not counts["profile_packets"]:
        print("No uncompressed native heap profile packets observed; allocation-site attribution is not established.")
    else:
        print("Profile packets exist; verify process, sampled stacks and exact-build symbols in Perfetto.")
    return int(args.require_heap and not counts["profile_packets"])

if __name__ == "__main__":
    raise SystemExit(main())
