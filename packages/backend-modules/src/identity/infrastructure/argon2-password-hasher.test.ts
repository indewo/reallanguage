import { describe, expect, it } from "vitest";

import { Argon2PasswordHasher } from "./argon2-password-hasher.js";

describe("Argon2PasswordHasher", () => {
  const hasher = new Argon2PasswordHasher();

  it("uses the confirmed Argon2id minimum parameters", async () => {
    const encoded = await hasher.hash("a sufficiently long password");
    const [algorithm, version, parameters] = encoded.slice(1).split("$");

    expect(algorithm).toBe("argon2id");
    expect(version).toBe("v=19");
    expect(new Set(parameters?.split(","))).toEqual(
      new Set(["m=19456", "t=2", "p=1"]),
    );
  });

  it("uses an independent salt for every password", async () => {
    const first = await hasher.hash("a sufficiently long password");
    const second = await hasher.hash("a sufficiently long password");

    expect(first).not.toBe(second);
  });

  it("verifies the complete NFC-normalized password", async () => {
    const decomposed = `${"e\u0301".repeat(8)} password`;
    const composed = decomposed.normalize("NFC");
    const encoded = await hasher.hash(decomposed);

    await expect(hasher.verify(encoded, composed)).resolves.toBe(true);
    await expect(hasher.verify(encoded, `${composed}!`)).resolves.toBe(false);
  });
});
