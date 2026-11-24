"""Encrypted, exact-source CI handoff. Not a release or a cache of test results.

GPG AES256 symmetric encryption with integrity protection. A per-stage passphrase
is domain-separated using HMAC-SHA256 keyed by existing high-entropy repository
keystore material; it is passed on stdin, never logged, stored, or sent as an
argument. The signing key/keystore is NOT included in the bundle.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import io
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile

PATTERNS = (
    "app/build/outputs/apk/debug/*.apk",
    "app/build/outputs/apk/benchmark/*.apk",
    "app/build/outputs/apk/androidTest/debug/*.apk",
    "macrobenchmark/build/outputs/apk/**/*.apk",
)
BASELINE = "g0-baseline.apk"
MANIFEST = "g0-stage-manifest.txt"


def derive(secret: str, name: str) -> bytes:
    if len(secret) < 64 or not re.fullmatch(r"g0-encrypted-stage-[0-9]+-[0-9]+", name):
        raise ValueError("missing staging material or invalid stage name")
    return hmac.new(secret.encode(), ("pitwall/g0/apk-stage/v1/" + name).encode(),
                    hashlib.sha256).hexdigest().encode() + b"\n"


def digest(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def allowed(name: str) -> bool:
    p = PurePosixPath(name)
    if p.is_absolute() or ".." in p.parts or str(p) != name:
        return False
    if name == BASELINE:
        return True
    if p.suffix != ".apk" or not re.fullmatch(r"[A-Za-z0-9_./-]+", name):
        return False
    return (
        name.startswith(("app/build/outputs/apk/debug/",
                         "app/build/outputs/apk/benchmark/",
                         "app/build/outputs/apk/androidTest/debug/",
                         "macrobenchmark/build/outputs/apk/"))
    )


def crypt(mode: str, source: Path, dest: Path, password: bytes, home: Path) -> None:
    home.mkdir(mode=0o700)
    cmd = ["gpg", "--homedir", str(home), "--batch", "--yes",
           "--no-symkey-cache", "--pinentry-mode", "loopback", "--passphrase-fd", "0",
           "--output", str(dest)]
    if mode == "pack":
        cmd += ["--symmetric", "--cipher-algo", "AES256", "--compress-algo", "none"]
    else:
        cmd += ["--decrypt"]
    subprocess.run(cmd + [str(source)], input=password, check=True)


def pack(root: Path, baseline: Path, archive: Path) -> None:
    entries = {}
    for pattern in PATTERNS:
        matches = sorted(root.glob(pattern))
        if not matches:
            raise ValueError(f"missing APK output: {pattern}")
        for path in matches:
            if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
                raise ValueError(f"invalid APK output: {path.name}")
            name = path.relative_to(root).as_posix()
            if not allowed(name):
                raise ValueError("invalid APK path")
            entries[name] = path
    if not baseline.is_file() or baseline.stat().st_size == 0:
        raise ValueError("missing baseline APK")
    entries[BASELINE] = baseline
    rows = "".join(f"{digest(path)}  {name}\n" for name,path in sorted(entries.items())).encode()
    with tarfile.open(archive, "w") as tar:
        for name, path in sorted(entries.items()):
            tar.add(path, arcname=name, recursive=False)
        info = tarfile.TarInfo(MANIFEST)
        info.size = len(rows)
        tar.addfile(info, io.BytesIO(rows))


def unpack(root: Path, archive: Path) -> None:
    with tarfile.open(archive, "r") as tar:
        members = tar.getmembers()
        names = [m.name for m in members]
        if len(names) != len(set(names)) or MANIFEST not in names:
            raise ValueError("duplicate or missing archive manifest")
        if any(not m.isfile() or (m.name != MANIFEST and not allowed(m.name)) for m in members):
            raise ValueError("unexpected archive entry")
        if len(members) > 32 or sum(m.size for m in members) > 512 * 1024 * 1024:
            raise ValueError("staging archive exceeds bounded size")
        manifest = tar.extractfile(MANIFEST)
        if manifest is None:
            raise ValueError("missing manifest")
        rows = manifest.read().decode("ascii").splitlines()
        expected = {}
        for row in rows:
            checksum, name = row.split("  ", 1)
            if name in expected or not allowed(name) or not re.fullmatch(r"[0-9a-f]{64}", checksum):
                raise ValueError("invalid manifest")
            expected[name] = checksum
        if set(expected) != set(names) - {MANIFEST} or BASELINE not in expected:
            raise ValueError("manifest and archive disagree")
        for name in expected:
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            payload = tar.extractfile(name)
            if payload is None:
                raise ValueError("missing payload")
            with payload, target.open("xb") as out:
                while chunk := payload.read(1024 * 1024):
                    out.write(chunk)
            if digest(target) != expected[name]:
                raise ValueError("APK digest mismatch")
        for pattern in PATTERNS:
            if not list(root.glob(pattern)):
                raise ValueError("missing required restored output")
        print(f"G0 exact-source staged APK manifest PASS: {len(expected)} APKs")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=("pack","unpack"))
    p.add_argument("name")
    p.add_argument("bundle", type=Path)
    p.add_argument("--baseline", type=Path)
    a = p.parse_args()
    # Remove key material from the subprocess environment once derived.
    password = derive(os.environ.pop("STAGING_KEY_MATERIAL", ""), a.name)
    with tempfile.TemporaryDirectory(prefix="g0-stage-") as tmp:
        base = Path(tmp)
        archive = base / "stage.tar"
        if a.mode == "pack":
            if a.baseline is None:
                raise ValueError("baseline required")
            pack(Path.cwd(), a.baseline, archive)
            crypt("pack", archive, a.bundle, password, base/"gpg")
        else:
            crypt("unpack", a.bundle, archive, password, base/"gpg")
            unpack(Path.cwd(), archive)


if __name__ == "__main__":
    main()
