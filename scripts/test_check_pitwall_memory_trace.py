import io
import unittest
from check_pitwall_memory_trace import inspect

def encode(n):
    out = bytearray()
    while n >= 128:
        out.append((n & 127) | 128)
        n >>= 7
    out.append(n)
    return bytes(out)

def field(n, payload):
    return encode((n << 3) | 2) + encode(len(payload)) + payload

class MemoryTraceTests(unittest.TestCase):
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
