import type { Kysely } from "kysely";
import type { ContentGenerationSettingsReader } from "../application/generate-english-candidates.js";

interface ContentGenerationSettingsTable {
  user_id: string;
  formality_level: number;
  slang_enabled: boolean;
  basic_vocabulary: string;
  purpose_vocabularies: string[];
  style_reference: string;
}
export interface ContentGenerationSettingsDatabase {
  "settings.user_settings": ContentGenerationSettingsTable;
}
export class KyselyContentGenerationSettingsReader implements ContentGenerationSettingsReader {
  public constructor(
    private readonly database: Kysely<ContentGenerationSettingsDatabase>,
  ) {}
  public async read(accountId: string) {
    const row = await this.database
      .selectFrom("settings.user_settings")
      .select([
        "formality_level",
        "slang_enabled",
        "basic_vocabulary",
        "purpose_vocabularies",
        "style_reference",
      ])
      .where("user_id", "=", accountId)
      .executeTakeFirstOrThrow();
    return {
      formalityLevel: row.formality_level,
      slangEnabled: row.slang_enabled,
      basicVocabulary: row.basic_vocabulary,
      purposeVocabularies: row.purpose_vocabularies,
      styleReference: row.style_reference,
    };
  }
}
