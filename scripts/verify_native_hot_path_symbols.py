"""Verify the complete NativeHotPath ABI against both signed and full ELF exports."""
import pathlib
import re
import sys

PREFIX = "Java_app_apexview_tiled_NativeHotPath_"

def required(source):
    names = re.findall(r"\bnative\s+\w+(?:\[\])?\s+(native\w+)\s*\(", source)
    if not names or len(set(names)) != len(names):
        raise ValueError("Missing or overloaded NativeHotPath JNI declarations")
    return {PREFIX + name for name in names}

def verify(source, exports):
    symbols = {line.split()[-1] for line in exports.splitlines() if line.split()}
    missing = required(source) - symbols
    if missing:
        raise ValueError("Missing JNI exports: " + ", ".join(sorted(missing)))

if __name__ == "__main__":
    source = pathlib.Path(sys.argv[1]).read_text()
    for filename in sys.argv[2:]:
        try:
            verify(source, pathlib.Path(filename).read_text())
        except ValueError as error:
            sys.exit(f"{filename}: {error}")
    print("NativeHotPath JNI declarations match packaged and full exports")
