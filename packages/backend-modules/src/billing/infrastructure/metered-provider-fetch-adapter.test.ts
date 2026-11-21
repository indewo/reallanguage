import { describe, expect, it, vi } from "vitest";

import { MeteredProviderFetchAdapter } from "./metered-provider-fetch-adapter.js";

describe("MeteredProviderFetchAdapter", () => {
  it("keeps the business operation while separately metering an automatic repair attempt", async () => {
    const execute = vi.fn().mockResolvedValue({});
    const adapter = new MeteredProviderFetchAdapter(
      { execute },
      { SCENE_DIALOGUE_TURN_V1: "生成场景对话" },
    );
    await adapter.fetch("https://example.test", {
      headers: {
        "Idempotency-Key": "root:repair",
        "X-RealLanguage-Account-Id": "account",
        "X-RealLanguage-Operation-Id": "root",
        "X-RealLanguage-Call-Type": "AUTOMATIC_RETRY",
      },
      body: JSON.stringify({
        task: "SCENE_DIALOGUE_TURN_V1",
        input: { fidelityIssues: [] },
      }),
    });
    expect(execute).toHaveBeenCalledWith(
      expect.objectContaining({
        operationId: "root",
        commandId: "root:repair",
        callType: "AUTOMATIC_RETRY",
      }),
    );
  });
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
