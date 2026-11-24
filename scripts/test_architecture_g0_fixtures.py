"""Deterministic synthetic clear sample sizing fixtures; not decodable HEVC."""
import hashlib
import pathlib
import sys
import tempfile
import unittest

def generate(directory):
    directory = pathlib.Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    rows = []
    for prefix, count, base in ((1,64,80),(2,128,2048),(3,256,4096)):
        payloads = [bytes([2,1])+bytes([(i*31+7)%256])*(base+i%31-2) for i in range(count)]
        sample = b"".join(len(n).to_bytes(prefix,"big")+n for n in payloads)
        filename = f"sample{prefix}.bin"
        (directory/filename).write_bytes(sample)
        rows.append(f"{filename} {prefix} {sum(len(n)+4 for n in payloads)} {hashlib.sha256(sample).hexdigest()}")
    (directory/"manifest.txt").write_text("\n".join(rows)+"\n",encoding="ascii")

class FixtureTests(unittest.TestCase):
    def test_determinism_and_manifest(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            generate(first); generate(second)
            for row in (pathlib.Path(first)/"manifest.txt").read_text().splitlines():
                name,prefix,expected,digest=row.split()
                a=(pathlib.Path(first)/name).read_bytes()
                self.assertEqual(a,(pathlib.Path(second)/name).read_bytes())
                self.assertEqual(hashlib.sha256(a).hexdigest(),digest)
                pos=total=0
                while pos<len(a):
                    p=int(prefix);n=int.from_bytes(a[pos:pos+p],"big")
                    self.assertGreaterEqual(n,2)
                    pos+=p+n;total+=4+n
                self.assertEqual(pos,len(a))
                self.assertEqual(total,int(expected))

if __name__=="__main__":
    if len(sys.argv)==3 and sys.argv[1]=="--generate":
        generate(sys.argv[2])
    else:
        unittest.main()
