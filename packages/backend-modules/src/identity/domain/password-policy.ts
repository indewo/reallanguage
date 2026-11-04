export type PasswordRejectionReason = "TOO_SHORT" | "TOO_LONG" | "BLOCKED";

export type PasswordAssessment =
  | Readonly<{ accepted: false; reason: PasswordRejectionReason }>
  | Readonly<{ accepted: true; normalizedPassword: string }>;

export interface PasswordBlocklist {
  isBlocked(normalizedPassword: string): Promise<boolean>;
}

const MINIMUM_CODE_POINTS = 4;
const MAXIMUM_CODE_POINTS = 64;

export class PasswordPolicy {
  public constructor(private readonly blocklist: PasswordBlocklist) {}

  public async assess(password: string): Promise<PasswordAssessment> {
    const normalizedPassword = password.normalize("NFC");
    const codePointLength = Array.from(normalizedPassword).length;

    if (codePointLength < MINIMUM_CODE_POINTS) {
      return { accepted: false, reason: "TOO_SHORT" };
    }
    if (codePointLength > MAXIMUM_CODE_POINTS) {
      return { accepted: false, reason: "TOO_LONG" };
    }
    if (await this.blocklist.isBlocked(normalizedPassword)) {
      return { accepted: false, reason: "BLOCKED" };
    }

    return { accepted: true, normalizedPassword };
  }
}
