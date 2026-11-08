import { createHash } from "node:crypto";
import { UnprocessableEntityException } from "@nestjs/common";
import type { CheckContentSafety } from "@reallanguage/backend-modules/safety";
import type { BilingualPassagesRequest } from "@reallanguage/contracts";

type Command = BilingualPassagesRequest & { accountId: string };
type Draft = { textZh: string; textEn: string };
type Passage = Draft & { sentences: Draft[] };
type BatchDraft = Draft & { sourceText: string; continuesPrevious: boolean };
type MeteredCall = (
  task: string,
  input: Record<string, unknown>,
  identity: string,
) => Promise<unknown>;

const WORDING = "BILINGUAL_PASSAGE_WORDING_V1";
const VERIFY = "BILINGUAL_PASSAGE_FIDELITY_V1";
const SPLIT = "BILINGUAL_ENGLISH_UNITS_V1";
const compact = (s: string) => s.replace(/\s+/gu, "");
const normalized = (s: string) => s.trim().replace(/\s+/gu, " ");
const isHan = (s: string) => /\p{Script=Han}/u.test(s);
const words = (s: string) =>
  s
    .toLowerCase()
    .replace(/[’‘]/gu, "'")
    .match(/[a-z]+(?:'[a-z]+)?/gu) ?? [];
const nonempty = (v: unknown): v is string =>
  typeof v === "string" && v.trim().length > 0;
const object = (v: unknown): Record<string, unknown> =>
  typeof v === "object" && v !== null ? (v as Record<string, unknown>) : {};

// A supplier request budget, not a product sentence/paragraph limit. Keep reading context across batches.
export function textBatches(text: string, budget = 1800): string[] {
  const result: string[] = [];
  let start = 0;
  while (text.length - start > budget) {
    const sample = text.slice(start, start + budget);
    let end = -1;
    for (const boundary of [
      /\n+/gu,
      /[。！？.!?；;][”’"'）)]*\s*/gu,
      /[，,、:]\s*|\s+/gu,
    ]) {
      for (const match of sample.matchAll(boundary)) {
        const e = match.index + match[0].length;
        if (e >= budget * 0.5) end = e;
      }
      if (end > 0) break;
    }
    if (end < 1)
      end = /[\uD800-\uDBFF]$/u.test(sample)
        ? sample.length - 1
        : sample.length;
    result.push(text.slice(start, start + end));
    start += end;
  }
  if (start < text.length) result.push(text.slice(start));
  return result;
}

// Turn headings from scene generation/transcripts are layout metadata, not words to study or pronounce.
const role =
  "(?:用户|对方|说话人\\s*[A-Za-z0-9一二三四五六七八九十]*|顾客|店员|服务员|收银员|User|Other person|Other party|Speaker(?:\\s+[A-Za-z0-9]+)?|Customer|Cashier|Barista|Server|Clerk|Partner|Interlocutor|Assistant)";
const sourceHeadings = () =>
  new RegExp(`(?:^|\\n|(?<=[。！？!?]))\\s*${role}\\s*[：:]`, "giu");
const leadingRole = new RegExp(`^\\s*${role}\\s*[：:]\\s*`, "iu");
const englishRole =
  /(?:^|\s)(?:user|other person|other party|speaker(?:\s+[A-Za-z0-9]+)?|customer|cashier|barista|server|clerk|partner|interlocutor|assistant)\s*[:：]/iu;

function readDraft(raw: unknown, source: string): BatchDraft[] {
  const paragraphs = object(raw).paragraphs;
  if (!Array.isArray(paragraphs) || !paragraphs.length)
    throw new Error("缺少段落");
  const rows = paragraphs.map((item, index): BatchDraft => {
    const { sourceText, textZh, textEn, continuesPrevious } = object(item);
    if (
      !nonempty(sourceText) ||
      !nonempty(textEn) ||
      !nonempty(textZh) ||
      typeof continuesPrevious !== "boolean"
    )
      throw new Error("段落字段不完整");
    if (!isHan(textZh) && isHan(sourceText)) throw new Error("缺少中文原文");
    if (isHan(textEn) || !/[a-z]/iu.test(textEn))
      throw new Error("缺少英文段落");
    if (Array.from(sourceText.matchAll(sourceHeadings())).length > 1)
      throw new Error("不同说话人要分段。每名说话人独占一个对应段落。");
    const spokenSource = sourceText.trim().replace(leadingRole, "");
    const english = isHan(spokenSource) ? textEn.trim() : spokenSource;
    if (englishRole.test(english))
      throw new Error(
        "英文段落只能含实际说出的话。角色标签留在中文侧，不得朗读。",
      );
    if (
      isHan(spokenSource) &&
      /付款|刷卡|信用卡/u.test(spokenSource) &&
      /\bI accept[.!?]|\bplease help me pay (?:with|by)/iu.test(english)
    )
      throw new Error(
        "请用日常结账口语表达接受价格和付款请求，保留信用卡方式。",
      );

    // Colloquial 块 describes an amount but does not automatically make the transaction American.
    if (
      /[零一二两三四五六七八九十百千万\d]\s*块/u.test(spokenSource) &&
      !/美元|美金|美国|纽约|人民币|元人民币|中国大陆|欧元|英镑|日元|加元|澳元|港币|港元|台币|dollars?|yuan|euros?|pounds?|yen|USD|CNY|RMB/iu.test(
        source,
      ) &&
      /\b(?:dollars?|bucks?|euros?|pounds?|yen|yuan|USD|CNY|RMB)\b|[$€£¥]/iu.test(
        english,
      )
    )
      throw new Error(
        "币种未说明，保留人民币口语单位 kuai（如 one extra kuai）；不得假设 dollar、yuan 或任何具体币种。",
      );
    const englishSource =
      spokenSource.match(
        /[A-Za-z]+(?:['’][A-Za-z]+)?(?:[\s,;:!?."“”]+[A-Za-z]+(?:['’][A-Za-z]+)?){2,}/gu,
      ) ?? [];
    const allWords = " " + words(english).join(" ") + " ";
    for (const piece of englishSource)
      if (!allWords.includes(" " + words(piece).join(" ") + " "))
        throw new Error("原有英文被改写");
    const chinese =
      isHan(sourceText) && !/[a-z]{2,}\s+[a-z]{2,}/iu.test(sourceText)
        ? sourceText.trim()
        : textZh.trim();
    return {
      sourceText,
      textZh: chinese,
      textEn: english,
      continuesPrevious:
        index === 0 && continuesPrevious && !leadingRole.test(sourceText),
    };
  });
  if (compact(rows.map((r) => r.sourceText).join("")) !== compact(source))
    throw new Error("原文覆盖不完整");
  return rows;
}

function readUnits(
  raw: unknown,
  originals: { id: number; textEn: string }[],
): Draft[][] {
  const output = object(raw).paragraphs;
  if (!Array.isArray(output) || output.length !== originals.length)
    throw new Error("英文段落对应错误");
  return output.map((item, i) => {
    const row = object(item);
    if (
      row.id !== originals[i]!.id ||
      !Array.isArray(row.sentences) ||
      !row.sentences.length
    )
      throw new Error("缺少可学习的英文短句");
    const sentences = row.sentences.map((v: unknown) => {
      const { textEn, textZh } = object(v);
      if (!nonempty(textEn) || !nonempty(textZh))
        throw new Error("短句内容不完整");
      return { textEn: textEn.trim(), textZh: textZh.trim() };
    });
    if (
      normalized(sentences.map((s) => s.textEn).join(" ")) !==
      normalized(originals[i]!.textEn)
    )
      throw new Error("英文未按原文完整切分");
    return sentences;
  });
}

export async function generateBilingualPassages(
  command: Command,
  safety: Pick<CheckContentSafety, "execute">,
  supplier: MeteredCall,
  checkActive: () => void = () => {},
): Promise<{ paragraphs: Passage[] }> {
  const call: MeteredCall = async (task, input, identity) => {
    try {
      return await supplier(task, input, identity);
    } catch (error) {
      const code = object(error).code;
      const message = error instanceof Error ? error.message : "";
      const status = /status (\d{3})/u.exec(message)?.[1];
      console.warn("Bilingual supplier failure", {
        task,
        code:
          typeof code === "string" && /^[A-Z_]+$/u.test(code)
            ? code
            : "PROVIDER_ERROR",
        status: status ?? null,
      });
      throw error;
    }
  };
  const identity = createHash("sha256")
    .update(
      JSON.stringify([
        command.commandId,
        command.text,
        command.sourceType,
        command.contentShape,
      ]),
    )
    .digest("hex")
    .slice(0, 26);
  const batches = textBatches(command.text).filter((v) => v.trim());
  const sourceType =
    command.sourceType === "SCREENSHOT"
      ? "DIARY"
      : command.sourceType === "AUDIO_IMPORT"
        ? "RECORDING"
        : command.sourceType;
  // Screen the entire input before the first wording call; a late blocked excerpt cannot yield a partial course.
  for (const [i, text] of batches.entries()) {
    checkActive();
    const check = await safety.execute({
      ...command,
      text,
      sourceType,
      commandId: `bp-${identity}-safe-${i}`,
    });
    if (check.status === "BLOCKED")
      throw new UnprocessableEntityException({
        error: {
          code: "CONTENT_BLOCKED",
          message: "这段内容无法用于生成学习材料。",
          safetyCaseId: check.safetyCaseId,
        },
      });
  }
  const drafts: Draft[] = [];
  let priorSource = "";
  for (const [i, text] of batches.entries()) {
    const input = {
      text,
      sourceType: command.sourceType,
      contentShape: command.contentShape,
      previousContext: priorSource,
    };
    let correction = "";
    for (let pass = 0; pass < 2; pass++) {
      const id = `bp-turn3-${identity}-${i}-${pass}`;
      checkActive();
      const generated = await call(
        WORDING,
        { ...input, correction },
        id + "-draft",
      );
      let rows: BatchDraft[];
      try {
        rows = readDraft(generated, text);
      } catch (error) {
        if (pass === 1) {
          console.warn("Bilingual wording invalid after correction");
          throw error;
        }
        correction =
          error instanceof Error ? error.message : "请检查原文完整性";
        continue;
      }
      checkActive();
      const verification = await call(
        VERIFY,
        {
          source: text,
          paragraphs: rows.map(({ sourceText, textZh, textEn }) => ({
            sourceText,
            textZh,
            textEn,
          })),
        },
        id + "-verify",
      );
      if (object(verification).faithful !== true) {
        correction = "原文事实、语气或完整性需要复核";
        if (pass === 1) {
          console.warn("Bilingual paragraph fidelity check failed");
          throw new Error(correction);
        }
        continue;
      }
      for (const row of rows) {
        const prior = drafts.at(-1);
        if (row.continuesPrevious && prior) {
          prior.textZh +=
            (/\p{Script=Han}|[。！？]/u.test(prior.textZh.slice(-1))
              ? ""
              : " ") + row.textZh;
          prior.textEn += " " + row.textEn;
        } else drafts.push({ textZh: row.textZh, textEn: row.textEn });
      }
      break;
    }
    priorSource = text.slice(-480);
  }
  const result = drafts.map((draft) => ({
    ...draft,
    sentences: [] as Draft[],
  }));
  // Split only the English already accepted above. Each processing piece retains its original paragraph.
  const groups: { paragraph: number; textEn: string }[][] = [];
  let group: { paragraph: number; textEn: string }[] = [];
  let size = 0;
  for (const [paragraph, row] of drafts.entries()) {
    // Never cut an existing English word. A very long unbroken token is one study unit.
    const pieces = row.textEn.match(/\S+\s*/gu) ?? [];
    let part = "";
    const flush = () => {
      if (!part.trim()) return;
      if (size + part.length > 2300 && group.length) {
        groups.push(group);
        group = [];
        size = 0;
      }
      group.push({ paragraph, textEn: part.trim() });
      size += part.length;
      part = "";
    };
    for (const word of pieces) {
      if (part.length > 1700 && /[.!?][”'"]?\s*$/u.test(part)) flush();
      part += word;
    }
    flush();
  }
  if (group.length) groups.push(group);
  for (const [i, batch] of groups.entries()) {
    const input = batch.map(({ textEn }, id) => ({ id, textEn }));
    for (let pass = 0; pass < 2; pass++) {
      checkActive();
      const raw = await call(
        SPLIT,
        {
          paragraphs: input,
          correction:
            pass === 1
              ? "Copy every English word and punctuation mark exactly in order."
              : "",
        },
        `bp-turn3-${identity}-units-${i}-${pass}`,
      );
      try {
        const units = readUnits(raw, input);
        units.forEach((sentences, n) =>
          result[batch[n]!.paragraph]!.sentences.push(...sentences),
        );
        break;
      } catch (error) {
        if (pass === 1) throw error;
      }
    }
  }
  for (const row of result)
    if (
      !row.sentences.length ||
      normalized(row.sentences.map((s) => s.textEn).join(" ")) !==
        normalized(row.textEn)
    )
      throw new Error("英文段落未完整覆盖");
  return { paragraphs: result };
}
