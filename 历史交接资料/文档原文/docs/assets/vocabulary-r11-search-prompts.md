# Vocabulary R11 search paper and underline asset provenance

Mode for both assets: built-in `image_gen` tool, new image generation, transparent-background PNG. No fallback CLI was used. The generated source files were copied without raster edits into the Android project.

## Search paper background

- Project path: `/Users/eric/Documents/英语学习APP/apps/android/app/src/main/res/drawable-nodpi/rl_vocabulary_search_paper_r11.png`
- Generated source path: `/Users/eric/.codex/generated_images/01a0b599-6d38-7e72-95ea-6ec312f04675/exec-c8a8f10a-b090-45af-af65-5562435751d0.png`
- Source dimensions: 1888 × 833 pixels, RGBA with transparency.
- Main content bounds at alpha > 32: `(20, 307)–(1869, 529)`.
- Suggested painter source rectangle including edge and shadow: `x=8, y=294, width=1872, height=256`.
- Reference inspected before generation: `/Users/eric/.codex/generated_images/01a06cf6-0efd-76f3-9a18-6d9962ccd0d4/exec-419478c1-bdc6-4b13-a032-04a2816d4cae.png`.

Exact final prompt sent to ImageGen:

```text
Use case: ui-mockup. Generate ONE isolated raster background asset for a scrapbook vocabulary app search input. The asset is a perfectly horizontal very long rectangle of warm ivory-white handmade paper. It is viewed straight from above, orthographic, flat. Actual paper aspect ratio 6.8:1, e.g. 2048 pixels wide and 300 pixels tall. It fills 97–99% of the image width, centered vertically. Use a wide landscape transparent canvas. Genuinely transparent background including all empty canvas outside paper; preserve alpha. Paper has delicate low-contrast cream fiber texture, VERY subtle tiny irregular deckle edges, near-straight edges; a barely perceptible narrow neutral gray shadow directly below. This must match a quiet physical paper input field on a cream scrapbook, not a decorative label. Mostly clean warm white (#faf7ee); tiny subtle texture, not yellow. No corner folds, no outline border, no tape, no red tape, no ribbon, no other paper layers, no words, no letters, no icon, no magnifying glass, no text, no watermark. The entire rectangle including all edges must be visible, with no more than 2% horizontal transparent margins. Generate only this one paper rectangle.
```

## Green dry-brush underline

- Project path: `/Users/eric/Documents/英语学习APP/apps/android/app/src/main/res/drawable-nodpi/rl_vocabulary_underline_r11.png`
- Generated source path: `/Users/eric/.codex/generated_images/01a0b599-6d38-7e72-95ea-6ec312f04675/exec-65f99b1a-8e03-4b2e-a771-e19bdb77a5a4.png`
- Source dimensions: 2172 × 724 pixels, RGBA with transparency.
- Main content bounds at alpha > 32: `(67, 324)–(2105, 392)`.
- Suggested painter source rectangle including fine edge fibers: `x=60, y=318, width=2054, height=80`.
- Intended reference detail: the green brush underline below `academic` in the same selected reference image.

Exact final prompt sent to ImageGen:

```text
Use case: ui-mockup. Asset type: one isolated scrapbook underline brush stroke with actual transparent alpha background. Generate exactly ONE short, very thin, horizontal irregular dry-brush stroke, forest sage green #58734d. Intended as a small hand-painted underline underneath a word on warm ivory paper UI, but DO NOT draw the paper. The visible stroke is approximately 180 by 10 pixels in proportion (18:1), gently wavering but almost straight, slightly thicker opaque painted middle with thin tapered broken ends and delicate fibrous transparent rough edges, very fine dry-brush gaps. It is ONE stroke, not two, no shadow. Put the full stroke centered across a wide transparent image with very small horizontal margins, every edge fully visible. Match understated realistic scrapbook craft. Genuine transparent background; no white or beige background; no paper rectangle, no letters, no words, no icons, no labels, no borders, no arrows, no other objects.
```

## Consumption note

Both source files contain negligible-alpha scattered pixels outside the visible subject. Use the documented source rectangle when drawing the asset rather than deriving a crop from any nonzero alpha value. The source images themselves remain unchanged.
