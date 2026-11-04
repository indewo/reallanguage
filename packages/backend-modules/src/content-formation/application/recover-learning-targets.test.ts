import { describe, expect, it, vi } from "vitest";
import {
  InvalidLearningTargetInputError,
  InvalidLearningTargetOutputError,
  type ExtractLearningTargetsCommand,
} from "./extract-learning-targets.js";
import { RecoverLearningTargets } from "./recover-learning-targets.js";

const command: ExtractLearningTargetsCommand = {
  accountId: "account-1",
  commandId: "8b135fa2-5794-45d4-a47b-8f91bb222744",
  chapters: [
    {
      chapterId: "chapter-1",
      sentences: [
        {
          unitId: "unit-1",
          textZh: "我今天很开心。",
          textEn: "I was happy today.",
          isFocus: true,
        },
      ],
    },
  ],
};
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_6",
  purposeVocabularies: ["BUSINESS_ENGLISH"],
  styleReference: "natural",
};
const delivered = {
  targets: [],
  queryableExpressions: [],
  learningScopes: ["CET_6", "BUSINESS_ENGLISH"],
};

describe("RecoverLearningTargets", () => {
  it("uses a stable independent repair delivery after semantic validation fails", async () => {
    const execute = vi
      .fn()
      .mockRejectedValueOnce(
        new InvalidLearningTargetOutputError("Invalid learning target output"),
      )
      .mockResolvedValueOnce(delivered);
    const report = vi.fn();
    const recovery = new RecoverLearningTargets(
      { execute },
      { read: async () => settings },
      report,
    );

    await expect(recovery.execute(command)).resolves.toEqual(delivered);
    expect(execute).toHaveBeenCalledTimes(2);
    const repaired = execute.mock.calls[1]![0] as ExtractLearningTargetsCommand;
    expect(repaired.commandId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u,
    );
    expect(repaired.commandId).not.toBe(command.commandId);
    expect(report).toHaveBeenCalledWith(
      expect.objectContaining({ phase: "REPAIR_REQUESTED" }),
    );
  });

  it("returns an honest empty-target fallback after two unusable generated deliveries", async () => {
    const execute = vi
      .fn()
      .mockRejectedValue(
        new InvalidLearningTargetOutputError("Invalid learning target output"),
      );
    const report = vi.fn();
    const recovery = new RecoverLearningTargets(
      { execute },
      { read: async () => settings },
      report,
    );

    await expect(recovery.execute(command)).resolves.toEqual({
      targets: [],
      queryableExpressions: [],
      learningScopes: ["CET_6", "BUSINESS_ENGLISH"],
    });
    expect(execute).toHaveBeenCalledTimes(2);
    expect(report).toHaveBeenLastCalledWith(
      expect.objectContaining({ phase: "SAFE_FALLBACK_USED" }),
    );
  });

  it("does not disguise transport or input failures as generated content", async () => {
    for (const error of [
      new Error("Learning target provider request failed"),
      new InvalidLearningTargetInputError("Invalid learning target input"),
    ]) {
      const execute = vi.fn().mockRejectedValue(error);
      const recovery = new RecoverLearningTargets(
        { execute },
        { read: async () => settings },
      );
      await expect(recovery.execute(command)).rejects.toBe(error);
      expect(execute).toHaveBeenCalledTimes(1);
    }
  });

  it("never turns a failed user-requested expression into an empty fake success", async () => {
    const error = new InvalidLearningTargetOutputError(
      "Invalid requested expression result",
    );
    const execute = vi.fn().mockRejectedValue(error);
    const recovery = new RecoverLearningTargets(
      { execute },
      { read: async () => settings },
    );
    await expect(
      recovery.execute({
        ...command,
        requestedExpression: {
          unitId: "unit-1",
          expressionId: "word:6",
          display: "happy",
        },
      }),
    ).rejects.toBe(error);
    expect(execute).toHaveBeenCalledTimes(2);
  });
});
