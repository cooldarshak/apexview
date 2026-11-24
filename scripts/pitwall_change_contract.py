"""Fail-closed coordinated-change policy for the Pitwall debug workflow.

This is a pre-build integrity check, not a substitute for Kotlin compilation,
scenario tests, code review, real devices, DRM entitlement, or release gates.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from collections.abc import Iterable

PLAN = "docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md"


def is_production(path: str) -> bool:
    """Changes that require a matching plan entry and automated regression."""
    return (
        path.startswith(("app/src/main/", "web/cast/", "app/src/debug/"))
        or path.startswith(("gradle/", ".github/workflows/"))
        or path in {
            "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
            "gradle.properties", "scripts/pitwall_change_contract.py",
        }
        or (path.startswith(("tools/", "scripts/")) and not (
            Path(path).name.startswith("test_")
            or Path(path).name.endswith("_test.py")
        ))
    )


def is_test(path: str) -> bool:
    name = Path(path).name
    return (
        path.startswith(("app/src/test/", "app/src/androidTest/"))
        or (path.startswith(("scripts/", "tools/", "web/"))
            and (name.startswith("test_") or name.endswith("_test.py")
                 or name.endswith(".test.mjs")))
    )


def validate_change_set(paths: Iterable[str]) -> list[str]:
    """Require a coordinated plan+test for every production-affecting commit.

    A file-count/line-count threshold would reward artificial code churn. This
    contract checks cross-file accountability instead of measuring patch size.
    """
    changed = set(paths)
    production = sorted(p for p in changed if is_production(p))
    if not production:
        return []
    issues: list[str] = []
    if PLAN not in changed:
        issues.append(f"Production changed without updating the sole consolidated plan: {PLAN}")
    if not any(is_test(p) for p in changed):
        issues.append("Production changed without a changed automated regression test")
    return issues


def changed_from_parent() -> list[str]:
    p = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMRD", "HEAD^", "HEAD"],
        capture_output=True, text=True, check=False,
    )
    if p.returncode != 0:
        raise RuntimeError("Git parent diff unavailable; checkout must use fetch-depth >= 2")
    return [v.strip() for v in p.stdout.splitlines() if v.strip()]


def main() -> int:
    try:
        paths = changed_from_parent()
        issues = validate_change_set(paths)
    except (OSError, RuntimeError) as exc:
        print(f"PITWALL_CHANGE_CONTRACT=FAIL: {exc}", file=sys.stderr)
        return 1
    if issues:
        print("PITWALL_CHANGE_CONTRACT=FAIL", file=sys.stderr)
        for issue in issues:
            print(f"  - {issue}", file=sys.stderr)
        return 1
    print(f"PITWALL_CHANGE_CONTRACT=PASS changed_files={len(paths)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
