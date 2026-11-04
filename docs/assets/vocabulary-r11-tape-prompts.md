# Vocabulary R11 tape asset provenance

Both assets were generated with the built-in `image_gen__imagegen` tool. The supplied image was used as a style reference; these are newly generated isolated assets, not edits of the reference interface. No fallback CLI or API runner was used. The generated PNGs were copied unchanged into the Android drawable directory; their original files remain in place.

Reference image for both calls:

`/Users/eric/.codex/generated_images/01a06cf6-0efd-76f3-9a18-6d9962ccd0d4/exec-419478c1-bdc6-4b13-a032-04a2816d4cae.png`

## Terracotta tape

- Mode: built-in ImageGen; new single asset with one style-reference image supplied through `referenced_image_paths`.
- Saved project path: `/Users/eric/Documents/英语学习APP/apps/android/app/src/main/res/drawable-nodpi/rl_vocabulary_terracotta_tape_r11.png`
- Original generated source: `/Users/eric/.codex/generated_images/01a0b599-3028-7db3-a342-3666e7c62e2f/exec-e3db00e1-5586-47c6-9665-6d804380ef8e.png`
- Canvas: 1983 × 793 pixels, with alpha.
- Main subject bounds at alpha > 32: `(91, 215)–(1899, 574)`.
- Suggested sampling rectangle retaining soft edges: `x=88, y=210, width=1818, height=370`.
- Very low-alpha stray pixels extend beyond the subject; alpha > 0 is not an appropriate subject-boundary threshold.

Exact final prompt sent to ImageGen:

```text
Use case: product-mockup. Asset type: ONE isolated reusable washi tape bitmap for a vintage paper vocabulary app. Use the supplied image only as style reference: closely match the small terracotta washi tape above the word abstract at x545 y138, approximately145x38px. Generate ONLY that single horizontal strip, do not recreate the interface. A thin desaturated terracotta reddish-brown paper tape strip around #bf755a, approximate physical width-to-height ratio3.8:1, zero rotation. Matte fibrous translucent washi paper with fine crumpled creases and visible subtle tissue fibers; slightly irregular torn/fuzzy ends and organic top/bottom edge. Delicate modest wrinkles not deep folds. Centered, front-on flat product texture, nearly fills canvas width with modest margins, ideally1024x512 canvas. Genuine transparent alpha background throughout surrounding area; tape itself can have modest semi-translucency but must read terracotta. No beige tape below it, no papers, no objects, no letters, no words, no icons, no watermark, no large shadow, no glow, no white backdrop. Avoid opaque uniform red rectangular slab; faithful tiny vintage textured tape from supplied UI reference.
```

## Beige tape

- Mode: built-in ImageGen; new single asset with one style-reference image supplied through `referenced_image_paths`.
- Saved project path: `/Users/eric/Documents/英语学习APP/apps/android/app/src/main/res/drawable-nodpi/rl_vocabulary_beige_tape_r11.png`
- Original generated source: `/Users/eric/.codex/generated_images/01a0b599-3028-7db3-a342-3666e7c62e2f/exec-d7d1a854-6611-459b-8ed4-701b7fa9a4c9.png`
- Canvas: 2172 × 724 pixels, with alpha.
- Main subject bounds at alpha > 32: `(97, 172)–(2071, 544)`.
- Suggested sampling rectangle retaining soft edges: `x=92, y=167, width=1984, height=382`.

Exact final prompt sent to ImageGen:

```text
Use case: product-mockup. Asset type: ONE reusable isolated pale beige washi tape bitmap for a vintage scrapbook vocabulary app. Supplied image is style reference only. Match the pale beige translucent tape at top right of the upper meaning paper, at around x1690 y125, about112x35 displayed pixels. Generate ONLY one single pale beige horizontal tape strip. True transparent alpha background. Flat front-on, nearly horizontal, zero rotation because code adds rotation. Physical strip width-to-height ratio3.2:1. Light cream warm beige around #d8bd88, delicate fibrous matte tissue texture and subtle fine shallow creases, translucent thin paper, shallow softly irregular torn edges and slightly frayed ends. Soft flat lighting; no cast shadow beyond negligible edge. Center the single tape and have it occupy most of the image width, modest transparent margins. No red tape, no objects, no underlying paper, no letters, no typography, no logo, no watermark, no white backdrop, no big curl, no deep folds. Clean usable cutout, avoid stray low-alpha particles outside the main strip.
```
