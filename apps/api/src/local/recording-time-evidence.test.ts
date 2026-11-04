import { describe, expect, it, vi } from "vitest";
import {
  assembleRecordingTranscript,
  validateTimedRecording,
} from "./recording-time-evidence.js";
import {
  LocalOpenAiMeteredProvider,
  OpenAiLocalClient,
} from "./openai-metered-provider.js";

const evidence = {
  text: "我去了公园。我还想",
  language: "chinese",
  durationSeconds: 120,
  words: [
    { word: "我", start: 1, end: 1.2 },
    { word: "去了", start: 1.2, end: 1.5 },
    { word: "公园", start: 1.5, end: 2 },
    { word: "我", start: 119, end: 119.2 },
    { word: "还想", start: 119.2, end: 120 },
  ],
};
const units = [
  { startWordIndex: 0, endWordIndex: 3, completion: "COMPLETE" },
  { startWordIndex: 3, endWordIndex: 5, completion: "INCOMPLETE" },
];
const confirmation = {
  needsConfirmation: true,
  alternatives: ["我去了果园。", "我去了公园。"],
  mostLikelyText: "我去了果园。",
  mostLikelyIsInference: true,
};
const clearConfirmation = {
  needsConfirmation: false,
  alternatives: [],
  mostLikelyText: null,
  mostLikelyIsInference: false,
};
const command = (endReason = "MAX_DURATION") => ({
  accountId: "isolated",
  operationId: "operation",
  operationLabelZh: "测试转写",
  commandId: "command",
  task: "SPEECH_TRANSCRIPTION_V1",
  callType: "INITIAL_CALL" as const,
  input: {
    recordingId: "r1",
    durationSeconds: 120,
    endReason,
    audioBase64: "aXNvbGF0ZWQ=",
    audioFormat: "audio/mp4",
  },
});
const execute = async (
  client: ConstructorParameters<typeof LocalOpenAiMeteredProvider>[0],
  endReason?: string,
) => {
  const provider = new LocalOpenAiMeteredProvider(client);
  const request = command(endReason);
  const quote = await provider.quote(request);
  return (
    await provider.execute({
      ...request,
      providerAttemptId: quote.providerAttemptId,
    })
  ).output;
};
const client = () => ({
  transcribe: vi.fn().mockResolvedValue(evidence.text),
  transcribeTimed: vi.fn().mockResolvedValue(evidence),
  generateJson: vi.fn().mockResolvedValue({ units }),
});

