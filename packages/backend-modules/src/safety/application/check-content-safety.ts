export type SafetySourceType = "DIARY" | "RECORDING" | "AI_SCENE";
export type SafetyContentShape = "ARTICLE" | "DIALOGUE";

export type CheckContentSafetyCommand = Readonly<{
  accountId: string;
  text: string;
  sourceType: SafetySourceType;
  contentShape: SafetyContentShape;
  commandId: string;
}>;

export type ContentSafetyGatewayResult =
  | Readonly<{ decision: "PASS" }>
  | Readonly<{ decision: "BLOCK"; safetyCaseId: string }>;

export interface ContentSafetyGateway {
  check(
    command: CheckContentSafetyCommand,
  ): Promise<ContentSafetyGatewayResult>;
}

export type CheckContentSafetyResult =
  | Readonly<{ status: "PASSED" }>
  | Readonly<{ status: "BLOCKED"; safetyCaseId: string }>;

export class CheckContentSafety {
  public constructor(private readonly gateway: ContentSafetyGateway) {}

  public async execute(
    command: CheckContentSafetyCommand,
  ): Promise<CheckContentSafetyResult> {
    const result = await this.gateway.check(command);
    return result.decision === "PASS"
      ? { status: "PASSED" }
      : { status: "BLOCKED", safetyCaseId: result.safetyCaseId };
  }
}
