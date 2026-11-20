import { expect, it } from "vitest";
import { configuredSentenceAudio } from "./sentence-audio-configuration.js";

it("stays disabled by default and refuses partial production configuration", () => {
  expect(configuredSentenceAudio(".", {})).toBeUndefined();
  expect(() => configuredSentenceAudio(".", { REAL_LANGUAGE_SENTENCE_AUDIO_ENABLED: "true" })).toThrow();
  const env = { REAL_LANGUAGE_SENTENCE_AUDIO_ENABLED: "true", REAL_LANGUAGE_SENTENCE_AUDIO_KEY_HEX: "01".repeat(32),
    REAL_LANGUAGE_SENTENCE_AUDIO_API_KEY: "isolated-key", REAL_LANGUAGE_SENTENCE_AUDIO_DIRECTORY: ".local-data/isolated-audio",
    REAL_LANGUAGE_SENTENCE_AUDIO_COST_MICRO_CNY_PER_CHARACTER: "80", REAL_LANGUAGE_SENTENCE_AUDIO_MAXIMUM_CHARGE_FEN: "10",
    REAL_LANGUAGE_SENTENCE_AUDIO_RETENTION_MS: "3600000" };
  expect(configuredSentenceAudio(".", env)).toBeDefined();
  expect(() => configuredSentenceAudio(".", { ...env, REAL_LANGUAGE_SENTENCE_AUDIO_COST_MICRO_CNY_PER_CHARACTER: "0" })).toThrow();
  expect(() => configuredSentenceAudio(".", { ...env, REAL_LANGUAGE_SENTENCE_AUDIO_RETENTION_MS: "999999999" })).toThrow();
});
