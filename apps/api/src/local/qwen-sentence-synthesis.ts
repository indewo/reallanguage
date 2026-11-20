/** Provider-only artifact. The media gateway must ingest it before public delivery. */
export interface SynthesizedSentence {
  downloadUrl: string;
  expiresAtSeconds: number;
  providerRequestId: string;
  characters: number;
}

export class QwenSentenceSynthesis {
  constructor(
    private readonly apiKey: string,
    private readonly fetcher: typeof fetch = fetch,
    private readonly nowSeconds: () => number = () => Date.now() / 1000,
  ) {}

  async synthesize(text: string): Promise<SynthesizedSentence> {
    if (!text.trim() || [...text].length > 600) throw new Error("Unsupported synthesis text length");
    if (!this.apiKey.trim()) throw new Error("Missing synthesis credentials");
    // One whole utterance; no per-word splitting or automatic provider replay.
    const response = await this.fetcher("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", {
      method: "POST",
      headers: { Authorization: `Bearer ${this.apiKey}`, "Content-Type": "application/json" },
      signal: AbortSignal.timeout(45_000),
      body: JSON.stringify({ model: "qwen3-tts-flash", input: { text, voice: "Cherry", language_type: "English" } }),
    });
    if (!response.ok) throw new Error(`Sentence synthesis failed (${response.status})`);
    const value = await response.json() as {
      status_code?: number; request_id?: string;
      output?: { finish_reason?: string; audio?: { url?: string; expires_at?: number } };
      usage?: { characters?: number };
    };
    const audio = value.output?.audio;
    if (value.output?.finish_reason !== "stop" || (value.status_code != null && value.status_code !== 200) ||
        !audio?.url || !Number.isFinite(audio.expires_at) || audio.expires_at! <= this.nowSeconds() ||
        !value.request_id || !Number.isInteger(value.usage?.characters) || value.usage!.characters! <= 0) {
      throw new Error("Incomplete sentence synthesis artifact");
    }
    const url = new URL(audio.url);
    if (!["https:", "http:"].includes(url.protocol) || url.username || url.password ||
        !url.hostname.endsWith(".aliyuncs.com")) throw new Error("Unexpected synthesis artifact origin");
    // No invented duration, word timings, or en-US metadata: this provider response supplies none.
    return { downloadUrl: audio.url, expiresAtSeconds: audio.expires_at!, providerRequestId: value.request_id,
      characters: value.usage!.characters! };
  }
}
