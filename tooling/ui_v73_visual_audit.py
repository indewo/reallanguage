#!/usr/bin/env python3
"""Inventory and inspect real preview captures; never claim screenshot similarity is a functional test."""
from pathlib import Path
import argparse
import hashlib
import json
import re
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / '.local-data/ui-v73'
EXCLUDED = {'HOME', 'DIARY_INPUT'}
EXTENSIONS = {
    'SAFETY_HANDLING_LOADING': 'v73-safety-handling-loader.png', 'SAFETY_HANDLING_LOAD_FAILED': 'v73-safety-handling-loader.png',
    'LOGIN': '73-login.png', 'ACCOUNT_DELETION': '74-account-deletion.png',
    'SEMANTIC_SPLIT': 'v73-semantic-split.png', 'SEMANTIC_SPLIT_FAILED': 'v73-semantic-split.png',
    'ENGLISH_GENERATION': 'v73-english-generation.png', 'ENGLISH_GENERATION_FAILED': 'v73-english-generation.png',
    'LEARNING_TARGET_LOADING': 'v73-learning-target-loading.png', 'LEARNING_TARGET_FAILED': 'v73-learning-target-loading.png',
    'SCENE_DIRECT_DESCRIPTION': 'v73-scene-direct.png', 'LOCAL_RECORDINGS': 'v73-local-recordings.png',
    'EXPLANATION_PREPARATION': '73-explanation-preparation.png', 'EXPLANATION_PREPARATION_FAILED': '73-explanation-preparation.png',
    'READ_WRITE_CORRECTION': '74-read-write-correction.png', 'PATTERN_INDEPENDENT': '75-pattern-independent.png',
}

def inventory():
    source = (ROOT / 'apps/android/app/src/main/java/com/reallanguage/app/development/DevelopmentPreviewScreen.kt').read_text()
    enum = re.search(r'private enum class PreviewDestination \{(.*?)\n\}', source, re.S).group(1)
    states = re.findall(r'^\s*([A-Z_]+),', enum, re.M)
    capture = (ROOT / 'tooling/capture-android-visual-qa.sh').read_text()
    original_states = re.search(r'destinations=\((.*?)\)', capture, re.S).group(1).split()
    originals = sorted(p for p in (ROOT / 'product-showcase/ui-design-final').glob('*.png')
                       if p.name[:2].isdigit() and int(p.name[:2]) not in (17, 18))
    assert len(original_states) == len(originals) == 70
    sources = dict(zip(original_states, originals))
    sources.update({k: ROOT / 'product-showcase/ui-design-extensions' / v for k, v in EXTENSIONS.items()})
    vocabulary = ROOT / 'product-showcase/ui-design-extensions/40-vocabulary-r11.png'
    if vocabulary.exists(): sources['VOCABULARY_BOOK'] = vocabulary
    supplemental = {}
    for mapping in sorted(OUTPUT.glob('manifest-*-edges.json')):
        for item in json.loads(mapping.read_text())['screens']:
            supplemental[item['destination']] = item
            sources[item['destination']] = ROOT / item['reference']
            if item['destination'] not in states: states.append(item['destination'])
    rows = []
    for state in states:
        if state == 'CATALOG': continue
        path = sources.get(state)
        rows.append({'destination': state, 'excluded': state in EXCLUDED,
                     'reference': str(path.relative_to(ROOT)) if path else None,
                     'referenceSize': list(Image.open(path).size) if path and path.exists() else None,
                     'baseline': str((OUTPUT / 'before' / f'{state}.png').relative_to(ROOT)),
                     'newDesign': supplemental.get(state, {}).get('newDesign', state in EXTENSIONS),
                     **({'stateDesignBasis': supplemental[state]['stateDesignBasis']} if state in supplemental else {})})
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / 'manifest.json').write_text(json.dumps({'deleted': ['17-chapter-formation', '18-chapter-formation-failed'],
        'screens': rows}, ensure_ascii=False, indent=2))
    print(json.dumps({'screens': len(rows), 'excluded': sorted(EXCLUDED),
        'missingDesign': [r['destination'] for r in rows if not r['referenceSize']]}, ensure_ascii=False))

