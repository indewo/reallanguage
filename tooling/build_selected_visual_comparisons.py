from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path("/Users/eric/Documents/英语学习APP")
REFERENCE_DIR = ROOT / "product-showcase/ui-design-final"
CURRENT_DIR = ROOT / ".local-data/visual-pass6"
OUTPUT_DIR = ROOT / ".local-data/visual-pass6-comparisons"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

PAIRS = {
    "email-verification": ("03-email-verification.png", "EMAIL_VERIFICATION.png"),
    "handwriting": ("25-handwriting.png", "HANDWRITING.png"),
    "learning-exit": ("31-learning-exit.png", "LEARNING_EXIT-fixed2.png"),
    "pattern-book": ("42-pattern-book.png", "PATTERN_BOOK.png"),
    "recharge": ("46-recharge.png", "RECHARGE.png"),
    "account": ("58-account.png", "ACCOUNT.png"),
}

CANVAS = (2000, 1220)
CELL = (980, 1100)


def fitted(path: Path) -> Image.Image:
    image = Image.open(path).convert("RGB")
    image.thumbnail(CELL, Image.Resampling.LANCZOS)
    return image


for name, (reference_name, current_name) in PAIRS.items():
    sheet = Image.new("RGB", CANVAS, "#eee9e1")
    draw = ImageDraw.Draw(sheet)
    draw.text((18, 16), f"REFERENCE  |  {reference_name}", fill="#20252b")
    draw.text((1018, 16), f"CURRENT  |  {current_name}", fill="#20252b")
    for column, path in enumerate(
        (REFERENCE_DIR / reference_name, CURRENT_DIR / current_name),
    ):
        image = fitted(path)
        x = column * 1000 + (1000 - image.width) // 2
        y = 70 + (1130 - image.height) // 2
        sheet.paste(image, (x, y))
    draw.line((1000, 0, 1000, CANVAS[1]), fill="#8e877e", width=2)
    sheet.save(OUTPUT_DIR / f"{name}.png")

print(f"comparisons={len(PAIRS)} output={OUTPUT_DIR}")
