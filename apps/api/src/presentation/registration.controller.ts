import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Header,
  Headers,
  HttpCode,
  Inject,
  Param,
  Post,
  Query,
} from "@nestjs/common";
import type {
  GetRegistrationVerificationStatus,
  ResendRegistrationVerification,
  StartRegistration,
  VerifyRegistration,
} from "@reallanguage/backend-modules/identity";
import {
  apiErrorResponseSchema,
  registrationIdSchema,
  registrationRequestSchema,
  registrationResultSchema,
  registrationVerificationEmailResendResultSchema,
  registrationVerificationRequestSchema,
  registrationVerificationResultSchema,
  registrationVerificationStatusResultSchema,
  type RegistrationResult,
  type RegistrationVerificationEmailResendResult,
  type RegistrationVerificationResult,
  type RegistrationVerificationStatusResult,
} from "@reallanguage/contracts";

export const START_REGISTRATION_APPLICATION = Symbol(
  "START_REGISTRATION_APPLICATION",
);
export const VERIFY_REGISTRATION_APPLICATION = Symbol(
  "VERIFY_REGISTRATION_APPLICATION",
);
export const GET_REGISTRATION_VERIFICATION_STATUS_APPLICATION = Symbol(
  "GET_REGISTRATION_VERIFICATION_STATUS_APPLICATION",
);
export const RESEND_REGISTRATION_VERIFICATION_APPLICATION = Symbol(
  "RESEND_REGISTRATION_VERIFICATION_APPLICATION",
);

type StartRegistrationApplication = Pick<StartRegistration, "execute">;
type VerifyRegistrationApplication = Pick<VerifyRegistration, "execute">;
type GetRegistrationVerificationStatusApplication = Pick<
  GetRegistrationVerificationStatus,
  "execute"
>;
type ResendRegistrationVerificationApplication = Pick<
  ResendRegistrationVerification,
  "execute"
>;

@Controller("auth/registrations")
export class RegistrationController {
  public constructor(
    @Inject(START_REGISTRATION_APPLICATION)
    private readonly startRegistration: StartRegistrationApplication,
    @Inject(VERIFY_REGISTRATION_APPLICATION)
    private readonly verifyRegistration: VerifyRegistrationApplication,
    @Inject(GET_REGISTRATION_VERIFICATION_STATUS_APPLICATION)
    private readonly getRegistrationVerificationStatus: GetRegistrationVerificationStatusApplication,
    @Inject(RESEND_REGISTRATION_VERIFICATION_APPLICATION)
    private readonly resendRegistrationVerification: ResendRegistrationVerificationApplication,
  ) {}

  @Get(":registrationId/verification-status")
  public async verificationStatus(
    @Param("registrationId") registrationId: string,
    @Headers("x-request-id") requestId: string,
  ): Promise<RegistrationVerificationStatusResult> {
    const parsedId = registrationIdSchema.safeParse(registrationId);
    if (!parsedId.success) throw validationError(requestId);
    return registrationVerificationStatusResultSchema.parse(
      await this.getRegistrationVerificationStatus.execute({
        registrationId: parsedId.data,
      }),
    );
  }

  @Post()
  @HttpCode(200)
  public async start(
    @Body() body: unknown,
    @Headers("x-request-id") requestId: string,
  ): Promise<RegistrationResult> {
    const parsed = registrationRequestSchema.safeParse(body);
    if (!parsed.success) throw validationError(requestId);
    const [year, month] = parsed.data.birthYearMonth.split("-").map(Number);
    if (year === undefined || month === undefined) {
      throw validationError(requestId);
    }

    const result = await this.startRegistration.execute({
      requestId,
      email: parsed.data.email,
      password: parsed.data.password,
      birthYearMonth: { year, month },
      acceptedTerms: parsed.data.acceptedTerms,
      acceptedPrivacyPolicy: parsed.data.acceptedPrivacyPolicy,
    });
    return registrationResultSchema.parse(result);
  }

