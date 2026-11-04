#!/usr/bin/env python3
"""Run native UI interaction suites at their declared physical viewport/font size."""
from pathlib import Path
import argparse
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ADB = ['/Users/eric/Library/Android/sdk/platform-tools/adb', '-s', 'emulator-5554']
BASE = 'com.reallanguage.app.feature.'
STANDARD = [
    'auth.presentation.AuthScreenTest',
    'auth.presentation.AgreementDetailScreenTest',
    'auth.presentation.PasswordResetScreenTest',
    'auth.presentation.BirthYearMonthDialogTest',
    'home.presentation.AccountLogoutVisibilityTest',
    'home.presentation.IncompleteLearningScreenTest',
    'settings.presentation.SubsequentSettingsNavigationTest',
    'billing.presentation.RechargeScreenTest',
    'billing.presentation.BillingEditorialLayoutTest',
    'recording.presentation.CompactEditorialPriorityScreenTest',
    'scene.presentation.SceneChineseReviewScreenTest',
    'scene.presentation.SceneAbandonDialogTest',
    'formation.presentation.LearningTargetScreenTest',
    'learning.presentation.ExplanationReadingScreenTest',
    'learning.presentation.ReadSpeakFingerTrackingTest',
    'review.presentation.ReviewChapterScreenTest',
    'knowledge.presentation.PatternBookScreenTest',
    'knowledge.presentation.PatternDetailScreenTest',
    'knowledge.presentation.PatternPronunciationScreenTest',
    'recording.presentation.ActiveRecordingScreenTest',
    'recording.presentation.TranscriptResultScreenTest',
    'recording.presentation.TranscriptSegmentConfirmationScreenTest',
    'error.presentation.ConnectionErrorOverlayTest',
    'error.presentation.ServiceFailureScreenTest',
    'error.presentation.StartupConnectionScreenTest',
]
PATTERN = BASE + 'knowledge.presentation.PatternBookTextVisibilityTest#'
COVERAGE = [
    'diary.presentation.DiaryProcessingScreenTest',
    'learning.presentation.CompleteWritingScreenTest',
    'learning.presentation.PatternSpeakingScreenTest',
    'learning.presentation.CompletedStepReviewScreenTest',
    'learning.presentation.CompletedSceneReadOnlyScreenTest',
    'learning.presentation.SceneResponseScreenTest',
    'formation.presentation.FocusSelectionScreenTest',
    'formation.presentation.EnglishConfirmationScreenTest',
    'scene.presentation.SceneQuestionsScreenTest',
    'scene.presentation.SceneGenerationScreenTest',
    'scene.presentation.SceneTypeScreenTest',
    'scene.presentation.SceneDirectDescriptionScreenTest',
    'safety.presentation.SafetyCheckScreenTest',
    'safety.presentation.SafetyHandlingScreenTest',
    'safety.presentation.ManualReviewPendingScreenTest',
    'safety.presentation.ManualReviewResultScreenTest',
    'safety.presentation.ManualReviewUnavailableScreenTest',
    'review.presentation.TodayReviewScreenTest',
    'review.presentation.ReviewResultScreenTest',
    'review.presentation.ReviewExitDialogTest',
    'settings.presentation.InitialSettingsScreenTest',
    'settings.presentation.SubsequentSettingsScreenTest',
    'settings.presentation.SettingsConflictScreenTest',
    'settings.presentation.SettingsEffectScopeScreenTest',
    'settings.presentation.ReminderSettingsScreenTest',
]
_covered = set(STANDARD + COVERAGE + [
    'knowledge.presentation.VocabularyBookScreenTest',
    'home.presentation.HomeScreenTest',
    'diary.presentation.DiaryInputScreenTest',
])
_feature_tests = ROOT / 'apps/android/app/src/androidTest/java/com/reallanguage/app/feature'
REMAINING = sorted({
    '.'.join(path.relative_to(_feature_tests).with_suffix('').parts)
    for path in _feature_tests.glob('*/presentation/*ScreenTest.kt')
} - _covered)
REMAINING += ['recording.presentation.RecordingDeletionDialogTest']
GROUPS = [
    ('standard', '1831x859', '1.0', ','.join(BASE + name for name in STANDARD)),
    ('vocabulary-reference', '2340x1080', '1.0', BASE + 'knowledge.presentation.VocabularyBookScreenTest'),
    ('pattern-layout', '1774x887', '1.0', BASE + 'knowledge.presentation.PatternBookLayoutTest'),
    ('pattern-visible', '1774x887', '1.0', ','.join(PATTERN + name for name in [
        'referenceExamplesPlaceEnglishAboveTheirOwnChineseTranslation',
        'referenceInitialThreeExamplesAndDetailActionsHaveFullyVisibleGlyphs'])),
    ('pattern-touch-large', '1280x720', '2.0', ','.join(PATTERN + name for name in [
        'shortSystemDoubleFontCanTouchScrollEveryExampleAndStatsThenUseExactActions',
        'shortSystemDoubleFontLongPatternRowCanReadAndOpenExactPattern'])),
    ('screen-coverage', '1831x859', '1.0', ','.join(BASE + name for name in COVERAGE)),
    ('remaining-screens', '1831x859', '1.0', ','.join(BASE + name for name in REMAINING)),
    ('target-reference', '1846x852', '1.0', BASE + 'formation.presentation.LearningTargetReferenceCardFitTest'),
    ('safety-reference', '1829x860', '1.0', BASE + 'safety.presentation.SafetyHandlingReferenceFitTest'),
    ('process-reference', '1829x860', '1.0', BASE + 'formation.presentation.FormationProcessReferenceFitTest'),
    ('last-changes', '1831x859', '1.0', ','.join(BASE + name for name in [
        'billing.presentation.AiBillingScreenTest',
        'billing.presentation.BillingEditorialLayoutTest',
        'settings.presentation.SettingsEffectScopeScreenTest',
        'settings.presentation.SettingsEffectReminderScreenTest',
        'learning.presentation.SceneResponseScreenTest',
        'review.presentation.ReviewResultScreenTest',
        'recording.presentation.RecordingTranscriptionScreenTest',
        'formation.presentation.FocusSelectionScreenTest',
        'knowledge.presentation.PatternBookScreenTest',
        'knowledge.presentation.PatternDetailScreenTest',
        'knowledge.presentation.PatternPronunciationScreenTest',
    ])),
]

