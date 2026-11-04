import { type FormEvent, useEffect, useMemo, useState } from "react";

import {
  AdminApi,
  AdminApiError,
  type AdminReviewDecision,
  type AdminSetupResult,
  type PendingAdminReview,
  type PendingAdminReviewDetails,
} from "./admin-api.js";

type CreatedSetup = Extract<AdminSetupResult, { status: "CREATED" }>;
type View =
  | { kind: "LOADING" }
  | { kind: "ERROR"; message: string }
  | { kind: "SETUP"; email: string }
  | { kind: "MFA_SETUP"; setup: CreatedSetup }
  | { kind: "LOGIN"; email: string }
  | { kind: "AUTHENTICATED"; email: string; token: string; expiresAt: string };

const defaultAdminApi = new AdminApi();

export const App = ({ api = defaultAdminApi }: { api?: AdminApi }) => {
  const [view, setView] = useState<View>({ kind: "LOADING" });

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const status = await api.status();
        if (!active) return;
        if (status.enrollmentPending) {
          const setup = await api.resumeEnrollment();
          if (active) setView({ kind: "MFA_SETUP", setup });
          return;
        }
        setView(
          status.configured
            ? { kind: "LOGIN", email: status.email }
            : { kind: "SETUP", email: status.email },
        );
      } catch (error: unknown) {
        if (active) setView({ kind: "ERROR", message: errorMessage(error) });
      }
    })();
    return () => {
      active = false;
    };
  }, [api]);

  return (
    <main className="admin-shell">
      <header className="brand-row">
        <div>
          <p className="eyebrow">REAL LANGUAGE · SECURE OPERATIONS</p>
          <h1>RealLanguage Admin</h1>
        </div>
        <span className="environment-chip">本机管理</span>
      </header>
      <section className="admin-panel" aria-live="polite">
        {view.kind === "LOADING" && <Loading />}
        {view.kind === "ERROR" && (
          <ErrorState
            message={view.message}
            retry={() => window.location.reload()}
          />
        )}
        {view.kind === "SETUP" && (
          <SetupForm
            api={api}
            email={view.email}
            onCreated={(setup) => setView({ kind: "MFA_SETUP", setup })}
            onConfigured={() => setView({ kind: "LOGIN", email: view.email })}
          />
        )}
        {view.kind === "MFA_SETUP" && (
          <MfaSetup
            setup={view.setup}
            finish={() => setView({ kind: "LOGIN", email: view.setup.email })}
          />
        )}
        {view.kind === "LOGIN" && (
          <LoginForm
            api={api}
            email={view.email}
            onAuthenticated={(token, expiresAt) =>
              setView({
                kind: "AUTHENTICATED",
                email: view.email,
                token,
                expiresAt,
              })
            }
          />
        )}
        {view.kind === "AUTHENTICATED" && (
          <Authenticated
            api={api}
            email={view.email}
            token={view.token}
            expiresAt={view.expiresAt}
            onLoggedOut={() => setView({ kind: "LOGIN", email: view.email })}
          />
        )}
      </section>
    </main>
  );
};

const Loading = () => (
  <div className="status-block">
    <span className="spinner" aria-hidden="true" />
    <h2>正在连接本机服务</h2>
    <p>正在确认管理员账号状态。</p>
  </div>
);

const ErrorState = ({ message, retry }: { message: string; retry(): void }) => (
  <div className="status-block">
    <p className="state-label error">连接失败</p>
    <h2>暂时无法连接管理员服务</h2>
    <p>{message}</p>
    <button type="button" className="primary-button" onClick={retry}>
      重新连接
    </button>
  </div>
);

const SetupForm = ({
  api,
  email,
  onCreated,
  onConfigured,
}: {
  api: AdminApi;
  email: string;
  onCreated(setup: CreatedSetup): void;
  onConfigured(): void;
}) => {
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const canSubmit = password.length >= 12 && password === confirmation && !busy;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setError("");
    try {
      const result = await api.setup(password);
      setPassword("");
      setConfirmation("");
      if (result.status === "CREATED") onCreated(result);
      else if (result.status === "ALREADY_CONFIGURED") onConfigured();
      else setError("密码未达到管理员安全要求，请换一个更强的密码。");
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="form-stack" onSubmit={submit}>
      <p className="state-label">首次初始化</p>
      <h2>创建本机独立管理员</h2>
      <p className="supporting-copy">
        该账号与普通学习账号完全隔离。创建后必须绑定动态验证码。
      </p>
      <label>
        管理员邮箱
        <input value={email} readOnly autoComplete="username" />
      </label>
      <label>
        设置管理员密码
        <input
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="new-password"
          minLength={12}
          maxLength={128}
          required
        />
        <small>至少 12 个字符；不要与普通账号共用密码。</small>
      </label>
      <label>
        再次输入密码
        <input
          type="password"
          value={confirmation}
          onChange={(event) => setConfirmation(event.target.value)}
          autoComplete="new-password"
          minLength={12}
          maxLength={128}
          required
        />
      </label>
      {confirmation && confirmation !== password && (
        <p className="form-error">两次输入的密码不一致。</p>
      )}
      {error && <p className="form-error">{error}</p>}
      <button type="submit" className="primary-button" disabled={!canSubmit}>
        {busy ? "正在安全创建…" : "创建并绑定动态验证码"}
      </button>
    </form>
  );
};

