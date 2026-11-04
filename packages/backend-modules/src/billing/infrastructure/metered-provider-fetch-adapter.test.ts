import { describe, expect, it, vi } from "vitest";

import { MeteredProviderFetchAdapter } from "./metered-provider-fetch-adapter.js";

describe("MeteredProviderFetchAdapter", () => {
  it("converts existing provider requests into metered attempts", async () => {
    const execute = vi.fn().mockResolvedValue({ units: [] });
    const adapter = new MeteredProviderFetchAdapter(
      { execute },
      { SEMANTIC_UNIT_SPLIT_V1: "拆分语义单元" },
    );
    const response = await adapter.fetch(
      "https://legacy-endpoint.example.test/tasks",
      {
        method: "POST",
        headers: {
          "Idempotency-Key": "command-1",
          "X-RealLanguage-Account-Id": "account-1",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          task: "SEMANTIC_UNIT_SPLIT_V1",
          input: { text: "你好" },
        }),
      },
    );
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ units: [] });
    expect(execute).toHaveBeenCalledWith({
      accountId: "account-1",
      operationId: "command-1",
      operationLabelZh: "拆分语义单元",
      commandId: "command-1",
      task: "SEMANTIC_UNIT_SPLIT_V1",
      callType: "INITIAL_CALL",
      input: { text: "你好" },
    });
  });

  it("fails closed when required billing metadata or task labels are absent", async () => {
    const adapter = new MeteredProviderFetchAdapter({ execute: vi.fn() }, {});
    await expect(
      adapter.fetch("https://legacy-endpoint.example.test/tasks", {
        method: "POST",
        headers: {},
        body: JSON.stringify({ task: "UNKNOWN", input: {} }),
      }),
    ).rejects.toThrow("Metered provider request metadata is incomplete");
  });
});
