"""Diagnostic contact sheets, never an edited/mock implementation screenshot."""
from argparse import ArgumentParser
from pathlib import Path
from PIL import Image, ImageDraw, ImageChops, ImageStat
import json

parser = ArgumentParser()
parser.add_argument("--reference", type=Path, required=True)
parser.add_argument("--actual", type=Path, required=True)
parser.add_argument("--out", type=Path, required=True)
args = parser.parse_args()
reference = Image.open(args.reference).convert("RGB")
actual = Image.open(args.actual).convert("RGB")
if reference.size != actual.size:
    raise ValueError(f"Capture at reference size; do not stretch: {reference.size} != {actual.size}")
args.out.mkdir(parents=True, exist_ok=True)
regions = {
    "full": (0, 0, *reference.size),
    "header": (0, 0, 1846, 115),
    "alphabet": (0, 110, 180, 852),
    "word-paper": (150, 110, 765, 600),
    "meanings-status": (730, 145, 1846, 610),
}
for name, rect in regions.items():
    a, b = reference.crop(rect), actual.crop(rect)
    horizontal = name == "alphabet"
    width = a.width * (2 if horizontal else 1)
    height = (a.height + 28) * (1 if horizontal else 2)
    sheet = Image.new("RGB", (width, height), "#e9e6df")
    draw = ImageDraw.Draw(sheet)
    for i, (label, frame) in enumerate((("REFERENCE", a), ("ANDROID - REAL RENDER", b))):
        x, y = (i * a.width, 0) if horizontal else (0, i * (a.height + 28))
        draw.text((x + 12, y + 8), label, fill="#152434")
        sheet.paste(frame, (x, y + 28))
    sheet.save(args.out / f"{name}.png")
diff = ImageChops.difference(reference, actual)
diff.save(args.out / "absolute-pixel-difference.png")
stats = {
    "reference": str(args.reference), "actual": str(args.actual),
    "pixels": reference.size, "resized": False,
    "mean_absolute_rgb_difference": ImageStat.Stat(diff).mean,
    "interpretation": "Diagnostic only: real text, full A-Z, icon-only pronunciation and non-overlapping ribbon intentionally differ from reference. This is not a zero-difference claim.",
}
(args.out / "comparison.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2))
print(json.dumps(stats, ensure_ascii=False))
