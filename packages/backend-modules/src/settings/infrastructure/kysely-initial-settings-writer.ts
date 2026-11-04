import type { ColumnType, Generated, Kysely } from "kysely";

import type {
  InitialSettingsSnapshot,
  InitialSettingsWriter,
  CurrentSettingsReader,
} from "../application/complete-initial-settings.js";

type Timestamp = ColumnType<Date, Date | undefined, Date>;

interface UserSettingsTable {
  user_id: string;
  formality_level: number;
  slang_enabled: boolean;
  basic_vocabulary: string;
  purpose_vocabularies: string[];
  style_reference: string;
  voice_enabled: boolean;
  auto_play_enabled: boolean;
  speech_speed_level: number;
  american_english_default: Generated<boolean>;
  notification_timezone: string | null;
  reminder_time: string | null;
  row_version: Generated<number>;
  created_at: Timestamp;
  updated_at: Timestamp;
}

export interface SettingsDatabase {
  "settings.user_settings": UserSettingsTable;
}

export class KyselyInitialSettingsWriter
  implements InitialSettingsWriter, CurrentSettingsReader
{
  public constructor(private readonly database: Kysely<SettingsDatabase>) {}

  public async complete(
    accountId: string,
    settings: InitialSettingsSnapshot,
  ): Promise<number> {
    await this.database
      .insertInto("settings.user_settings")
      .values({
        user_id: accountId,
        formality_level: settings.formalityLevel,
        slang_enabled: settings.slangEnabled,
        basic_vocabulary: settings.basicVocabulary,
        purpose_vocabularies: [...settings.purposeVocabularies],
        style_reference: settings.styleReference,
        voice_enabled: settings.voiceEnabled,
        auto_play_enabled: settings.autoPlayEnabled,
        speech_speed_level: settings.speechSpeedLevel,
        notification_timezone: null,
        reminder_time: null,
      })
      .onConflict((conflict) => conflict.column("user_id").doNothing())
      .execute();

    const stored = await this.database
      .selectFrom("settings.user_settings")
      .select("row_version")
      .where("user_id", "=", accountId)
      .executeTakeFirstOrThrow();
    return Number(stored.row_version);
  }

  public async hasCompleted(accountId: string): Promise<boolean> {
    const stored = await this.database
      .selectFrom("settings.user_settings")
      .select("user_id")
      .where("user_id", "=", accountId)
      .executeTakeFirst();
    return stored !== undefined;
  }

  public async read(accountId: string): Promise<InitialSettingsSnapshot> {
    const row = await this.database
      .selectFrom("settings.user_settings")
      .select([
        "formality_level",
        "slang_enabled",
        "basic_vocabulary",
        "purpose_vocabularies",
        "style_reference",
        "voice_enabled",
        "auto_play_enabled",
        "speech_speed_level",
      ])
      .where("user_id", "=", accountId)
      .executeTakeFirstOrThrow();
    return {
      formalityLevel: row.formality_level,
      slangEnabled: row.slang_enabled,
      basicVocabulary:
        row.basic_vocabulary as InitialSettingsSnapshot["basicVocabulary"],
      purposeVocabularies:
        row.purpose_vocabularies as InitialSettingsSnapshot["purposeVocabularies"],
      styleReference: row.style_reference,
      voiceEnabled: row.voice_enabled,
      autoPlayEnabled: row.auto_play_enabled,
      speechSpeedLevel: row.speech_speed_level,
    };
  }
}
