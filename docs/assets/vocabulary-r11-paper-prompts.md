# Vocabulary R11 — paper, alphabet rail, botanical asset provenance

All three assets were generated with the built-in ImageGen tool (`image_gen__imagegen`), using new-image generation mode. The generated source PNGs were copied into the Android resource directory without raster edits. The selected vocabulary reference was visually inspected before generation:

`/Users/eric/.codex/generated_images/01a06cf6-0efd-76f3-9a18-6d9962ccd0d4/exec-419478c1-bdc6-4b13-a032-04a2816d4cae.png`

The full prompts below are the actual prompt strings sent to ImageGen for the final selected images.

## Blank card paper

- Mode: built-in ImageGen, new-image generation, transparent-background request.
- Final saved path: `/Users/eric/Documents/英语学习APP/apps/android/app/src/main/res/drawable-nodpi/rl_vocabulary_card_paper_r11.png`
- Original source path: `/Users/eric/.codex/generated_images/01a0b598-e4ac-7623-a55d-3b6e578bb672/exec-abeb7a52-9ff3-47cc-931d-7a19dd963cb8.png`
- Output: 1774 × 887, RGBA; alpha range 0–255.
- Opaque content bounds (alpha ≥ 250, right/bottom exclusive): `(25, 39, 1745, 848)`.
- Inspection: blank warm ivory paper, fine fibers, light deckled edges on all four sides, subtle lower shadow; no text, tape, icons, or lines.

### Full final prompt

```text
Use case: product-mockup. Asset type: blank UI paper texture cutout for a vintage scrapbook vocabulary app. Generate ONE blank warm ivory deckle paper rectangle, isolated on a genuinely transparent background with actual alpha. Wide landscape canvas 1536x768. The paper rectangle should fill the canvas nearly edge to edge: only 1.5 percent transparent margin on every side, no large margins. Straight-on orthographic overhead view, no perspective, no curl. Nearly straight but gently ragged handmade torn edges on ALL FOUR sides, tiny irregular white fibers. Clean warm ivory body color approximately #faf5eb, extremely subtle cream cross fibers and subtle handmade paper texture. Very subtle soft shadow along bottom edge only. This is an empty background to hold live UI text, so there must be ABSOLUTELY NO words, letters, numbers, icons, lines, folds, tape, stains, border decorations, objects, embossing, or graphics. Keep body evenly light with no dark beige discoloration. Paper intended to stretch to 290x170dp and 535x170dp vocabulary cards. Do not create mockup of application or multiple pieces, just one single blank full rectangle.
```

## Alphabet rail

- Mode: built-in ImageGen, new-image generation, transparent-background request.
- Final saved path: `/Users/eric/Documents/英语学习APP/apps/android/app/src/main/res/drawable-nodpi/rl_vocabulary_alphabet_rail_r11.png`
- Original source path: `/Users/eric/.codex/generated_images/01a0b598-e4ac-7623-a55d-3b6e578bb672/exec-668ca189-5b22-4197-acd0-5e76ce8d4e49.png`
- Output: 973 × 1616, RGBA; alpha range 0–255.
- Main content bounds (alpha ≥ 5, right/bottom exclusive): `(355, 50, 621, 1574)`.
- Recommended source rectangle retaining a small fiber margin: offset `(350, 45)`, size `(276, 1534)`.
- Inspection: muted green paper with small left binding notches and an almost straight shallow-deckled right edge; no text, selected circle, leaves, or large shadow. Measured central mean color is approximately `#5a7158`, darker than the requested `#61826b`.

### Full final prompt

```text
Use case: product-mockup. Asset type: single UI asset for A–Z vocabulary navigation. Generate ONE narrow tall vertical strip of vintage forest-green paper isolated on real transparent alpha background. The paper itself is 6.7 times taller than wide, composition like a narrow 58x350dp sidebar. Main body muted forest green approximately #61826b, subtle irregular paper fibers and faint mottling. Flat front view, no perspective. LEFT EDGE has subtle small rectangular square binding notches, irregular tiny notebook binding cutouts only; RIGHT EDGE almost perfectly straight with very gentle shallow natural torn paper fibers. Top and bottom edges almost straight, lightly deckled. Absolutely avoid repeating scalloped/wavy sawtooth contours. Canvas should be tall portrait, paper centered and occupying 94% height, approximately 35% of canvas width; transparency everywhere else. The entire green paper face is blank. NO letters, NO text, NO numbers, NO circles, NO selection badge, NO leaf, NO tape, NO decorations, NO icons, NO large shadow, NO folded paper, NO curved shape. Clean UI scrapbook texture matching warm ivory deckle-paper vocabulary cards. Only one isolated green vertical paper strip.
```

## Green botanical sprig

- Mode: built-in ImageGen, new-image generation, transparent-background request.
- Final saved path: `/Users/eric/Documents/英语学习APP/apps/android/app/src/main/res/drawable-nodpi/rl_vocabulary_botanical_r11.png`
- Original source path: `/Users/eric/.codex/generated_images/01a0b598-e4ac-7623-a55d-3b6e578bb672/exec-cb929fdb-dddd-4021-bbc5-39883078bf6c.png`
- Output: 971 × 1619, RGBA; alpha range 0–255.
- Main content bounds (alpha ≥ 5, right/bottom exclusive): `(160, 25, 940, 1587)`.
- Recommended source rectangle retaining a small edge margin: offset `(150, 15)`, size `(800, 1582)`.
- Inspection: single thin stem leaning from lower left to upper right with approximately ten slender matte olive/sage green leaves; no paper, text, flowers, or other objects.

### Full final prompt

```text
Use case: product-mockup. Asset type: single botanical cutout for a vintage scrapbook vocabulary interface. Generate ONE realistic delicate pressed olive/sage botanical sprig on an actually transparent alpha background. Tall upright branch, aspect of botanical subject about 0.6 width to 1 height. Thin natural greenish-brown stem leaning gently to the right from bottom-left to top-right. About ten slender elongated lanceolate matte olive-green leaves along the stem, alternating left and right; muted sage and olive greens, natural veins, slightly folded dimensional leaves, resembling a small olive branch or narrow sage sprig. Match a calm handmade paper scrapbook palette. Stem and leaves photographed straight-on overhead, pressed botanical realism, crisp cutout edges, very subtle natural self shading. Fill most of the tall canvas with subject but preserve 5 percent clear margin. No paper, no scrapbook background, no collage, no text, no flowers, no berries, no vase, no border, no multiple branches, no shadows outside the plant, no brown dried leaves, no yellow autumn leaves. This is an individual clean transparent UI decoration asset, not a full screen.
```
