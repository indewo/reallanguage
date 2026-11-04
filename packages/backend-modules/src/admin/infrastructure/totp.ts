import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";

export type TotpConfiguration = Readonly<{
  periodSeconds?: number;
  digits?: number;
  allowedWindow?: number;
}>;

export type TotpSetup = Readonly<{
  secretBase32: string;
  provisioningUri: string;
}>;

export class TotpService {
  private readonly periodSeconds: number;
  private readonly digits: number;
  private readonly allowedWindow: number;

  public constructor(configuration: TotpConfiguration = {}) {
    this.periodSeconds = configuration.periodSeconds ?? 30;
    this.digits = configuration.digits ?? 6;
    this.allowedWindow = configuration.allowedWindow ?? 1;
    if (
      !Number.isInteger(this.periodSeconds) ||
      this.periodSeconds < 15 ||
      !Number.isInteger(this.digits) ||
      this.digits < 6 ||
      this.digits > 8 ||
      !Number.isInteger(this.allowedWindow) ||
      this.allowedWindow < 0 ||
      this.allowedWindow > 2
    ) {
      throw new Error("Invalid TOTP configuration");
    }
  }

  public createSetup(
    accountLabel: string,
    issuer = "RealLanguage Admin",
  ): TotpSetup {
    return this.describeSetup(
      accountLabel,
      encodeBase32(randomBytes(20)),
      issuer,
    );
  }

  public describeSetup(
    accountLabel: string,
    secretBase32: string,
    issuer = "RealLanguage Admin",
  ): TotpSetup {
    const label = accountLabel.trim();
    if (!label || !issuer.trim()) throw new Error("Invalid TOTP account label");
    decodeBase32(secretBase32);
    const path = `${encodeURIComponent(issuer)}:${encodeURIComponent(label)}`;
    const query = new URLSearchParams({
      secret: secretBase32,
      issuer,
      algorithm: "SHA1",
      digits: String(this.digits),
      period: String(this.periodSeconds),
    });
    return {
      secretBase32,
      provisioningUri: `otpauth://totp/${path}?${query.toString()}`,
    };
  }

  public verify(
    secretBase32: string,
    candidate: string,
    now: Date,
    lastAcceptedCounter: number | null = null,
  ): number | null {
    if (!new RegExp(`^\\d{${this.digits}}$`, "u").test(candidate)) return null;
    const currentCounter = Math.floor(
      now.getTime() / 1_000 / this.periodSeconds,
    );
    for (
      let counter = currentCounter - this.allowedWindow;
      counter <= currentCounter + this.allowedWindow;
      counter += 1
    ) {
      if (
        counter < 0 ||
        (lastAcceptedCounter !== null && counter <= lastAcceptedCounter)
      ) {
        continue;
      }
      const expected = hotp(decodeBase32(secretBase32), counter, this.digits);
      if (timingSafeEqual(Buffer.from(expected), Buffer.from(candidate))) {
        return counter;
      }
    }
    return null;
  }
}

const hotp = (secret: Uint8Array, counter: number, digits: number): string => {
  const counterBytes = Buffer.alloc(8);
  counterBytes.writeBigUInt64BE(BigInt(counter));
  const digest = createHmac("sha1", secret).update(counterBytes).digest();
  const offset = (digest.at(-1) ?? 0) & 0x0f;
  const binary =
    (((digest[offset] ?? 0) & 0x7f) << 24) |
    ((digest[offset + 1] ?? 0) << 16) |
    ((digest[offset + 2] ?? 0) << 8) |
    (digest[offset + 3] ?? 0);
  return String(binary % 10 ** digits).padStart(digits, "0");
};

const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

const encodeBase32 = (value: Uint8Array): string => {
  let bits = "";
  for (const byte of value) bits += byte.toString(2).padStart(8, "0");
  let result = "";
  for (let index = 0; index < bits.length; index += 5) {
    const chunk = bits.slice(index, index + 5).padEnd(5, "0");
    result += alphabet[Number.parseInt(chunk, 2)];
  }
  return result;
};

const decodeBase32 = (value: string): Uint8Array => {
  const normalized = value
    .replaceAll("=", "")
    .replaceAll(" ", "")
    .toUpperCase();
  if (!normalized || /[^A-Z2-7]/u.test(normalized)) {
    throw new Error("Invalid TOTP secret");
  }
  let bits = "";
  for (const character of normalized) {
    bits += alphabet.indexOf(character).toString(2).padStart(5, "0");
  }
  const bytes: number[] = [];
  for (let index = 0; index + 8 <= bits.length; index += 8) {
    bytes.push(Number.parseInt(bits.slice(index, index + 8), 2));
  }
  return Uint8Array.from(bytes);
};
