from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import unicodedata
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Iterable

import openpyxl
from docx import Document


POOL_ORDER = ["初中", "高中", "四级", "六级", "考研", "托福", "雅思", "GRE"]
POOL_RANK = {pool: index for index, pool in enumerate(POOL_ORDER)}
REVIEW_ITEMS: list[dict[str, str]] = []

POS_ALIASES = {
    "n": "n.",
    "noun": "n.",
    "adj": "adj.",
    "adjective": "adj.",
    "a": "adj.",
    "v": "v.",
    "verb": "v.",
    "vt": "vt.",
    "vi": "vi.",
    "adv": "adv.",
    "ad": "adv.",
    "adverb": "adv.",
    "prep": "prep.",
    "preposition": "prep.",
    "pron": "pron.",
    "pronoun": "pron.",
    "conj": "conj.",
    "conjunction": "conj.",
    "art": "art.",
    "article": "art.",
    "num": "num.",
    "numeral": "num.",
    "int": "int.",
    "interj": "int.",
    "aux": "aux.",
    "modal": "modal v.",
    "abbr": "abbr.",
}

POS_PATTERN = re.compile(
    r"(?<![A-Za-z])"
    r"(?P<marker>preposition|conjunction|adjective|adverb|pronoun|article|numeral|"
    r"noun|verb|interj|modal|abbr|prep|pron|conj|adj|adv|aux|art|num|vt|vi|v|n|a|ad|int)"
    r"(?:\s*\.|\s*(?=[\u3400-\u9fff0-9(（\[/<]|$))",
    re.IGNORECASE,
)

CHINESE_POS_PREFIX = re.compile(r"^[（(]\s*(名|动|形|副|介|连|代|数|冠|叹)\s*[)）]\s*")
CHINESE_POS_ALIASES = {
    "名": "n.",
    "动": "v.",
    "形": "adj.",
    "副": "adv.",
    "介": "prep.",
    "连": "conj.",
    "代": "pron.",
    "数": "num.",
    "冠": "art.",
    "叹": "int.",
}

WORD_ANNOTATION_PATTERNS = [
    re.compile(r"\s*[（(]\s*(?:复数|复|pl\.?|美|英|过去式|过去分词|现在分词|比较级|最高级).*$", re.I),
    re.compile(r"\s+(?:conj|pron|adj|adv|prep|vt|vi|v|n|art|num|int)(?:\s*[&/]\s*(?:conj|pron|adj|adv|prep|vt|vi|v|n|art|num|int))*\.?\s*$", re.I),
]

DEFINITION_STOP_FRAGMENTS = {
    "一种",
    "表示",
    "用于",
    "用来",
    "有关",
    "关于",
    "东西",
    "事物",
    "某物",
    "某人",
}


@dataclass(frozen=True)
class RawSense:
    pool: str
    source_file: str
    source_location: str
    raw_word: str
    word: str
    normalized_word: str
    pronunciation: str
    pos: str
    definition_zh: str
    raw_definition: str
    term_type: str


def clean_text(value: object) -> str:
    if value is None:
        return ""
    text = unicodedata.normalize("NFKC", str(value))
    text = text.replace("\u00a0", " ").replace("\u200b", "")
    return re.sub(r"\s+", " ", text).strip()


def canonical_word(raw_word: str) -> tuple[str, str, str]:
    display = clean_text(raw_word).replace("’", "'").replace("‘", "'")
    display = re.sub(r"^[\d.、)）\s]+", "", display)
    display = re.sub(r"\($", "", display).strip()
    if re.fullmatch(r"a\s*[（(]\s*an\s*[)）]", display, flags=re.I):
        display = "a/an"
    else:
        for pattern in WORD_ANNOTATION_PATTERNS:
            display = pattern.sub("", display).strip()
    display = re.sub(r"\s*/\s*", "/", display)
    normalized = unicodedata.normalize("NFKC", display).casefold()
    normalized = normalized.replace("’", "'").replace("‘", "'")
    normalized = re.sub(r"\s+", " ", normalized).strip(" .;,，；")
    token_count = len(re.findall(r"[A-Za-z]+(?:'[A-Za-z]+)?", normalized))
    if (
        re.fullmatch(r"(?:[a-z]\.){2,}|[a-z]{1,8}\.?", normalized)
        and ("." in display or display.isupper())
    ):
        term_type = "缩写"
    elif token_count <= 1:
        term_type = "单词"
    elif token_count <= 5:
        term_type = "词组"
    else:
        term_type = "长词组"
    return display, normalized, term_type


