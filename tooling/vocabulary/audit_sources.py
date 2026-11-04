from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import openpyxl
from docx import Document


def display_value(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def audit_workbook(path: Path) -> dict[str, Any]:
    workbook = openpyxl.load_workbook(path, read_only=True, data_only=True)
    sheets: list[dict[str, Any]] = []
    for sheet in workbook.worksheets:
        rows = [
            [display_value(cell) for cell in row]
            for row in sheet.iter_rows(values_only=True)
        ]
        nonempty = [row for row in rows if any(row)]
        widths = max((len(row) for row in nonempty), default=0)
        sheets.append(
            {
                "title": sheet.title,
                "max_row": sheet.max_row,
                "max_column": sheet.max_column,
                "nonempty_rows": len(nonempty),
                "sample_head": nonempty[:8],
                "sample_tail": nonempty[-5:],
                "observed_width": widths,
            }
        )
    workbook.close()
    return {"path": str(path), "kind": "workbook", "sheets": sheets}


def audit_document(path: Path) -> dict[str, Any]:
    document = Document(path)
    paragraphs = [p.text.strip() for p in document.paragraphs if p.text.strip()]
    tables: list[dict[str, Any]] = []
    for table_index, table in enumerate(document.tables):
        rows = []
        for row in table.rows:
            values = [cell.text.strip() for cell in row.cells]
            if any(values):
                rows.append(values)
        tables.append(
            {
                "index": table_index,
                "rows": len(rows),
                "columns": max((len(row) for row in rows), default=0),
                "sample_head": rows[:8],
                "sample_tail": rows[-5:],
            }
        )
    return {
        "path": str(path),
        "kind": "document",
        "paragraph_count": len(paragraphs),
        "sample_head": paragraphs[:20],
        "sample_tail": paragraphs[-10:],
        "tables": tables,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("paths", nargs="+")
    args = parser.parse_args()

    reports = []
    for raw_path in args.paths:
        path = Path(raw_path)
        if path.suffix.lower() == ".docx":
            reports.append(audit_document(path))
        else:
            reports.append(audit_workbook(path))
    Path(args.output).write_text(
        json.dumps(reports, ensure_ascii=False, indent=2), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
