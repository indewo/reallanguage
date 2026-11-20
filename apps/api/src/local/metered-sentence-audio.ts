import type { RunMeteredProviderAttempt } from "@reallanguage/backend-modules/billing";
import type { SentenceAudioApplication, SentenceAudioRequest } from "../presentation/sentence-audio.controller.js";
import type { SentenceAudioStore } from "./sentence-audio-store.js";
import { createHash } from "node:crypto";
import { ConflictException } from "@nestjs/common";
import type { AiDeliveryIdentity } from "@reallanguage/backend-modules/ai";

export class MeteredSentenceAudio implements SentenceAudioApplication {
  constructor(private readonly attempts: Pick<RunMeteredProviderAttempt, "execute">, private readonly store: Pick<SentenceAudioStore, "get">,
    private readonly failed?: (identity: AiDeliveryIdentity) => Promise<boolean>) {}
  async render(accountId: string, request: SentenceAudioRequest) {
    const { commandId, retryOfCommandId, ...originalInput } = request;
    if (retryOfCommandId) {
      const suffix = createHash("sha256").update(retryOfCommandId).digest("hex");
      const providerAttemptId = `qwen-tts-${createHash("sha256").update(JSON.stringify([accountId, retryOfCommandId, "TTS_RENDER_V1"])).digest("hex")}`;
      if (commandId !== `sentence-audio-retry-${suffix}` || !this.failed || !(await this.failed({ accountId, commandId: retryOfCommandId,
        task: "TTS_RENDER_V1", providerAttemptId, request: { input: originalInput, operationId: retryOfCommandId, callType: "INITIAL_CALL" } }))) {
        throw new ConflictException("Original speech request is not eligible for a new user attempt");
      }
    }
    const input = retryOfCommandId ? { ...originalInput, retryOfCommandId } : originalInput;
    return this.attempts.execute({ accountId, commandId, operationId: commandId, operationLabelZh: "英文发音",
      task: "TTS_RENDER_V1", callType: retryOfCommandId ? "USER_RETRY" : "INITIAL_CALL", input });
  }
  read(accountId: string, objectRef: string) { return this.store.get(accountId, objectRef); }
}
