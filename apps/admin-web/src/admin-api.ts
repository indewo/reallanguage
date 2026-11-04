export type AdminStatus = Readonly<{
  configured: boolean;
  enrollmentPending: boolean;
  email: string;
}>;
export type AdminSetupResult =
  | Readonly<{ status: "ALREADY_CONFIGURED" }>
  | Readonly<{ status: "PASSWORD_REJECTED" }>
  | Readonly<{
      status: "CREATED";
      email: string;
      totpSecret: string;
      provisioningUri: string;
    }>;
export type AdminLoginResult =
  | Readonly<{ status: "INVALID_CREDENTIALS" }>
  | Readonly<{
      status: "AUTHENTICATED";
      accessToken: string;
      expiresAt: string;
    }>;
export type AdminReviewDecision =
  "APPROVE" | "KEEP_BLOCKED" | "REQUIRES_DEIDENTIFICATION";
export type PendingAdminReview = Readonly<{
  reviewRequestId: string;
  sourceType: "DIARY" | "RECORDING" | "AI_SCENE";
  reasonCode: string;
  userReasonZh: string;
  createdAt: string;
}>;
export type PendingAdminReviewDetails = PendingAdminReview &
  Readonly<{ contentShape: "ARTICLE" | "DIALOGUE"; text: string }>;

type FetchLike = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export class AdminApiError extends Error {
  public constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = "AdminApiError";
  }
}

export class AdminApi {
  private readonly fetcher: FetchLike;

  public constructor(fetcher?: FetchLike) {
    this.fetcher = fetcher ?? ((input, init) => fetch(input, init));
  }

  public async status(): Promise<AdminStatus> {
    const value = await this.request("/api/v1/admin/bootstrap/status", {
      method: "GET",
    });
    if (
      !isRecord(value) ||
      typeof value.configured !== "boolean" ||
      typeof value.enrollmentPending !== "boolean" ||
      typeof value.email !== "string"
    ) {
      throw new AdminApiError(502, "管理员状态响应无效。");
    }
    return {
      configured: value.configured,
      enrollmentPending: value.enrollmentPending,
      email: value.email,
    };
  }

