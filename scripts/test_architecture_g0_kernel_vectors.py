"""Deterministic shared diagnostic vectors; no media credentials or encrypted payloads."""
import pathlib
import sys
import tempfile
import unittest

ROWS = (
    "bits fixed8 a6 8 166 8",
    "bits fixed32 89abcdef 32 2309737967 32",
    "bits ue0 80 0 0 1",
    "bits ue5 30 0 5 5",
    "bits ue30 0f80 0 30 9",
    "boxes ordinary 0000000866726565000000086d646174 2",
    "boxes extended 00000001667265650000000000000010000000006d646174 2",
    "meta separate 4 2 16,32 6:16,0:32",
    "meta longclear 4 70000 16,32 65535:0,4469:16,0:32",
    "meta onebyte 1 2 16 3:16",
)

def generate(path):
    pathlib.Path(path).write_text("\n".join(ROWS)+"\n", encoding="ascii")

class Vectors(unittest.TestCase):
    def test_vectors_are_deterministic_and_cover_expected_work(self):
        with tempfile.TemporaryDirectory() as d:
            p=pathlib.Path(d)/"vectors.txt"
            generate(p)
            self.assertEqual(p.read_text().splitlines(),list(ROWS))
        self.assertEqual(len({row.split()[1] for row in ROWS}),len(ROWS))
        for row in ROWS:
            f=row.split()
            if f[0]=="bits":
                bits="".join(f"{v:08b}" for v in bytes.fromhex(f[2]))
                n=int(f[3])
                if n:
                    self.assertEqual(int(bits[:n],2),int(f[4]))
                    self.assertEqual(n,int(f[5]))
                else:
                    zeros=bits.index("1")
                    self.assertEqual(int(bits[zeros:2*zeros+1],2)-1,int(f[4]))
                    self.assertEqual(2*zeros+1,int(f[5]))
            if f[0]=="meta":
                spans=list(map(int,f[4].split(",")))
                mapped=[tuple(map(int,p.split(":"))) for p in f[5].split(",")]
                self.assertEqual([e for _,e in mapped if e],spans)
                self.assertEqual(sum(c for c,_ in mapped),int(f[2])+int(f[3]))

if __name__=="__main__":
    if len(sys.argv)==3 and sys.argv[1]=="--generate":
        generate(sys.argv[2])
    else:
        unittest.main()
