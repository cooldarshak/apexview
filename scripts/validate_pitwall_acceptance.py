#!/usr/bin/env python3
"""Verify documented device acceptance before the official APK is signed.

CI runs this against a CLOSED owner-reviewed GitHub issue with the
pitwall-hardware-accepted label. No claim is made that parsing a link
itself proves playback. Real traces and video evidence need human review.
"""
import argparse
import json
import re
import sys
from urllib.parse import urlparse

GATES = tuple(
    [f"P0.{i}" for i in range(1, 5)]
    + [f"P1.{i}" for i in range(1, 4)]
    + [f"P2.{i}" for i in range(1, 10)]
)
REQUIRED_PATHS = (
    "Galaxy S22 Ultra", "TCL 55C655", "Widevine L1",
    "Widevine L3", "clear HEVC", "SINGLE", "MULTIPLE",
    "Pitwall Cast", "closed captions", "Material 3",
)
GATE_LINE = re.compile(
    r"^GATE\s+(P[012]\.\d+):\s*(PASS|FAIL|PENDING)"
    r"\s*\|\s*evidence:\s*(\S+)\s*$",
    re.IGNORECASE | re.MULTILINE,
)


def validate(body: str, accepted_sha: str, accepted_run: str) -> None:
    if not re.fullmatch(r"[0-9a-fA-F]{40}", accepted_sha):
        raise ValueError("Invalid accepted commit SHA")
    if not re.fullmatch(r"[0-9]+", str(accepted_run)):
        raise ValueError("Invalid accepted debug run")
    if not re.search(
        r"^Accepted commit:\s*" + re.escape(accepted_sha) + r"\s*$",
        body, re.IGNORECASE | re.MULTILINE,
    ):
        raise ValueError("Acceptance issue does not bind the accepted source commit")
    if not re.search(
        r"^Accepted debug run:\s*" + re.escape(str(accepted_run)) + r"\s*$",
        body, re.IGNORECASE | re.MULTILINE,
    ):
        raise ValueError("Acceptance issue does not bind the successful debug workflow")
    lower = body.casefold()
    for required in REQUIRED_PATHS:
        if required.casefold() not in lower:
            raise ValueError(f"Device/playback matrix is missing {required}")
    rows = [line.strip() for line in body.splitlines()
            if re.match(r"^\s*GATE\b", line, re.IGNORECASE)]
    if len(rows) != len(GATES):
        raise ValueError(f"Expected 16 individually documented gates, found {len(rows)}")
    found = {}
    for line in rows:
        match = GATE_LINE.fullmatch(line)
        if not match:
            raise ValueError("Gate line must include explicit PASS and HTTPS evidence")
        gate, result, link = match.groups()
        gate = gate.upper()
        if gate not in GATES or gate in found:
            raise ValueError(f"Unknown or duplicate acceptance gate {gate}")
        if result.upper() != "PASS":
            raise ValueError(f"Acceptance gate {gate} is not PASS")
        url = urlparse(link)
        if (url.scheme != "https" or not url.hostname or
            url.hostname in ("localhost", "127.0.0.1", "0.0.0.0") or
            url.username is not None or url.password is not None or
            not url.path.strip("/") or "example.com" in url.hostname):
            raise ValueError(f"Missing verifiable HTTPS evidence for {gate}")
        found[gate] = link
    missing = set(GATES) - found.keys()
    if missing:
        raise ValueError("Missing gates: " + ", ".join(sorted(missing)))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sha", required=True)
    parser.add_argument("--run", required=True)
    args = parser.parse_args()
    issue = json.load(sys.stdin)
    try:
        validate(issue.get("body") or "", args.sha, args.run)
    except (ValueError, TypeError) as error:
        print(f"::error::Pitwall hardware acceptance rejected: {error}", file=sys.stderr)
        return 1
    print("16/16 gates have explicit evidence references for the accepted source.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
