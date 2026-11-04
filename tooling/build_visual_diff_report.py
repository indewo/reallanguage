from __future__ import annotations

import argparse
import csv
from pathlib import Path

from PIL import Image, ImageChops, ImageEnhance, ImageOps, ImageStat


DESTINATIONS = """
REGISTRATION AGE_INELIGIBLE EMAIL_VERIFICATION SETTINGS_ONE SETTINGS_TWO SETTINGS_THREE HOME
DIARY_INPUT DIARY_PROCESSING DIARY_PROCESSING_FAILED SAFETY_CHECK SAFETY_HANDLING REVIEW_PENDING
REVIEW_APPROVED REVIEW_NOT_APPROVED FOCUS_SELECTION CHAPTER_FORMATION CHAPTER_FORMATION_FAILED
ENGLISH_CONFIRMATION LEARNING_TARGETS FINAL_FIX CHAPTER_LEARNING EXPLANATION_READING READ_SPEAK
HANDWRITING AUDIO_DICTATION FULL_SENTENCE_SPEAKING PATTERN_SPEAKING COMPLETE_WRITING SCENE_RESPONSE
LEARNING_EXIT CHAPTER_RESULT CHAPTER_SELECTION CHAPTER_ABANDON COMPLETED_STEP_REVIEW TODAY_REVIEW
REVIEW_CHAPTER REVIEW_EXIT REVIEW_RESULT VOCABULARY_BOOK VOCABULARY_DETAIL PATTERN_BOOK PATTERN_DETAIL
PATTERN_REMOVAL AI_BILLING RECHARGE INSUFFICIENT_BALANCE REFUND CONNECTION_ERROR SERVICE_FAILURE
AGREEMENT_DETAIL PASSWORD_RESET SUBSEQUENT_SETTINGS SETTINGS_EFFECT_SCOPE DAILY_REMINDER_INTRO
INCOMPLETE_LEARNING MESSAGES ACCOUNT RECORDING_INTRO ACTIVE_RECORDING RECORDING_CONFIRMATION
RECORDING_DELETION RECORDING_TRANSCRIPTION TRANSCRIPT_RESULT TRANSCRIPTION_FAILURE MICROPHONE_PERMISSION
SCENE_HOME SCENE_TYPE SCENE_QUESTIONS SCENE_ABANDON SCENE_GENERATION SCENE_CHINESE_REVIEW
""".split()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--references", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--only",
        choices=DESTINATIONS,
        help="Build the comparison for one preview destination instead of all 72.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    references = sorted(args.references.glob("*.png"))
    if len(references) != 72 or len(DESTINATIONS) != 72:
        raise RuntimeError("Expected exactly 72 reference and destination states")

    args.output.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, str]] = []
    indexed_pairs = list(enumerate(zip(references, DESTINATIONS, strict=True), start=1))
    if args.only:
        indexed_pairs = [pair for pair in indexed_pairs if pair[1][1] == args.only]
    for index, (reference_path, destination) in indexed_pairs:
        current_path = args.current / f"{destination}.png"
        reference = Image.open(reference_path).convert("RGB")
        current = Image.open(current_path).convert("RGB")
        normalized = ImageOps.fit(
            reference,
            current.size,
            method=Image.Resampling.LANCZOS,
            centering=(0.5, 0.5),
        )
        difference = ImageChops.difference(normalized, current)
        mean = ImageStat.Stat(difference).mean
        mean_absolute_error = sum(mean) / (len(mean) * 255)

        stem = f"{index:02d}-{destination.lower()}"
        Image.blend(normalized, current, 0.5).save(args.output / f"{stem}-overlay.png")
        ImageEnhance.Contrast(difference).enhance(2.5).save(
            args.output / f"{stem}-difference.png",
        )
        rows.append(
            {
                "index": str(index),
                "destination": destination,
                "reference": str(reference_path),
                "current": str(current_path),
                "reference_pixels": f"{reference.width}x{reference.height}",
                "current_pixels": f"{current.width}x{current.height}",
                "mean_absolute_error": f"{mean_absolute_error:.6f}",
            },
        )

    with (args.output / "metrics.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)

    print(f"diffs={len(rows)} output={args.output}")


if __name__ == "__main__":
    main()