def adb(*args):
    return subprocess.run(ADB + list(args), check=True, capture_output=True,
                          timeout=600 if 'instrument' in args else 30).stdout.decode()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--name', required=True)
    parser.add_argument('--groups', default=','.join(group[0] for group in GROUPS if group[0] != 'last-changes'))
    parser.add_argument('--exclude-classes', default='')
    args = parser.parse_args()
    out = ROOT / '.local-data/ui-v73' / args.name
    out.mkdir(parents=True, exist_ok=True)
    reports = []
    try:
        for name, size, font, classes in GROUPS:
            if name not in args.groups.split(','): continue
            excluded = {BASE + item for item in args.exclude_classes.split(',') if item}
            classes = ','.join(item for item in classes.split(',') if item.split('#')[0] not in excluded)
            adb('shell', 'wm', 'size', size)
            adb('shell', 'wm', 'density', '320')
            adb('shell', 'settings', 'put', 'system', 'font_scale', font)
            output = adb('shell', 'am', 'instrument', '-w', '-e', 'class', classes,
                'com.reallanguage.app.test/androidx.test.runner.AndroidJUnitRunner')
            (out / (name + '.log')).write_text(output)
            passed = re.search(r'OK \((\d+) tests?\)', output)
            failed = re.search(r'Tests run: (\d+),\s+Failures: (\d+)', output)
            report = {'group': name, 'size': size, 'fontScale': font, 'passed': bool(passed),
                'tests': int(passed.group(1)) if passed else int(failed.group(1)) if failed else None,
                'failures': 0 if passed else int(failed.group(2)) if failed else None}
            reports.append(report)
            print(json.dumps(report), flush=True)
    finally:
        adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        adb('shell', 'wm', 'size', '1831x859')
        (out / 'summary.json').write_text(json.dumps(reports, indent=2))
    if not reports or not all(report['passed'] for report in reports):
        raise SystemExit(1)

if __name__ == '__main__': main()
