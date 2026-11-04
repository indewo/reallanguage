import { describe, expect, it } from "vitest";

import { TotpService } from "./totp.js";

describe("TotpService", () => {
  it("matches the RFC 6238 SHA-1 test vector", () => {
    const service = new TotpService({ digits: 8, allowedWindow: 0 });

    expect(
      service.verify(
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
        "94287082",
        new Date(59_000),
      ),
    ).toBe(1);
  });

  it("accepts the adjacent time window but rejects reuse of an accepted counter", () => {
    const service = new TotpService({ digits: 8, allowedWindow: 1 });
    const secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    expect(service.verify(secret, "94287082", new Date(89_000))).toBe(1);
    expect(service.verify(secret, "94287082", new Date(89_000), 1)).toBeNull();
  });

  it("rejects malformed and incorrect codes", () => {
    const service = new TotpService({ digits: 8, allowedWindow: 0 });
    const secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    expect(service.verify(secret, "not-code", new Date(59_000))).toBeNull();
    expect(service.verify(secret, "00000000", new Date(59_000))).toBeNull();
  });

  it("creates a standards-compatible provisioning URI without leaking elsewhere", () => {
    const setup = new TotpService().createSetup("indewo@outlook.com");
    const parsed = new URL(setup.provisioningUri);

    expect(parsed.protocol).toBe("otpauth:");
    expect(parsed.hostname).toBe("totp");
    expect(parsed.searchParams.get("secret")).toBe(setup.secretBase32);
    expect(parsed.searchParams.get("issuer")).toBe("RealLanguage Admin");
    expect(setup.secretBase32).toMatch(/^[A-Z2-7]{32}$/u);
  });

  it("rebuilds a provisioning URI from the same pending secret", () => {
    const service = new TotpService();
    const secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    const setup = service.describeSetup("indewo@outlook.com", secret);

    expect(setup.secretBase32).toBe(secret);
    expect(new URL(setup.provisioningUri).searchParams.get("secret")).toBe(
      secret,
    );
  });
});
