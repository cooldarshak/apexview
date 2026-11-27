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


def snapshot(pid, timestamp, error=b"", index=0, continued=False, sequence=1):
    dump = scalar(1, pid) + scalar(9, timestamp) + field(2, scalar(1, 7)) + error
    profile = field(5, dump) + scalar(7, index) + scalar(6, int(continued))
    return field(1, scalar(10, sequence) + field(37, profile))


class MemoryTraceTests(unittest.TestCase):
    def test_strict_heap_rejects_build_901_overrun_and_wrong_pid(self):
        bad = snapshot(14022, 100, scalar(6, 1) + scalar(7, 1) + scalar(14, 1))
        quality = inspect_heap_quality(io.BytesIO(bad), 14022)
        self.assertEqual(quality["buffer_overrun"], 1)
        self.assertFalse(heap_quality_ok(quality, 1))
        self.assertFalse(heap_quality_ok(inspect_heap_quality(io.BytesIO(bad), 42), 1))

    def test_strict_heap_counts_distinct_snapshots_not_packet_continuations(self):
        first = snapshot(123, 100, index=0, continued=True)
        continuation = snapshot(123, 100, index=1)
        second = snapshot(123, 200, index=2)
        quality = inspect_heap_quality(io.BytesIO(first + continuation + second), 123)
        self.assertEqual(quality["snapshots"], 2)
        self.assertTrue(heap_quality_ok(quality, 2))
        self.assertFalse(heap_quality_ok(quality, 3))

    def test_strict_heap_rejects_missing_profile_packet_and_unfinished_continuation(self):
        first = snapshot(123, 100, index=0, continued=True)
        skipped = snapshot(123, 200, index=2)
        quality = inspect_heap_quality(io.BytesIO(first + skipped), 123)
        self.assertEqual(quality["profile_index_gaps"], 1)
        self.assertFalse(heap_quality_ok(quality, 2))
        unfinished = inspect_heap_quality(io.BytesIO(first), 123)
        self.assertEqual(unfinished["unfinished_profile_sequences"], 1)
        self.assertFalse(heap_quality_ok(unfinished, 1))
        unknown = inspect_heap_quality(io.BytesIO(snapshot(123, 100, sequence=0)), 123)
        self.assertFalse(heap_quality_ok(unknown, 1))

    def test_independent_profile_sequences_can_have_independent_indexes(self):
        a = snapshot(123, 100, index=0, sequence=1)
        b = snapshot(123, 200, index=0, sequence=2)
        self.assertTrue(heap_quality_ok(inspect_heap_quality(io.BytesIO(a + b), 123), 2))

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
