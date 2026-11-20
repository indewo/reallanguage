import { expect, it, vi } from "vitest";
import { SentenceAudioController } from "./sentence-audio.controller.js";

it("requires authentication before synthesis or audio lookup and binds lookup to its authenticated owner", async () => {
  const render = vi.fn();
  const read = vi.fn().mockResolvedValue(null);
  const controller = new SentenceAudioController({ authenticate: async token => token === "valid" ? "owner" : null }, { render, read });
  await expect(controller.render({}, undefined)).rejects.toThrow();
  await expect(controller.read("tts_00000000-0000-0000-0000-000000000000", "Bearer invalid")).rejects.toThrow();
  expect(render).not.toHaveBeenCalled(); expect(read).not.toHaveBeenCalled();
  await expect(controller.read("tts_00000000-0000-0000-0000-000000000000", "Bearer valid")).rejects.toThrow();
  expect(read).toHaveBeenCalledWith("owner", "tts_00000000-0000-0000-0000-000000000000");
  await expect(controller.render({ text: "Hello", accountId: "other" }, "Bearer valid")).rejects.toThrow();
  expect(render).not.toHaveBeenCalled();
});
