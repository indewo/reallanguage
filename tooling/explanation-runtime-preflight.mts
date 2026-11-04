import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { relative } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

/** Offline guard only; never creates an account, reads credentials, or contacts a provider. */
export async function assertExplanationRuntimeCompatibility() {
  // Resolve exactly as the real API consumer does, including active export conditions.
  const resolve = createRequire(
    new URL("../apps/api/package.json", import.meta.url),
  ).resolve;
  const learningPath = resolve("@reallanguage/backend-modules/learning");
  const contractsPath = resolve("@reallanguage/contracts");
  const { GenerateLearningExplanation } = await import(
    pathToFileURL(learningPath).href
  );
  const { learningExplanationResultSchema } = await import(
    pathToFileURL(contractsPath).href
  );
  for (const hint of ["explain something to someone", null]) {
    // Explicit diagnostic fixture, never a production result or real-AI acceptance claim.
    const delivered = {
      kind: "EXPRESSION",
      expressionId: "probe-expression",
      explanationZh: "这里指把自己的想法解释给同事。",
      contextualMeaningZh: "说明想法",
      usageZh: "explain 后接所说明的内容，用 to 引出听者。",
      expressionHintZh: hint,
      phonetic: null,
      naturalExample: {
        textEn: "She explained her idea.",
        textZh: "她解释了自己的想法。",
      },
    };
    const mapper = new GenerateLearningExplanation(
      {
        read: async () => ({
          formalityLevel: 3,
          slangEnabled: false,
          basicVocabulary: "CET_4",
          purposeVocabularies: [],
          styleReference: "NATURAL_CONCISE",
        }),
      },
      { generate: async () => delivered },
    );
    const mapped = await mapper.execute({
      accountId: "offline-runtime-probe",
      chapter: {
        chapterId: "probe-chapter",
        sentences: [
          {
            sentenceId: "probe-sentence",
            textEn: "I explained my idea.",
            textZh: "我解释了自己的想法。",
            isFocus: true,
          },
        ],
      },
      focusSentenceId: "probe-sentence",
      round: 1,
      requestedExpression: {
        expressionId: "probe-expression",
        display: "explained",
      },
      commandId: "offline-no-provider-call",
    });
    assert.deepEqual(
      learningExplanationResultSchema.parse(mapped),
      delivered,
      "The resolved mapper and HTTP contract must preserve the complete expression details",
    );
  }
  const workspace = fileURLToPath(new URL("../", import.meta.url));
  const formationPath = resolve(
    "@reallanguage/backend-modules/content-formation",
  );
  const { ExtractLearningTargets, HttpLearningTargetProvider } = await import(
    pathToFileURL(formationPath).href
  );
  const { learningTargetExtractionResultSchema } = await import(
    pathToFileURL(contractsPath).href
  );
  const target = {
    targetId: "offline-target",
    unitId: "u1",
    display: "work up the courage",
    explanationZh: "鼓起勇气",
    type: "FIXED_EXPRESSION",
    structureParts: [],
    relatedVocabulary: [],
    selectedByDefault: true,
    sourceEvidence: {
      version: 1,
      startUtf16: 2,
      endUtf16: 23,
      surfaceForm: "worked up the courage",
      normalizedForm: "work up the courage",
      allowedForms: ["worked up the courage"],
    },
  };
  const targetMapper = new ExtractLearningTargets(
    {
      read: async () => ({
        formalityLevel: 3,
        slangEnabled: false,
        basicVocabulary: "CET_4",
        purposeVocabularies: [],
        styleReference: "NATURAL_CONCISE",
      }),
    },
    new HttpLearningTargetProvider(
      { endpoint: "https://offline.invalid", apiKey: "offline-not-a-key" },
      async () =>
        new Response(
          JSON.stringify({ targets: [target], queryableExpressions: [] }),
        ),
    ),
  );
  const targetResult = await targetMapper.execute({
    accountId: "offline-runtime-probe",
    commandId: "offline-no-provider-call",
    chapters: [
      {
        chapterId: "c1",
        sentences: [
          {
            unitId: "u1",
            textEn: "I worked up the courage.",
            textZh: "我鼓起勇气。",
            isFocus: true,
          },
        ],
      },
    ],
  });
  assert.deepEqual(
    learningTargetExtractionResultSchema.parse(targetResult).targets,
    [target],
    "Target runtime must retain exact frozen source evidence",
  );
  return {
    providerCalls: 0,
    accountOperations: 0,
    detailsPreserved: true,
    targetEvidencePreserved: true,
    formationRuntime: relative(workspace, formationPath),
    learningRuntime: relative(workspace, learningPath),
    contractsRuntime: relative(workspace, contractsPath),
  };
}
