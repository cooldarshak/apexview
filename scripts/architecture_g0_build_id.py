"""Fail closed on absent, malformed or mismatched native GNU build IDs."""
import argparse
import pathlib
import re
import sys


def build_id(notes: str) -> str:
    # Both GNU and LLVM readelf -n use this label. Require exactly one
    # nonzero SHA-1 ID, as requested by the Android cdylib build script.
    labels = re.findall(r"^\s*Build ID:[ \t]*([^\r\n]*)", notes, re.MULTILINE)
    if len(labels) != 1:
        raise ValueError(f"expected one GNU build ID, found {len(labels)}")
    value = labels[0].strip().lower()
    if not re.fullmatch(r"[0-9a-f]{40}", value) or value == "0" * 40:
        raise ValueError("missing or malformed SHA-1 build ID")
    return value


def verify(notes: str, packaged: str | None = None) -> str:
    original = build_id(notes)
    if packaged is not None:
        actual = build_id(packaged)
        if actual != original:
            raise ValueError(f"packaged build ID {actual} differs from unstripped {original}")
    return original


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("unstripped", type=pathlib.Path)
    parser.add_argument("packaged", nargs="?", type=pathlib.Path)
    args = parser.parse_args()
    try:
        result = verify(
            args.unstripped.read_text(encoding="utf-8"),
            args.packaged.read_text(encoding="utf-8") if args.packaged else None,
        )
    except (OSError, ValueError) as error:
        print(f"G0 BUILD ID FAIL ({args.unstripped}, {args.packaged}): {error}", file=sys.stderr)
        return 1
    print(f"Build ID: {result}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
