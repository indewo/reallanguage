import { expect, it, vi } from "vitest";
import type { MeteredProviderCommand } from "@reallanguage/backend-modules/billing";
import { SentenceSynthesisMeteredGateway } from "./sentence-synthesis-metered-gateway.js";

it("binds attempts to owner and command, charges measured usage and stores only for that owner", async () => {
  const artifact = { downloadUrl: "https://test.aliyuncs.com/audio", expiresAtSeconds: 9999999999, providerRequestId: "isolated", characters: 12 };
  const synthesize = vi.fn().mockResolvedValue(artifact);
  const put = vi.fn().mockResolvedValue({ audioObjectRef: "isolated-ref", durationMs: 1234, expiresAtMs: 2000 });
  const receive = vi.fn().mockResolvedValue({ bytes: Buffer.from("isolated audio"), durationMs: 1234 });
  const gateway = new SentenceSynthesisMeteredGateway({ synthesize }, { put },
    { costMicroCnyPerCharacter: 2, maximumChargeFen: 1, retentionMs: 1000 }, receive);
  const command: MeteredProviderCommand = { accountId: "owner", commandId: "same-command", operationId: "operation", operationLabelZh: "发音",
    callType: "INITIAL_CALL", task: "TTS_RENDER_V1", input: { text: "Hello there.", language: "en-US", speed: 3, requireWordTimings: false } };
  const quote = await gateway.quote(command);
  expect(await gateway.quote(command)).toEqual(quote);
  expect((await gateway.quote({ ...command, accountId: "other-owner" })).providerAttemptId).not.toBe(quote.providerAttemptId);
  await expect(gateway.execute({ ...command, providerAttemptId: "wrong" })).rejects.toThrow();
  expect(synthesize).not.toHaveBeenCalled();
  const result = await gateway.execute({ ...command, providerAttemptId: quote.providerAttemptId });
  expect(result.receipt.providerCostMicroCny).toBe(24);
  expect(result.output).not.toHaveProperty("downloadUrl");
  expect(put.mock.calls[0]![0]).toBe("owner");
  await expect(gateway.quote({ ...command, input: { ...(command.input as object), requireWordTimings: true } })).rejects.toThrow();
  expect(synthesize).toHaveBeenCalledTimes(1);
});