describe("recording timing and semantic evidence", () => {
  it("never offers unchanged wording as an AI repair while retaining the need for confirmation", () => {
    const result = assembleRecordingTranscript(
      validateTimedRecording(evidence, 120),
      {
        units: [
          {
            ...units[0],
            needsConfirmation: true,
            alternatives: ["我去了公园。", "我走到了公园。"],
            mostLikelyText: "我去了公园。",
            mostLikelyIsInference: true,
          },
          {
            ...units[1],
            needsConfirmation: false,
            alternatives: [],
            mostLikelyText: null,
            mostLikelyIsInference: false,
          },
        ],
      },
      "MAX_DURATION",
    );
    expect(result.segments[0]).toMatchObject({
      needsConfirmation: true,
      alternatives: ["我走到了公园。"],
      mostLikelyText: null,
      mostLikelyIsInference: false,
    });
    expect(result.fullText).toBe("我去了公园。");
  });
  it("requests provider word timestamps instead of deriving times from client duration", async () => {
    const shortEvidence = {
      ...evidence,
      text: "我去了公园。",
      durationSeconds: 4,
      words: evidence.words.slice(0, 3),
    };
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          text: shortEvidence.text,
          language: shortEvidence.language,
          duration: shortEvidence.durationSeconds,
          words: shortEvidence.words,
        }),
      ),
    );
    const wave = Buffer.alloc(44 + 4 * 16000 * 2, 1);
    wave.write("RIFF");
    wave.writeUInt32LE(wave.length - 8, 4);
    wave.write("WAVEfmt ", 8);
    wave.writeUInt32LE(16, 16);
    wave.writeUInt16LE(1, 20);
    wave.writeUInt16LE(1, 22);
    wave.writeUInt32LE(16000, 24);
    wave.writeUInt32LE(32000, 28);
    wave.writeUInt16LE(2, 32);
    wave.writeUInt16LE(16, 34);
    wave.write("data", 36);
    wave.writeUInt32LE(wave.length - 44, 40);
    const decode = vi.fn().mockResolvedValue(wave.toString("base64"));
    const result = await new OpenAiLocalClient(
      "test-key",
      "test-model",
      fetcher,
      decode,
    ).transcribeTimed("aXNvbGF0ZWQ=", "audio/mp4");
    const form = fetcher.mock.calls[0]?.[1]?.body as FormData;
    expect(form.get("model")).toBe("whisper-1");
    expect(form.get("response_format")).toBe("verbose_json");
    expect(form.get("timestamp_granularities[]")).toBe("word");
    expect((form.get("file") as File).name).toBe("recording.wav");
    expect(form.get("language")).toBe("zh");
    expect(result).toEqual(shortEvidence);
    expect(decode).toHaveBeenCalledWith("aXNvbGF0ZWQ=", "audio/mp4", {
      maximumBase64Length: 8_000_000,
      maximumDurationSeconds: 121,
    });
  });

  it("semantic decisions inspect actual decoded audio and never use supplied words as generated audio", async () => {
    const decode = vi.fn().mockResolvedValue("ZGVjb2RlZC1vcmlnaW5hbA==");
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          choices: [
            {
              finish_reason: "stop",
              message: { content: JSON.stringify({ units }) },
            },
          ],
        }),
      ),
    );
    await new OpenAiLocalClient(
      "test-key",
      "test-model",
      fetcher,
      decode,
    ).generateJson("RECORDING_SEMANTIC_BOUNDARIES_V1", {
      audioBase64: "b3JpZ2luYWw=",
      words: evidence.words,
      transcript: evidence.text,
      endReason: "MAX_DURATION",
    });
    const body = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    expect(body.model).toBe("gpt-audio-1.5");
    expect(body.messages[1].content[1]).toEqual({
      type: "input_audio",
      input_audio: { data: "ZGVjb2RlZC1vcmlnaW5hbA==", format: "wav" },
    });
    expect(body.messages[1].content[0].text).not.toContain("b3JpZ2luYWw=");
    expect(body.store).toBe(false);
    expect(body.messages[0].content).toContain("needsConfirmation");
    expect(body.messages[0].content).toContain("mostLikelyIsInference");
    expect(body.messages[0].content).toContain(
      "completeness, not transcription certainty",
    );
    const supplied = JSON.parse(body.messages[1].content[0].text);
    expect(supplied.wordCount).toBe(evidence.words.length);
    expect(supplied.words).toEqual(
      evidence.words.map((word, index) => ({
        ...word,
        startWordIndex: index,
        endWordIndex: index + 1,
      })),
    );
    expect(body.messages[0].content).toContain(
      "Copy the supplied boundary indices; never count characters",
    );
    expect(body.messages[0].content).toContain(
      "Keep subordinate clauses and enumerations with the main expression",
    );
    expect(body.messages[0].content).toContain(
      "omitted repetitions, filler words or pronouns",
    );
  });
  it("drops only the incomplete cutoff unit and uses measured word boundaries", async () => {
    const ai = client();
    const result = await execute(ai);
    expect(result).toMatchObject({
      fullText: "我去了公园。",
      segments: [
        { startMs: 1000, endMs: 2000, text: "我去了公园。", confidence: null },
      ],
      finalBoundary: { acceptedEndMs: 2000, droppedIncompleteTail: true },
    });
    expect(ai.transcribe).not.toHaveBeenCalled();
    expect(ai.generateJson).toHaveBeenCalledWith(
      "RECORDING_SEMANTIC_BOUNDARIES_V1",
      expect.objectContaining({
        audioBase64: "aXNvbGF0ZWQ=",
        words: evidence.words,
      }),
    );
  });

  it("does not silently discard manually stopped unfinished speech", async () => {
    expect(await execute(client(), "USER_STOPPED")).toMatchObject({
      fullText: evidence.text,
      segments: [
        { startMs: 1000, endMs: 2000 },
        { startMs: 119000, endMs: 120000 },
      ],
      finalBoundary: { acceptedEndMs: 120000, droppedIncompleteTail: false },
    });
  });

  it("preserves complete-unit candidates without replacing original text or inventing confidence", async () => {
    const ai = client();
    ai.generateJson.mockResolvedValue({
      units: [{ ...units[0], ...confirmation, confidence: 0.99 }, units[1]],
    });
    expect(await execute(ai)).toMatchObject({
      fullText: "我去了公园。",
      segments: [
        {
          text: "我去了公园。",
          confidence: null,
          ...confirmation,
          alternatives: ["我去了果园。"],
        },
      ],
      finalBoundary: { acceptedEndMs: 2000, droppedIncompleteTail: true },
    });
    expect(ai.transcribeTimed).toHaveBeenCalledTimes(1);
    expect(ai.generateJson).toHaveBeenCalledTimes(1);
  });

  it("distinguishes explicit clear content from legacy unknown confirmation state", async () => {
    const ai = client();
    ai.generateJson.mockResolvedValue({
      units: [{ ...units[0], ...clearConfirmation }, units[1]],
    });
    expect(await execute(ai)).toMatchObject({
      segments: [{ ...clearConfirmation, confidence: null }],
    });
    const legacy = (await execute(client())) as { segments: object[] };
    expect(legacy.segments[0]).not.toHaveProperty("needsConfirmation");
  });

  it("requires confirmation when ASR collapsed word times even if audio model claimed clear content", async () => {
    const ai = client();
    ai.transcribeTimed.mockResolvedValue({
      ...evidence,
      words: evidence.words.map((word, index) =>
        index === 1 ? { ...word, end: word.start } : word,
      ),
    });
    ai.generateJson.mockResolvedValue({
      units: [{ ...units[0], ...clearConfirmation }, units[1]],
    });
    expect(await execute(ai)).toMatchObject({
      segments: [
        {
          text: "我去了公园。",
          startMs: 1000,
          endMs: 2000,
          needsConfirmation: true,
          confidence: null,
          alternatives: [],
          mostLikelyText: null,
          mostLikelyIsInference: false,
        },
      ],
      finalBoundary: { acceptedEndMs: 2000, droppedIncompleteTail: true },
    });
  });

  it("allows uncertainty without inventing an unsupported candidate", async () => {
    const ai = client();
    ai.generateJson.mockResolvedValue({
      units: [
        { ...units[0], ...clearConfirmation, needsConfirmation: true },
        units[1],
      ],
    });
    expect(await execute(ai)).toMatchObject({
      segments: [
        { needsConfirmation: true, alternatives: [], mostLikelyText: null },
      ],
    });
  });

  it("never turns uncertain semantic completeness into success using a candidate", async () => {
    const ai = client();
    ai.generateJson.mockResolvedValue({
      units: [
        { ...units[0], ...confirmation, completion: "UNCERTAIN" },
        units[1],
      ],
    });
    await expect(execute(ai)).rejects.toThrow(
      "Uncertain recording audio evidence",
    );
  });

  it("excludes all candidate content from an incomplete cutoff unit", async () => {
    const ai = client();
    ai.generateJson.mockResolvedValue({
      units: [
        units[0],
        { ...units[1], ...confirmation, mostLikelyText: "尾句推测" },
      ],
    });
    const result = await execute(ai);
    expect(result).toMatchObject({
      fullText: "我去了公园。",
      segments: [{ text: "我去了公园。" }],
    });
    expect(JSON.stringify(result)).not.toContain("尾句推测");
  });

  it("does not attach inferred completions to manually stopped unfinished speech", async () => {
    const ai = client();
    ai.generateJson.mockResolvedValue({
      units: [units[0], { ...units[1], ...confirmation }],
    });
    await expect(execute(ai, "USER_STOPPED")).rejects.toThrow();
  });

  it("retains uncertain manually stopped original speech without inventing completion candidates", async () => {
    const ai = client();
    ai.generateJson.mockResolvedValue({
      units: [
        units[0],
        { ...units[1], ...clearConfirmation, needsConfirmation: true },
      ],
    });
    expect(await execute(ai, "USER_STOPPED")).toMatchObject({
      fullText: evidence.text,
      segments: [
        { text: "我去了公园。" },
        {
          text: "我还想",
          needsConfirmation: true,
          alternatives: [],
          mostLikelyText: null,
          mostLikelyIsInference: false,
        },
      ],
      finalBoundary: { acceptedEndMs: 120000, droppedIncompleteTail: false },
    });
  });

  it.each(Object.keys(confirmation))(
    "rejects a partially omitted semantic confirmation group: %s",
    async (missing) => {
      const fields = Object.fromEntries(
        Object.entries(confirmation).filter(([key]) => key !== missing),
      );
      const ai = client();
      ai.generateJson.mockResolvedValue({
        units: [{ ...units[0], ...fields }, units[1]],
      });
      await expect(execute(ai)).rejects.toThrow(
        "Invalid recording confirmation evidence",
      );
    },
  );

  it.each([
    { needsConfirmation: true },
    { ...confirmation, needsConfirmation: "true" },
    { ...confirmation, needsConfirmation: null },
    { ...confirmation, needsConfirmation: false },
    { ...confirmation, alternatives: [" "] },
    { ...confirmation, alternatives: [3] },
    { ...confirmation, alternatives: ["相同", "相同"] },
    { ...confirmation, mostLikelyText: " " },
    { ...confirmation, mostLikelyText: null },
    { ...confirmation, mostLikelyIsInference: false },
  ])(
    "rejects malformed or contradictory confirmation evidence %j",
    async (fields) => {
      const ai = client();
      ai.generateJson.mockResolvedValue({
        units: [{ ...units[0], ...fields }, units[1]],
      });
      await expect(execute(ai)).rejects.toThrow();
    },
  );

  it("fails instead of fabricating timestamps when the client has only text", async () => {
    await expect(
      execute({
        transcribe: vi.fn().mockResolvedValue(evidence.text),
        generateJson: vi.fn(),
      }),
    ).rejects.toThrow();
  });

  it.each(
    [
      [{ ...units[0], endWordIndex: 8 }],
      [{ ...units[0], startWordIndex: 1 }, units[1]],
      [units[0], { ...units[1], startWordIndex: 2 }],
      [units[0], { ...units[1], completion: "UNCERTAIN" }],
      // Live 120s audio returned inclusive ends and invented indices. Do not
      // silently shift or fill these ranges: that would invent audio evidence.
      [{ ...units[0], endWordIndex: 2 }, units[1]],
    ].map((units) => ({ units })),
  )(
    "rejects invented range or uncertain cutoff rather than returning success %j",
    async ({ units: invalidUnits }) => {
      const ai = client();
      ai.generateJson.mockResolvedValue({ units: invalidUnits });
      await expect(execute(ai)).rejects.toThrow();
    },
  );

  it("rejects timestamp evidence that does not match the transcript words", async () => {
    const ai = client();
    ai.transcribeTimed.mockResolvedValue({
      ...evidence,
      text: "另外编出来的内容。",
    });
    await expect(execute(ai)).rejects.toThrow();
    expect(ai.generateJson).not.toHaveBeenCalled();
  });
});
