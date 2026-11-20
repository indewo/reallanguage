import type { RunMeteredProviderAttempt } from "@reallanguage/backend-modules/billing";
import type { SentenceAudioApplication, SentenceAudioRequest } from "../presentation/sentence-audio.controller.js";
import type { SentenceAudioStore } from "./sentence-audio-store.js";
import { createHash } from "node:crypto";
import { ConflictException } from "@nestjs/common";
import type { AiDeliveryIdentity } from "@reallanguage/backend-modules/ai";

export class MeteredSentenceAudio implements SentenceAudioApplication {
  constructor(private readonly attempts: Pick<RunMeteredProviderAttempt, "execute">, private readonly store: Pick<SentenceAudioStore, "get">,
    private readonly canRegenerate?: (identity: AiDeliveryIdentity) => Promise<boolean>) {}
  async render(accountId: string, request: SentenceAudioRequest) {
    const { commandId, retryOfCommandId, retryGeneration, ...originalInput } = request;
    if (retryGeneration && !retryOfCommandId) throw new ConflictException("Missing original speech request");
    const generation = retryGeneration ?? 1;
    const retryCommand = (root: string, index: number) => `sentence-audio-retry-${createHash("sha256").update(index === 1 ? root : `${root}:${index}`).digest("hex")}`;
    if (retryOfCommandId) {
      const previous = generation === 1 ? retryOfCommandId : retryCommand(retryOfCommandId, generation - 1);
      const previousInput = generation === 1 ? originalInput : { ...originalInput, retryOfCommandId,
        ...(generation > 2 ? { retryGeneration: generation - 1 } : {}) };
      const providerAttemptId = `qwen-tts-${createHash("sha256").update(JSON.stringify([accountId, previous, "TTS_RENDER_V1"])).digest("hex")}`;
      if (commandId !== retryCommand(retryOfCommandId, generation) || !this.canRegenerate || !(await this.canRegenerate({ accountId, commandId: previous,
        task: "TTS_RENDER_V1", providerAttemptId, request: { input: previousInput, operationId: previous, callType: generation === 1 ? "INITIAL_CALL" : "USER_RETRY" } }))) {
        throw new ConflictException("Original speech request is not eligible for a new user attempt");
      }
    }
    const input = retryOfCommandId ? { ...originalInput, retryOfCommandId, ...(retryGeneration ? { retryGeneration } : {}) } : originalInput;
    return this.attempts.execute({ accountId, commandId, operationId: commandId, operationLabelZh: "英文发音",
      task: "TTS_RENDER_V1", callType: retryOfCommandId ? "USER_RETRY" : "INITIAL_CALL", input });
  }
  read(accountId: string, objectRef: string) { return this.store.get(accountId, objectRef); }
}