def normalized_pos(marker: str) -> str:
    key = re.sub(r"[^A-Za-z]", "", marker).casefold()
    return POS_ALIASES.get(key, "未标注")


def split_pos_senses(raw_definition: str, default_pos: str | None) -> list[tuple[str, str]]:
    definition = clean_text(raw_definition)
    if not definition:
        return []
    definition = re.sub(r"\bvt\s*[./]\s*vi\.?", "v.", definition, flags=re.I)
    definition = re.sub(r"\bvi\s*[./]\s*vt\.?", "v.", definition, flags=re.I)
    definition = re.sub(r"\bv\s*\.\s*&\s*(?:aux|link)\.?", "v.", definition, flags=re.I)
    definition = re.sub(r"\ba\s*\.\s*dj\.?", "adj.", definition, flags=re.I)
    definition = re.sub(r"\bad\s+\.", "adv.", definition, flags=re.I)
    definition = re.sub(r"\bini\s*\.", "int.", definition, flags=re.I)
    definition = re.sub(r"\bn\s*。", "n.", definition, flags=re.I)
    definition = re.sub(
        r"\b(n|v|vt|vi|adj|adv|prep|pron|conj|art|num|int|aux|abbr)\s*\.+",
        lambda match: f"{match.group(1)}.",
        definition,
        flags=re.I,
    )
    chinese_prefix = CHINESE_POS_PREFIX.match(definition)
    if chinese_prefix:
        pos = CHINESE_POS_ALIASES[chinese_prefix.group(1)]
        value = definition[chinese_prefix.end() :].strip(" ,，;；")
        return [(pos, value)] if value else []
    matches = list(POS_PATTERN.finditer(definition))
    senses: list[tuple[str, str]] = []
    if not matches:
        inferred = default_pos
        if inferred is None and re.match(r"^[（(]\s*缩\s*[)）]", definition):
            inferred = "abbr."
        return [(inferred or "未标注", definition)]

    prefix = definition[: matches[0].start()].strip(" ,，;；")
    if prefix and default_pos:
        senses.append((default_pos, prefix))

    for index, match in enumerate(matches):
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(definition)
        value = definition[start:end].strip(" ,，;；")
        if value:
            senses.append((normalized_pos(match.group("marker")), value))

    if not senses:
        return []
    return senses


def junior_default_pos(table_index: int) -> str:
    if table_index <= 53:
        return "n."
    if table_index <= 60:
        return "pron."
    if table_index <= 62:
        return "num."
    return {
        63: "prep.",
        64: "conj.",
        65: "adj.",
        66: "adv.",
        67: "v.",
        68: "vt.",
        69: "vi.",
        70: "modal v.",
        71: "art.",
        72: "int.",
    }.get(table_index, "未标注")


def add_entry(
    rows: list[RawSense],
    *,
    pool: str,
    source_file: str,
    source_location: str,
    raw_word: str,
    pronunciation: str,
    raw_definition: str,
    default_pos: str | None = None,
) -> None:
    word, normalized_word, term_type = canonical_word(raw_word)
    if not normalized_word or not re.search(r"[a-z]", normalized_word):
        REVIEW_ITEMS.append(
            {
                "pool": pool,
                "source_file": source_file,
                "source_location": source_location,
                "raw_word": clean_text(raw_word),
                "raw_definition": clean_text(raw_definition),
                "reason": "词头无法规范化",
            }
        )
        return
    effective_default_pos = default_pos or ("abbr." if term_type == "缩写" else None)
    parsed_senses = split_pos_senses(raw_definition, effective_default_pos)
    if not parsed_senses:
        REVIEW_ITEMS.append(
            {
                "pool": pool,
                "source_file": source_file,
                "source_location": source_location,
                "raw_word": clean_text(raw_word),
                "raw_definition": clean_text(raw_definition),
                "reason": "源释义缺失或仅含词类标记",
            }
        )
        return
    for pos, definition in parsed_senses:
        if not normalize_definition(definition):
            continue
        rows.append(
            RawSense(
                pool=pool,
                source_file=source_file,
                source_location=source_location,
                raw_word=clean_text(raw_word),
                word=word,
                normalized_word=normalized_word,
                pronunciation=clean_text(pronunciation),
                pos=pos,
                definition_zh=clean_text(definition),
                raw_definition=clean_text(raw_definition),
                term_type=term_type,
            )
        )


