import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  ForbiddenException,
  Get,
  Header,
  Headers,
  HttpCode,
  Inject,
  Ip,
  NotFoundException,
  Post,
  UnauthorizedException,
} from "@nestjs/common";

export const SETUP_INITIAL_ADMIN_APPLICATION = Symbol(
  "SETUP_INITIAL_ADMIN_APPLICATION",
);
export const CREATE_ADMIN_SESSION_APPLICATION = Symbol(
  "CREATE_ADMIN_SESSION_APPLICATION",
);
export const ADMIN_SESSION_AUTHENTICATOR = Symbol(
  "ADMIN_SESSION_AUTHENTICATOR",
);
export const REVOKE_ADMIN_SESSION_APPLICATION = Symbol(
  "REVOKE_ADMIN_SESSION_APPLICATION",
);
export const ADMIN_BOOTSTRAP_ACCESS = Symbol("ADMIN_BOOTSTRAP_ACCESS");
export const ADMIN_ACCOUNT_EMAIL = Symbol("ADMIN_ACCOUNT_EMAIL");

interface SetupApplication {
  status(): Promise<"UNCONFIGURED" | "ENROLLMENT_PENDING" | "CONFIGURED">;
  resumeEnrollment(): Promise<unknown | null>;
  execute(command: { password: string }): Promise<unknown>;
}

interface SessionApplication {
  execute(command: {
    email: string;
    password: string;
    totpCode: string;
  }): Promise<unknown>;
}

interface BearerApplication {
  execute(
    bearer: string,
  ): Promise<
    | { status: "AUTHENTICATED"; adminId: string }
    | { status: "INVALID_OR_EXPIRED" }
    | { status: "REVOKED" }
  >;
}

@Controller("admin")
export class AdminSessionController {
  public constructor(
    @Inject(SETUP_INITIAL_ADMIN_APPLICATION)
    private readonly setup: SetupApplication,
    @Inject(CREATE_ADMIN_SESSION_APPLICATION)
    private readonly createSession: SessionApplication,
    @Inject(ADMIN_SESSION_AUTHENTICATOR)
    private readonly authenticate: BearerApplication,
    @Inject(REVOKE_ADMIN_SESSION_APPLICATION)
    private readonly revoke: BearerApplication,
    @Inject(ADMIN_BOOTSTRAP_ACCESS)
    private readonly bootstrapAccess: { allows(ip: string): boolean },
    @Inject(ADMIN_ACCOUNT_EMAIL)
    private readonly accountEmail: string,
  ) {}

  @Get("bootstrap/status")
  @Header("Cache-Control", "no-store")
  public async status() {
    const status = await this.setup.status();
    return {
      configured: status === "CONFIGURED",
      enrollmentPending: status === "ENROLLMENT_PENDING",
      email: this.accountEmail,
    };
  }

  @Get("bootstrap/enrollment")
  @Header("Cache-Control", "no-store")
  public async enrollment(@Ip() ip: string) {
    if (!this.bootstrapAccess.allows(ip)) throw new ForbiddenException();
    const result = await this.setup.resumeEnrollment();
    if (result === null) throw new NotFoundException();
    return result;
  }

  @Post("bootstrap")
  @Header("Cache-Control", "no-store")
  public async bootstrap(@Body() body: unknown, @Ip() ip: string) {
    if (!this.bootstrapAccess.allows(ip)) throw new ForbiddenException();
    if (!isRecord(body) || !isBoundedString(body.password, 1, 256)) {
      throw new BadRequestException();
    }
    return this.setup.execute({ password: body.password });
  }

  @Post("sessions")
  @HttpCode(200)
  @Header("Cache-Control", "no-store")
  public async login(@Body() body: unknown) {
    if (
      !isRecord(body) ||
      !isEmail(body.email) ||
      !isBoundedString(body.password, 1, 256) ||
      typeof body.totpCode !== "string" ||
      !/^\d{6}$/u.test(body.totpCode)
    ) {
      throw new BadRequestException();
    }
    return this.createSession.execute({
      email: body.email,
      password: body.password,
      totpCode: body.totpCode,
    });
  }

  @Get("sessions/current")
  @Header("Cache-Control", "no-store")
  public async current(@Headers("authorization") authorization?: string) {
    const bearer = parseBearer(authorization);
    if (!bearer) throw new UnauthorizedException();
    const result = await this.authenticate.execute(bearer);
    if (result.status !== "AUTHENTICATED") throw new UnauthorizedException();
    return { status: "AUTHENTICATED" } as const;
  }

  @Delete("sessions/current")
  @HttpCode(200)
  @Header("Cache-Control", "no-store")
  public async logout(@Headers("authorization") authorization?: string) {
    const bearer = parseBearer(authorization);
    if (!bearer) throw new UnauthorizedException();
    const result = await this.revoke.execute(bearer);
    if (result.status !== "REVOKED") throw new UnauthorizedException();
    return result;
  }
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const isBoundedString = (
  value: unknown,
  minimum: number,
  maximum: number,
): value is string =>
  typeof value === "string" &&
  value.length >= minimum &&
  value.length <= maximum;

const isEmail = (value: unknown): value is string =>
  isBoundedString(value, 3, 254) && /^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(value);

const parseBearer = (value?: string): string | null => {
  if (!value?.startsWith("Bearer ")) return null;
  const bearer = value.slice("Bearer ".length);
  return /^[A-Za-z0-9_-]{8,256}$/u.test(bearer) ? bearer : null;
};
