import { describe, expect, it, vi } from "vitest";
import {
  generateBilingualPassages,
  textBatches,
} from "./generate-bilingual-passages.js";

const cmd = {
  accountId: "test",
  commandId: "review",
  sourceType: "DIARY" as const,
  contentShape: "ARTICLE" as const,
  text: "我需要再找找发票。",
};
const safety = { execute: async () => ({ status: "PASSED" as const }) };
const wording = {
  paragraphs: [
    {
      sourceText: cmd.text,
      textZh: cmd.text,
      textEn: "I still need to track down that receipt.",
      continuesPrevious: false,
    },
  ],
};
const sentences = {
  paragraphs: [
    {
      id: 0,
      sentences: [
        {
          textEn: "I still need to track down that receipt.",
          textZh: "我还得找到那张发票。",
        },
      ],
    },
  ],
};
describe("whole-paragraph bilingual formation", () => {
  it("drafts a paragraph, validates its fidelity, then splits only the accepted English", async () => {
    const tasks: string[] = [];
    const result = await generateBilingualPassages(
      cmd,
      safety,
      async (task, input) => {
        tasks.push(task);
        if (task.includes("WORDING")) return wording;
        if (task.includes("FIDELITY")) return { faithful: true };
        expect(input).toEqual({
          paragraphs: [{ id: 0, textEn: wording.paragraphs[0]!.textEn }],
          correction: "",
        });
        return sentences;
      },
    );
    expect(tasks).toEqual([
      "BILINGUAL_PASSAGE_WORDING_V1",
      "BILINGUAL_PASSAGE_FIDELITY_V1",
      "BILINGUAL_ENGLISH_UNITS_V1",
    ]);
    expect(result.paragraphs[0]?.sentences).toEqual(
      sentences.paragraphs[0]?.sentences,
    );
  });
  it("fails rather than returning omissions, fabrications or rewritten English units", async () => {
    for (const issue of ["missing", "fidelity", "rewrite"]) {
      await expect(
        generateBilingualPassages(cmd, safety, async (task) => {
          if (task.includes("WORDING"))
            return issue === "missing"
              ? {
                  paragraphs: [
                    { ...wording.paragraphs[0], sourceText: "少。" },
                  ],
                }
              : wording;
          if (task.includes("FIDELITY"))
            return { faithful: issue !== "fidelity" };
          return {
            paragraphs: [
              {
                id: 0,
                sentences: [
                  { textEn: "I'll find it.", textZh: "我会找到的。" },
                ],
              },
            ],
          };
        }),
      ).rejects.toThrow();
    }
  });
  it("checks all source batches before delivering any of them, even when a late batch is blocked", async () => {
    const long = {
      ...cmd,
      text: "周一讨论了报销。".repeat(450) + "另外一件事。",
    };
    const ai = vi.fn();
    const chunks = textBatches(long.text);
    expect(chunks.length).toBeGreaterThan(1);
    expect(chunks.join("")).toBe(long.text);
    let n = 0;
    await expect(
      generateBilingualPassages(
        long,
        {
          execute: async () =>
            ++n === chunks.length
              ? { status: "BLOCKED", safetyCaseId: "synthetic" }
              : { status: "PASSED" },
        },
        ai,
      ),
    ).rejects.toThrow();
    expect(ai).not.toHaveBeenCalled();
  });
});

it("keeps explicitly labeled dialogue turns on the Chinese side and speaks only their words", async () => {
  const source = "用户：我想刷信用卡。";
  const en = "Can I pay by credit card?";
  let attempts = 0;
  const result = await generateBilingualPassages(
    { ...cmd, text: source, contentShape: "DIALOGUE" },
    safety,
    async (task) => {
      if (task.includes("WORDING")) {
        attempts++;
        return {
          paragraphs: [
            {
              sourceText: source,
              textZh: source,
              textEn:
                attempts === 1
                  ? "User: Please help me pay with a credit card."
                  : en,
              continuesPrevious: false,
            },
          ],
        };
      }
      if (task.includes("FIDELITY")) return { faithful: true };
      return {
        paragraphs: [
          { id: 0, sentences: [{ textEn: en, textZh: "可以刷信用卡吗？" }] },
        ],
      };
    },
  );
  expect(attempts).toBe(2);
  expect(result.paragraphs[0]?.textZh).toBe(source);
  expect(result.paragraphs[0]?.sentences[0]?.textEn).toBe(en);
});
