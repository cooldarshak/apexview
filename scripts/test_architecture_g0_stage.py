import pathlib
import tempfile
import unittest
import shutil
from architecture_g0_stage import derive, pack, unpack, crypt, allowed

class StagingTest(unittest.TestCase):
    def test_exact_source_roundtrip_and_integrity(self):
        with tempfile.TemporaryDirectory() as temp:
            base=pathlib.Path(temp); src=base/"src"; dst=base/"dst"; src.mkdir(); dst.mkdir()
            entries=("app/build/outputs/apk/debug/app-debug.apk",
                "app/build/outputs/apk/benchmark/app-benchmark.apk",
                "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
                "macrobenchmark/build/outputs/apk/benchmark/bench.apk")
            for i, name in enumerate(entries):
                f=src/name; f.parent.mkdir(parents=True,exist_ok=True); f.write_bytes(bytes([i])*32)
            baseline=base/"old.apk"; baseline.write_bytes(b"baseline")
            archive=base/"stage.tar"
            pack(src,baseline,archive)
            unpack(dst,archive)
            for name in entries:
                self.assertEqual((src/name).read_bytes(),(dst/name).read_bytes())
            self.assertEqual((dst/"g0-baseline.apk").read_bytes(),baseline.read_bytes())
            self.assertFalse(allowed("../keystore.properties"))
            self.assertFalse(allowed("app/build/outputs/apk/debug/../../secret.apk"))
            self.assertFalse(allowed("app/build/outputs/apk/debug/private.p12"))

    @unittest.skipUnless(shutil.which("gpg"),"GPG not available outside CI")
    def test_encrypted_handoff_and_wrong_key(self):
        import subprocess
        with tempfile.TemporaryDirectory() as temp:
            b=pathlib.Path(temp); src=b/"src"; src.write_bytes(b"test only")
            password=derive("a"*128,"g0-encrypted-stage-123-1")
            other=derive("a"*128,"g0-encrypted-stage-123-2")
            self.assertNotEqual(password,other)
            enc=b/"enc"; dec=b/"dec"
            crypt("pack",src,enc,password,b/"g1")
            self.assertNotEqual(enc.read_bytes(),src.read_bytes())
            crypt("unpack",enc,dec,password,b/"g2")
            self.assertEqual(src.read_bytes(),dec.read_bytes())
            with self.assertRaises(subprocess.CalledProcessError):
                crypt("unpack",enc,b/"wrong",other,b/"g3")
