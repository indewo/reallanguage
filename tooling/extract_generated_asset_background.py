from __future__ import annotations

import argparse
from collections import deque
from pathlib import Path

from PIL import Image


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()

    image = Image.open(args.source).convert("RGBA")
    pixels = image.load()
    width, height = image.size
    seen = bytearray(width * height)
    background = bytearray(width * height)
    queue: deque[tuple[int, int]] = deque()

    def enqueue(x: int, y: int) -> None:
        index = y * width + x
        if not seen[index]:
            seen[index] = 1
            queue.append((x, y))

    for x in range(width):
        enqueue(x, 0)
        enqueue(x, height - 1)
    for y in range(1, height - 1):
        enqueue(0, y)
        enqueue(width - 1, y)

    while queue:
        x, y = queue.popleft()
        red, green, blue, _ = pixels[x, y]
        if min(red, green, blue) < 225 or max(red, green, blue) - min(red, green, blue) > 12:
            continue
        background[y * width + x] = 1
        enqueue(x - 1, y) if x > 0 else None
        enqueue(x + 1, y) if x + 1 < width else None
        enqueue(x, y - 1) if y > 0 else None
        enqueue(x, y + 1) if y + 1 < height else None

    for y in range(height):
        for x in range(width):
            if background[y * width + x]:
                red, green, blue, _ = pixels[x, y]
                pixels[x, y] = (red, green, blue, 0)

    image.save(args.destination)


if __name__ == "__main__":
    main()
