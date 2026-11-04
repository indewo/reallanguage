import {
  BadRequestException,
  Controller,
  Get,
  Inject,
  Param,
} from "@nestjs/common";
import {
  agreementDocumentSchema,
  type AgreementDocument,
  type AgreementKind,
} from "@reallanguage/contracts";

export const AGREEMENT_DOCUMENT_READER = Symbol("AGREEMENT_DOCUMENT_READER");

export interface AgreementDocumentReader {
  load(kind: AgreementKind): Promise<AgreementDocument>;
}

@Controller("legal/documents")
export class AgreementDocumentController {
  public constructor(
    @Inject(AGREEMENT_DOCUMENT_READER)
    private readonly documents: AgreementDocumentReader,
  ) {}

  @Get(":kind")
  public async get(@Param("kind") kind: string): Promise<AgreementDocument> {
    const resolved = routeKind(kind);
    if (!resolved) throw new BadRequestException();
    return agreementDocumentSchema.parse(await this.documents.load(resolved));
  }
}

const routeKind = (value: string): AgreementKind | null => {
  if (value === "user-agreement") return "USER_AGREEMENT";
  if (value === "privacy-policy") return "PRIVACY_POLICY";
  return null;
};
