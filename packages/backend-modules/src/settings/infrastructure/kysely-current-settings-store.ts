import type { Kysely, Selectable } from "kysely";
import type {
  CurrentSettingsStore,
  UpdateSettingsResult,
  VersionedSettings,
} from "../application/update-current-settings.js";
import type { SettingsDatabase } from "./kysely-initial-settings-writer.js";

export class KyselyCurrentSettingsStore implements CurrentSettingsStore {
  public constructor(private readonly database: Kysely<SettingsDatabase>) {}
  public async readVersioned(
    accountId: string,
  ): Promise<VersionedSettings | null> {
    const row = await this.database
      .selectFrom("settings.user_settings")
      .selectAll()
      .where("user_id", "=", accountId)
      .executeTakeFirst();
    return row ? snapshot(row) : null;
  }
  public async update(
    command: Parameters<CurrentSettingsStore["update"]>[0],
  ): Promise<UpdateSettingsResult> {
    return this.database.transaction().execute(async (transaction) => {
      const row = await transaction
        .selectFrom("settings.user_settings")
        .selectAll()
        .where("user_id", "=", command.accountId)
        .forUpdate()
        .executeTakeFirst();
      if (!row) return { status: "NOT_FOUND" };
      const current = snapshot(row);
      // This PUT sets a state, it does not charge or generate. An identical persisted
      // state is a read-confirmed no-op, including a retry after a lost response.
      if (
        command.expectedRowVersion <= current.rowVersion &&
        sameSettings(current.settings, command.settings)
      ) {
        return { status: "SAVED", rowVersion: current.rowVersion };
      }
      if (command.expectedRowVersion !== current.rowVersion) {
        return { status: "CONFLICT", rowVersion: current.rowVersion };
      }
      const settings = command.settings;
      const result = await transaction
        .updateTable("settings.user_settings")
        .set({
          formality_level: settings.formalityLevel,
          slang_enabled: settings.slangEnabled,
          basic_vocabulary: settings.basicVocabulary,
          purpose_vocabularies: [...settings.purposeVocabularies],
          style_reference: settings.styleReference,
          voice_enabled: settings.voiceEnabled,
          auto_play_enabled: settings.autoPlayEnabled,
          speech_speed_level: settings.speechSpeedLevel,
          row_version: current.rowVersion + 1,
          updated_at: new Date(),
        })
        .where("user_id", "=", command.accountId)
        .where("row_version", "=", command.expectedRowVersion)
        .returning("row_version")
        .executeTakeFirst();
      if (!result)
        throw new Error("Locked settings version changed unexpectedly");
      return { status: "SAVED", rowVersion: Number(result.row_version) };
    });
  }
}

const snapshot = (
  row: Selectable<SettingsDatabase["settings.user_settings"]>,
): VersionedSettings => ({
  rowVersion: Number(row.row_version),
  settings: {
    formalityLevel: row.formality_level,
    slangEnabled: row.slang_enabled,
    basicVocabulary:
      row.basic_vocabulary as VersionedSettings["settings"]["basicVocabulary"],
    purposeVocabularies:
      row.purpose_vocabularies as VersionedSettings["settings"]["purposeVocabularies"],
    styleReference: row.style_reference,
    voiceEnabled: row.voice_enabled,
    autoPlayEnabled: row.auto_play_enabled,
    speechSpeedLevel: row.speech_speed_level,
  },
});
const sameSettings = (
  left: VersionedSettings["settings"],
  right: VersionedSettings["settings"],
): boolean =>
  left.formalityLevel === right.formalityLevel &&
  left.slangEnabled === right.slangEnabled &&
  left.basicVocabulary === right.basicVocabulary &&
  left.styleReference === right.styleReference &&
  left.voiceEnabled === right.voiceEnabled &&
  left.autoPlayEnabled === right.autoPlayEnabled &&
  left.speechSpeedLevel === right.speechSpeedLevel &&
  [...left.purposeVocabularies].sort().join("|") ===
    [...right.purposeVocabularies].sort().join("|");
