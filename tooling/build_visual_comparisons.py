from pathlib import Path
import os
from PIL import Image, ImageDraw

ROOT = Path("/Users/eric/Documents/英语学习APP")
REFERENCES = sorted((ROOT / "product-showcase/ui-design-final").glob("*.png"))
CURRENT = Path(os.environ.get("RL_CURRENT_DIR", ROOT / ".local-data/visual-qa-current"))
OUTPUT = Path(os.environ.get("RL_COMPARISON_DIR", ROOT / ".local-data/visual-qa-comparisons"))
OUTPUT.mkdir(parents=True, exist_ok=True)

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

if len(REFERENCES) != 72 or len(DESTINATIONS) != 72:
    raise RuntimeError("Expected exactly 72 reference/current states")

CELL_WIDTH = 840
CELL_HEIGHT = 390
LABEL_HEIGHT = 34
ROWS_PER_SHEET = 6


def fit(image: Image.Image) -> Image.Image:
    copy = image.convert("RGB")
    copy.thumbnail((CELL_WIDTH, CELL_HEIGHT - LABEL_HEIGHT), Image.Resampling.LANCZOS)
    return copy


for sheet_index in range(0, 72, ROWS_PER_SHEET):
    rows = min(ROWS_PER_SHEET, 72 - sheet_index)
    sheet = Image.new("RGB", (CELL_WIDTH * 2, CELL_HEIGHT * rows), "#eee9e1")
    draw = ImageDraw.Draw(sheet)
    for row in range(rows):
        index = sheet_index + row
        pair = [REFERENCES[index], CURRENT / f"{DESTINATIONS[index]}.png"]
        labels = [f"REFERENCE {index + 1:02d} {REFERENCES[index].stem}", f"CURRENT {DESTINATIONS[index]}"]
        for column, (path, label) in enumerate(zip(pair, labels, strict=True)):
            image = fit(Image.open(path))
            x = column * CELL_WIDTH + (CELL_WIDTH - image.width) // 2
            y = row * CELL_HEIGHT + LABEL_HEIGHT + (CELL_HEIGHT - LABEL_HEIGHT - image.height) // 2
            sheet.paste(image, (x, y))
            draw.text((column * CELL_WIDTH + 12, row * CELL_HEIGHT + 9), label, fill="#20252b")
        draw.line((0, (row + 1) * CELL_HEIGHT - 1, CELL_WIDTH * 2, (row + 1) * CELL_HEIGHT - 1), fill="#a8a098")
        draw.line((CELL_WIDTH, row * CELL_HEIGHT, CELL_WIDTH, (row + 1) * CELL_HEIGHT), fill="#a8a098")
    sheet.save(OUTPUT / f"comparison-{sheet_index // ROWS_PER_SHEET + 1:02d}.png")

print(f"comparisons={len(list(OUTPUT.glob('comparison-*.png')))} output={OUTPUT}")
