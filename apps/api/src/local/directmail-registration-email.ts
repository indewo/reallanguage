import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

import type {
  AesGcmAccountSensitiveFieldsProtector,
  AesGcmPasswordResetDeliverySecretProtector,
  AesGcmRegistrationPayloadProtector,
} from "@reallanguage/backend-modules/identity";
import type { Kysely } from "kysely";

import type { ProductionDatabase } from "../production/production-api.module.js";

export type RegistrationVerificationEmail = Readonly<{
  recipient: string;
  verificationUrl: string;
}>;

export interface RegistrationEmailSender {
  send(message: RegistrationVerificationEmail): Promise<void>;
  sendPasswordReset(message: RegistrationVerificationEmail): Promise<void>;
}

export class CompositeRegistrationEmailSender implements RegistrationEmailSender {
  public constructor(
    private readonly senders: readonly RegistrationEmailSender[],
  ) {}

  public async send(message: RegistrationVerificationEmail): Promise<void> {
    await Promise.all(this.senders.map((sender) => sender.send(message)));
  }

  public async sendPasswordReset(
    message: RegistrationVerificationEmail,
  ): Promise<void> {
    await Promise.all(
      this.senders.map((sender) => sender.sendPasswordReset(message)),
    );
  }
}

export class LocalMailboxRegistrationEmailSender implements RegistrationEmailSender {
  public constructor(private readonly mailboxPath: string) {}

  public async send(message: RegistrationVerificationEmail): Promise<void> {
    await this.capture(message, "EMAIL_VERIFICATION");
  }

  public async sendPasswordReset(
    message: RegistrationVerificationEmail,
  ): Promise<void> {
    await this.capture(message, "PASSWORD_RESET");
  }

  private async capture(
    message: RegistrationVerificationEmail,
    purpose: "EMAIL_VERIFICATION" | "PASSWORD_RESET",
  ): Promise<void> {
    await mkdir(dirname(this.mailboxPath), { recursive: true });
    await writeFile(
      this.mailboxPath,
      JSON.stringify({
        ...message,
        purpose,
        capturedAt: new Date().toISOString(),
      }),
      { encoding: "utf8", mode: 0o600 },
    );
  }
}

type DirectMailRequest = Readonly<{
  accountName: string;
  addressType: number;
  replyToAddress: boolean;
  toAddress: string;
  fromAlias: string;
  subject: string;
  htmlBody: string;
}>;

type DirectMailClient = Readonly<{
  singleSendMail(request: unknown): Promise<unknown>;
}>;

export class AliyunRegistrationEmailSender implements RegistrationEmailSender {
  public constructor(
    private readonly client: DirectMailClient,
    private readonly requestFactory: (request: DirectMailRequest) => unknown = (
      request,
    ) => request,
  ) {}

  public async send(message: RegistrationVerificationEmail): Promise<void> {
    await this.client.singleSendMail(
      this.requestFactory({
        accountName: "verify@mail.reallanguage.me",
        addressType: 1,
        replyToAddress: false,
        toAddress: message.recipient,
        fromAlias: "RealLanguage",
        subject: "验证你的 RealLanguage 邮箱",
        htmlBody: verificationEmailHtml(message.verificationUrl),
      }),
    );
  }

  public async sendPasswordReset(
    message: RegistrationVerificationEmail,
  ): Promise<void> {
    await this.client.singleSendMail(
      this.requestFactory({
        accountName: "verify@mail.reallanguage.me",
        addressType: 1,
        replyToAddress: false,
        toAddress: message.recipient,
        fromAlias: "RealLanguage",
        subject: "重置你的 RealLanguage 密码",
        htmlBody: passwordResetEmailHtml(message.verificationUrl),
      }),
    );
  }
}

