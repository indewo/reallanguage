import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const [, , inputJsonPath, statsJsonPath, outputPath, qaDirectory] = process.argv;
if (!inputJsonPath || !statsJsonPath || !outputPath || !qaDirectory) {
  throw new Error(
    "Usage: node build_master_workbook.mjs <master.json> <stats.json> <output.xlsx> <qa-dir>",
  );
}

const payload = JSON.parse(await fs.readFile(inputJsonPath, "utf8"));
const stats = JSON.parse(await fs.readFile(statsJsonPath, "utf8"));
const pools = payload.pool_order;
const master = payload.master_senses;
const reviewItems = payload.review_items;
delete payload.raw_senses;

const workbook = Workbook.create();
const summarySheet = workbook.worksheets.add("导入说明");
const masterSheet = workbook.worksheets.add("总词表");
const indexSheet = workbook.worksheets.add("词条索引");
const reviewSheet = workbook.worksheets.add("待核对");

const palette = {
  ink: "#102A43",
  muted: "#52606D",
  paper: "#FFFDF7",
  blue: "#476B82",
  green: "#647A61",
  terracotta: "#BE5B43",
  gold: "#B38B49",
  line: "#D8D0C3",
  paleBlue: "#EAF1F5",
  paleGreen: "#EDF2EA",
  paleGold: "#F6EEDB",
};
const fontFamily = "Aptos";

function writeRows(sheet, startRow, rows, columnCount, chunkSize = 1500) {
  for (let offset = 0; offset < rows.length; offset += chunkSize) {
    const chunk = rows.slice(offset, offset + chunkSize).map((row) =>
      row.map((value) =>
        typeof value === "string" && value.startsWith("=") ? `'${value}` : value,
      ),
    );
    sheet
      .getRangeByIndexes(startRow + offset, 0, chunk.length, columnCount)
      .writeValues(chunk);
  }
}

function setColumnWidth(sheet, columnLetter, width, lastRow) {
  sheet.getRange(`${columnLetter}1:${columnLetter}${lastRow}`).format.columnWidth = width;
}

function styleDataSheet(sheet, headerRange, usedRange, wrapRanges = []) {
  sheet.showGridLines = false;
  sheet.freezePanes.freezeRows(1);
  headerRange.format = {
    fill: palette.blue,
    font: { name: fontFamily, size: 11, bold: true, color: "#FFFFFF" },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    wrapText: true,
    borders: { preset: "all", style: "thin", color: palette.line },
  };
  headerRange.format.rowHeight = 32;
  usedRange.format.font = { name: fontFamily, size: 10, color: palette.ink };
  usedRange.format.verticalAlignment = "center";
  usedRange.format.borders = {
    insideHorizontal: { style: "thin", color: "#E9E4DB" },
    bottom: { style: "thin", color: palette.line },
  };
  for (const range of wrapRanges) {
    sheet.getRange(range).format.wrapText = true;
    sheet.getRange(range).format.verticalAlignment = "top";
  }
}

summarySheet.showGridLines = false;
summarySheet.tabColor = palette.terracotta;
summarySheet.mergeCells("A1:H2");
summarySheet.getRange("A1").values = [["RealLanguage 统一总词表"]];
summarySheet.getRange("A1:H2").format = {
  fill: palette.ink,
  font: { name: fontFamily, size: 22, bold: true, color: "#FFFFFF" },
  horizontalAlignment: "center",
  verticalAlignment: "center",
};
summarySheet.mergeCells("A3:H3");
summarySheet.getRange("A3").values = [[
  "8 个平级单词池｜同词同词类近义合并｜跨池共享词义状态｜不新增任何源文件之外的释义",
]];
summarySheet.getRange("A3:H3").format = {
  fill: palette.paleBlue,
  font: { name: fontFamily, size: 11, italic: true, color: palette.muted },
  horizontalAlignment: "center",
  verticalAlignment: "center",
};

summarySheet.getRange("A5:B10").values = [
  ["指标", "数量"],
  ["规范词头", stats.unique_words],
  ["规范词义", stats.master_senses],
  ["去重后的来源词义", stats.deduplicated_raw_senses],
  ["词类待核对", stats.unmarked_pos_senses],
  ["源文件缺释义／格式损坏", stats.review_items],
];
summarySheet.getRange("D5:F13").values = [
  ["单词池", "唯一词头", "规范词义"],
  ...pools.map((pool) => [
    pool,
    stats.unique_words_by_pool[pool],
    stats.master_senses_by_pool[pool],
  ]),
];
summarySheet.getRange("A12:H18").values = [
  ["规则摘要", "", "", "", "", "", "", ""],
  ["1", "只保留初中、高中、四级、六级、考研、托福、雅思、GRE；八池平级单选。", "", "", "", "", "", ""],
  ["2", "总词表按 A–Z 排序；初中原表已纳入统一排序。", "", "", "", "", "", ""],
  ["3", "同词＋同词类＋相同或高度近似释义才合并；规范释义逐字取自源文件。", "", "", "", "", "", ""],
  ["4", "每个词义预存 8 个池标记、首字母、规范词头和查找键，切换时不做全表计算。", "", "", "", "", "", ""],
  ["5", "学习状态绑定全局 Sense ID；跨池共享，同词不同义不串状态。", "", "", "", "", "", ""],
  ["6", "完整句子不能作为单词；多词条目仅在源词表明确提供时保留。", "", "", "", "", "", ""],
];
for (let row = 13; row <= 18; row += 1) summarySheet.mergeCells(`B${row}:H${row}`);

