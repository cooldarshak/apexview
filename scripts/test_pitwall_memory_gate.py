import hashlib
import tempfile
import unittest
from pathlib import Path
from pitwall_memory_gate import evaluate, evaluate_run, FIELDS


class MemoryGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scenario = dict(device="SM-S908E", firmware="firmware", content_id="replay",
            source_profile="1080p+3x540p@50", decoder_mode="SINGLE", protection="CLEAR_HEVC",
            delivery="PHONE", feed_count=4, overlay="BOTH", speed=1, display_policy="FIXED",
            display_hz=100, cache_state="warm", network_profile="wifi", renderer="surface",
            software_drm=False, screen_state="ON", codec="codec", thermal_start=0)

    def evidence(self, name, content):
        data = content.encode()
        (self.root / name).write_bytes(data)
        return {"path": name, "sha256": hashlib.sha256(data).hexdigest()}

    def run_data(self, index, candidate, pss=500, missing=False):
        active_start, active_end, stop = 40000, 640000, 650000
        rows = []
        for tick in range(0, 810000, 10000):
            active = active_start <= tick <= active_end
            f = dict.fromkeys(FIELDS, 0)
            f.update(pssKiB=(pss if active else 100) * 1024,
                     platformGraphicsKiB=(200 if active else 10) * 1024,
                     media3InUseBytes=48*1024*1024 if active else 0,
                     nativeFragmentCapacityBytes=8*1024*1024 if active else 0,
                     sceneGraphicsOwners=int(active),
                     nativeOwnedOutputAllocations=tick//100000,
                     nativeOwnedOutputReuses=tick//10000)
            if missing:
                del f["platformGraphicsKiB"]
            rows.append("GC_PROCESS_MEMORY uptimeMs=%d pid=%d %s" % (
                tick, 42+index, " ".join("%s=%s" % x for x in f.items())))
        name = ("c" if candidate else "b") + str(index)
        return dict(commit=("a" if candidate else "b")*40,
            apk_sha256=("c" if candidate else "d")*64, pid=42+index,
            scenario=dict(self.scenario), thermal_end=0, profiled=False,
            idle_ms=[0,30000], active_ms=[active_start,active_end], terminal_stop_ms=stop,
            quality_and_function_review="PASS",
            memory_log=self.evidence(name+".log", "\n".join(rows)),
            presentation_evidence=self.evidence(name+".csv", "synthetic unit test only"))

    def manifest(self, candidate_pss=500):
        return dict(schema_version=1, pairs=[
            dict(baseline=self.run_data(i,False,530),candidate=self.run_data(i+3,True,candidate_pss))
            for i in range(3)])

    def test_matching_repetitions_and_real_target(self):
        self.assertEqual(0,evaluate(self.manifest(),self.root)["issues"])
        self.assertGreater(evaluate(self.manifest(570),self.root)["issues"],0)

    def test_missing_counter_and_artifact_hash_fail(self):
        with self.assertRaisesRegex(ValueError,"counter unavailable"):
            evaluate_run(self.run_data(0,True,missing=True),self.root)
        run=self.run_data(0,True)
        (self.root/run["memory_log"]["path"]).write_text("changed")
        with self.assertRaisesRegex(ValueError,"digest mismatch"):
            evaluate_run(run,self.root)

    def test_unmatched_reused_and_insufficient_evidence_fail(self):
        manifest=self.manifest()
        manifest["pairs"][0]["candidate"]["scenario"]["source_profile"]="different"
        with self.assertRaisesRegex(ValueError,"Mismatched"):
            evaluate(manifest,self.root)
        manifest=self.manifest()
        manifest["pairs"][1]=manifest["pairs"][0]
        with self.assertRaisesRegex(ValueError,"Reused"):
            evaluate(manifest,self.root)
        manifest=self.manifest()
        manifest["pairs"].pop()
        with self.assertRaisesRegex(ValueError,"three independent"):
            evaluate(manifest,self.root)

    def test_profiled_and_unreviewed_do_not_pass(self):
        run=self.run_data(0,True)
        run["profiled"]=True
        with self.assertRaisesRegex(ValueError,"Profiler"):
            evaluate_run(run,self.root)
        run["profiled"]=False
        run["quality_and_function_review"]="PENDING"
        with self.assertRaisesRegex(ValueError,"review"):
            evaluate_run(run,self.root)
