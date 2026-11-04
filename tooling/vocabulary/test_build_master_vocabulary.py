from __future__ import annotations

import unittest

from tooling.vocabulary.build_master_vocabulary import (
    canonical_word,
    definitions_are_close,
    split_pos_senses,
)


class MasterVocabularyImportTest(unittest.TestCase):
    def test_normalizes_annotated_headword_without_rewriting_source_meaning(self) -> None:
        display, normalized, term_type = canonical_word("child （复数children）")
        self.assertEqual(display, "child")
        self.assertEqual(normalized, "child")
        self.assertEqual(term_type, "单词")

    def test_splits_multiple_source_parts_of_speech(self) -> None:
        self.assertEqual(
            split_pos_senses("vt.放弃,遗弃 n.放纵", None),
            [("vt.", "放弃,遗弃"), ("n.", "放纵")],
        )

    def test_keeps_materially_different_senses_separate(self) -> None:
        self.assertFalse(definitions_are_close("书房", "学习;研究"))
        self.assertFalse(definitions_are_close("银行", "岸;浅滩"))

    def test_merges_close_source_glosses(self) -> None:
        self.assertTrue(definitions_are_close("丢弃 放弃 抛弃", "抛弃;放弃"))


if __name__ == "__main__":
    unittest.main()
