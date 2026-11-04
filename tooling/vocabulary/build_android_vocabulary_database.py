from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import unicodedata
from pathlib import Path

import openpyxl


POOL_IDS = {
    "初中": "JUNIOR_HIGH",
    "高中": "SENIOR_HIGH",
    "四级": "CET_4",
    "六级": "CET_6",
    "考研": "POSTGRADUATE",
    "托福": "TOEFL",
    "雅思": "IELTS",
    "GRE": "GRE",
}

VOWELS = frozenset("aeiou")
IRREGULAR_VERBS = {
    "be": {"am", "is", "are", "was", "were", "been", "being"},
    "begin": {"began", "begun", "beginning"},
    "break": {"broke", "broken", "breaking"},
    "bring": {"brought", "bringing"},
    "build": {"built", "building"},
    "buy": {"bought", "buying"},
    "come": {"came", "coming"},
    "do": {"does", "did", "done", "doing"},
    "drink": {"drank", "drunk", "drinking"},
    "drive": {"drove", "driven", "driving"},
    "eat": {"ate", "eaten", "eating"},
    "fall": {"fell", "fallen", "falling"},
    "feel": {"felt", "feeling"},
    "find": {"found", "finding"},
    "fly": {"flew", "flown", "flying"},
    "forget": {"forgot", "forgotten", "forgetting"},
    "get": {"got", "gotten", "getting"},
    "give": {"gave", "given", "giving"},
    "go": {"goes", "went", "gone", "going"},
    "grow": {"grew", "grown", "growing"},
    "have": {"has", "had", "having"},
    "hear": {"heard", "hearing"},
    "keep": {"kept", "keeping"},
    "know": {"knew", "known", "knowing"},
    "leave": {"left", "leaving"},
    "lose": {"lost", "losing"},
    "make": {"made", "making"},
    "meet": {"met", "meeting"},
    "pay": {"paid", "paying"},
    "read": {"read", "reading"},
    "run": {"ran", "running"},
    "say": {"said", "saying"},
    "see": {"saw", "seen", "seeing"},
    "send": {"sent", "sending"},
    "sit": {"sat", "sitting"},
    "speak": {"spoke", "spoken", "speaking"},
    "stand": {"stood", "standing"},
    "swim": {"swam", "swum", "swimming"},
    "take": {"took", "taken", "taking"},
    "teach": {"taught", "teaching"},
    "tell": {"told", "telling"},
    "think": {"thought", "thinking"},
    "understand": {"understood", "understanding"},
    "wear": {"wore", "worn", "wearing"},
    "win": {"won", "winning"},
    "write": {"wrote", "written", "writing"},
}


def normalize_word(value: object) -> str:
    text = unicodedata.normalize("NFKC", str(value or ""))
    text = text.replace("’", "'").replace("‘", "'")
    text = re.sub(r"\s+", " ", text.casefold()).strip(" .;,，；")
    return text


def is_verb(pos_values: set[str]) -> bool:
    return any(value in {"v.", "vt.", "vi.", "modal v."} for value in pos_values)


def is_noun(pos_values: set[str]) -> bool:
    return "n." in pos_values


def consonant_vowel_consonant(word: str) -> bool:
    return (
        3 <= len(word) <= 4
        and word[-1].isalpha()
        and word[-1] not in VOWELS | frozenset("wxy")
        and word[-2] in VOWELS
        and word[-3] not in VOWELS
    )


