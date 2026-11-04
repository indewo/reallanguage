import { describe, expect, it } from "vitest";
import { SelectChapterContext } from "./select-chapter-context.js";

const units = Array.from({ length: 7 }, (_, order) => ({
  unitId: `u${order + 1}`,
  order,
  textZh: `第${order + 1}句`,
}));
describe("SelectChapterContext", () => {
  it("accepts a valid ordered suggestion with every focus exactly once", async () => {
    const candidate = [{ chapterId: "c1", unitIds: ["u1", "u2", "u3"] }];
    await expect(
      new SelectChapterContext({ select: async () => candidate }).execute({
        accountId: "a",
        units,
        selectedUnitIds: ["u2"],
        commandId: "cmd",
      }),
    ).resolves.toEqual({ chapters: candidate });
  });
  it("deterministically rebuilds an invalid oversized or missing-focus suggestion", async () => {
    const result = await new SelectChapterContext({
      select: async () => [
        { chapterId: "bad", unitIds: ["u1", "u2", "u3", "u4", "u5"] },
      ],
    }).execute({
      accountId: "a",
      units,
      selectedUnitIds: ["u2", "u6"],
      commandId: "cmd",
    });
    expect(
      result.chapters.every((chapter) => chapter.unitIds.length <= 4),
    ).toBe(true);
    expect(
      result.chapters
        .flatMap((chapter) => chapter.unitIds)
        .filter((id) => ["u2", "u6"].includes(id))
        .sort(),
    ).toEqual(["u2", "u6"]);
  });

  it("deterministically rebuilds when the AI provider is unavailable", async () => {
    const result = await new SelectChapterContext({
      select: async () => {
        throw new Error("provider timeout");
      },
    }).execute({
      accountId: "a",
      units,
      selectedUnitIds: ["u2", "u6"],
      commandId: "cmd-provider-outage",
    });

    expect(result.chapters).toEqual([
      { chapterId: "chapter-1", unitIds: ["u1", "u2", "u3"] },
      { chapterId: "chapter-2", unitIds: ["u5", "u6", "u7"] },
    ]);
  });

  it("keeps every sentence when a four-sentence source suggestion drops its opening", async () => {
    const fourSentenceSource = [
      { unitId: "u1", order: 0, textZh: "我把车停在了公司。" },
      { unitId: "u2", order: 1, textZh: "但是我人在家。" },
      {
        unitId: "u3",
        order: 2,
        textZh: "所以今天上班我就需要通过其他的方式去公司。",
      },
      { unitId: "u4", order: 3, textZh: "有时候住得远也是一个大问题。" },
    ];

    const result = await new SelectChapterContext({
      select: async () => [
        { chapterId: "incomplete", unitIds: ["u2", "u3", "u4"] },
      ],
    }).execute({
      accountId: "a",
      units: fourSentenceSource,
      selectedUnitIds: ["u2"],
      commandId: "cmd-four-sentences",
    });

    expect(result.chapters).toEqual([
      { chapterId: "chapter-1", unitIds: ["u1", "u2", "u3", "u4"] },
    ]);
  });
});
