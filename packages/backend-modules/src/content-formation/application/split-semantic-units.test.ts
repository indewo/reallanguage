import { describe, expect, it, vi } from "vitest";
import { SplitSemanticUnits } from "./split-semantic-units.js";

const command = {
  accountId: "account",
  text: "今天很忙。",
  contentShape: "ARTICLE" as const,
  commandId: "cmd",
};
const unit = {
  unitId: "u1",
  order: 0,
  sourceSpans: [{ startCodePoint: 0, endCodePoint: 5 }],
  textZh: "今天很忙。",
  speakerRole: null,
  semanticPurposeZh: "陈述状态",
  recommendationReasonZh: null,
};

describe("SplitSemanticUnits", () => {
  it("returns only a complete exact split", async () => {
    const split = vi.fn().mockResolvedValue([unit]);
    await expect(
      new SplitSemanticUnits({ split }).execute(command),
    ).resolves.toMatchObject({ coverage: { complete: true } });
    expect(split).toHaveBeenCalledWith(command);
  });

  it("repairs rewritten or missing source coverage with an exact local split", async () => {
    const invalid = { ...unit, textZh: "被改写" };
    await expect(
      new SplitSemanticUnits({ split: async () => [invalid] }).execute(command),
    ).resolves.toMatchObject({
      units: [expect.objectContaining({ textZh: command.text })],
    });
    await expect(
      new SplitSemanticUnits({
        split: async () => [
          {
            ...unit,
            sourceSpans: [{ startCodePoint: 0, endCodePoint: 4 }],
            textZh: "今天很忙",
          },
        ],
      }).execute(command),
    ).resolves.toMatchObject({
      units: [expect.objectContaining({ textZh: command.text })],
    });
  });

  it("deterministically repairs a paragraph merged across distinct propositions", async () => {
    const text =
      "今天我很不开心，因为我发现外耗是一个很有用的事，但是我容易内耗，所以我要想办法让自己变得更加外耗一点，耗别人。";
    await expect(
      new SplitSemanticUnits({
        split: async () => [
          {
            ...unit,
            sourceSpans: [
              { startCodePoint: 0, endCodePoint: Array.from(text).length },
            ],
            textZh: text,
          },
        ],
      }).execute({ ...command, text }),
    ).resolves.toMatchObject({
      units: expect.arrayContaining([
        expect.objectContaining({ textZh: "今天我很不开心，" }),
        expect.objectContaining({ textZh: "因为我发现外耗是一个很有用的事，" }),
        expect.objectContaining({ textZh: "但是我容易内耗，" }),
        expect.objectContaining({
          textZh: "所以我要想办法让自己变得更加外耗一点，耗别人。",
        }),
      ]),
    });
  });

  it("repairs even one merged reason proposition", async () => {
    const text = "今天我很不开心，因为我发现外耗很有用。";
    await expect(
      new SplitSemanticUnits({
        split: async () => [
          {
            ...unit,
            sourceSpans: [
              { startCodePoint: 0, endCodePoint: Array.from(text).length },
            ],
            textZh: text,
          },
        ],
      }).execute({ ...command, text }),
    ).resolves.toMatchObject({
      units: [
        expect.objectContaining({ textZh: "今天我很不开心，" }),
        expect.objectContaining({ textZh: "因为我发现外耗很有用。" }),
      ],
    });
  });

  it("accepts the reported paragraph as five exact ordered learnable units", async () => {
    const parts = [
      "今天我很不开心，",
      "因为我发现外耗是一个很有用的事，",
      "但是我容易内耗，",
      "所以我要想办法让自己变得更加外耗一点，",
      "耗别人。",
    ];
    const text = parts.join("");
    let cursor = 0;
    const units = parts.map((part, order) => {
      const startCodePoint = cursor;
      cursor += Array.from(part).length;
      return {
        ...unit,
        unitId: `u${order + 1}`,
        order,
        sourceSpans: [{ startCodePoint, endCodePoint: cursor }],
        textZh: part,
      };
    });
    const result = await new SplitSemanticUnits({
      split: async () => units,
    }).execute({ ...command, text });
    expect(result.units.map((candidate) => candidate.textZh)).toEqual(parts);
    expect(result.units.map((candidate) => candidate.textZh).join("")).toBe(
      text,
    );
  });

  it("uses one stable corrective attempt when the first provider result is too coarse", async () => {
    const first = "今天我很不开心，";
    const second = "因为我发现外耗很有用。";
    const parts = [first, second];
    const text = parts.join("");
    const validUnits = [
      {
        ...unit,
        textZh: first,
        sourceSpans: [
          { startCodePoint: 0, endCodePoint: Array.from(first).length },
        ],
      },
      {
        ...unit,
        unitId: "u2",
        order: 1,
        textZh: second,
        sourceSpans: [
          {
            startCodePoint: Array.from(first).length,
            endCodePoint: Array.from(text).length,
          },
        ],
      },
    ];
    const split = vi
      .fn()
      .mockResolvedValueOnce([
        {
          ...unit,
          textZh: text,
          sourceSpans: [
            { startCodePoint: 0, endCodePoint: Array.from(text).length },
          ],
        },
      ])
      .mockResolvedValueOnce(validUnits);

    await expect(
      new SplitSemanticUnits({ split }).execute({ ...command, text }),
    ).resolves.toMatchObject({ units: validUnits });
    expect(split).toHaveBeenNthCalledWith(1, { ...command, text });
    expect(split).toHaveBeenNthCalledWith(2, {
      ...command,
      text,
      commandId: "cmd:granularity-retry-1",
    });
  });

  it("does not use commas alone to break an enumeration or indivisible condition", async () => {
    const text = "如果明天下雨，我就带伞，也带雨衣。";
    await expect(
      new SplitSemanticUnits({
        split: async () => [
          {
            ...unit,
            sourceSpans: [
              { startCodePoint: 0, endCodePoint: Array.from(text).length },
            ],
            textZh: text,
          },
        ],
      }).execute({ ...command, text }),
    ).resolves.toMatchObject({ coverage: { complete: true } });
  });

  it("falls back locally when both provider attempts throw", async () => {
    const text = "第一句。第二句！\n第三句。";
    const split = vi.fn().mockRejectedValue(new Error("offline"));
    const result = await new SplitSemanticUnits({ split }).execute({
      ...command,
      text,
    });
    expect(result.units.map((candidate) => candidate.textZh).join("")).toBe(
      text,
    );
    expect(result.units.map((candidate) => candidate.textZh)).toEqual([
      "第一句。",
      "第二句！\n",
      "第三句。",
    ]);
    expect(split).toHaveBeenCalledTimes(2);
  });
});
