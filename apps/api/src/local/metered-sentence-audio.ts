import type { RunMeteredProviderAttempt } from "@reallanguage/backend-modules/billing";
import type { SentenceAudioApplication, SentenceAudioRequest } from "../presentation/sentence-audio.controller.js";
import type { SentenceAudioStore } from "./sentence-audio-store.js";

export class MeteredSentenceAudio implements SentenceAudioApplication {
  constructor(private readonly attempts: Pick<RunMeteredProviderAttempt, "execute">, private readonly store: Pick<SentenceAudioStore, "get">) {}
  async render(accountId: string, request: SentenceAudioRequest) {
    const { commandId, ...input } = request;
    return this.attempts.execute({ accountId, commandId, operationId: commandId, operationLabelZh: "英文发音",
      task: "TTS_RENDER_V1", callType: "INITIAL_CALL", input });
  }
  read(accountId: string, objectRef: string) { return this.store.get(accountId, objectRef); }
}
