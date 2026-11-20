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

it("returns audio payload only through the current account without a provider URL", async () => {
  const read = vi.fn().mockResolvedValue(Buffer.from("isolated-audio"));
  const controller = new SentenceAudioController({ authenticate: async token => token === "valid" ? "owner" : null }, { render: vi.fn(), read });
  const ref = "tts_00000000-0000-0000-0000-000000000000";
  await expect(controller.payload(ref, undefined)).rejects.toThrow();
  expect(read).not.toHaveBeenCalled();
  expect(await controller.payload(ref, "Bearer valid")).toEqual({ audioBase64: Buffer.from("isolated-audio").toString("base64"), audioMimeType: "audio/wav" });
  expect(read).toHaveBeenCalledWith("owner", ref);
});
