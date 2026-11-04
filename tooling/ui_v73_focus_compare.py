#!/usr/bin/env python3
"""Pair source and native captures at original pixel size for region-level review."""
from pathlib import Path
import argparse
import json
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--folder', type=Path, required=True)
parser.add_argument('--screens', required=True)
args = parser.parse_args()
records = {x['destination']: x for x in json.loads(
    (ROOT / '.local-data/ui-v73/manifest.json').read_text())['screens']}
output = args.folder.parent / ('focus-' + args.folder.name)
output.mkdir(exist_ok=True)
for state in args.screens.split(','):
    reference = Image.open(ROOT / records[state]['reference']).convert('RGB')
    actual = Image.open(args.folder / (state + '.png')).convert('RGB')
    if reference.size != actual.size:
        raise ValueError(f'{state}: mismatched viewport; capture again, do not resize')
    w, h = reference.size
    for name, box in {
        'full': (0, 0, w, h),
        'top-left': (0, 0, w//2, h//2),
        'top-right': (w//2, 0, w, h//2),
        'bottom-left': (0, h//2, w//2, h),
        'bottom-right': (w//2, h//2, w, h),
    }.items():
        crops = [reference.crop(box), actual.crop(box)]
        cw, ch = crops[0].size
        board = Image.new('RGB', (cw*2, ch+28), '#e3dfd5')
        draw = ImageDraw.Draw(board)
        for index, (label, crop) in enumerate(zip(['DESIGN', 'NATIVE'], crops)):
            draw.text((index*cw+8, 6), f'{state} / {name} / {label} / 1:1 pixels', fill='#142533')
            board.paste(crop, (index*cw, 28))
        board.save(output / f'{state}-{name}.png')
print(output)