def read_junior(path: Path) -> list[RawSense]:
    rows: list[RawSense] = []
    document = Document(path)
    for table_index, table in enumerate(document.tables):
        default_pos = junior_default_pos(table_index)
        for row_index, row in enumerate(table.rows, start=1):
            values = [clean_text(cell.text) for cell in row.cells]
            if len(values) < 2 or not values[0]:
                continue
            add_entry(
                rows,
                pool="初中",
                source_file="初中单词.doc",
                source_location=f"表{table_index + 1}/行{row_index}",
                raw_word=values[0],
                pronunciation="",
                raw_definition=values[1],
                default_pos=default_pos,
            )
    return rows


def read_sheet(
    path: Path,
    *,
    pool: str,
    source_file: str,
    sheet_name: str,
    start_row: int,
    word_column: int,
    pronunciation_column: int | None,
    definition_column: int,
) -> list[RawSense]:
    rows: list[RawSense] = []
    workbook = openpyxl.load_workbook(path, read_only=True, data_only=True)
    sheet = workbook[sheet_name]
    for row_index, values in enumerate(sheet.iter_rows(values_only=True), start=1):
        if row_index < start_row:
            continue
        raw_word = values[word_column] if word_column < len(values) else None
        raw_definition = values[definition_column] if definition_column < len(values) else None
        pronunciation = (
            values[pronunciation_column]
            if pronunciation_column is not None and pronunciation_column < len(values)
            else ""
        )
        if not clean_text(raw_word):
            continue
        add_entry(
            rows,
            pool=pool,
            source_file=source_file,
            source_location=f"{sheet_name}!{row_index}",
            raw_word=clean_text(raw_word),
            pronunciation=clean_text(pronunciation),
            raw_definition=clean_text(raw_definition),
        )
    workbook.close()
    return rows


def normalize_definition(value: str) -> str:
    text = unicodedata.normalize("NFKC", value).casefold()
    text = re.sub(r"[\s,，;；、。.!！?？:：()（）\[\]【】<>《》\-—_/&]", "", text)
    return text


def definition_units(value: str) -> list[str]:
    units = []
    for unit in re.split(r"[;,，；、。/]|\s+|(?:\s+or\s+)|或|以及", value, flags=re.I):
        normalized = normalize_definition(unit)
        if len(normalized) >= 2 and normalized not in DEFINITION_STOP_FRAGMENTS:
            units.append(normalized)
    return units


def definitions_are_close(left: str, right: str) -> bool:
    a = normalize_definition(left)
    b = normalize_definition(right)
    if not a or not b:
        return False
    if a == b:
        return True
    shorter, longer = sorted((a, b), key=len)
    if len(shorter) >= 2 and shorter in longer and len(shorter) / len(longer) >= 0.48:
        return True
    ratio = SequenceMatcher(None, a, b).ratio()
    if ratio >= 0.65:
        return True
    left_units = set(definition_units(left))
    right_units = set(definition_units(right))
    common = left_units & right_units
    if common:
        common_size = sum(len(unit) for unit in common)
        coverage = common_size / max(1, min(sum(map(len, left_units)), sum(map(len, right_units))))
        length_ratio = min(len(a), len(b)) / max(len(a), len(b))
        return coverage >= 0.5 and length_ratio >= 0.55
    left_bigrams = {a[index : index + 2] for index in range(len(a) - 1)}
    right_bigrams = {b[index : index + 2] for index in range(len(b) - 1)}
    common_bigrams = left_bigrams & right_bigrams
    if len(common_bigrams) >= 2:
        coverage = len(common_bigrams) / max(1, min(len(left_bigrams), len(right_bigrams)))
        return coverage >= 0.4
    return False


def stable_id(prefix: str, *parts: str) -> str:
    payload = "\u241f".join(parts).encode("utf-8")
    return f"{prefix}_{hashlib.sha256(payload).hexdigest()[:16]}"


def first_letter(word: str) -> str:
    match = re.search(r"[a-z]", word)
    return match.group(0).upper() if match else "#"


