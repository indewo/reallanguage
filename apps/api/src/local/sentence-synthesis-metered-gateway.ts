import { createHash } from "node:crypto";
import type { MeteredProviderCommand, MeteredProviderGateway } from "@reallanguage/backend-modules/billing";
import type { QwenSentenceSynthesis } from "./qwen-sentence-synthesis.js";
import type { SentenceAudioStore } from "./sentence-audio-store.js";
import { receiveSentenceAudio } from "./synthesized-sentence-audio.js";

/** Dedicated gateway supplied to RunMeteredProviderAttempt, never called by a public route directly. */
export class SentenceSynthesisMeteredGateway implements MeteredProviderGateway {
  constructor(
    private readonly provider: Pick<QwenSentenceSynthesis, "synthesize">,
    private readonly store: Pick<SentenceAudioStore, "put">,
    private readonly policy: { costMicroCnyPerCharacter: number; maximumChargeFen: number; retentionMs: number },
    private readonly receive = receiveSentenceAudio,
  ) {
    if (![policy.costMicroCnyPerCharacter, policy.maximumChargeFen, policy.retentionMs].every(n => Number.isSafeInteger(n) && n > 0) ||
        policy.retentionMs > 86_400_000) throw new Error("Missing verified synthesis pricing or retention policy");
  }
  private text(command: MeteredProviderCommand): string {
    const input = command.input as Record<string, unknown> | null;
    if (command.task !== "TTS_RENDER_V1" || !command.accountId.trim() || !command.commandId.trim() ||
        !input || typeof input.text !== "string" || !input.text.trim() || [...input.text].length > 600 ||
        input.language !== "en-US" || input.speed !== 3 || input.requireWordTimings !== false) {
      throw new Error("Unsupported sentence synthesis request");
    }
    return input.text;
  }
  private identity(command: MeteredProviderCommand) {
    return `qwen-tts-${createHash("sha256").update(JSON.stringify([command.accountId, command.commandId, command.task])).digest("hex")}`;
  }
  async quote(command: MeteredProviderCommand) {
    this.text(command);
    return { providerAttemptId: this.identity(command), maximumChargeFen: this.policy.maximumChargeFen };
  }
  async execute(command: MeteredProviderCommand & { providerAttemptId: string }) {
    const text = this.text(command);
    if (command.providerAttemptId !== this.identity(command)) throw new Error("Synthesis attempt identity mismatch");
    const artifact = await this.provider.synthesize(text);
    const cost = artifact.characters * this.policy.costMicroCnyPerCharacter;
    if (!Number.isSafeInteger(cost) || cost <= 0) throw new Error("Invalid synthesis usage receipt");
    const audio = await this.receive(artifact);
    const output = await this.store.put(command.accountId, audio.bytes, this.policy.retentionMs);
    return { output, receipt: { providerAttemptId: command.providerAttemptId,
      modelSource: "dashscope/qwen3-tts-flash", providerCostMicroCny: cost } };
  }
}