export const createLocalAliyunRegistrationEmailSender = async (
  projectRoot: string,
): Promise<RegistrationEmailSender> => {
  const environment = parseEnvironment(
    await readFile(resolve(projectRoot, ".local-data/directmail.env"), "utf8"),
  );
  const accessKeyId = required(environment, "ALIYUN_DIRECTMAIL_ACCESS_KEY_ID");
  const accessKeySecret = required(
    environment,
    "ALIYUN_DIRECTMAIL_ACCESS_KEY_SECRET",
  );
  const imported = await import("@alicloud/dm20151123");
  const Client = findConstructor<
    Readonly<Record<string, unknown>>,
    DirectMailClient
  >(imported, "default");
  const SingleSendMailRequest = findConstructor<DirectMailRequest, unknown>(
    imported,
    "SingleSendMailRequest",
  );
  const client = new Client({
    accessKeyId,
    accessKeySecret,
    endpoint: "dm.aliyuncs.com",
    regionId: "cn-hangzhou",
  });
  return new AliyunRegistrationEmailSender(
    client,
    (request) => new SingleSendMailRequest(request),
  );
};

export class RegistrationEmailOutboxDispatcher {
  private dispatching = false;

  public constructor(
    private readonly database: Kysely<ProductionDatabase>,
    private readonly payloadProtector: AesGcmRegistrationPayloadProtector,
    private readonly sender: RegistrationEmailSender,
    private readonly publicApiBaseUrl: string,
    private readonly now: () => Date = () => new Date(),
  ) {}

  public async dispatchPending(): Promise<number> {
    if (this.dispatching) return 0;
    this.dispatching = true;
    try {
      const events = await this.database
        .selectFrom("platform.outbox_events as event")
        .innerJoin(
          "identity.verification_challenges as challenge",
          "challenge.challenge_id",
          "event.aggregate_id",
        )
        .select([
          "event.event_id as eventId",
          "event.aggregate_id as challengeId",
          "event.attempt_count as attemptCount",
          "challenge.status as challengeStatus",
          "challenge.expires_at as expiresAt",
          "challenge.pending_registration_ciphertext as ciphertext",
          "challenge.payload_algorithm as algorithm",
          "challenge.payload_key_version as keyVersion",
        ])
        .where("event.event_type", "=", "identity.email-verification-requested")
        .where("event.status", "=", "PENDING")
        .where("event.available_at", "<=", this.now())
        .orderBy("event.created_at", "asc")
        .limit(10)
        .execute();

      let delivered = 0;
      for (const event of events) {
        if (
          event.challengeStatus !== "ACTIVE" ||
          new Date(event.expiresAt).getTime() <= this.now().getTime() ||
          event.ciphertext === null ||
          event.algorithm === null ||
          event.keyVersion === null
        ) {
          await this.markPublished(event.eventId);
          continue;
        }
        try {
          const payload = await this.payloadProtector.unprotect({
            ciphertext: event.ciphertext,
            algorithm: event.algorithm,
            keyVersion: event.keyVersion,
          });
          const verificationUrl = `${this.publicApiBaseUrl.replace(/\/$/u, "")}/api/v1/auth/registrations/${encodeURIComponent(event.challengeId)}/verification/confirm?bearer=${encodeURIComponent(payload.verificationBearer)}`;
          await this.sender.send({
            recipient: payload.email,
            verificationUrl,
          });
          await this.markPublished(event.eventId);
          delivered += 1;
        } catch {
          await this.database
            .updateTable("platform.outbox_events")
            .set({
              attempt_count: event.attemptCount + 1,
              available_at: new Date(
                this.now().getTime() +
                  retryDelayMilliseconds(event.attemptCount),
              ),
              row_version: event.attemptCount + 1,
              updated_at: this.now(),
            })
            .where("event_id", "=", event.eventId)
            .where("status", "=", "PENDING")
            .execute();
        }
      }
      return delivered;
    } finally {
      this.dispatching = false;
    }
  }

