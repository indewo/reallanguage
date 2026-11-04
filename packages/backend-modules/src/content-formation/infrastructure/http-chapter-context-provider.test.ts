import { describe, expect, it, vi } from "vitest";
import { HttpChapterContextProvider } from "./http-chapter-context-provider.js";
describe("HttpChapterContextProvider", () => {
  it("uses the versioned task and stable command", async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        chapters: [{ chapterId: "c1", unitIds: ["u1"] }],
      }),
    });
    await new HttpChapterContextProvider(
      { endpoint: "https://provider.test/tasks", apiKey: "secret" },
      fetcher,
    ).select({
      accountId: "a",
      units: [{ unitId: "u1", order: 0, textZh: "第一句" }],
      selectedUnitIds: ["u1"],
      commandId: "chapter-command",
    });
    expect(fetcher).toHaveBeenCalledWith(
      "https://provider.test/tasks",
      expect.objectContaining({
        headers: expect.objectContaining({
          "Idempotency-Key": "chapter-command",
        }),
        body: expect.stringContaining("CHAPTER_CONTEXT_SELECTION_V1"),
      }),
    );
  });
});