const MfaSetup = ({
  setup,
  finish,
}: {
  setup: CreatedSetup;
  finish(): void;
}) => {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(setup.totpSecret);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <div className="form-stack">
      <p className="state-label success">账号已安全创建</p>
      <h2>绑定动态验证码</h2>
      <ol className="steps">
        <li>
          打开 Microsoft Authenticator、Google Authenticator 或其他 TOTP 应用。
        </li>
        <li>选择“输入设置密钥”，账号填写 {setup.email}。</li>
        <li>复制下方密钥并保存；离开此步骤后不会再次显示。</li>
      </ol>
      <div className="secret-block">
        <code>{setup.totpSecret}</code>
        <button type="button" className="text-button" onClick={copy}>
          {copied ? "已复制" : "复制密钥"}
        </button>
      </div>
      <a className="secondary-button" href={setup.provisioningUri}>
        尝试在验证器中打开
      </a>
      <button type="button" className="primary-button" onClick={finish}>
        已保存，进入登录
      </button>
    </div>
  );
};

const LoginForm = ({
  api,
  email,
  onAuthenticated,
}: {
  api: AdminApi;
  email: string;
  onAuthenticated(token: string, expiresAt: string): void;
}) => {
  const [password, setPassword] = useState("");
  const [totpCode, setTotpCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const canSubmit = password.length > 0 && /^\d{6}$/u.test(totpCode) && !busy;
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setError("");
    try {
      const result = await api.login(email, password, totpCode);
      setPassword("");
      setTotpCode("");
      if (result.status === "AUTHENTICATED")
        onAuthenticated(result.accessToken, result.expiresAt);
      else setError("邮箱、密码或动态验证码不正确。");
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="form-stack" onSubmit={submit}>
      <p className="state-label">独立身份域 · MFA</p>
      <h2>管理员登录</h2>
      <label>
        管理员邮箱
        <input value={email} readOnly autoComplete="username" />
      </label>
      <label>
        管理员密码
        <input
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          maxLength={128}
          required
        />
      </label>
      <label>
        6 位动态验证码
        <input
          value={totpCode}
          onChange={(event) =>
            setTotpCode(event.target.value.replace(/\D/gu, "").slice(0, 6))
          }
          inputMode="numeric"
          autoComplete="one-time-code"
          pattern="[0-9]{6}"
          required
        />
      </label>
      {error && <p className="form-error">{error}</p>}
      <button type="submit" className="primary-button" disabled={!canSubmit}>
        {busy ? "正在验证…" : "安全登录"}
      </button>
    </form>
  );
};

const Authenticated = ({
  api,
  email,
  token,
  expiresAt,
  onLoggedOut,
}: {
  api: AdminApi;
  email: string;
  token: string;
  expiresAt: string;
  onLoggedOut(): void;
}) => {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const expiry = useMemo(
    () =>
      new Intl.DateTimeFormat("zh-CN", { timeStyle: "medium" }).format(
        new Date(expiresAt),
      ),
    [expiresAt],
  );
  const logout = async () => {
    setBusy(true);
    setError("");
    try {
      await api.logout(token);
      onLoggedOut();
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="form-stack">
      <p className="state-label success">身份验证成功</p>
      <h2>管理员会话已建立</h2>
      <dl className="session-facts">
        <div>
          <dt>账号</dt>
          <dd>{email}</dd>
        </div>
        <div>
          <dt>会话到期</dt>
          <dd>{expiry}</dd>
        </div>
      </dl>
      <p className="supporting-copy">
        会话令牌只存在于当前页面内存中；刷新页面后需要重新登录。
      </p>
      <ReviewWorkspace api={api} token={token} />
      {error && <p className="form-error">{error}</p>}
      <button
        type="button"
        className="secondary-button"
        onClick={logout}
        disabled={busy}
      >
        {busy ? "正在退出…" : "安全退出"}
      </button>
    </div>
  );
};

const ReviewWorkspace = ({ api, token }: { api: AdminApi; token: string }) => {
  const [items, setItems] = useState<readonly PendingAdminReview[]>([]);
  const [details, setDetails] = useState<PendingAdminReviewDetails | null>(
    null,
  );
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const refresh = async () => {
    setLoading(true);
    setError("");
    try {
      setItems(await api.listReviews(token));
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
  }, [api, token]);

  const open = async (reviewRequestId: string) => {
    setLoading(true);
    setError("");
    setNotice("");
    try {
      setDetails(await api.getReview(token, reviewRequestId));
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setLoading(false);
    }
  };

  const completed = async () => {
    setDetails(null);
    setNotice("复核决定已记录，用户消息已创建。");
    await refresh();
  };

  return (
    <section className="review-workspace">
      <div className="workspace-heading">
        <div>
          <p className="state-label">人工复核</p>
          <h3>待处理队列</h3>
        </div>
        <button
          type="button"
          className="text-button"
          onClick={refresh}
          disabled={loading}
        >
          刷新队列
        </button>
      </div>
      {notice && <p className="form-notice">{notice}</p>}
      {error && <p className="form-error">{error}</p>}
      {loading && <p className="supporting-copy">正在读取最新状态…</p>}
      {!loading && details && (
        <ReviewDetails
          api={api}
          token={token}
          details={details}
          close={() => setDetails(null)}
          completed={completed}
        />
      )}
      {!loading && !details && items.length === 0 && (
        <div className="empty-state">
          <strong>当前没有待处理复核</strong>
          <span>用户主动提交后会出现在这里。</span>
        </div>
      )}
      {!loading && !details && items.length > 0 && (
        <div className="review-list">
          {items.map((item) => (
            <button
              key={item.reviewRequestId}
              type="button"
              className="review-row"
              onClick={() => open(item.reviewRequestId)}
            >
              <span className="review-kind">
                {sourceLabel(item.sourceType)}
              </span>
              <span>
                <strong>{item.userReasonZh}</strong>
                <small>
                  {formatTime(item.createdAt)} · {item.reasonCode}
                </small>
              </span>
              <span aria-hidden="true">→</span>
            </button>
          ))}
        </div>
      )}
    </section>
  );
};

const ReviewDetails = ({
  api,
  token,
  details,
  close,
  completed,
}: {
  api: AdminApi;
  token: string;
  details: PendingAdminReviewDetails;
  close(): void;
  completed(): Promise<void>;
}) => {
  const [decision, setDecision] = useState<AdminReviewDecision>("APPROVE");
  const [reasonZh, setReasonZh] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (reasonZh.trim().length < 2 || busy) return;
    setBusy(true);
    setError("");
    try {
      await api.decideReview(token, details.reviewRequestId, {
        decision,
        reasonCode: reasonCode(decision),
        reasonZh,
      });
      await completed();
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="review-details">
      <button type="button" className="text-button back-button" onClick={close}>
        ← 返回队列
      </button>
      <div className="review-meta">
        <span>{sourceLabel(details.sourceType)}</span>
        <span>{details.contentShape === "ARTICLE" ? "文章" : "对话"}</span>
        <span>{details.reasonCode}</span>
      </div>
      <h4>{details.userReasonZh}</h4>
      <div className="content-frame">
        <p>{details.text}</p>
      </div>
      <p className="privacy-note">
        仅显示解决这张复核单所必需的内容；本次查看已经写入审计记录。
      </p>
      <form className="decision-form" onSubmit={submit}>
        <fieldset>
          <legend>复核决定</legend>
          {decisionOptions.map((option) => (
            <label key={option.value} className="decision-option">
              <input
                type="radio"
                name="decision"
                value={option.value}
                checked={decision === option.value}
                onChange={() => setDecision(option.value)}
              />
              <span>
                <strong>{option.label}</strong>
                <small>{option.help}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <label>
          决定理由
          <textarea
            value={reasonZh}
            onChange={(event) => setReasonZh(event.target.value)}
            maxLength={500}
            rows={4}
            required
          />
        </label>
        {error && <p className="form-error">{error}</p>}
        <button
          type="submit"
          className="primary-button"
          disabled={reasonZh.trim().length < 2 || busy}
        >
          {busy ? "正在记录…" : "确认并通知用户"}
        </button>
      </form>
    </div>
  );
};

const decisionOptions: readonly {
  value: AdminReviewDecision;
  label: string;
  help: string;
}[] = [
  { value: "APPROVE", label: "通过", help: "允许该内容继续后续形成流程。" },
  {
    value: "KEEP_BLOCKED",
    label: "维持拦截",
    help: "确认当前安全拦截仍然有效。",
  },
  {
    value: "REQUIRES_DEIDENTIFICATION",
    label: "要求进一步脱敏",
    help: "返回安全处理并提示仍需隐藏细节。",
  },
];

const reasonCode = (decision: AdminReviewDecision): string => {
  if (decision === "APPROVE") return "CONTEXT_ACCEPTABLE";
  if (decision === "KEEP_BLOCKED") return "POLICY_RISK_CONFIRMED";
  return "MORE_REDACTION_REQUIRED";
};

const sourceLabel = (sourceType: PendingAdminReview["sourceType"]): string => {
  if (sourceType === "DIARY") return "日记";
  if (sourceType === "RECORDING") return "录音转写";
  return "AI 场景";
};

const formatTime = (value: string): string =>
  new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));

const errorMessage = (error: unknown): string =>
  error instanceof AdminApiError || error instanceof Error
    ? error.message
    : "发生未知错误，请稍后重试。";
