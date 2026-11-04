import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { describe, expect, it, vi } from "vitest";

import {
  AliyunRegistrationEmailSender,
  CompositeRegistrationEmailSender,
  LocalMailboxRegistrationEmailSender,
} from "./directmail-registration-email.js";

describe("AliyunRegistrationEmailSender", () => {
  it("maps a registration verification link to one transactional email", async () => {
    const singleSendMail = vi.fn().mockResolvedValue({});
    const sender = new AliyunRegistrationEmailSender({ singleSendMail });

    await sender.send({
      recipient: "learner@example.com",
      verificationUrl: "http://192.168.1.20:3000/verification?bearer=secret",
    });

    expect(singleSendMail).toHaveBeenCalledWith(
      expect.objectContaining({
        accountName: "verify@mail.reallanguage.me",
        addressType: 1,
        replyToAddress: false,
        toAddress: "learner@example.com",
        fromAlias: "RealLanguage",
        subject: "验证你的 RealLanguage 邮箱",
        htmlBody: expect.stringContaining("验证邮箱"),
      }),
    );
  });

  it("maps a password-reset link to a distinct transactional email", async () => {
    const singleSendMail = vi.fn().mockResolvedValue({});
    const sender = new AliyunRegistrationEmailSender({ singleSendMail });

    await sender.sendPasswordReset({
      recipient: "learner@example.com",
      verificationUrl:
        "reallanguage://password-reset/verify?challengeId=challenge&bearer=secret",
    });

    expect(singleSendMail).toHaveBeenCalledWith(
      expect.objectContaining({
        toAddress: "learner@example.com",
        subject: "重置你的 RealLanguage 密码",
        htmlBody: expect.stringContaining("重置密码"),
      }),
    );
  });
});

describe("LocalMailboxRegistrationEmailSender", () => {
  it("captures verification mail locally without an external send", async () => {
    const directory = await mkdtemp(resolve(tmpdir(), "reallanguage-mailbox-"));
    const mailboxPath = resolve(directory, "latest.json");
    const sender = new LocalMailboxRegistrationEmailSender(mailboxPath);

    await sender.send({
      recipient: "learner@example.com",
      verificationUrl: "http://10.0.2.2:3000/verification?bearer=secret",
    });

    expect(JSON.parse(await readFile(mailboxPath, "utf8"))).toMatchObject({
      recipient: "learner@example.com",
      verificationUrl: "http://10.0.2.2:3000/verification?bearer=secret",
    });
  });
});

describe("CompositeRegistrationEmailSender", () => {
  it("delivers both identity email purposes to every configured sender", async () => {
    const first = { send: vi.fn(), sendPasswordReset: vi.fn() };
    const second = { send: vi.fn(), sendPasswordReset: vi.fn() };
    const sender = new CompositeRegistrationEmailSender([first, second]);
    const message = {
      recipient: "learner@example.com",
      verificationUrl: "reallanguage://password-reset/verify",
    };

    await sender.send(message);
    await sender.sendPasswordReset(message);

    expect(first.send).toHaveBeenCalledWith(message);
    expect(second.send).toHaveBeenCalledWith(message);
    expect(first.sendPasswordReset).toHaveBeenCalledWith(message);
    expect(second.sendPasswordReset).toHaveBeenCalledWith(message);
  });
});