  private async markPublished(eventId: string): Promise<void> {
    const publishedAt = this.now();
    await this.database
      .updateTable("platform.outbox_events")
      .set({
        status: "PUBLISHED",
        published_at: publishedAt,
        updated_at: publishedAt,
      })
      .where("event_id", "=", eventId)
      .where("status", "=", "PENDING")
      .execute();
  }
}

export class PasswordResetEmailOutboxDispatcher {
  private dispatching = false;

  public constructor(
    private readonly database: Kysely<ProductionDatabase>,
    private readonly deliverySecrets: AesGcmPasswordResetDeliverySecretProtector,
    private readonly accountFields: AesGcmAccountSensitiveFieldsProtector,
    private readonly sender: RegistrationEmailSender,
    private readonly now: () => Date = () => new Date(),
  ) {}

  public async dispatchPending(): Promise<number> {
    if (this.dispatching) return 0;
    this.dispatching = true;
    try {
      const events = await this.database
        .selectFrom("platform.outbox_events as event")
        .innerJoin(
          "identity.password_reset_requests as request",
          "request.challenge_id",
          "event.aggregate_id",
        )
        .innerJoin(
          "identity.verification_challenges as challenge",
          "challenge.challenge_id",
          "event.aggregate_id",
        )
        .innerJoin(
          "identity.auth_identities as identity",
          "identity.account_id",
          "request.account_id",
        )
        .select([
          "event.event_id as eventId",
          "event.aggregate_id as challengeId",
          "event.attempt_count as attemptCount",
          "challenge.status as challengeStatus",
          "challenge.expires_at as expiresAt",
          "request.delivery_secret_ciphertext as secretCiphertext",
          "request.delivery_secret_algorithm as secretAlgorithm",
          "request.delivery_secret_key_version as secretKeyVersion",
          "identity.email_ciphertext as emailCiphertext",
          "identity.email_algorithm as emailAlgorithm",
          "identity.email_key_version as emailKeyVersion",
        ])
        .where("event.event_type", "=", "identity.password-reset-requested")
        .where("event.status", "=", "PENDING")
        .where("event.available_at", "<=", this.now())
        .orderBy("event.created_at", "asc")
        .limit(10)
        .execute();

      let delivered = 0;
      for (const event of events) {
        if (
          event.challengeStatus !== "ACTIVE" ||
          new Date(event.expiresAt).getTime() <= this.now().getTime() ||
          event.secretCiphertext === null ||
          event.secretAlgorithm === null ||
          event.secretKeyVersion === null
        ) {
          await this.markPublished(event.eventId);
          continue;
        }
        try {
          const [bearer, recipient] = await Promise.all([
            this.deliverySecrets.unprotect({
              ciphertext: event.secretCiphertext,
              algorithm: event.secretAlgorithm,
              keyVersion: event.secretKeyVersion,
            }),
            this.accountFields.unprotectEmail({
              ciphertext: event.emailCiphertext,
              algorithm: event.emailAlgorithm,
              keyVersion: event.emailKeyVersion,
            }),
          ]);
          const verificationUrl = `reallanguage://password-reset/verify?challengeId=${encodeURIComponent(event.challengeId)}&bearer=${encodeURIComponent(bearer)}`;
          await this.sender.sendPasswordReset({ recipient, verificationUrl });
          await this.markPublished(event.eventId);
          delivered += 1;
        } catch {
          await this.database
            .updateTable("platform.outbox_events")
            .set({
              attempt_count: event.attemptCount + 1,
              available_at: new Date(
                this.now().getTime() +
                  retryDelayMilliseconds(event.attemptCount),
              ),
              row_version: event.attemptCount + 1,
              updated_at: this.now(),
            })
            .where("event_id", "=", event.eventId)
            .where("status", "=", "PENDING")
            .execute();
        }
      }
      return delivered;
    } finally {
      this.dispatching = false;
    }
  }

  private async markPublished(eventId: string): Promise<void> {
    const publishedAt = this.now();
    await this.database
      .updateTable("platform.outbox_events")
      .set({
        status: "PUBLISHED",
        published_at: publishedAt,
        updated_at: publishedAt,
      })
      .where("event_id", "=", eventId)
      .where("status", "=", "PENDING")
      .execute();
  }
}

