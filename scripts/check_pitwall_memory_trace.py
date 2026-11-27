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

def inspect_heap_quality(stream, expected_pid):
    """Inspect uncompressed heapprofd snapshots, not symbol or stack quality."""
    counts = collections.Counter()
    snapshots = set()
    profile_indexes = {}
    continued = {}
    for number, wire, packet in fields(stream):
        if number != 1 or wire != 2:
            continue
        packet_fields = list(fields(io.BytesIO(packet)))
        sequence = next((val for tag, typ, val in packet_fields
                         if tag == 10 and typ == 0), 0)
        for field, kind, value in packet_fields:
            if kind != 2:
                continue
            if field == 50 and value:
                counts["compressed_packets"] += 1
            if field != 37:
                continue
            profile_fields = list(fields(io.BytesIO(value)))
            # ProfilePacket index is per trusted_packet_sequence_id. A gap
            # means allocation-site samples or their metadata may be lost.
            index = next((val for tag, typ, val in profile_fields
                          if tag == 7 and typ == 0), 0)
            more = next((val for tag, typ, val in profile_fields
                         if tag == 6 and typ == 0), 0)
            if not sequence:
                counts["missing_profile_sequence"] += 1
            elif sequence in profile_indexes and index != profile_indexes[sequence] + 1:
                counts["profile_index_gaps"] += 1
            profile_indexes[sequence] = index
            continued[sequence] = bool(more)
            for tag, typ, dump in profile_fields:
                if tag != 5 or typ != 2:
                    continue
                scalar = {}
                samples = 0
                for key, wire_type, val in fields(io.BytesIO(dump)):
                    if wire_type == 0:
                        scalar[key] = val
                    elif key == 2 and wire_type == 2:
                        samples += 1
                pid = scalar.get(1, 0)
                if pid != expected_pid:
                    continue
                counts["dumps"] += 1
                counts["samples"] += samples
                if samples and scalar.get(9, 0):
                    snapshots.add((pid, scalar[9]))
                for key, name in ((4, "rejected_concurrent"), (6, "disconnected"),
                                  (7, "buffer_overrun"), (8, "buffer_corrupted"),
                                  (10, "guardrail"), (14, "client_error")):
                    if scalar.get(key, 0):
                        counts[name] += 1
    counts["snapshots"] = len(snapshots)
    counts["unfinished_profile_sequences"] = sum(continued.values())
    return counts


def heap_quality_ok(counts, minimum_snapshots):
    return (counts["snapshots"] >= minimum_snapshots and counts["samples"] > 0
            and not any(counts[k] for k in ("compressed_packets",
                "rejected_concurrent", "disconnected", "buffer_overrun",
                "buffer_corrupted", "guardrail", "client_error",
                "missing_profile_sequence", "profile_index_gaps",
                "unfinished_profile_sequences")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=pathlib.Path)
    parser.add_argument("--require-heap", action="store_true")
    parser.add_argument("--strict-heap", action="store_true",
                        help="Reject interrupted or insufficient native heap captures")
    parser.add_argument("--pid", type=int)
    parser.add_argument("--min-snapshots", type=int, default=2)
    args = parser.parse_args()
    if args.min_snapshots < 1 or (args.pid is not None and args.pid <= 0):
        parser.error("positive PID and snapshot minimum required")
    if args.strict_heap and args.pid is None:
        parser.error("--strict-heap requires --pid")
    quality = collections.Counter()
    if zipfile.is_zipfile(args.trace):
        with zipfile.ZipFile(args.trace) as archive:
            traces = [n for n in archive.namelist() if n.endswith((".perfetto-trace", ".trace", ".pftrace"))]
            if len(traces) != 1:
                parser.error("ZIP must contain exactly one Perfetto trace")
            with archive.open(traces[0]) as stream:
                counts = inspect(stream)
            if args.strict_heap:
                with archive.open(traces[0]) as stream:
                    quality = inspect_heap_quality(stream, args.pid)
    else:
        with args.trace.open("rb") as stream:
            counts = inspect(stream)
        if args.strict_heap:
            with args.trace.open("rb") as stream:
                quality = inspect_heap_quality(stream, args.pid)
    print("packets=%d profile_packets=%d compressed_packets=%d" %
          tuple(counts[k] for k in ("packets", "profile_packets", "compressed_packets")))
    if counts["compressed_packets"]:
        print("Compressed packets not inspected; use Perfetto trace processor to establish heap coverage.")
    if not counts["profile_packets"]:
        print("No uncompressed native heap profile packets observed; allocation-site attribution is not established.")
    else:
        print("Profile packets exist; verify process, sampled stacks and exact-build symbols in Perfetto.")
    if args.strict_heap:
        print("heap_quality=" + " ".join(f"{k}={v}" for k, v in sorted(quality.items())))
        if not heap_quality_ok(quality, args.min_snapshots):
            print("Heap capture is incomplete or unverified; do not compare allocation sites.")
            return 1
    return int(args.require_heap and not counts["profile_packets"])

if __name__ == "__main__":
    raise SystemExit(main())