def regular_verb_forms(word: str) -> set[str]:
    forms: set[str] = set()
    if word.endswith("ie"):
        forms.add(word[:-2] + "ying")
    elif word.endswith("e") and not word.endswith(("ee", "ye", "oe")):
        forms.add(word[:-1] + "ing")
    elif consonant_vowel_consonant(word):
        forms.add(word + word[-1] + "ing")
    else:
        forms.add(word + "ing")

    if word.endswith("e"):
        forms.add(word + "d")
    elif len(word) > 1 and word.endswith("y") and word[-2] not in VOWELS:
        forms.add(word[:-1] + "ied")
    elif consonant_vowel_consonant(word):
        forms.add(word + word[-1] + "ed")
    else:
        forms.add(word + "ed")

    if len(word) > 1 and word.endswith("y") and word[-2] not in VOWELS:
        forms.add(word[:-1] + "ies")
    elif word.endswith(("s", "sh", "ch", "x", "z", "o")):
        forms.add(word + "es")
    else:
        forms.add(word + "s")
    return forms


def regular_noun_forms(word: str) -> set[str]:
    if len(word) > 1 and word.endswith("y") and word[-2] not in VOWELS:
        return {word[:-1] + "ies"}
    if word.endswith(("s", "sh", "ch", "x", "z")):
        return {word + "es"}
    return {word + "s"}


def generated_forms(word: str, pos_values: set[str]) -> set[tuple[str, str]]:
    forms = {(word, "EXACT")}
    if not re.fullmatch(r"[a-z]+", word):
        return forms
    if is_verb(pos_values):
        candidates = IRREGULAR_VERBS.get(word, regular_verb_forms(word))
        forms.update((candidate, "INFLECTION") for candidate in candidates if candidate != word)
    if is_noun(pos_values):
        forms.update((candidate, "INFLECTION") for candidate in regular_noun_forms(word) if candidate != word)
    return forms


def load_rows(path: Path) -> list[dict[str, object]]:
    workbook = openpyxl.load_workbook(path, read_only=True, data_only=True)
    sheet = workbook["总词表"]
    rows = sheet.iter_rows(values_only=True)
    headers = {str(value): index for index, value in enumerate(next(rows))}
    required = {
        "英文",
        "词类",
        "中文释义",
        "词条类型",
        "规范词头",
        "首字母",
        "音标",
        "查找键",
        "Word ID",
        "Sense ID",
        *POOL_IDS,
    }
    missing = required - headers.keys()
    if missing:
        raise ValueError(f"Missing workbook columns: {sorted(missing)}")
    output: list[dict[str, object]] = []
    for row in rows:
        pools = [POOL_IDS[name] for name in POOL_IDS if row[headers[name]]]
        output.append(
            {
                "display": str(row[headers["英文"]] or "").strip(),
                "normalized_word": normalize_word(row[headers["规范词头"]]),
                "pos": str(row[headers["词类"]] or "").strip(),
                "meaning_zh": str(row[headers["中文释义"]] or "").strip(),
                "term_type": str(row[headers["词条类型"]] or "").strip(),
                "initial": str(row[headers["首字母"]] or "#").strip().upper(),
                "phonetic": str(row[headers["音标"]] or "").strip(),
                "lookup_key": str(row[headers["查找键"]] or "").strip(),
                "word_id": str(row[headers["Word ID"]] or "").strip(),
                "sense_id": str(row[headers["Sense ID"]] or "").strip(),
                "pools": pools,
            }
        )
    workbook.close()
    return output


