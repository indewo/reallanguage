import { describe, expect, it, vi } from "vitest";
import {
  GenerateSceneDraft,
  GenerateSceneRecommendations,
  GetNextSceneQuestion,
  ReviewSceneEdit,
} from "./scene-formation.js";

describe("scene formation application boundary", () => {
  it("owns draft identity so repeated model IDs cannot reopen an old review screen", async () => {
    const provider = {
      execute: vi.fn().mockResolvedValue({
        draftId: "draft-1",
        contentType: "ARTICLE",
        units: [
          {
            unitId: "u1",
            speaker: null,
            textZh: "今天完成了计划。",
            factReferences: [],
          },
          {
            unitId: "u2",
            speaker: null,
            textZh: "我检查了最后的结果。",
            factReferences: [],
          },
          {
            unitId: "u3",
            speaker: null,
            textZh: "明天会开始下一步。",
            factReferences: [],
          },
        ],
      }),
    };
    const base = {
      accountId: "account",
      commandId: "first",
      task: "SCENE_ARTICLE_DRAFT_V1" as const,
      session: {
        operationId: "operation",
        start: { kind: "DIRECT_DESCRIPTION" as const, description: "我的计划" },
        contentType: "ARTICLE" as const,
        answers: [{ questionId: "q1", answerZh: "完成计划" }],
        collectedFacts: ["完成计划"],
        missingNonBlockingFacts: [],
      },
      remainingContentBudget: 10,
    };
    const app = new GenerateSceneDraft(provider);
    const first = await app.execute(base);
    const retry = await app.execute(base);
    const regenerated = await app.execute({ ...base, commandId: "second" });
    expect(first.draftId).toBe(retry.draftId);
    expect(regenerated.draftId).not.toBe(first.draftId);
  });
  it("uses the account learning range when choosing the generated scene", async () => {
    const execute = vi.fn().mockResolvedValue({
      draftId: "draft",
      contentType: "DIALOGUE",
      units: [
        {
          unitId: "u1",
          speaker: "USER",
          textZh: "不好意思，我刚才手机没电了。",
          factReferences: ["手机没电"],
        },
        {
          unitId: "u2",
          speaker: "OTHER",
          textZh: "没事，你现在在哪里？",
          factReferences: [],
        },
        {
          unitId: "u3",
          speaker: "USER",
          textZh: "我在苏州街，可能晚点到。",
          factReferences: ["在苏州街"],
        },
      ],
    });
    const read = vi.fn().mockResolvedValue({
      formalityLevel: 3,
      slangEnabled: false,
      basicVocabulary: "CET_6",
      purposeVocabularies: ["IELTS_TOEFL"],
      styleReference: "natural short dialogue",
    });
    await new GenerateSceneDraft({ execute }, { read }).execute({
      accountId: "account",
      commandId: "command",
      task: "SCENE_DIALOGUE_TURN_V1",
      session: {
        operationId: "operation",
        start: {
          kind: "DIRECT_DESCRIPTION",
          description: "手机没电后联系朋友",
        },
        contentType: "DIALOGUE",
        answers: [{ questionId: "q1", answerZh: "我在苏州街" }],
        collectedFacts: ["手机没电", "在苏州街"],
        missingNonBlockingFacts: [],
      },
      remainingContentBudget: 6,
    });
    expect(read).toHaveBeenCalledWith("account");
    expect(execute).toHaveBeenCalledWith(
      "SCENE_DIALOGUE_TURN_V1",
      expect.objectContaining({
        settings: expect.objectContaining({
          basicVocabulary: "CET_6",
          purposeVocabularies: ["IELTS_TOEFL"],
        }),
      }),
    );
  });

  it("rejects essay-length dialogue turns before they reach the editor", async () => {
    const longTurn = "这是一段不应该被压缩在一个对话框里的长篇总结，".repeat(3);
    const execute = vi.fn().mockResolvedValue({
      draftId: "draft",
      contentType: "DIALOGUE",
      units: [
        { unitId: "u1", speaker: "USER", textZh: longTurn, factReferences: [] },
        {
          unitId: "u2",
          speaker: "OTHER",
          textZh: "你现在在哪里？",
          factReferences: [],
        },
        {
          unitId: "u3",
          speaker: "USER",
          textZh: "我在苏州街。",
          factReferences: [],
        },
      ],
    });
    await expect(
      new GenerateSceneDraft({ execute }).execute({
        accountId: "account",
        commandId: "command",
        task: "SCENE_DIALOGUE_TURN_V1",
        session: {
          operationId: "operation",
          start: { kind: "DIRECT_DESCRIPTION", description: "手机没电" },
          contentType: "DIALOGUE",
          answers: [{ questionId: "q1", answerZh: "我在苏州街" }],
          collectedFacts: ["手机没电"],
          missingNonBlockingFacts: [],
        },
        remainingContentBudget: 6,
      }),
    ).rejects.toThrow("Scene unit is too long for focused learning");
  });
  it("rejects regeneration that only changes IDs, whitespace or punctuation", async () => {
    const previousUnits = [
      {
        unitId: "u1",
        speaker: null,
        textZh: "今天完成了计划。",
        factReferences: [],
      },
      {
        unitId: "u2",
        speaker: null,
        textZh: "我检查了最后的结果。",
        factReferences: [],
      },
      {
        unitId: "u3",
        speaker: null,
        textZh: "明天会开始下一步。",
        factReferences: [],
      },
    ];
    const execute = vi.fn().mockResolvedValue({
      draftId: "new",
      contentType: "ARTICLE",
      units: previousUnits.map((unit, index) => ({
        ...unit,
        unitId: `new-u${index + 1}`,
        textZh: `${unit.textZh.replace(/。$/u, "")}！ `,
      })),
    });
    const command = {
      accountId: "account",
      commandId: "regenerate",
      task: "SCENE_ARTICLE_DRAFT_V1" as const,
      session: {
        operationId: "operation",
        start: { kind: "DIRECT_DESCRIPTION" as const, description: "我的计划" },
        contentType: "ARTICLE" as const,
        answers: [{ questionId: "q1", answerZh: "完成计划" }],
        collectedFacts: ["完成计划"],
        missingNonBlockingFacts: [],
      },
      remainingContentBudget: 10,
      previousUnits,
    };
    await expect(
      new GenerateSceneDraft({ execute }).execute(command),
    ).rejects.toThrow("Scene regeneration repeated previous content");
    execute.mockResolvedValue({
      draftId: "new",
      contentType: "ARTICLE",
      units: [
        { ...previousUnits[0], textZh: "计划中的事情今天都做好了。" },
        previousUnits[1],
        previousUnits[2],
      ],
    });
    await expect(
      new GenerateSceneDraft({ execute }).execute(command),
    ).resolves.toMatchObject({ draftId: expect.stringMatching(/^scene-/u) });
    expect(execute).toHaveBeenLastCalledWith(
      "SCENE_ARTICLE_DRAFT_V1",
      expect.objectContaining({ previousUnits }),
    );
  });
  it("validates up to four differentiated recent recommendations", async () => {
    const execute = vi.fn().mockResolvedValue({
      recommendations: [
        {
          recommendationId: "r1",
          shortTopicZh: "和同事确认计划",
          whyRelevantZh: "最近学习过工作沟通",
          sourceRefs: ["source-1"],
          differentiationKey: "work-plan",
        },
      ],
    });
    const result = await new GenerateSceneRecommendations({ execute }).execute({
      accountId: "account",
      commandId: "command",
      sources: [
        {
          sourceId: "source-1",
          sourceType: "DIARY",
          completedLocalDate: "2026-09-01",
          topicSummaryZh: "工作沟通",
          confirmedTextZh: "今天确认了计划。",
          sceneContextZh: "办公室",
        },
      ],
    });
    expect(result.recommendations).toHaveLength(1);
  });

  it("orders recommendations by newest referenced source regardless of model order", async () => {
    const execute = vi.fn().mockResolvedValue({
      recommendations: [
        {
          recommendationId: "old",
          shortTopicZh: "旧主题",
          whyRelevantZh: "旧",
          sourceRefs: ["old-source"],
          differentiationKey: "old",
        },
        {
          recommendationId: "new",
          shortTopicZh: "新主题",
          whyRelevantZh: "新",
          sourceRefs: ["new-source"],
          differentiationKey: "new",
        },
      ],
    });
    const sources = [
      {
        sourceId: "old-source",
        sourceType: "DIARY" as const,
        completedLocalDate: "2026-08-01",
        topicSummaryZh: "旧",
        confirmedTextZh: "旧内容",
        sceneContextZh: "",
      },
      {
        sourceId: "new-source",
        sourceType: "AI_SCENE" as const,
        completedLocalDate: "2026-09-14",
        topicSummaryZh: "新",
        confirmedTextZh: "新内容",
        sceneContextZh: "",
      },
    ];
    const result = await new GenerateSceneRecommendations({ execute }).execute({
      accountId: "account",
      commandId: "command",
      sources,
    });
    expect(result.recommendations.map((item) => item.recommendationId)).toEqual(
      ["new", "old"],
    );
    expect(execute).toHaveBeenCalledWith(
      "RECENT_SCENE_RECOMMENDATION_V1",
      expect.objectContaining({ sources: [sources[1], sources[0]] }),
    );
  });

  it("requires three confirmed answers before scene generation", async () => {
    const execute = vi.fn().mockResolvedValue({
      action: "GENERATE",
      collectedFacts: ["和朋友见面", "手机没电"],
      missingNonBlockingFacts: [],
    });
    const answers = [
      { questionId: "q1", questionZh: "和谁？", answerZh: "和朋友" },
      { questionId: "q2", questionZh: "发生了什么？", answerZh: "手机没电" },
    ];
    await expect(
      new GetNextSceneQuestion({ execute }).execute({
        accountId: "account",
        commandId: "command",
        operationId: "operation",
        start: { kind: "DIRECT_DESCRIPTION", description: "约朋友见面" },
        contentType: "DIALOGUE",
        confirmedAnswers: answers,
        remainingQuestions: 8,
        remainingContentBudget: 6,
        stepIndex: 2,
      }),
    ).rejects.toThrow("Invalid next-question output");
  });

  it("reassesses after the third answer and generates as soon as one concrete moment is sufficient", async () => {
    const execute = vi.fn().mockResolvedValue({
      action: "GENERATE",
      collectedFacts: ["手机没电", "联系朋友", "我在苏州街"],
      missingNonBlockingFacts: ["充电地点的店名"],
    });
    const answers = [
      { questionId: "q1", questionZh: "发生了什么？", answerZh: "手机没电" },
      {
        questionId: "q2",
        questionZh: "你要做什么？",
        answerZh: "联系朋友说会晚到",
      },
      { questionId: "q3", questionZh: "你在哪里？", answerZh: "我在苏州街" },
    ];
    const result = await new GetNextSceneQuestion({ execute }).execute({
      accountId: "account",
      commandId: "command",
      operationId: "operation",
      start: { kind: "DIRECT_DESCRIPTION", description: "赴约时手机没电" },
      contentType: "DIALOGUE",
      confirmedAnswers: answers,
      remainingQuestions: 7,
      remainingContentBudget: 6,
      stepIndex: 3,
    });
    expect(result).toEqual({
      action: "GENERATE",
      collectedFacts: ["手机没电", "联系朋友", "我在苏州街"],
      missingNonBlockingFacts: ["充电地点的店名"],
    });
    expect(execute).toHaveBeenCalledWith(
      "SCENE_NEXT_QUESTION_V1",
      expect.objectContaining({
        minimumQuestions: 3,
        maximumQuestions: 10,
        reassessAfterEveryAnswer: true,
        preferredConcreteSceneMoments: 1,
        maximumConcreteSceneMoments: 2,
        missingOutsideChosenMomentIsNonBlocking: true,
      }),
    );
  });

  it("recovers from a false insufficiency decision when ten answers contain a usable concrete scene", async () => {
    const execute = vi
      .fn()
      .mockResolvedValue({ action: "INSUFFICIENT_INFORMATION" });
    const answerTexts = [
      "我们逛完街以后去吃饭",
      "在一家西式小酒馆",
      "和朋友一起",
      "我很开心，也比较放松",
      "我终于没有被蚊子咬",
      "吃饭时会先点饮料",
      "我想喝果汁",
      "不知道店名，但我知道位置",
      "就是普通朋友聚餐",
      "只需要围绕吃饭生成一段短对话",
    ];
    const result = await new GetNextSceneQuestion({ execute }).execute({
      accountId: "account",
      commandId: "command",
      operationId: "operation",
      start: { kind: "DIRECT_DESCRIPTION", description: "和朋友逛街吃饭" },
      contentType: "DIALOGUE",
      confirmedAnswers: answerTexts.map((answerZh, index) => ({
        questionId: `q${index + 1}`,
        questionZh: `问题${index + 1}`,
        answerZh,
      })),
      remainingQuestions: 0,
      remainingContentBudget: 6,
      stepIndex: 10,
    });
    expect(result.action).toBe("GENERATE");
    if (result.action === "GENERATE") {
      expect(result.collectedFacts).toContain("和朋友逛街吃饭");
      expect(result.collectedFacts).toContain("在一家西式小酒馆");
      expect(result.collectedFacts).toContain("只需要围绕吃饭生成一段短对话");
    }
  });

  it("rejects an eleventh question and returns explicit insufficiency after ten answers", async () => {
    const execute = vi
      .fn()
      .mockResolvedValueOnce({
        action: "ASK",
        questionId: "q11",
        questionZh: "更多？",
        options: [],
      })
      .mockResolvedValueOnce({ action: "INSUFFICIENT_INFORMATION" });
    const app = new GetNextSceneQuestion({ execute });
    const answers = Array.from({ length: 10 }, (_, index) => ({
      questionId: `q${index + 1}`,
      questionZh: `问题${index + 1}`,
      answerZh: "回答",
    }));
    const base = {
      accountId: "account",
      commandId: "command",
      operationId: "operation",
      start: { kind: "DIRECT_DESCRIPTION" as const, description: "练习沟通" },
      contentType: "DIALOGUE" as const,
      confirmedAnswers: answers,
      remainingQuestions: 0,
      remainingContentBudget: 10,
      stepIndex: 10,
    };
    await expect(app.execute(base)).rejects.toThrow(
      "Invalid next-question output",
    );
    await expect(app.execute(base)).resolves.toEqual({
      action: "INSUFFICIENT_INFORMATION",
    });
  });

  it("does not expose recommendation history to questioning or draft generation", async () => {
    const execute = vi
      .fn()
      .mockResolvedValueOnce({
        action: "ASK",
        questionId: "q1",
        questionZh: "你希望讨论哪一件具体事情？",
        options: [],
      })
      .mockResolvedValueOnce({
        draftId: "draft",
        contentType: "ARTICLE",
        units: [
          {
            unitId: "u1",
            speaker: null,
            textZh: "第一句。",
            factReferences: ["具体事情"],
          },
          {
            unitId: "u2",
            speaker: null,
            textZh: "第二句。",
            factReferences: ["具体事情"],
          },
          {
            unitId: "u3",
            speaker: null,
            textZh: "第三句。",
            factReferences: ["具体事情"],
          },
        ],
      });
    const start = {
      kind: "RECOMMENDATION" as const,
      recommendationId: "r1",
      shortTopicZh: "确认项目进度",
      whyRelevantZh: "来自旧对话",
      sourceRefs: ["secret-source"],
    };
    await new GetNextSceneQuestion({ execute }).execute({
      accountId: "a",
      commandId: "c1",
      operationId: "o",
      start,
      contentType: "ARTICLE",
      confirmedAnswers: [],
      remainingQuestions: 10,
      remainingContentBudget: 10,
      stepIndex: 0,
    });
    await new GenerateSceneDraft({ execute }).execute({
      accountId: "a",
      commandId: "c2",
      task: "SCENE_ARTICLE_DRAFT_V1",
      session: {
        operationId: "o",
        start,
        contentType: "ARTICLE",
        answers: [
          { questionId: "q1", questionZh: "具体事情？", answerZh: "具体事情" },
        ],
        collectedFacts: ["具体事情"],
        missingNonBlockingFacts: [],
      },
      remainingContentBudget: 10,
    });
    for (const call of execute.mock.calls) {
      const serialized = JSON.stringify(call[1]);
      expect(serialized).not.toContain("来自旧对话");
      expect(serialized).not.toContain("secret-source");
    }
  });

  it("rejects an exactly repeated question", async () => {
    const app = new GetNextSceneQuestion({
      execute: vi.fn().mockResolvedValue({
        action: "ASK",
        questionId: "new-id",
        questionZh: "在什么场景下？",
        options: [],
      }),
    });
    await expect(
      app.execute({
        accountId: "a",
        commandId: "c",
        operationId: "o",
        start: {
          kind: "TEMPLATE",
          templateId: "work",
          titleZh: "工作沟通",
          scopeZh: "沟通",
        },
        contentType: "DIALOGUE",
        confirmedAnswers: [
          {
            questionId: "old-id",
            questionZh: "在什么场景下?",
            answerZh: "办公室",
          },
        ],
        remainingQuestions: 9,
        remainingContentBudget: 10,
        stepIndex: 1,
      }),
    ).rejects.toThrow("Invalid next-question output");
  });

  it("enforces article/dialogue shape and bounded edit reasons", async () => {
    const provider = {
      execute: vi.fn().mockResolvedValue({
        draftId: "draft",
        contentType: "ARTICLE",
        units: [
          {
            unitId: "u1",
            speaker: null,
            textZh: "一段文字。",
            factReferences: ["事实"],
          },
          {
            unitId: "u2",
            speaker: null,
            textZh: "第二句。",
            factReferences: ["事实"],
          },
          {
            unitId: "u3",
            speaker: null,
            textZh: "第三句。",
            factReferences: ["事实"],
          },
        ],
      }),
    };
    await expect(
      new GenerateSceneDraft(provider).execute({
        accountId: "account",
        commandId: "command",
        task: "SCENE_ARTICLE_DRAFT_V1",
        session: {
          operationId: "operation",
          start: { kind: "DIRECT_DESCRIPTION", description: "写文章" },
          contentType: "ARTICLE",
          answers: [{ questionId: "q1", answerZh: "回答" }],
          collectedFacts: ["事实"],
          missingNonBlockingFacts: [],
        },
        remainingContentBudget: 10,
      }),
    ).resolves.toMatchObject({ contentType: "ARTICLE" });

    const reviewProvider = {
      execute: vi.fn().mockResolvedValue({
        decision: "NEEDS_USER_EDIT",
        issues: [
          {
            unitId: "u1",
            startOffset: 0,
            endOffset: 1,
            reason: "STYLE",
            explanationZh: "问题",
          },
        ],
      }),
    };
    await expect(
      new ReviewSceneEdit(reviewProvider).execute({
        accountId: "account",
        commandId: "command",
        contentType: "ARTICLE",
        changedUnitIds: ["u1"],
        originalUnits: [
          { unitId: "u1", speaker: null, textZh: "原文", factReferences: [] },
        ],
        editedUnits: [
          { unitId: "u1", speaker: null, textZh: "修改", factReferences: [] },
        ],
      }),
    ).rejects.toThrow("Invalid scene edit review output");
  });
});