export class LocalRegistrationEmailPump {
  private timer: ReturnType<typeof setInterval> | undefined;

  public constructor(
    private readonly dispatcher: RegistrationEmailOutboxDispatcher,
    private readonly passwordResetDispatcher?: PasswordResetEmailOutboxDispatcher,
  ) {}

  public onApplicationBootstrap(): void {
    void this.dispatchAll();
    this.timer = setInterval(() => {
      void this.dispatchAll();
    }, 250);
    this.timer.unref();
  }

  public onApplicationShutdown(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }

  private async dispatchAll(): Promise<void> {
    await this.dispatcher.dispatchPending();
    await this.passwordResetDispatcher?.dispatchPending();
  }
}

const verificationEmailHtml = (verificationUrl: string): string =>
  `<div style="margin:0;padding:32px;background:#f4f1eb;color:#40443f;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;line-height:1.7"><div style="max-width:560px;margin:auto;padding:32px;border-radius:22px;background:#e6ded2"><h2 style="margin-top:0">验证你的 RealLanguage 邮箱</h2><p>请点击下方按钮完成邮箱验证。验证链接将在 30 分钟后失效。</p><p style="margin:28px 0"><a href="${escapeHtml(verificationUrl)}" style="display:inline-block;padding:12px 24px;border-radius:12px;background:#77887a;color:#fff;text-decoration:none;font-weight:600">验证邮箱</a></p><p style="font-size:13px;color:#6b7069">如果不是你发起的注册，请忽略这封邮件。</p></div></div>`;

const passwordResetEmailHtml = (verificationUrl: string): string =>
  `<div style="margin:0;padding:32px;background:#f4f1eb;color:#40443f;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;line-height:1.7"><div style="max-width:560px;margin:auto;padding:32px;border-radius:22px;background:#e6ded2"><h2 style="margin-top:0">重置你的 RealLanguage 密码</h2><p>请在安装 RealLanguage 的 Android 设备上点击下方按钮。链接将在 30 分钟后失效。</p><p style="margin:28px 0"><a href="${escapeHtml(verificationUrl)}" style="display:inline-block;padding:12px 24px;border-radius:12px;background:#77887a;color:#fff;text-decoration:none;font-weight:600">重置密码</a></p><p style="font-size:13px;color:#6b7069">如果不是你发起的密码重置，请忽略这封邮件，不要转发此链接。</p></div></div>`;

const escapeHtml = (value: string): string =>
  value
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");

const retryDelayMilliseconds = (attemptCount: number): number =>
  Math.min(5 * 60_000, 5_000 * 2 ** Math.min(attemptCount, 6));

const parseEnvironment = (contents: string): Record<string, string> =>
  Object.fromEntries(
    contents
      .split(/\r?\n/u)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith("#"))
      .map((line) => {
        const separator = line.indexOf("=");
        return [line.slice(0, separator), line.slice(separator + 1)];
      }),
  );

const required = (values: Record<string, string>, name: string): string => {
  const value = values[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
};

type UnknownConstructor<TInput, TOutput> = new (input: TInput) => TOutput;

const findConstructor = <TInput, TOutput>(
  module: unknown,
  exportName: string,
): UnknownConstructor<TInput, TOutput> => {
  let current: unknown = module;
  for (let depth = 0; depth < 4; depth += 1) {
    if (typeof current === "function" && exportName === "default") {
      return current as UnknownConstructor<TInput, TOutput>;
    }
    if (isRecord(current)) {
      const candidate = current[exportName];
      if (typeof candidate === "function") {
        return candidate as UnknownConstructor<TInput, TOutput>;
      }
      current = current.default;
      continue;
    }
    break;
  }
  throw new Error(
    `Alibaba DirectMail SDK export is unavailable: ${exportName}`,
  );
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
