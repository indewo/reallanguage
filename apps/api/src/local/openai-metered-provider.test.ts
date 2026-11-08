import { describe, expect, it, vi } from "vitest";

import {
  LocalOpenAiMeteredProvider,
  OpenAiLocalClient,
} from "./openai-metered-provider.js";

const command = (task: string, input: unknown = {}) => ({
  accountId: "account-1",
  operationId: "operation-1",
  operationLabelZh: "测试",
  commandId: "command-1",
  task,
  callType: "INITIAL_CALL" as const,
  input,
});

describe("LocalOpenAiMeteredProvider", () => {
  it("requires scene-grounded natural American English instead of text-only literal translation", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          output_text: JSON.stringify({ sentences: [] }),
        }),
      ),
    );
    const input = {
      sourceType: "DIARY",
      sourceContextUnits: [
        { unitId: "u0", order: 0, textZh: "我这周都在做新版本。" },
        { unitId: "u1", order: 1, textZh: "今天我在开发 APP。" },
      ],
      units: [{ unitId: "u1", order: 1, textZh: "今天我在开发 APP。" }],
      chapter: { chapterId: "c1", unitIds: ["u1"] },
    };
    await new OpenAiLocalClient(
      "isolated-test",
      "test-model",
      fetcher,
    ).generateJson("ENGLISH_CANDIDATE_GENERATION_V1", input);

    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    expect(JSON.parse(request.input)).toEqual({
      format: "json",
      task: "ENGLISH_CANDIDATE_GENERATION_V1",
      input,
    });
    expect(request.instructions).toContain(
      "Faithful translation means preserving the user's intended meaning",
    );
    expect(request.instructions).toContain(
      "It does NOT mean mirroring Chinese words, word order or sentence structure",
    );
    expect(request.instructions).toContain(
      "infer only the minimum pragmatic frame needed to choose natural wording",
    );
    expect(request.instructions).toContain(
      "contemporary idiomatic American English",
    );
    expect(request.instructions).toContain(
      "must never appear as a new asserted person, place, event, time, reason, result or other user fact",
    );
    expect(request.instructions).toContain(
      "never copy, merge, move or restate a claim that appears only in another unit",
    );
    expect(request.instructions).toContain(
      "if a previous unit says the version is new and the target only says this app, do not call the app new",
    );
  });

  it.each(["SENTENCE_EXPLANATION_V1", "WORD_EXPLANATION_V1"])(
    "uses immutable selected-target context for %s",
    async (task) => {
      const fetcher = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            output_text: JSON.stringify({
              kind: "SENTENCE",
              explanationZh: "隔离输出",
            }),
          }),
        ),
      );
      await new OpenAiLocalClient(
        "isolated-test",
        "test-model",
        fetcher,
      ).generateJson(task, { lockedTargets: [] });
      const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
      expect(request.instructions).toContain("lockedTargets");
      expect(request.instructions).toContain(
        "related vocabulary is explanatory context, not an additional selected target",
      );
      if (task === "WORD_EXPLANATION_V1")
        expect(request.instructions).toContain(
          "contextualMeaningZh must exactly preserve the locked meaning",
        );
      expect(fetcher).toHaveBeenCalledTimes(1);
    },
  );
  it("reuses the accepted current-word explanation inside the existing provider request", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          output_text: JSON.stringify({
            targets: [],
            queryableExpressions: [],
          }),
        }),
      ),
    );
    await new OpenAiLocalClient(
      "isolated-test",
      "test-model",
      fetcher,
    ).generateJson("LEARNING_TARGET_EXTRACTION_V1", { chapters: [] });
    expect(fetcher).toHaveBeenCalledTimes(1);
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    expect(request.instructions).toContain("acceptedVocabulary");
    expect(request.instructions).toContain(
      "Never replace its Chinese meaning with a synonym or paraphrase",
    );
    expect(request.instructions).toContain(
      "word:N must return WORD or FIXED_EXPRESSION, never PATTERN",
    );
  });
  it("requires resolvable related vocabulary snapshots without another extraction request", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          output_text: JSON.stringify({
            targets: [],
            queryableExpressions: [],
          }),
        }),
      ),
    );
    await new OpenAiLocalClient(
      "isolated-test",
      "test-model",
      fetcher,
    ).generateJson("LEARNING_TARGET_EXTRACTION_V1", { chapters: [] });
    expect(fetcher).toHaveBeenCalledTimes(1);
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    const schema = request.text.format.schema.properties.targets.items;
    expect(schema.required).toEqual(
      expect.arrayContaining([
        "relatedVocabularyItems",
        "relatedVocabularyItemRefs",
      ]),
    );
    expect(
      schema.properties.relatedVocabularyItems.items.additionalProperties,
    ).toBe(false);
    expect(schema.properties.relatedVocabularyItems.items.required).toEqual([
      "itemRef",
      "unitId",
      "display",
      "senseZh",
      "unitType",
      "sourceEvidence",
    ]);
    expect(
      schema.properties.relatedVocabularyItems.items.properties.sourceEvidence
        .additionalProperties,
    ).toBe(false);
    expect(request.instructions).toContain(
      "explanatory snapshots, not additional learning targets",
    );
    expect(request.instructions).toContain(
      "relatedVocabulary must equal the resolved display values in relatedVocabularyItemRefs order",
    );
  });
  it("requests one complete bilingual target candidate example within the existing strict extraction call", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          output_text: JSON.stringify({
            targets: [],
            queryableExpressions: [],
          }),
        }),
      ),
    );
    const client = new OpenAiLocalClient(
      "isolated-test",
      "test-model",
      fetcher,
    );
    await client.generateJson("LEARNING_TARGET_EXTRACTION_V1", {
      chapters: [],
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    const targetSchema = request.text.format.schema.properties.targets.items;
    expect(targetSchema.required).toContain("candidateExample");
    expect(targetSchema.properties.candidateExample).toEqual({
      type: "object",
      additionalProperties: false,
      required: ["textEn", "textZh"],
      properties: {
        textEn: { type: "string", pattern: "\\S" },
        textZh: { type: "string", pattern: "\\S" },
      },
    });
    expect(request.instructions).toContain("candidateExample");
    expect(request.instructions).toContain(
      "illustrative, not a new fact about the user",
    );
    expect(request.instructions).toContain(
      "not the three formal knowledge examples",
    );
    expect(request.instructions).toContain(
      "never use the example as sourceEvidence or allowedForms",
    );
  });
  it("repairs from the accepted original audio prefix instead of a second full ASR transcript", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          choices: [
            {
              message: { content: JSON.stringify({ decision: "NO_CHANGE" }) },
            },
          ],
        }),
      ),
    );
    const decode = vi.fn().mockResolvedValue("YWNjZXB0ZWQtcHJlZml4");
    const client = new OpenAiLocalClient(
      "test-key",
      "test-model",
      fetcher,
      decode,
    );
    const evidence = {
      durationSeconds: 120,
      acceptedEndMs: 113500,
      endReason: "MAX_DURATION",
      segments: [
        {
          segmentId: "segment-1",
          startMs: 0,
          endMs: 113500,
          text: "真实原音内容。",
        },
      ],
    };
    await expect(
      client.generateJson("TRANSCRIPT_REPAIR_V1", {
        currentText: "用户文字。",
        manuallyEdited: true,
        audioBase64: "bTRh",
        audioFormat: "audio/mp4",
        evidence,
      }),
    ).resolves.toEqual({ decision: "NO_CHANGE" });
    expect(decode).toHaveBeenCalledWith("bTRh", "audio/mp4", {
      maximumBase64Length: 8_000_000,
      maximumDurationSeconds: 121,
      acceptedEndMs: 113500,
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(String(fetcher.mock.calls[0]?.[0])).toBe(
      "https://api.openai.com/v1/chat/completions",
    );
    const body = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    expect(body.messages[1].content[1]).toEqual({
      type: "input_audio",
      input_audio: { data: "YWNjZXB0ZWQtcHJlZml4", format: "wav" },
    });
    expect(body.messages[1].content[0].text).not.toContain("bTRh");
  });
  it("does not add an unbounded second transcription to a repair operation", async () => {
    const transcribe = vi.fn();
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue({ decision: "NO_CHANGE" }),
      transcribe,
    });
    const request = command("TRANSCRIPT_REPAIR_V1", {
      audioBase64: "bTRh",
      audioFormat: "audio/mp4",
    });
    const quote = await provider.quote(request);
    await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });
    expect(transcribe).not.toHaveBeenCalled();
  });
  it("requests a separate bilingual natural example with a strict expression schema", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          output_text: JSON.stringify({
            kind: "EXPRESSION",
            expressionId: "x",
            explanationZh: "高兴的",
            contextualMeaningZh: "高兴的",
            usageZh: "在 be 后说明感受。",
            expressionHintZh: null,
            phonetic: null,
            naturalExample: {
              textEn: "She looks happy.",
              textZh: "她看起来很高兴。",
            },
          }),
        }),
      ),
    );
    const client = new OpenAiLocalClient("test-key", "test-model", fetcher);
    await client.generateJson("WORD_EXPLANATION_V1", {
      requestedExpression: { expressionId: "x", display: "happy" },
    });
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    expect(request.text.format).toMatchObject({
      type: "json_schema",
      strict: true,
      schema: {
        additionalProperties: false,
        properties: {
          contextualMeaningZh: { type: "string", minLength: 1 },
          usageZh: { type: "string", minLength: 1 },
          expressionHintZh: { type: ["string", "null"], minLength: 1 },
          naturalExample: {
            required: ["textEn", "textZh"],
            additionalProperties: false,
          },
        },
      },
    });
    expect(request.text.format.schema.required).toEqual(
      Object.keys(request.text.format.schema.properties),
    );
    expect(request.instructions).toContain("contextualMeaningZh");
    expect(request.instructions).toContain("usageZh");
    expect(request.instructions).toContain("expressionHintZh");
    expect(request.instructions).toContain("do not fill a quota");
  });
  it("does not let correct confirmed text override non-English audio evidence", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            choices: [
              {
                message: {
                  content: JSON.stringify({
                    observedTranscript: "今天我很平静。",
                    language: "zh",
                    intelligible: false,
                    confidence: 0.9,
                    wordIssues: [],
                    rhythmIssues: [],
                  }),
                },
              },
            ],
          }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            output_text: JSON.stringify({
              decision: "PASS",
              pronunciationIntelligible: true,
              pronunciationConfidence: 0.99,
            }),
          }),
        ),
      );
    const client = new OpenAiLocalClient(
      "test-key",
      "test-model",
      fetcher,
      async () => "d2F2",
    );
    await expect(
      client.generateJson("FULL_SENTENCE_SPEAKING_EVALUATION_V1", {
        audioBase64: "bTRh",
        audioMimeType: "audio/mp4",
        confirmedTranscript: "I felt calm today.",
        targetTextEn: "I felt calm today.",
      }),
    ).resolves.toMatchObject({
      decision: "INCONCLUSIVE",
      pronunciationIntelligible: false,
    });
    const contentRequest = JSON.parse(String(fetcher.mock.calls[1]?.[1]?.body));
    expect(
      JSON.parse(contentRequest.input).input.pronunciationEvidence.language,
    ).toBe("zh");
  });
  it("records the audio model for an audio assessment receipt", async () => {
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue({ decision: "INCONCLUSIVE" }),
      transcribe: vi.fn(),
    });
    const request = command("FULL_SENTENCE_SPEAKING_EVALUATION_V1");
    const quote = await provider.quote(request);
    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });
    expect(result.receipt.modelSource).toBe("openai/gpt-audio-1.5");
  });
  it("sends pronunciation evidence as native audio rather than base64 text", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            choices: [
              {
                message: {
                  content:
                    '{"observedTranscript":"I can explain it.","language":"en","intelligible":true,"confidence":0.9,"wordIssues":[],"rhythmIssues":[]}',
                },
              },
            ],
          }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ output_text: JSON.stringify({ decision: "PASS" }) }),
        ),
      );
    const decode = vi.fn().mockResolvedValue("d2F2");
    const client = new OpenAiLocalClient(
      "test-key",
      "test-text-model",
      fetcher,
      decode,
    );
    await client.generateJson("FULL_SENTENCE_SPEAKING_EVALUATION_V1", {
      audioBase64: "bTRh",
      audioMimeType: "audio/mp4",
      confirmedTranscript: "I can explain it.",
      targetTextEn: "I can explain it.",
    });
    expect(fetcher.mock.calls[0]?.[0]).toBe(
      "https://api.openai.com/v1/chat/completions",
    );
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    expect(request.model).toBe("gpt-audio-1.5");
    expect(request.store).toBe(false);
    expect(request.messages[1].content[1]).toEqual({
      type: "input_audio",
      input_audio: { data: "d2F2", format: "wav" },
    });
    expect(request.messages[1].content[0].text).not.toContain("bTRh");
    expect(JSON.stringify(request.messages)).not.toContain("I can explain it.");
    expect(decode).toHaveBeenCalledWith("bTRh", "audio/mp4");
  });
  it("transcribes mixed-language capture and excludes words belonging to the adjacent windows", async () => {
    const transcribeTimed = vi.fn().mockResolvedValue({
      text: "Before. 周五 works. After.", language: "mixed", durationSeconds: 5,
      words: [
        { word: "Before", start: 0.1, end: 0.6 },
        { word: "周五", start: 1.2, end: 1.8 },
        { word: "works", start: 2.3, end: 2.7 },
        { word: "After", start: 4.1, end: 4.6 },
      ],
    });
    const client = { generateJson: vi.fn(), transcribe: vi.fn(), transcribeTimed };
    const provider = new LocalOpenAiMeteredProvider(client);
    const request = command("SPEECH_TRANSCRIPTION_V1", { durationSeconds: 5, audioFormat: "audio/wav",
      endReason: "CAPTURE_WINDOW", languageHint: "auto", coreStartMs: 1000, coreEndMs: 4000, audioBase64: "YWJj" });
    const quote = await provider.quote(request);
    const result = await provider.execute({ ...request, providerAttemptId: quote.providerAttemptId });
    expect(transcribeTimed).toHaveBeenCalledWith("YWJj", "audio/wav", "auto");
    expect(result.output).toMatchObject({ fullText: "周五 works.", segments: [{ startMs: 1200, endMs: 2700 }] });
    expect(client.generateJson).not.toHaveBeenCalled();
  });

  it("does not manufacture segment confidence for recording transcription", async () => {
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue({
        units: [{ startWordIndex: 0, endWordIndex: 1, completion: "COMPLETE" }],
      }),
      transcribe: vi.fn().mockResolvedValue("今天去公园。"),
      transcribeTimed: vi.fn().mockResolvedValue({
        text: "今天去公园。",
        language: "chinese",
        durationSeconds: 3,
        words: [{ word: "今天去公园", start: 0.2, end: 2.8 }],
      }),
    });
    const request = command("SPEECH_TRANSCRIPTION_V1", {
      durationSeconds: 3,
      endReason: "USER_STOPPED",
      audioBase64: "YWJj",
    });
    const quote = await provider.quote(request);
    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });
    expect(result.output).toMatchObject({ segments: [{ confidence: null }] });
  });
  it("does not manufacture confidence for a text-only transcription", async () => {
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn(),
      transcribe: vi.fn().mockResolvedValue("I can explain it."),
    });
    const request = command("SPEAKING_TRANSCRIPTION_V1", {
      audioBase64: "YWJj",
      audioMimeType: "audio/mp4",
    });
    const quote = await provider.quote(request);
    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });
    expect(result.output).toMatchObject({
      transcript: "I can explain it.",
      confidence: null,
    });
  });
  it("uses the current transcription language field and matching audio filename", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response('{"text":"I can explain it."}'));
    await new OpenAiLocalClient("test-key", "test-model", fetcher).transcribe(
      "YWJj",
      "audio/wav",
    );
    const form = fetcher.mock.calls[0]?.[1]?.body as FormData;
    expect(form.has("language")).toBe(false);
    expect((form.get("file") as File).name).toBe("recording.wav");
  });
  it("does not convert rejected audio requests into a successful empty transcript", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response("", { status: 503 }));
    await expect(
      new OpenAiLocalClient("test-key", "test-model", fetcher).transcribe(
        "YWJj",
        "audio/mp4",
      ),
    ).rejects.toThrow("503");
  });
  it("asks for conventional learning targets without filling a card quota", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response('{"output_text":"{}"}'));
    await new OpenAiLocalClient("test-key", "test-model", fetcher).generateJson(
      "LEARNING_TARGET_EXTRACTION_V1",
      {},
    );
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    expect(request.instructions).toContain("Do not fill a quota");
    const structure =
      request.text.format.schema.properties.targets.items.properties
        .structureParts;
    expect(structure).toEqual({
      type: "array",
      items: { type: "string", pattern: "\\S" },
    });
    // Empty WORD/FIXED_EXPRESSION structures remain permitted: no unconditional minimum.
    expect(structure.minItems).toBeUndefined();
    expect(request.instructions).toContain("conventional collocations");
    expect(request.instructions).toContain("UTF-16");
    expect(request.instructions).toContain(
      "WORD display must match surfaceForm",
    );
    expect(request.instructions).toContain(
      "never bind a different word through normalizedForm",
    );
    expect(request.instructions).toContain("‘ ’ ʼ ＇");
    expect(request.instructions).toContain(
      "allowedForms must contain only surfaceForm",
    );
    expect(
      request.text.format.schema.properties.targets.items.required,
    ).toContain("sourceEvidence");
    expect(
      request.text.format.schema.properties.targets.items.properties
        .sourceEvidence.required,
    ).toEqual([
      "version",
      "startUtf16",
      "endUtf16",
      "surfaceForm",
      "normalizedForm",
      "allowedForms",
    ]);
  });
  it("quotes and executes one idempotent metered attempt", async () => {
    const generateJson = vi.fn().mockResolvedValue({ decision: "PASS" });
    const provider = new LocalOpenAiMeteredProvider({
      generateJson,
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("CONTENT_SAFETY_CHECK_V1", {
      text: "今天和朋友吃饭。",
      sourceType: "DIARY",
      contentShape: "ARTICLE",
    });
    const quote = await provider.quote(request);
    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });

    expect(quote.maximumChargeFen).toBe(1);
    expect(result.output).toMatchObject({
      decision: "PASS",
      contextConsidered: true,
    });
    expect(result.receipt.providerAttemptId).toBe(quote.providerAttemptId);
    expect(generateJson).toHaveBeenCalledWith(
      "CONTENT_SAFETY_CHECK_V1",
      expect.objectContaining({ text: "今天和朋友吃饭。" }),
    );
  });

  it("splits the complete source without losing punctuation or spaces", async () => {
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue({
        segments: [
          {
            textZh: "今天下雨。 ",
            speakerRole: null,
            semanticPurposeZh: "描述天气",
            recommendationReasonZh: "内容具体",
          },
          {
            textZh: "但是我还是出门了！",
            speakerRole: null,
            semanticPurposeZh: "描述行动",
            recommendationReasonZh: "适合学习",
          },
        ],
      }),
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("SEMANTIC_UNIT_SPLIT_V1", {
      text: "今天下雨。 但是我还是出门了！",
      contentShape: "ARTICLE",
    });
    const quote = await provider.quote(request);
    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });
    const units = (result.output as { units: Array<{ textZh: string }> }).units;

    expect(units.map((unit) => unit.textZh).join("")).toBe(
      "今天下雨。 但是我还是出门了！",
    );
    expect(units).toHaveLength(2);
  });

  it("instructs semantic splitting to produce shortest learnable propositions instead of a whole comma-linked paragraph", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response('{"output_text":"{}"}'));
    await new OpenAiLocalClient("test-key", "test-model", fetcher).generateJson(
      "SEMANTIC_UNIT_SPLIT_V1",
      {},
    );
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    expect(request.instructions).toContain("SHORTEST units");
    expect(request.instructions).toContain("reason, contrast, consequence");
    expect(request.instructions).toContain("今天我很不开心");
    expect(request.instructions).toContain("must be multiple ordered segments");
    expect(request.instructions).toContain("如果…就…");
  });

  it("reconstructs omitted inter-unit whitespace from exact source spans without rewriting dialogue", async () => {
    const source =
      "  用户：如果明天下雨，我就改乘地铁。\r\n对方：好的，到了告诉我。\n";
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue({
        segments: [
          {
            textZh: "用户：如果明天下雨，我就改乘地铁。",
            speakerRole: "USER",
            semanticPurposeZh: "说明条件与行动",
            recommendationReasonZh: null,
          },
          {
            textZh: "对方：好的，到了告诉我。",
            speakerRole: "OTHER",
            semanticPurposeZh: "回应并请求通知",
            recommendationReasonZh: null,
          },
        ],
      }),
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("SEMANTIC_UNIT_SPLIT_V1", {
      text: source,
      contentShape: "DIALOGUE",
    });
    const quote = await provider.quote(request);
    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });
    const units = (
      result.output as {
        units: Array<{
          textZh: string;
          sourceSpans: Array<{ startCodePoint: number; endCodePoint: number }>;
        }>;
      }
    ).units;
    expect(units).toHaveLength(2);
    expect(units.map((unit) => unit.textZh).join("")).toBe(source);
    for (const unit of units)
      expect(
        unit.sourceSpans
          .map((span) =>
            Array.from(source)
              .slice(span.startCodePoint, span.endCodePoint)
              .join(""),
          )
          .join(""),
      ).toBe(unit.textZh);
  });

  it("merges excess segments into the tenth unit without losing source", async () => {
    const source = Array.from(
      { length: 12 },
      (_, index) => `第${index + 1}句。`,
    ).join("");
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue({
        segments: Array.from({ length: 12 }, (_, index) => ({
          textZh: `第${index + 1}句。`,
          speakerRole: null,
          semanticPurposeZh: "完整意思",
          recommendationReasonZh: null,
        })),
      }),
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("SEMANTIC_UNIT_SPLIT_V1", {
      text: source,
      contentShape: "ARTICLE",
    });
    const quote = await provider.quote(request);
    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });
    const units = (result.output as { units: Array<{ textZh: string }> }).units;

    expect(units).toHaveLength(10);
    expect(units.map((unit) => unit.textZh).join("")).toBe(source);
  });

  it("fails instead of returning a fake safety success when OpenAI is unavailable", async () => {
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue(null),
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("CONTENT_SAFETY_CHECK_V1", { text: "测试" });
    const quote = await provider.quote(request);

    await expect(
      provider.execute({
        ...request,
        providerAttemptId: quote.providerAttemptId,
      }),
    ).rejects.toThrow(
      "OpenAI returned no usable CONTENT_SAFETY_CHECK_V1 result",
    );
  });

  it("keeps generated English identities and fidelity evidence stable", async () => {
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue({
        sentences: [
          {
            sourceUnitId: "u1",
            candidates: [
              {
                candidateId: "candidate-u1-1",
                english: "I felt calm today.",
                chineseCorrespondenceZh: "今天我很平静。",
                meaningClaims: ["今天", "我很平静"],
                styleFit: "自然、简洁",
                reusedKnowledgeRefs: [],
                fidelity: {
                  facts: true,
                  entities: true,
                  time: true,
                  conditions: true,
                  causality: true,
                  logic: true,
                  tone: true,
                  questions: true,
                  noHallucination: true,
                },
              },
            ],
          },
        ],
      }),
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("ENGLISH_CANDIDATE_GENERATION_V1", {
      units: [{ unitId: "u1", textZh: "今天我很平静。" }],
    });
    const quote = await provider.quote(request);
    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });

    expect(result.output).toMatchObject({
      sentences: [
        {
          sourceUnitId: "u1",
          candidates: [
            {
              english: "I felt calm today.",
              candidateId: "candidate-u1-1",
              fidelity: { facts: true, noHallucination: true },
            },
          ],
        },
      ],
    });
  });

  it("fails instead of inventing an English candidate when OpenAI is unavailable", async () => {
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue(null),
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("ENGLISH_CANDIDATE_GENERATION_V1", {
      units: [{ unitId: "u1", textZh: "今天我很平静。" }],
    });
    const quote = await provider.quote(request);

    await expect(
      provider.execute({
        ...request,
        providerAttemptId: quote.providerAttemptId,
      }),
    ).rejects.toThrow(
      "OpenAI returned no usable ENGLISH_CANDIDATE_GENERATION_V1 result",
    );
  });

  it("uses OpenAI chapter context output instead of a local fixed window", async () => {
    const generateJson = vi.fn().mockResolvedValue({
      chapters: [{ chapterId: "model-chapter", unitIds: ["u2", "u3"] }],
    });
    const provider = new LocalOpenAiMeteredProvider({
      generateJson,
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("CHAPTER_CONTEXT_SELECTION_V1", {
      units: [
        { unitId: "u1", textZh: "一" },
        { unitId: "u2", textZh: "二" },
        { unitId: "u3", textZh: "三" },
      ],
      selectedUnitIds: ["u2"],
    });
    const quote = await provider.quote(request);

    await expect(
      provider.execute({
        ...request,
        providerAttemptId: quote.providerAttemptId,
      }),
    ).resolves.toMatchObject({
      output: {
        chapters: [{ chapterId: "model-chapter", unitIds: ["u2", "u3"] }],
      },
    });
    expect(generateJson).toHaveBeenCalledWith(
      "CHAPTER_CONTEXT_SELECTION_V1",
      expect.objectContaining({ selectedUnitIds: ["u2"] }),
    );
  });

  it("returns exactly the clicked expression target for an incremental extraction", async () => {
    const modelOutput = {
      targets: [
        {
          targetId: "target-u1-expression-3",
          unitId: "u1",
          display: "worked",
          explanationZh: "worked 在当前语境中表示‘鼓起’。",
          type: "WORD",
          structureParts: [],
          relatedVocabulary: ["work up"],
          selectedByDefault: true,
        },
      ],
      queryableExpressions: [
        { expressionId: "u1-expression-3", unitId: "u1", display: "worked" },
      ],
    };
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue(modelOutput),
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("LEARNING_TARGET_EXTRACTION_V1", {
      chapters: [
        {
          chapterId: "chapter-1",
          sentences: [
            {
              unitId: "u1",
              textZh: "我终于鼓起勇气。",
              textEn: "I finally worked up the courage.",
              isFocus: true,
            },
          ],
        },
      ],
      requestedExpression: { unitId: "u1", expressionId: "u1-expression-3" },
    });
    const quote = await provider.quote(request);
    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });
    const output = result.output as {
      targets: Array<{ unitId: string; display: string }>;
    };

    expect(output.targets).toEqual(modelOutput.targets);
  });

  it.each([
    "KNOWLEDGE_EXAMPLE_GENERATION_V1",
    "SENTENCE_EXPLANATION_V1",
    "WORD_EXPLANATION_V1",
  ])(
    "fails closed for %s instead of returning canned learning content",
    async (task) => {
      const provider = new LocalOpenAiMeteredProvider({
        generateJson: vi.fn().mockResolvedValue(null),
        transcribe: vi.fn().mockResolvedValue(""),
      });
      const request = command(task, {
        itemId: "item-1",
        display: "work up the courage",
        meaningZh: "鼓起勇气",
        chapter: {
          sentences: [
            {
              sentenceId: "s1",
              textEn: "I worked up the courage.",
              textZh: "我鼓起了勇气。",
              isFocus: true,
            },
          ],
        },
        focusSentenceId: "s1",
        requestedExpression:
          task === "WORD_EXPLANATION_V1"
            ? { expressionId: "e1", display: "work up the courage" }
            : null,
      });
      const quote = await provider.quote(request);

      await expect(
        provider.execute({
          ...request,
          providerAttemptId: quote.providerAttemptId,
        }),
      ).rejects.toThrow(`OpenAI returned no usable ${task} result`);
    },
  );

  it("passes through model-generated examples and explanations", async () => {
    const generated = {
      itemId: "item-1",
      phonetic: null,
      pronunciationText: "work up the courage",
      representativeScene: "面试前决定开口",
      naturalRevalidation: true,
      examples: [
        {
          textEn: "I worked up the courage to ask.",
          textZh: "我鼓起勇气去问。",
          pronunciationText: "I worked up the courage to ask.",
        },
        {
          textEn: "She worked up the courage to call.",
          textZh: "她鼓起勇气打了电话。",
          pronunciationText: "She worked up the courage to call.",
        },
        {
          textEn: "We worked up the courage to speak.",
          textZh: "我们鼓起勇气发言。",
          pronunciationText: "We worked up the courage to speak.",
        },
      ],
    };
    const generateJson = vi.fn().mockResolvedValue(generated);
    const provider = new LocalOpenAiMeteredProvider({
      generateJson,
      transcribe: vi.fn().mockResolvedValue(""),
    });
    const request = command("KNOWLEDGE_EXAMPLE_GENERATION_V1", {
      itemId: "item-1",
      display: "work up the courage",
      meaningZh: "鼓起勇气",
    });
    const quote = await provider.quote(request);

    await expect(
      provider.execute({
        ...request,
        providerAttemptId: quote.providerAttemptId,
      }),
    ).resolves.toMatchObject({ output: generated });
  });

  it.each([
    "READING_FEEDBACK_V1",
    "FULL_SENTENCE_SPEAKING_EVALUATION_V1",
    "PATTERN_PRACTICE_FEEDBACK_V1",
    "PATTERN_INDEPENDENT_EVALUATION_V1",
    "SCENE_RESPONSE_TRAINING_FEEDBACK_V1",
    "SCENE_RESPONSE_EVALUATION_V1",
  ])("fails closed for %s instead of returning a canned pass", async (task) => {
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue(null),
      transcribe: vi.fn().mockResolvedValue("learner transcript"),
    });
    const request = command(task, {
      targetTextEn: "I worked up the courage.",
      targetResponseEn: "I worked up the courage.",
      targetStructure: "work up the courage",
      confirmedTranscript: "I worked courage.",
      audio: { base64: "YXVkaW8=", mimeType: "audio/mp4" },
      audioBase64: "YXVkaW8=",
      audioMimeType: "audio/mp4",
    });
    const quote = await provider.quote(request);

    await expect(
      provider.execute({
        ...request,
        providerAttemptId: quote.providerAttemptId,
      }),
    ).rejects.toThrow(`OpenAI returned no usable ${task} result`);
  });

  it("uses the actual reading transcript as model evidence instead of substituting the target", async () => {
    const generateJson = vi.fn().mockResolvedValue({
      feedbackZh: "有漏词，请放慢后再读一次。",
      feedbackItems: [
        {
          aspect: "WORD_OR_PHRASE",
          targetText: "up the",
          observationZh: "识别文本缺少该短语。",
          suggestionZh: "把 up the 连贯读出。",
          evidence: "TRANSCRIPT",
        },
        {
          aspect: "RHYTHM",
          targetText: null,
          observationZh: "没有可用的原音节奏证据。",
          suggestionZh: "按意群停顿。",
          evidence: "GENERAL",
        },
        {
          aspect: "COMPLETENESS",
          targetText: "up the",
          observationZh: "识别文本未覆盖该片段。",
          suggestionZh: "重读完整句子。",
          evidence: "TRANSCRIPT",
        },
      ],
      transcript: "I worked courage.",
      feedbackConfidence: 0.82,
      action: "REPEAT_FOR_CLARITY",
    });
    const provider = new LocalOpenAiMeteredProvider({
      generateJson,
      transcribe: vi.fn().mockResolvedValue("I worked courage."),
    });
    const request = command("READING_FEEDBACK_V1", {
      targetTextEn: "I worked up the courage.",
      audio: { base64: "YXVkaW8=", mimeType: "audio/mp4" },
    });
    const quote = await provider.quote(request);

    const result = await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    });
    expect(result.output).toEqual(await generateJson.mock.results[0]?.value);
    expect(generateJson).toHaveBeenCalledWith(
      "READING_FEEDBACK_V1",
      expect.objectContaining({ transcript: "I worked courage." }),
    );
  });

  it.each([
    "PATTERN_PRACTICE_GENERATION_V1",
    "SCENE_RESPONSE_GENERATION_V1",
    "TRANSCRIPT_REPAIR_V1",
    "RECENT_SCENE_RECOMMENDATION_V1",
    "SCENE_NEXT_QUESTION_V1",
    "SCENE_DIALOGUE_TURN_V1",
    "SCENE_ARTICLE_DRAFT_V1",
    "SCENE_EDIT_REVIEW_V1",
  ])(
    "fails closed for %s instead of returning fixed generated content",
    async (task) => {
      const provider = new LocalOpenAiMeteredProvider({
        generateJson: vi.fn().mockResolvedValue(null),
        transcribe: vi.fn().mockResolvedValue("真实录音内容"),
      });
      const request = command(task, {
        patternId: "p1",
        sourceTextEn: "I worked up the courage.",
        sourceTextZh: "我鼓起了勇气。",
        focusSentenceId: "s1",
        chapterSentences: [
          {
            sentenceId: "s1",
            textEn: "I worked up the courage.",
            textZh: "我鼓起了勇气。",
            isFocus: true,
          },
        ],
        audioBase64: "YXVkaW8=",
        audioFormat: "audio/mp4",
      });
      const quote = await provider.quote(request);

      await expect(
        provider.execute({
          ...request,
          providerAttemptId: quote.providerAttemptId,
        }),
      ).rejects.toThrow(`OpenAI returned no usable ${task} result`);
    },
  );

  it("rejects an empty speech transcription instead of inventing a diary sentence", async () => {
    const provider = new LocalOpenAiMeteredProvider({
      generateJson: vi.fn().mockResolvedValue(null),
      transcribe: vi.fn().mockResolvedValue(""),
      transcribeTimed: vi.fn().mockResolvedValue({ text: "", words: [] }),
    });
    const request = command("SPEECH_TRANSCRIPTION_V1", {
      durationSeconds: 3,
      endReason: "USER_STOPPED",
      audioBase64: "YXVkaW8=",
    });
    const quote = await provider.quote(request);

    await expect(
      provider.execute({
        ...request,
        providerAttemptId: quote.providerAttemptId,
      }),
    ).rejects.toThrow("Missing measured transcription evidence");
  });
});