  @Post(":registrationId/verification")
  @HttpCode(200)
  public async verify(
    @Param("registrationId") registrationId: string,
    @Body() body: unknown,
    @Headers("x-request-id") requestId: string,
  ): Promise<RegistrationVerificationResult> {
    const parsedId = registrationIdSchema.safeParse(registrationId);
    const parsedBody = registrationVerificationRequestSchema.safeParse(body);
    if (!parsedId.success || !parsedBody.success) {
      throw validationError(requestId);
    }

    const result = await this.verifyRegistration.execute({
      registrationId: parsedId.data,
      bearer: parsedBody.data.bearer,
    });
    return registrationVerificationResultSchema.parse(result);
  }

  @Get(":registrationId/verification/confirm")
  @Header("content-type", "text/html; charset=utf-8")
  @Header(
    "content-security-policy",
    "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'",
  )
  public verificationConfirmationPage(
    @Param("registrationId") registrationId: string,
    @Query("bearer") bearer: string,
    @Headers("x-request-id") requestId: string,
  ): string {
    const parsedId = registrationIdSchema.safeParse(registrationId);
    const parsedBearer = registrationVerificationRequestSchema.safeParse({
      bearer,
    });
    if (!parsedId.success || !parsedBearer.success) {
      throw validationError(requestId);
    }
    return confirmationPage(parsedId.data, parsedBearer.data.bearer);
  }

  @Post(":registrationId/verification-email")
  @HttpCode(200)
  public async resendVerificationEmail(
    @Param("registrationId") registrationId: string,
    @Headers("x-request-id") requestId: string,
  ): Promise<RegistrationVerificationEmailResendResult> {
    const parsedId = registrationIdSchema.safeParse(registrationId);
    if (!parsedId.success) throw validationError(requestId);

    const result = await this.resendRegistrationVerification.execute({
      registrationId: parsedId.data,
      requestId,
    });
    return registrationVerificationEmailResendResultSchema.parse(result);
  }
}

const validationError = (requestId: string): BadRequestException =>
  new BadRequestException(
    apiErrorResponseSchema.parse({
      error: {
        code: "VALIDATION_FAILED",
        message: "请求内容无效。",
        retryable: false,
        requestId,
        details: {},
      },
    }),
  );

const confirmationPage = (registrationId: string, bearer: string): string => {
  const verificationPath = `/api/v1/auth/registrations/${registrationId}/verification`;
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>验证 RealLanguage 邮箱</title>
<style>body{margin:0;background:#f4f1eb;color:#40443f;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}.card{box-sizing:border-box;width:min(92vw,520px);margin:12vh auto;padding:36px;border-radius:24px;background:#e6ded2;box-shadow:0 16px 45px #5e62581f}h1{margin:0 0 14px;font-size:28px}p{line-height:1.7;color:#666b64}button{width:100%;margin-top:18px;padding:14px;border:0;border-radius:14px;background:#77887a;color:white;font-size:17px;font-weight:650}button:disabled{opacity:.55}.ok{color:#526b58}.error{color:#9a5f58}</style>
</head><body><main class="card"><h1>验证你的邮箱</h1><p id="message">点击下方按钮完成 RealLanguage 账号验证。</p><button id="confirm">确认邮箱</button></main>
<script>const button=document.getElementById("confirm"),message=document.getElementById("message");const fail=text=>{message.className="error";message.textContent=text;button.disabled=false};button.onclick=async()=>{button.disabled=true;message.textContent="正在验证…";let response;try{response=await fetch(${JSON.stringify(verificationPath)},{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({bearer:${JSON.stringify(bearer)}})})}catch{fail("无法连接 RealLanguage 服务。请确认手机和电脑使用同一网络后重试。");return}let result;try{result=await response.json()}catch{fail("服务响应无法读取，请稍后重试。");return}if(!response.ok){fail(result&&result.error&&result.error.message?result.error.message:"服务暂时出现问题，请稍后重试。");return}if(result.status==="ACCOUNT_CREATED"){message.className="ok";message.textContent="邮箱验证成功。返回 RealLanguage 后，应用会自动继续初始设置。";button.remove();return}if(result.status==="INVALID_OR_EXPIRED"){message.textContent="此链接已使用或已过期。如果你刚完成验证，请返回 RealLanguage；否则请在应用中重新发送。";button.remove();return}fail("服务暂时出现问题，请稍后重试。")};</script>
</body></html>`;
};