summarySheet.getRange("A5:B5").format = {
  fill: palette.green,
  font: { name: fontFamily, bold: true, color: "#FFFFFF" },
  horizontalAlignment: "center",
};
summarySheet.getRange("D5:F5").format = {
  fill: palette.gold,
  font: { name: fontFamily, bold: true, color: "#FFFFFF" },
  horizontalAlignment: "center",
};
summarySheet.getRange("A12:H12").format = {
  fill: palette.terracotta,
  font: { name: fontFamily, bold: true, color: "#FFFFFF" },
};
summarySheet.getRange("A5:B10").format.borders = {
  preset: "all",
  style: "thin",
  color: palette.line,
};
summarySheet.getRange("D5:F13").format.borders = {
  preset: "all",
  style: "thin",
  color: palette.line,
};
summarySheet.getRange("A13:H18").format = {
  fill: palette.paper,
  font: { name: fontFamily, size: 11, color: palette.ink },
  wrapText: true,
  verticalAlignment: "center",
  borders: { preset: "all", style: "thin", color: palette.line },
};
summarySheet.getRange("A13:A18").format = {
  fill: palette.paleGold,
  font: { name: fontFamily, bold: true, color: palette.terracotta },
  horizontalAlignment: "center",
  verticalAlignment: "center",
  borders: { preset: "all", style: "thin", color: palette.line },
};
summarySheet.getRange("A1:H18").format.font.name = fontFamily;
summarySheet.getRange("A13:H18").format.rowHeight = 31;
setColumnWidth(summarySheet, "A", 18, 18);
setColumnWidth(summarySheet, "B", 18, 18);
setColumnWidth(summarySheet, "C", 4, 18);
setColumnWidth(summarySheet, "D", 16, 18);
setColumnWidth(summarySheet, "E", 16, 18);
setColumnWidth(summarySheet, "F", 16, 18);
setColumnWidth(summarySheet, "G", 16, 18);
setColumnWidth(summarySheet, "H", 16, 18);

const masterHeaders = [
  "序号",
  "英文",
  "词类",
  "中文释义",
  "隶属单词池",
  ...pools,
  "词条类型",
  "规范词头",
  "首字母",
  "音标",
  "查找键",
  "Word ID",
  "Sense ID",
  "源释义变体",
  "来源定位",
];
masterSheet.getRangeByIndexes(0, 0, 1, masterHeaders.length).values = [masterHeaders];
const masterRows = master.map((row, index) => [
  index + 1,
  row.word,
  row.pos,
  row.definition_zh,
  row.pools.join("、"),
  ...pools.map((pool) => (row.pool_flags[pool] ? "✓" : "")),
  row.term_type,
  row.normalized_word,
  row.initial,
  row.pronunciations.join(" | "),
  row.lookup_key,
  row.word_id,
  row.sense_id,
  row.source_definition_variants.join(" | "),
  row.source_refs.join(" | "),
]);
writeRows(masterSheet, 1, masterRows, masterHeaders.length);
const masterLastRow = masterRows.length + 1;
styleDataSheet(
  masterSheet,
  masterSheet.getRange(`A1:V1`),
  masterSheet.getRange(`A2:V${masterLastRow}`),
  [`D2:E${masterLastRow}`, `Q2:V${masterLastRow}`],
);
masterSheet.freezePanes.freezeColumns(2);
masterSheet.tabColor = palette.blue;
[
  ["A", 9], ["B", 24], ["C", 11], ["D", 36], ["E", 25],
  ["F", 8], ["G", 8], ["H", 8], ["I", 8], ["J", 8], ["K", 8], ["L", 8], ["M", 8],
  ["N", 12], ["O", 24], ["P", 9], ["Q", 24], ["R", 34], ["S", 24], ["T", 24], ["U", 48], ["V", 54],
].forEach(([column, width]) => setColumnWidth(masterSheet, column, width, masterLastRow));
masterSheet.tables.add(`A1:V${masterLastRow}`, true, "MasterVocabularyTable");

