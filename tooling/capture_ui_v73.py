#!/usr/bin/env python3
"""Capture the actual Android window, with per-reference dimensions when requested."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import time
from datetime import datetime, timezone
from PIL import Image, ImageStat

ROOT = Path(__file__).resolve().parents[1]
ADB = ['/Users/eric/Library/Android/sdk/platform-tools/adb', '-s', 'emulator-5554']

def run(*args):
    return subprocess.run(ADB + list(args), check=True, capture_output=True).stdout

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--screens')
    parser.add_argument('--exclude', default='')
    parser.add_argument('--reference-size', action='store_true')
    parser.add_argument('--settle', type=float, default=.7)
    parser.add_argument('--font-scale', type=float, default=1.0)
    args = parser.parse_args()
    manifest = json.loads((ROOT / '.local-data/ui-v73/manifest.json').read_text())['screens']
    selection = args.screens.split(',') if args.screens else [m['destination'] for m in manifest
        if m['destination'] not in args.exclude.split(',')]
    records = {m['destination']: m for m in manifest}
    args.output.mkdir(parents=True, exist_ok=True)
    archive = ROOT / 'apps/android/app/build/outputs/apk/debug/app-debug.apk'
    installed = run('shell', 'pm', 'path', 'com.reallanguage.app').decode().splitlines()
    installed_base = next(line.removeprefix('package:') for line in installed if line.endswith('/base.apk'))
    installed_sha = run('shell', 'sha256sum', installed_base).decode().split()[0]
    with archive.open('rb') as stream:
        archive_sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    if installed_sha != archive_sha:
        raise RuntimeError('Installed APK differs from the current build; install that build before capturing')
    (args.output / 'build-evidence.json').write_text(json.dumps({
        'startedAtUtc': datetime.now(timezone.utc).isoformat(),
        'apk': str(archive.relative_to(ROOT)), 'sha256': archive_sha,
        'installedArchiveSha256': installed_sha, 'device': 'emulator-5554',
        'referenceSize': args.reference_size, 'fontScale': args.font_scale,
    }, indent=2))
    results = []
    for name in selection:
        item = records[name]
        if args.reference_size:
            w, h = item['referenceSize']
            run('shell', 'wm', 'size', f'{w}x{h}')
            run('shell', 'wm', 'density', '320')
        run('shell', 'am', 'force-stop', 'com.reallanguage.app')
        launch = run('shell', 'am', 'start', '-W', '-n', 'com.reallanguage.app/.development.DevelopmentPreviewActivity',
            '--es', 'preview_destination', name, '--ez', 'reference_canvas', str(args.reference_size).lower(),
            '--ef', 'preview_font_scale', str(args.font_scale)).decode()
        if 'Error' in launch: raise RuntimeError(launch)
        time.sleep(args.settle)
        target = args.output / f'{name}.png'
        target.write_bytes(run('exec-out', 'screencap', '-p'))
        image = Image.open(target).convert('RGB')
        variance = sum(ImageStat.Stat(image).var)
        if variance < 50: raise RuntimeError(f'{name}: possible blank or failed render ({variance})')
        results.append({'destination': name, 'pixels': list(image.size), 'variance': variance,
            'referenceCanvas': args.reference_size, 'fontScale': args.font_scale,
            'referenceMatchedSize': list(image.size) == item['referenceSize']})
        print(f'{len(results)}/{len(selection)} {name} {image.size}', flush=True)
    (args.output / 'captures.json').write_text(json.dumps(results, indent=2))

if __name__ == '__main__': main()
