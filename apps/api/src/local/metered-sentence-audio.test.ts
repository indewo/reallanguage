import { createHash } from "node:crypto";
import { expect, it, vi } from "vitest";
import { MeteredSentenceAudio } from "./metered-sentence-audio.js";

it("links one stable explicit user retry to its exact failed original without silently retrying", async () => {
  const execute = vi.fn().mockResolvedValue({ audioObjectRef: "isolated" });
  const failed = vi.fn().mockResolvedValue(true);
  const app = new MeteredSentenceAudio({ execute }, { get: vi.fn() }, failed);
  const original = { commandId: "original", text: "Hello.", language: "en-US" as const, speed: 3 as const,
    requireWordTimings: false as const, purpose: "READING" as const };
  const commandId = `sentence-audio-retry-${createHash("sha256").update(original.commandId).digest("hex")}`;
  await app.render("owner", { ...original, commandId, retryOfCommandId: original.commandId });
  expect(failed.mock.calls[0]![0]).toMatchObject({ accountId: "owner", commandId: "original",
    request: { operationId: "original", callType: "INITIAL_CALL", input: { text: "Hello." } } });
  expect(execute.mock.calls[0]![0]).toMatchObject({ accountId: "owner", commandId, callType: "USER_RETRY", input: { retryOfCommandId: "original" } });
  failed.mockResolvedValue(false);
  await expect(app.render("owner", { ...original, commandId, retryOfCommandId: "original" })).rejects.toThrow();
  expect(execute).toHaveBeenCalledTimes(1);
  await expect(app.render("owner", { ...original, commandId: "arbitrary", retryOfCommandId: "original" })).rejects.toThrow();
  expect(execute).toHaveBeenCalledTimes(1);
});