const words = new Map();
for (const sense of master) {
  const existing = words.get(sense.word_id) ?? {
    word_id: sense.word_id,
    word: sense.word,
    normalized_word: sense.normalized_word,
    initial: sense.initial,
    term_type: sense.term_type,
    sense_count: 0,
    pools: new Set(),
  };
  existing.sense_count += 1;
  for (const pool of sense.pools) existing.pools.add(pool);
  words.set(sense.word_id, existing);
}
const wordRows = [...words.values()]
  .sort((a, b) =>
    `${a.initial}|${a.normalized_word}`.localeCompare(`${b.initial}|${b.normalized_word}`, "en"),
  )
  .map((word, index) => [
    index + 1,
    word.word,
    word.normalized_word,
    word.initial,
    word.term_type,
    word.sense_count,
    pools.filter((pool) => word.pools.has(pool)).join("、"),
    ...pools.map((pool) => (word.pools.has(pool) ? "✓" : "")),
    word.word_id,
  ]);
const indexHeaders = [
  "序号", "英文", "规范词头", "首字母", "词条类型", "词义数", "隶属单词池", ...pools, "Word ID",
];
indexSheet.getRangeByIndexes(0, 0, 1, indexHeaders.length).values = [indexHeaders];
writeRows(indexSheet, 1, wordRows, indexHeaders.length);
const indexLastRow = wordRows.length + 1;
styleDataSheet(
  indexSheet,
  indexSheet.getRange("A1:P1"),
  indexSheet.getRange(`A2:P${indexLastRow}`),
  [`G2:G${indexLastRow}`],
);
indexSheet.freezePanes.freezeColumns(2);
indexSheet.tabColor = palette.green;
[
  ["A", 9], ["B", 25], ["C", 25], ["D", 9], ["E", 12], ["F", 10], ["G", 28],
  ["H", 8], ["I", 8], ["J", 8], ["K", 8], ["L", 8], ["M", 8], ["N", 8], ["O", 8], ["P", 24],
].forEach(([column, width]) => setColumnWidth(indexSheet, column, width, indexLastRow));
indexSheet.tables.add(`A1:P${indexLastRow}`, true, "VocabularyWordIndexTable");

const unmarkedRows = master
  .filter((row) => row.pos === "未标注")
  .map((row) => [
    "词类待核对",
    row.pools.join("、"),
    row.word,
    row.definition_zh,
    row.source_refs.join(" | "),
    "源文件没有可确认的词类标记；未自行补写",
  ]);
const missingRows = reviewItems.map((row) => [
  row.reason,
  row.pool,
  row.raw_word,
  row.raw_definition,
  `${row.source_file}:${row.source_location}`,
  "保留原记录，未自行补写",
]);
const reviewRows = [...unmarkedRows, ...missingRows];
const reviewHeaders = ["问题类型", "单词池", "源词头", "源释义", "来源定位", "处理结果"];
reviewSheet.getRange("A1:F1").values = [reviewHeaders];
writeRows(reviewSheet, 1, reviewRows, reviewHeaders.length);
const reviewLastRow = reviewRows.length + 1;
styleDataSheet(
  reviewSheet,
  reviewSheet.getRange("A1:F1"),
  reviewSheet.getRange(`A2:F${reviewLastRow}`),
  [`C2:F${reviewLastRow}`],
);
reviewSheet.tabColor = palette.terracotta;
[
  ["A", 22], ["B", 14], ["C", 26], ["D", 46], ["E", 52], ["F", 34],
].forEach(([column, width]) => setColumnWidth(reviewSheet, column, width, reviewLastRow));
reviewSheet.tables.add(`A1:F${reviewLastRow}`, true, "VocabularyReviewTable");

workbook.recalculate();
await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.mkdir(qaDirectory, { recursive: true });

const inspections = [];
for (const sheet of [summarySheet, masterSheet, indexSheet, reviewSheet]) {
  const range =
    sheet.name === "导入说明"
      ? "A1:H18"
      : sheet.name === "总词表"
        ? "A1:V22"
        : sheet.name === "词条索引"
          ? "A1:P22"
          : `A1:F${Math.min(reviewLastRow, 28)}`;
  const preview = await workbook.render({ sheetName: sheet.name, range, format: "png" });
  const safeName = sheet.name.replaceAll("/", "-");
  await fs.writeFile(
    path.join(qaDirectory, `${safeName}.png`),
    new Uint8Array(await preview.arrayBuffer()),
  );
  inspections.push(
    (await workbook.inspect({
      kind: "region",
      sheetId: sheet.name,
      range,
      maxChars: 2500,
      tableMaxRows: 6,
      tableMaxCols: 8,
    })).ndjson,
  );
}
await fs.writeFile(path.join(qaDirectory, "inspection.ndjson"), inspections.join("\n"), "utf8");

const xlsx = await SpreadsheetFile.exportXlsx(workbook);
await xlsx.save(outputPath);
console.log(JSON.stringify({ outputPath, qaDirectory, sheetCount: 4, masterRows: master.length }));
