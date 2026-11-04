#!/usr/bin/env python3
"""Run explicit offline Compose fixtures and retrieve measured text bounds/screenshots."""
from pathlib import Path
import argparse
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ADB = ['/Users/eric/Library/Android/sdk/platform-tools/adb', '-s', 'emulator-5554']

def run(*args, capture=False):
    return subprocess.run(ADB + list(args), check=True, capture_output=capture)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--name', required=True)
    parser.add_argument('--font-scale', default='1.0')
    parser.add_argument('--screens')
    parser.add_argument('--exclude', default='')
    parser.add_argument('--size', default='1829x860')
    parser.add_argument('--density', default='320')
    args = parser.parse_args()
    manifest = json.loads((ROOT / '.local-data/ui-v73/manifest.json').read_text())['screens']
    screens = args.screens or ','.join(item['destination'] for item in manifest
        if not item['excluded'] and item['destination'] not in args.exclude.split(','))
    run('shell', 'wm', 'size', args.size)
    run('shell', 'wm', 'density', args.density)
    result = run('shell', 'am', 'instrument', '-w', '-e', 'class',
        'com.reallanguage.app.development.UiV73LayoutAuditTest#captureScreensAndTextGeometry',
        '-e', 'destinations', screens, '-e', 'fontScale', args.font_scale, '-e', 'auditName', args.name,
        'com.reallanguage.app.test/androidx.test.runner.AndroidJUnitRunner', capture=True)
    output = result.stdout.decode()
    print(output, flush=True)
    if 'OK (1 test)' not in output:
        raise RuntimeError('Layout audit failed; do not treat partial captures as a pass')
    destination = ROOT / '.local-data/ui-v73' / args.name
    destination.mkdir(parents=True, exist_ok=True)
    run('pull', f'/sdcard/Android/data/com.reallanguage.app/files/ui-v73-audit/{args.name}/.', str(destination))

if __name__ == '__main__': main()
