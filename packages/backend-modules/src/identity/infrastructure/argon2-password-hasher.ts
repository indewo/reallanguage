import { argon2id, hash, verify } from "argon2";

const ARGON2ID_MINIMUMS = {
  type: argon2id,
  memoryCost: 19 * 1024,
  timeCost: 2,
  parallelism: 1,
} as const;

export class Argon2PasswordHasher {
  public async hash(password: string): Promise<string> {
    return hash(password.normalize("NFC"), ARGON2ID_MINIMUMS);
  }

  public async verify(encodedHash: string, password: string): Promise<boolean> {
    return verify(encodedHash, password.normalize("NFC"));
  }
}