  public async setup(password: string): Promise<AdminSetupResult> {
    const value = await this.request("/api/v1/admin/bootstrap", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ password }),
    });
    const created = parseCreatedSetup(value);
    if (created) return created;
    if (!isRecord(value) || typeof value.status !== "string")
      throw new AdminApiError(502, "管理员初始化响应无效。");
    if (
      value.status === "ALREADY_CONFIGURED" ||
      value.status === "PASSWORD_REJECTED"
    ) {
      return { status: value.status };
    }
    throw new AdminApiError(502, "管理员初始化响应无效。");
  }

  public async resumeEnrollment(): Promise<
    Extract<AdminSetupResult, { status: "CREATED" }>
  > {
    const value = await this.request("/api/v1/admin/bootstrap/enrollment", {
      method: "GET",
    });
    const created = parseCreatedSetup(value);
    if (!created) throw new AdminApiError(502, "动态验证码绑定响应无效。");
    return created;
  }

  public async login(
    email: string,
    password: string,
    totpCode: string,
  ): Promise<AdminLoginResult> {
    const value = await this.request("/api/v1/admin/sessions", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email, password, totpCode }),
    });
    if (!isRecord(value) || typeof value.status !== "string") {
      throw new AdminApiError(502, "管理员登录响应无效。");
    }
    if (value.status === "INVALID_CREDENTIALS") {
      return { status: "INVALID_CREDENTIALS" };
    }
    if (
      value.status === "AUTHENTICATED" &&
      typeof value.accessToken === "string" &&
      typeof value.expiresAt === "string"
    ) {
      return {
        status: "AUTHENTICATED",
        accessToken: value.accessToken,
        expiresAt: value.expiresAt,
      };
    }
    throw new AdminApiError(502, "管理员登录响应无效。");
  }

  public async current(bearer: string): Promise<boolean> {
    const response = await this.fetcher("/api/v1/admin/sessions/current", {
      method: "GET",
      credentials: "omit",
      cache: "no-store",
      headers: { authorization: `Bearer ${bearer}` },
    });
    if (response.status === 401) return false;
    await parseSuccessful(response);
    return true;
  }

  public async logout(bearer: string): Promise<void> {
    await this.request("/api/v1/admin/sessions/current", {
      method: "DELETE",
      headers: { authorization: `Bearer ${bearer}` },
    });
  }

  public async listReviews(
    bearer: string,
  ): Promise<readonly PendingAdminReview[]> {
    const value = await this.authenticatedRequest(
      "/api/v1/admin/manual-reviews",
      bearer,
      { method: "GET" },
    );
    if (!isRecord(value) || !Array.isArray(value.items)) {
      throw new AdminApiError(502, "人工复核队列响应无效。");
    }
    return value.items.map(parseReview);
  }

  public async getReview(
    bearer: string,
    reviewRequestId: string,
  ): Promise<PendingAdminReviewDetails> {
    const value = await this.authenticatedRequest(
      `/api/v1/admin/manual-reviews/${encodeURIComponent(reviewRequestId)}`,
      bearer,
      { method: "GET" },
    );
    const review = parseReview(value);
    if (
      !isRecord(value) ||
      (value.contentShape !== "ARTICLE" && value.contentShape !== "DIALOGUE") ||
      typeof value.text !== "string"
    ) {
      throw new AdminApiError(502, "人工复核详情响应无效。");
    }
    return { ...review, contentShape: value.contentShape, text: value.text };
  }

  public async decideReview(
    bearer: string,
    reviewRequestId: string,
    decision: {
      decision: AdminReviewDecision;
      reasonCode: string;
      reasonZh: string;
    },
  ): Promise<{ status: "COMPLETED" }> {
    const value = await this.authenticatedRequest(
      `/api/v1/admin/manual-reviews/${encodeURIComponent(reviewRequestId)}/decisions`,
      bearer,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(decision),
      },
    );
    if (!isRecord(value) || value.status !== "COMPLETED") {
      throw new AdminApiError(502, "人工复核决定响应无效。");
    }
    return { status: "COMPLETED" };
  }

  private async request(path: string, init: RequestInit): Promise<unknown> {
    const response = await this.fetcher(path, {
      ...init,
      credentials: "omit",
      cache: "no-store",
    });
    return parseSuccessful(response);
  }

  private authenticatedRequest(
    path: string,
    bearer: string,
    init: RequestInit,
  ): Promise<unknown> {
    return this.request(path, {
      ...init,
      headers: {
        ...(init.headers ?? {}),
        authorization: `Bearer ${bearer}`,
      },
    });
  }
}

const parseSuccessful = async (response: Response): Promise<unknown> => {
  let value: unknown;
  try {
    value = await response.json();
  } catch {
    throw new AdminApiError(response.status, "服务响应无法读取。");
  }
  if (!response.ok) {
    const message =
      isRecord(value) &&
      isRecord(value.error) &&
      typeof value.error.message === "string"
        ? value.error.message
        : "服务暂时不可用。";
    throw new AdminApiError(response.status, message);
  }
  return value;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const parseCreatedSetup = (
  value: unknown,
): Extract<AdminSetupResult, { status: "CREATED" }> | null => {
  if (
    !isRecord(value) ||
    value.status !== "CREATED" ||
    typeof value.email !== "string" ||
    typeof value.totpSecret !== "string" ||
    typeof value.provisioningUri !== "string"
  )
    return null;
  return {
    status: "CREATED",
    email: value.email,
    totpSecret: value.totpSecret,
    provisioningUri: value.provisioningUri,
  };
};

const parseReview = (value: unknown): PendingAdminReview => {
  if (
    !isRecord(value) ||
    typeof value.reviewRequestId !== "string" ||
    (value.sourceType !== "DIARY" &&
      value.sourceType !== "RECORDING" &&
      value.sourceType !== "AI_SCENE") ||
    typeof value.reasonCode !== "string" ||
    typeof value.userReasonZh !== "string" ||
    typeof value.createdAt !== "string"
  ) {
    throw new AdminApiError(502, "人工复核项目响应无效。");
  }
  return {
    reviewRequestId: value.reviewRequestId,
    sourceType: value.sourceType,
    reasonCode: value.reasonCode,
    userReasonZh: value.userReasonZh,
    createdAt: value.createdAt,
  };
};