def choose_canonical(entries: Iterable[RawSense]) -> RawSense:
    values = list(entries)
    frequencies = Counter(normalize_definition(entry.definition_zh) for entry in values)
    return min(
        values,
        key=lambda entry: (
            -frequencies[normalize_definition(entry.definition_zh)],
            len(normalize_definition(entry.definition_zh)),
            POOL_RANK[entry.pool],
            entry.definition_zh,
        ),
    )


def merge_senses(raw_rows: list[RawSense]) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    deduplicated: dict[tuple[str, str, str, str], RawSense] = {}
    for row in raw_rows:
        key = (row.pool, row.normalized_word, row.pos, normalize_definition(row.definition_zh))
        deduplicated.setdefault(key, row)
    raw_rows = list(deduplicated.values())

    grouped: dict[tuple[str, str], list[RawSense]] = defaultdict(list)
    for row in raw_rows:
        grouped[(row.normalized_word, row.pos)].append(row)

    master: list[dict[str, object]] = []
    for (normalized_word, pos), entries in grouped.items():
        entries.sort(
            key=lambda item: (
                POOL_RANK[item.pool],
                normalize_definition(item.definition_zh),
                item.source_location,
            )
        )
        clusters: list[list[RawSense]] = []
        for entry in entries:
            placed = False
            for cluster in clusters:
                exact_match = any(
                    normalize_definition(entry.definition_zh)
                    == normalize_definition(candidate.definition_zh)
                    for candidate in cluster
                )
                canonical = choose_canonical(cluster)
                if exact_match or definitions_are_close(
                    entry.definition_zh, canonical.definition_zh
                ):
                    cluster.append(entry)
                    placed = True
                    break
            if not placed:
                clusters.append([entry])

        exact_merged_clusters: list[list[RawSense]] = []
        for cluster in clusters:
            cluster_keys = {
                normalize_definition(item.definition_zh) for item in cluster
            }
            destination = next(
                (
                    existing
                    for existing in exact_merged_clusters
                    if cluster_keys
                    & {
                        normalize_definition(item.definition_zh)
                        for item in existing
                    }
                ),
                None,
            )
            if destination is None:
                exact_merged_clusters.append(cluster)
            else:
                destination.extend(cluster)
        clusters = exact_merged_clusters

        for cluster in clusters:
            canonical = choose_canonical(cluster)
            definitions = sorted(
                {entry.definition_zh for entry in cluster},
                key=lambda value: (len(normalize_definition(value)), value),
            )
            pools = sorted({entry.pool for entry in cluster}, key=POOL_RANK.__getitem__)
            pronunciations = sorted({entry.pronunciation for entry in cluster if entry.pronunciation})
            source_refs = sorted(
                {
                    f"{entry.pool}:{entry.source_file}:{entry.source_location}"
                    for entry in cluster
                }
            )
            sense_seed = min(normalize_definition(entry.definition_zh) for entry in cluster)
            master.append(
                {
                    "word_id": stable_id("w", normalized_word),
                    "sense_id": stable_id("s", normalized_word, pos, sense_seed),
                    "word": canonical.word,
                    "normalized_word": normalized_word,
                    "initial": first_letter(normalized_word),
                    "term_type": canonical.term_type,
                    "pos": pos,
                    "definition_zh": canonical.definition_zh,
                    "source_definition_variants": definitions,
                    "pronunciations": pronunciations,
                    "pools": pools,
                    "pool_flags": {pool: pool in pools for pool in POOL_ORDER},
                    "lookup_key": f"{first_letter(normalized_word)}|{normalized_word}|{pos}",
                    "source_refs": source_refs,
                }
            )

    master.sort(
        key=lambda row: (
            row["initial"] == "#",
            row["initial"],
            row["normalized_word"],
            row["pos"],
            row["definition_zh"],
        )
    )
    raw_export = [asdict(row) for row in raw_rows]
    raw_export.sort(
        key=lambda row: (
            POOL_RANK[row["pool"]],
            row["normalized_word"],
            row["pos"],
            row["definition_zh"],
        )
    )
    return master, raw_export


