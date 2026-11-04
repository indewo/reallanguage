export type BasicVocabulary =
  | "PRIMARY_SCHOOL"
  | "JUNIOR_HIGH"
  | "SENIOR_HIGH"
  | "CET_4"
  | "CET_6"
  | "POSTGRADUATE"
  | "TOEFL"
  | "IELTS"
  | "GRE"
  | "TEM_8";

export type PurposeVocabulary = "IELTS_TOEFL" | "GRE" | "BUSINESS_ENGLISH";

export type InitialSettingsSnapshot = Readonly<{
  formalityLevel: number;
  slangEnabled: boolean;
  basicVocabulary: BasicVocabulary;
  purposeVocabularies: readonly PurposeVocabulary[];
  styleReference: string;
  voiceEnabled: boolean;
  autoPlayEnabled: boolean;
  speechSpeedLevel: number;
}>;

export interface InitialSettingsWriter {
  complete(
    accountId: string,
    settings: InitialSettingsSnapshot,
  ): Promise<number>;
}

export interface CurrentSettingsReader {
  read(accountId: string): Promise<InitialSettingsSnapshot>;
}

export class CompleteInitialSettings {
  public constructor(private readonly settings: InitialSettingsWriter) {}

  public async execute(command: {
    accountId: string;
    settings: InitialSettingsSnapshot;
  }): Promise<Readonly<{ status: "COMPLETED"; rowVersion: number }>> {
    const rowVersion = await this.settings.complete(
      command.accountId,
      command.settings,
    );
    return { status: "COMPLETED", rowVersion };
  }
}

export class GetCurrentSettings {
  public constructor(private readonly settings: CurrentSettingsReader) {}

  public execute(query: {
    accountId: string;
  }): Promise<InitialSettingsSnapshot> {
    return this.settings.read(query.accountId);
  }
}
