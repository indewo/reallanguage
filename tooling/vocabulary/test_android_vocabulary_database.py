from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path

from tooling.vocabulary.build_android_vocabulary_database import (
    build_database,
    generated_forms,
    normalize_word,
    write_manifest,
)


class AndroidVocabularyDatabaseTest(unittest.TestCase):
    def test_normalizes_user_input_without_locale_or_network(self) -> None:
        self.assertEqual(normalize_word("  Studied\u00a0At  The Library. "), "studied at the library")

    def test_precomputes_regular_and_irregular_forms(self) -> None:
        self.assertIn(("studied", "INFLECTION"), generated_forms("study", {"v."}))
        self.assertIn(("went", "INFLECTION"), generated_forms("go", {"vi."}))
        self.assertNotIn(("bed", "INFLECTION"), generated_forms("be", {"v."}))

    def test_builds_indexed_database_with_pool_specific_senses(self) -> None:
        rows = [
            {
                "display": "study",
                "normalized_word": "study",
                "pos": "v.",
                "meaning_zh": "学习;研究",
                "term_type": "单词",
                "initial": "S",
                "phonetic": "",
                "lookup_key": "S|study|v.",
                "word_id": "w-study",
                "sense_id": "s-study",
                "pools": ["CET_4", "TOEFL"],
            }
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.db"
            build_database(rows, path)
            database = sqlite3.connect(path)
            result = database.execute(
                """
                select s.sense_id,p.pool from catalog_forms f
                join catalog_senses s on s.word_id=f.word_id
                join catalog_pool_senses p on p.sense_id=s.sense_id
                where f.normalized_form=? order by p.pool
                """,
                ("studied",),
            ).fetchall()
            indexes = {row[1] for row in database.execute("pragma index_list('catalog_pool_senses')")}
            database.close()
        self.assertEqual(result, [("s-study", "CET_4"), ("s-study", "TOEFL")])
        self.assertIn("catalog_pool_browse", indexes)

    def test_writes_runtime_manifest_from_the_same_database_build(self) -> None:
        rows = [
            {
                "display": "go",
                "normalized_word": "go",
                "pos": "vi.",
                "meaning_zh": "去",
                "term_type": "单词",
                "initial": "G",
                "phonetic": "",
                "lookup_key": "G|go|vi.",
                "word_id": "w-go",
                "sense_id": "s-go",
                "pools": ["JUNIOR_HIGH"],
            }
        ]
        with tempfile.TemporaryDirectory() as directory:
            database_path = Path(directory) / "catalog.db"
            manifest_path = Path(directory) / "catalog.manifest.json"
            manifest = build_database(rows, database_path)
            write_manifest(manifest, manifest_path)
            content = manifest_path.read_text(encoding="utf-8")
        self.assertIn('"schemaVersion": 1', content)
        self.assertIn('"wordCount": 1', content)
        self.assertIn('"senseCount": 1', content)


if __name__ == "__main__":
    unittest.main()
