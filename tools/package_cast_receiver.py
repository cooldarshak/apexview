#!/usr/bin/env python3
"""Prepare the static CAF receiver for HTTPS hosting. Contains no credentials or media."""
import argparse
import pathlib
import shutil

root = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--output-dir', type=pathlib.Path, required=True)
args = parser.parse_args()
args.output_dir.mkdir(parents=True, exist_ok=True)
for name in ('index.html', 'receiver.mjs', 'cast-scene-controller.mjs'):
    shutil.copyfile(root / 'web' / 'cast' / name, args.output_dir / name)
shutil.copyfile(root / 'app/src/main/assets/cast/receiver-model.mjs', args.output_dir / 'receiver-model.mjs')
(args.output_dir / '.nojekyll').touch()
print(f'CAST_RECEIVER_PACKAGE_READY files=5 destination={args.output_dir}')