def build_database(rows: list[dict[str, object]], output: Path) -> dict[str, object]:
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    digest = hashlib.sha256()
    for row in rows:
        digest.update(
            "\u241f".join(
                [
                    str(row["sense_id"]),
                    str(row["word_id"]),
                    str(row["normalized_word"]),
                    str(row["pos"]),
                    str(row["meaning_zh"]),
                    ",".join(row["pools"]),
                ]
            ).encode("utf-8")
        )
    data_version = "2026-09-17-" + digest.hexdigest()[:12]

    database = sqlite3.connect(output)
    database.executescript(
        """
        pragma page_size=4096;
        pragma journal_mode=off;
        pragma synchronous=off;
        pragma temp_store=memory;
        pragma foreign_keys=on;
        create table catalog_metadata(
          key text primary key,
          value text not null
        ) without rowid;
        create table catalog_words(
          word_id text primary key,
          display text not null,
          normalized_word text not null unique,
          initial text not null,
          item_type text not null,
          phonetic text
        ) without rowid;
        create table catalog_senses(
          sense_id text primary key,
          word_id text not null references catalog_words(word_id),
          part_of_speech text not null,
          meaning_zh text not null,
          lookup_key text not null
        ) without rowid;
        create table catalog_pool_senses(
          pool text not null,
          initial text not null,
          normalized_word text not null,
          sense_id text not null references catalog_senses(sense_id),
          primary key(pool, sense_id)
        ) without rowid;
        create table catalog_forms(
          normalized_form text not null,
          word_id text not null references catalog_words(word_id),
          match_kind text not null check(match_kind in ('EXACT','INFLECTION')),
          primary key(normalized_form, word_id)
        ) without rowid;
        create index catalog_senses_word on catalog_senses(word_id, sense_id);
        create index catalog_pool_browse on catalog_pool_senses(pool, initial, normalized_word, sense_id);
        create index catalog_forms_lookup on catalog_forms(normalized_form, match_kind, word_id);
        """
    )

    words: dict[str, dict[str, object]] = {}
    positions_by_word: dict[str, set[str]] = {}
    for row in rows:
        word_id = str(row["word_id"])
        words.setdefault(word_id, row)
        positions_by_word.setdefault(word_id, set()).add(str(row["pos"]))

    with database:
        database.executemany(
            "insert into catalog_metadata(key,value) values(?,?)",
            [
                ("schema_version", "1"),
                ("data_version", data_version),
                ("sense_count", str(len(rows))),
                ("word_count", str(len(words))),
                ("pool_ids", ",".join(POOL_IDS.values())),
            ],
        )
        database.executemany(
            "insert into catalog_words(word_id,display,normalized_word,initial,item_type,phonetic) values(?,?,?,?,?,?)",
            [
                (
                    word_id,
                    row["display"],
                    row["normalized_word"],
                    row["initial"],
                    row["term_type"],
                    row["phonetic"] or None,
                )
                for word_id, row in words.items()
            ],
        )
        database.executemany(
            "insert into catalog_senses(sense_id,word_id,part_of_speech,meaning_zh,lookup_key) values(?,?,?,?,?)",
            [
                (
                    row["sense_id"],
                    row["word_id"],
                    row["pos"],
                    row["meaning_zh"],
                    row["lookup_key"],
                )
                for row in rows
            ],
        )
        database.executemany(
            "insert into catalog_pool_senses(pool,initial,normalized_word,sense_id) values(?,?,?,?)",
            [
                (pool, row["initial"], row["normalized_word"], row["sense_id"])
                for row in rows
                for pool in row["pools"]
            ],
        )
        database.executemany(
            "insert into catalog_forms(normalized_form,word_id,match_kind) values(?,?,?)",
            [
                (form, word_id, kind)
                for word_id, row in words.items()
                for form, kind in generated_forms(
                    str(row["normalized_word"]), positions_by_word[word_id]
                )
            ],
        )
    database.execute("pragma optimize")
    database.execute("vacuum")
    integrity = database.execute("pragma integrity_check").fetchone()
    if integrity != ("ok",):
        raise RuntimeError(f"SQLite integrity check failed: {integrity}")
    database.close()
    return {
        "schemaVersion": 1,
        "dataVersion": data_version,
        "wordCount": len(words),
        "senseCount": len(rows),
        "pools": list(POOL_IDS.values()),
    }


def write_manifest(manifest: dict[str, object], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    temporary.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--master-workbook", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest-output", type=Path)
    args = parser.parse_args()
    rows = load_rows(args.master_workbook)
    manifest = build_database(rows, args.output)
    manifest_output = args.manifest_output or args.output.with_suffix(".manifest.json")
    write_manifest(manifest, manifest_output)


if __name__ == "__main__":
    main()
