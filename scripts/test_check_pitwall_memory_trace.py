import io
import unittest
from check_pitwall_memory_trace import inspect, inspect_heap_quality, heap_quality_ok

def encode(n):
    out = bytearray()
    while n >= 128:
        out.append((n & 127) | 128)
        n >>= 7
    out.append(n)
    return bytes(out)

def field(n, payload):
    return encode((n << 3) | 2) + encode(len(payload)) + payload

def scalar(n, value):
    return encode(n << 3) + encode(value)


def snapshot(pid, timestamp, error=b""):
    dump = scalar(1, pid) + scalar(9, timestamp) + field(2, scalar(1, 7)) + error
    return field(1, field(37, field(5, dump)))


class MemoryTraceTests(unittest.TestCase):
    def test_strict_heap_rejects_build_901_overrun_and_wrong_pid(self):
        bad = snapshot(14022, 100, scalar(6, 1) + scalar(7, 1) + scalar(14, 1))
        quality = inspect_heap_quality(io.BytesIO(bad), 14022)
        self.assertEqual(quality["buffer_overrun"], 1)
        self.assertFalse(heap_quality_ok(quality, 1))
        self.assertFalse(heap_quality_ok(inspect_heap_quality(io.BytesIO(bad), 42), 1))

    def test_strict_heap_counts_distinct_snapshots_not_packet_continuations(self):
        first = snapshot(123, 100)
        second = snapshot(123, 200)
        quality = inspect_heap_quality(io.BytesIO(first + first + second), 123)
        self.assertEqual(quality["snapshots"], 2)
        self.assertTrue(heap_quality_ok(quality, 2))
        self.assertFalse(heap_quality_ok(quality, 3))

    def test_system_only_is_not_heap_evidence(self):
        counts = inspect(io.BytesIO(field(1, field(2, b"system"))))
        self.assertEqual(counts["packets"], 1)
        self.assertEqual(counts["profile_packets"], 0)

    def test_heap_and_compressed_are_distinguished(self):
        counts = inspect(io.BytesIO(field(1, field(37, b"profile") + field(50, b"compressed"))))
        self.assertEqual(counts["profile_packets"], 1)
        self.assertEqual(counts["compressed_packets"], 1)

    def test_truncation_does_not_claim_valid_evidence(self):
        with self.assertRaises(ValueError):
            inspect(io.BytesIO(b"\x0a\x08short"))

if __name__ == "__main__":
    unittest.main()