describe("OpenAiLocalClient", () => {
  it.each(["LEARNING_TARGET_EXTRACTION_V1", "KNOWLEDGE_EXAMPLE_GENERATION_V1"])(
    "requires strict structured fields for %s",
    async (task) => {
      const fetcher = vi
        .fn()
        .mockResolvedValue(new Response('{"output_text":"{}"}'));
      await new OpenAiLocalClient(
        "test-key",
        "test-model",
        fetcher,
      ).generateJson(task, {});
      const body = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
      expect(body.text.format).toMatchObject({
        type: "json_schema",
        strict: true,
        schema: { type: "object", additionalProperties: false },
      });
      expect(body.text.format.schema.required.length).toBeGreaterThan(0);
    },
  );
  it("reads JSON text from the raw Responses HTTP payload", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          output: [
            {
              type: "message",
              content: [{ type: "output_text", text: '{"decision":"PASS"}' }],
            },
          ],
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );
    const client = new OpenAiLocalClient("test-key", "test-model", fetcher);

    await expect(
      client.generateJson("CONTENT_SAFETY_CHECK_V1", {}),
    ).resolves.toEqual({
      decision: "PASS",
    });
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body)) as {
      instructions: string;
      input: string;
    };
    expect(request.instructions).toContain("json");
    expect(JSON.parse(request.input)).toMatchObject({ format: "json" });
  });

  it("reports the HTTP status instead of silently returning a fakeable null", async () => {
    const client = new OpenAiLocalClient(
      "test-key",
      "test-model",
      vi.fn().mockResolvedValue(
        new Response('{"error":{"message":"request was rejected"}}', {
          status: 403,
        }),
      ),
    );

    await expect(
      client.generateJson("CONTENT_SAFETY_CHECK_V1", {}),
    ).rejects.toThrow(
      "OpenAI CONTENT_SAFETY_CHECK_V1 request failed with status 403: request was rejected",
    );
  });
});