def write_flat_csv(path: Path, master: list[dict[str, object]]) -> None:
    columns = [
        "word_id",
        "sense_id",
        "word",
        "normalized_word",
        "initial",
        "term_type",
        "pos",
        "definition_zh",
        "pools",
        *[f"pool_{pool}" for pool in POOL_ORDER],
        "lookup_key",
        "pronunciations",
        "source_definition_variants",
        "source_refs",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for row in master:
            writer.writerow(
                {
                    **{key: row.get(key, "") for key in columns},
                    "pools": "、".join(row["pools"]),
                    **{
                        f"pool_{pool}": "1" if row["pool_flags"][pool] else "0"
                        for pool in POOL_ORDER
                    },
                    "pronunciations": " | ".join(row["pronunciations"]),
                    "source_definition_variants": " | ".join(row["source_definition_variants"]),
                    "source_refs": " | ".join(row["source_refs"]),
                }
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--converted-root", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-csv", type=Path, required=True)
    parser.add_argument("--stats-json", type=Path, required=True)
    args = parser.parse_args()

    source_root = args.source_root
    converted_root = args.converted_root
    raw_rows: list[RawSense] = []
    raw_rows.extend(read_junior(converted_root / "初中单词.docx"))
    raw_rows.extend(
        read_sheet(
            converted_root / "高中单词.xlsx",
            pool="高中",
            source_file="高中单词.xls",
            sheet_name="1",
            start_row=2,
            word_column=1,
            pronunciation_column=2,
            definition_column=3,
        )
    )
    for pool, filename in (("四级", "四级单词.xlsx"), ("六级", "六级单词.xlsx")):
        raw_rows.extend(
            read_sheet(
                converted_root / filename,
                pool=pool,
                source_file=filename.replace(".xlsx", ".xls"),
                sheet_name="单词",
                start_row=2,
                word_column=1,
                pronunciation_column=2,
                definition_column=3,
            )
        )
    raw_rows.extend(
        read_sheet(
            converted_root / "考研单词.xlsx",
            pool="考研",
            source_file="考研单词.xls",
            sheet_name="Sheet1",
            start_row=1,
            word_column=0,
            pronunciation_column=1,
            definition_column=2,
        )
    )
    raw_rows.extend(
        read_sheet(
            source_root / "托福单词.xlsx",
            pool="托福",
            source_file="托福单词.xlsx",
            sheet_name="Sheet1",
            start_row=2,
            word_column=1,
            pronunciation_column=2,
            definition_column=3,
        )
    )
    raw_rows.extend(
        read_sheet(
            converted_root / "雅思单词.xlsx",
            pool="雅思",
            source_file="雅思单词.xls",
            sheet_name="全部9400",
            start_row=1,
            word_column=1,
            pronunciation_column=None,
            definition_column=2,
        )
    )
    raw_rows.extend(
        read_sheet(
            converted_root / "GRE单词.xlsx",
            pool="GRE",
            source_file="GRE单词.xls",
            sheet_name="正序",
            start_row=2,
            word_column=1,
            pronunciation_column=None,
            definition_column=2,
        )
    )

    master, raw_export = merge_senses(raw_rows)
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema_version": 1,
        "pool_order": POOL_ORDER,
        "master_senses": master,
        "raw_senses": raw_export,
        "review_items": REVIEW_ITEMS,
    }
    args.output_json.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    write_flat_csv(args.output_csv, master)

    unique_words_by_pool = {
        pool: len({row.normalized_word for row in raw_rows if row.pool == pool})
        for pool in POOL_ORDER
    }
    raw_senses_by_pool = Counter(row.pool for row in raw_rows)
    master_senses_by_pool = {
        pool: sum(1 for row in master if row["pool_flags"][pool]) for pool in POOL_ORDER
    }
    stats = {
        "raw_senses": len(raw_rows),
        "deduplicated_raw_senses": len(raw_export),
        "master_senses": len(master),
        "unique_words": len({row["normalized_word"] for row in master}),
        "unique_words_by_pool": unique_words_by_pool,
        "raw_senses_by_pool": dict(raw_senses_by_pool),
        "master_senses_by_pool": master_senses_by_pool,
        "unmarked_pos_senses": sum(1 for row in master if row["pos"] == "未标注"),
        "long_phrase_senses": sum(1 for row in master if row["term_type"] == "长词组"),
        "review_items": len(REVIEW_ITEMS),
    }
    args.stats_json.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
