import { describe, expect, it } from "vitest";
import { AesGcmContentSafetyPayloadProtector } from "./aes-gcm-content-safety-payload-protector.js";

describe("AesGcmContentSafetyPayloadProtector", () => {
  it("round trips only with the configured versioned key", async () => {
    const cipher = new AesGcmContentSafetyPayloadProtector({
      key: Uint8Array.from({ length: 32 }, () => 7),
      version: 2,
    });
    const protectedPayload =
      await cipher.protect("只允许在安全处理中解密的内容");
    await expect(cipher.unprotect(protectedPayload)).resolves.toBe(
      "只允许在安全处理中解密的内容",
    );
    await expect(
      cipher.unprotect({ ...protectedPayload, keyVersion: 3 }),
    ).rejects.toThrow("Unsupported");
  });
});
