export type InitialAdminProvisioningInput = Readonly<{
  canonicalEmail: string;
  passwordHash: string;
  totpSecret: string;
  now: Date;
}>;

export type InitialAdminConfigurationStatus =
  "UNCONFIGURED" | "ENROLLMENT_PENDING" | "CONFIGURED";

export interface InitialAdminStore {
  configurationStatus(): Promise<InitialAdminConfigurationStatus>;
  loadPendingTotpSecret(canonicalEmail: string): Promise<string | null>;
  provision(
    input: InitialAdminProvisioningInput,
  ): Promise<"CREATED" | "ALREADY_CONFIGURED">;
}

export type SetupInitialAdminResult =
  | Readonly<{ status: "ALREADY_CONFIGURED" }>
  | Readonly<{ status: "PASSWORD_REJECTED" }>
  | Readonly<{
      status: "CREATED";
      email: string;
      totpSecret: string;
      provisioningUri: string;
    }>;

export class SetupInitialAdmin {
  public constructor(
    private readonly store: InitialAdminStore,
    private readonly dependencies: Readonly<{
      canonicalEmail: string;
      passwords: {
        assess(password: string): Promise<boolean>;
        hash(password: string): Promise<string>;
      };
      totp: {
        createSetup(accountLabel: string): Readonly<{
          secretBase32: string;
          provisioningUri: string;
        }>;
        describeSetup(
          accountLabel: string,
          secretBase32: string,
        ): Readonly<{
          secretBase32: string;
          provisioningUri: string;
        }>;
      };
      clock: { now(): Date };
    }>,
  ) {
    if (!dependencies.canonicalEmail.trim()) {
      throw new Error("Configured administrator email is required");
    }
  }

  public async isConfigured(): Promise<boolean> {
    return (await this.status()) === "CONFIGURED";
  }

  public status(): Promise<InitialAdminConfigurationStatus> {
    return this.store.configurationStatus();
  }

  public async resumeEnrollment(): Promise<SetupInitialAdminResult | null> {
    const canonicalEmail = this.dependencies.canonicalEmail
      .trim()
      .toLowerCase();
    const secret = await this.store.loadPendingTotpSecret(canonicalEmail);
    if (secret === null) return null;
    const setup = this.dependencies.totp.describeSetup(canonicalEmail, secret);
    return {
      status: "CREATED",
      email: canonicalEmail,
      totpSecret: setup.secretBase32,
      provisioningUri: setup.provisioningUri,
    };
  }

  public async execute(command: {
    password: string;
  }): Promise<SetupInitialAdminResult> {
    if ((await this.status()) !== "UNCONFIGURED") {
      return { status: "ALREADY_CONFIGURED" };
    }
    if (!(await this.dependencies.passwords.assess(command.password))) {
      return { status: "PASSWORD_REJECTED" };
    }
    const canonicalEmail = this.dependencies.canonicalEmail
      .trim()
      .toLowerCase();
    const passwordHash = await this.dependencies.passwords.hash(
      command.password,
    );
    const setup = this.dependencies.totp.createSetup(canonicalEmail);
    const result = await this.store.provision({
      canonicalEmail,
      passwordHash,
      totpSecret: setup.secretBase32,
      now: this.dependencies.clock.now(),
    });
    if (result !== "CREATED") return { status: "ALREADY_CONFIGURED" };
    return {
      status: "CREATED",
      email: canonicalEmail,
      totpSecret: setup.secretBase32,
      provisioningUri: setup.provisioningUri,
    };
  }
}