def boundaries():
    before = OUTPUT / 'source-before'
    after = ROOT / 'apps/android/app/src/main/java'
    changes = []
    protected = []
    for path in before.rglob('*.kt'):
        relative = path.relative_to(before)
        target = after / relative
        changed = not target.exists() or path.read_bytes() != target.read_bytes()
        if changed: changes.append(str(relative))
        if path.name in ('HomeScreen.kt', 'DiaryInputScreen.kt'):
            protected.append({'file': str(relative), 'unchanged': not changed,
                              'sha256': hashlib.sha256(target.read_bytes()).hexdigest() if target.exists() else None})
    # One missed presentation-only helper lived in ProductionApp. Verify everything
    # outside that exact composable is byte-identical; do not exempt the entire router.
    presentation_core = []
    for p in changes:
        if p.endswith('/core/app/ProductionApp.kt'):
            def omit_startup_ui(text):
                start = text.index('@Composable\nprivate fun StartupConnectionErrorScreen(')
                end = text.index('private fun <T : ViewModel> viewModelFactory', start)
                return text[:start] + text[end:]
            old, new = (before / p).read_text(), (after / p).read_text()
            if omit_startup_ui(old) == omit_startup_ui(new): presentation_core.append(p)
        elif Path(p).name in {'FormationContentGate.kt', 'FormationActionGate.kt',
                              'LearningRouteLoader.kt', 'LearningTargetSelectionEntry.kt'}:
            # These four adapters only supply literal UI copy to the existing
            # FailureDisplay. Compare every other Kotlin token, including all
            # callbacks, conditions, persistence and navigation arguments.
            def without_display_copy(text):
                text = re.sub(r'\b(?:title|supporting)\s*=\s*"[^"\n]*"\s*,?', '', text)
                text = re.sub(r'\bstageLabels\s*=\s*listOf\((?:\s*"[^"\n]*"\s*,?)*\)\s*,?', '', text)
                tokens = re.findall(r'"(?:\\.|[^"\\])*"|[A-Za-z_][A-Za-z_0-9]*|\d+|[^\s]', text)
                return [token for i, token in enumerate(tokens)
                        if not (token == ',' and i + 1 < len(tokens) and tokens[i+1] == ')')]
            old, new = (before / p).read_text(), (after / p).read_text()
            if without_display_copy(old) == without_display_copy(new): presentation_core.append(p)
    non_ui = [p for p in changes if p not in presentation_core and ('/domain/' in p or '/data/' in p or 'ViewModel.kt' in p
              or '/core/' in p or 'Navigation' in p or 'Contract.kt' in p)]
    result = {'protected': protected, 'modifiedExistingKotlin': changes, 'nonUiChanges': non_ui,
              'verifiedPresentationOnlyCoreChanges': presentation_core}
    (OUTPUT / 'source-boundaries.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({'changed': len(changes), 'protected': protected, 'nonUiChanges': non_ui}, ensure_ascii=False))

def boards(folder):
    manifest = json.loads((OUTPUT / 'manifest.json').read_text())['screens']
    out = OUTPUT / ('comparisons-' + folder.name)
    out.mkdir(exist_ok=True)
    cell_w, cell_h, caption = 650, 340, 26
    for offset in range(0, len(manifest), 4):
        batch = manifest[offset:offset+4]
        board = Image.new('RGB', (cell_w*3, (cell_h+caption)*len(batch)), '#e3dfd5')
        draw = ImageDraw.Draw(board)
        for row, item in enumerate(batch):
            paths = [ROOT / item['reference'] if item['reference'] else None,
                     ROOT / item['baseline'], folder / (item['destination'] + '.png')]
            for column, path in enumerate(paths):
                x, y = column*cell_w, row*(cell_h+caption)
                draw.text((x+8, y+6), item['destination'] + ' / ' + ('DESIGN', 'BEFORE', 'AFTER')[column], fill='#132633')
                if path and path.exists():
                    image = Image.open(path).convert('RGB')
                    image.thumbnail((cell_w, cell_h))
                    board.paste(image, (x+(cell_w-image.width)//2, y+caption+(cell_h-image.height)//2))
        board.save(out / f'{offset//4+1:02d}.jpg', quality=92)
    print(out)

def geometry(folder):
    findings = []
    for path in folder.glob('*.json'):
        report = json.loads(path.read_text())
        if not isinstance(report, dict): continue
        if 'textNodes' not in report: continue
        for node in report['textNodes']:
            # Compose's boolean includes subpixel rounding; compare actual line geometry
            # against measured dimensions with one raster pixel of rounding tolerance.
            width, height = node.get('layoutSize', node.get('measuredSize', [node['bounds'][2]-node['bounds'][0], node['bounds'][3]-node['bounds'][1]]))
            lines = node.get('lines', [])
            overflow = any(line[2]-line[0] > width+1 or line[3] > height+1 for line in lines)
            if overflow and not node.get('ellipsis'):
                findings.append({'screen': report['destination'], 'type': 'text-layout-overflow',
                    'text': node['text'], 'tag': node['tag'], 'size': [width, height], 'lines': lines})
    (folder / 'geometry-findings.json').write_text(json.dumps(findings, ensure_ascii=False, indent=2))
    print(json.dumps({'findings': len(findings), 'byScreen': sorted({f['screen'] for f in findings})}))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['inventory', 'boundaries', 'boards', 'geometry'])
    parser.add_argument('--folder', type=Path)
    args = parser.parse_args()
    if args.command == 'inventory': inventory()
    elif args.command == 'boundaries': boundaries()
    elif args.command == 'boards': boards(args.folder)
    else: geometry(args.folder)
