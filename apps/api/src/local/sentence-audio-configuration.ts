import { resolve } from "node:path";
import { QwenSentenceSynthesis } from "./qwen-sentence-synthesis.js";
import { SentenceAudioStore } from "./sentence-audio-store.js";
import { SentenceSynthesisMeteredGateway } from "./sentence-synthesis-metered-gateway.js";

/** Explicit opt-in: credentials and verified policy are never inferred from text-AI defaults. */
export function configuredSentenceAudio(projectRoot: string, env: NodeJS.ProcessEnv = process.env) {
  if (env.REAL_LANGUAGE_SENTENCE_AUDIO_ENABLED !== "true") return undefined;
  const integer = (name: string) => {
    const value = env[name];
    if (!value || !/^[1-9][0-9]*$/u.test(value) || !Number.isSafeInteger(Number(value))) throw new Error(`Missing valid ${name}`);
    return Number(value);
  };
  const keyHex = env.REAL_LANGUAGE_SENTENCE_AUDIO_KEY_HEX;
  const apiKey = env.REAL_LANGUAGE_SENTENCE_AUDIO_API_KEY?.trim();
  const directory = env.REAL_LANGUAGE_SENTENCE_AUDIO_DIRECTORY?.trim();
  if (!keyHex || !/^[0-9a-f]{64}$/iu.test(keyHex) || !apiKey || !directory) throw new Error("Incomplete sentence audio secrets or directory");
  const store = new SentenceAudioStore(resolve(projectRoot, directory), Buffer.from(keyHex, "hex"));
  const gateway = new SentenceSynthesisMeteredGateway(new QwenSentenceSynthesis(apiKey), store, {
    costMicroCnyPerCharacter: integer("REAL_LANGUAGE_SENTENCE_AUDIO_COST_MICRO_CNY_PER_CHARACTER"),
    maximumChargeFen: integer("REAL_LANGUAGE_SENTENCE_AUDIO_MAXIMUM_CHARGE_FEN"),
    retentionMs: integer("REAL_LANGUAGE_SENTENCE_AUDIO_RETENTION_MS"),
  });
  return { gateway, store };
}
