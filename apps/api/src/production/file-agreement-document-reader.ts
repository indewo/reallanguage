import { readFile } from "node:fs/promises";
import {
  agreementDocumentSchema,
  type AgreementDocument,
  type AgreementKind,
} from "@reallanguage/contracts";

export class FileAgreementDocumentReader {
  public constructor(
    private readonly paths: Readonly<Record<AgreementKind, string>>,
  ) {}

  public async load(kind: AgreementKind): Promise<AgreementDocument> {
    const parsed = agreementDocumentSchema.parse(
      JSON.parse(await readFile(this.paths[kind], "utf8")) as unknown,
    );
    if (parsed.kind !== kind)
      throw new Error("Agreement document kind mismatch");
    return parsed;
  }
}
