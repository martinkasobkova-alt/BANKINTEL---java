import React, { useState, useRef } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Eye, EyeOff, Hexagon, X } from "lucide-react";
import { useAuth } from "@/contexts/AuthContext";
import TurnstileField from "@/components/auth/TurnstileField";
import api from "@/lib/api";
import { PASSWORD_POLICY_HINT, validatePasswordClient } from "@/lib/passwordPolicy";
import { toast } from "sonner";

const IS_DEV = process.env.NODE_ENV === "development";
const SUBSCRIBE_INFO_URL = "https://www.bankovnictvionline.cz";
const TURNSTILE_SITE_KEY = (process.env.REACT_APP_TURNSTILE_SITE_KEY || "").trim();

export default function LoginModal() {
  const { t } = useTranslation();
  const { loginOpen, closeLogin, login, register } = useAuth();
  const [tab, setTab] = useState("login");

  const [email, setEmail] = useState(IS_DEV ? "admin@bankintel.local" : "");
  const [password, setPassword] = useState("");
  const [showPwd, setShowPwd] = useState(false);
  const [loginLoading, setLoginLoading] = useState(false);
  const [loginErr, setLoginErr] = useState("");

  const [regCompany, setRegCompany] = useState("");
  const [regName, setRegName] = useState("");
  const [regEmail, setRegEmail] = useState("");
  const [regPhone, setRegPhone] = useState("");
  const [regPassword, setRegPassword] = useState("");
  const [regPassword2, setRegPassword2] = useState("");
  const [regCode, setRegCode] = useState("");
  const [showRegPwd, setShowRegPwd] = useState(false);
  const [turnstileToken, setTurnstileToken] = useState("");
  const [regLoading, setRegLoading] = useState(false);
  const [regErr, setRegErr] = useState("");
  const [registrationSuccess, setRegistrationSuccess] = useState(null);
  const [resendTurnstileToken, setResendTurnstileToken] = useState("");
  const [resendKey, setResendKey] = useState(0);
  const [resendLoading, setResendLoading] = useState(false);
  const [loginResendTurnstile, setLoginResendTurnstile] = useState("");
  const [loginResendKey, setLoginResendKey] = useState(0);
  const [loginResendLoading, setLoginResendLoading] = useState(false);
  const [loginErrorCode, setLoginErrorCode] = useState(null);

  const registerSubmitLockRef = useRef(false);

  if (!loginOpen) return null;

  const switchTab = (t) => {
    setTab(t);
    setLoginErr("");
    setRegErr("");
    setTurnstileToken("");
    setRegPassword2("");
    setRegistrationSuccess(null);
    setResendTurnstileToken("");
    setResendKey((k) => k + 1);
  };

  const submitLogin = async (e) => {
    e.preventDefault();
    // Nativní HTML5 validace (`type="email"` + `required`) odesílání jen tiše zablokuje —
    // submitLogin se vůbec nespustil, loginErr zůstal prázdný a uživatel klikl na
    // „Přihlásit se" bez jakékoli reakce. Formulář má proto noValidate a validujeme sami,
    // aby chyba vždy skončila v `loginErr` a byla vidět.
    setLoginErrorCode(null);
    const emailValue = String(email || "").trim();
    if (!emailValue) {
      setLoginErr(t("auth.emailRequired"));
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(emailValue)) {
      setLoginErr(t("auth.emailInvalid"));
      return;
    }
    if (!password) {
      setLoginErr(t("auth.passwordRequired"));
      return;
    }
    setLoginLoading(true);
    setLoginErr("");
    const r = await login(emailValue, password);
    if (!r.ok) {
      setLoginErr(r.error);
      setLoginErrorCode(r.code || null);
    }
    setLoginLoading(false);
  };

  const submitLoginResend = async (e) => {
    e.preventDefault();
    setLoginResendLoading(true);
    if (TURNSTILE_SITE_KEY && !String(loginResendTurnstile || "").trim()) {
      toast.error(t("auth.turnstileRequired"));
      setLoginResendLoading(false);
      return;
    }
    try {
      await api.post("/auth/resend-verification", {
        email: email.trim(),
        turnstile_token: loginResendTurnstile || undefined,
        captcha_token: loginResendTurnstile || undefined,
      });
      toast.success(t("auth.resendSuccess"));
      setLoginResendTurnstile("");
      setLoginResendKey((k) => k + 1);
    } catch {
      toast.error(t("auth.resendFailed"));
    } finally {
      setLoginResendLoading(false);
    }
  };

  const submitRegisterFree = async (e) => {
    e.preventDefault();
    if (registerSubmitLockRef.current || regLoading) return;
    registerSubmitLockRef.current = true;
    try {
      setRegErr("");
      if (TURNSTILE_SITE_KEY && !String(turnstileToken || "").trim()) {
        setRegErr(t("auth.turnstileRequiredRegister"));
        return;
      }
      const pv = validatePasswordClient(regPassword);
      if (!pv.ok) {
        setRegErr(pv.message);
        return;
      }
      if (regPassword !== regPassword2) {
        setRegErr(t("auth.passwordMismatch"));
        return;
      }
      setRegLoading(true);
      const r = await register({
        company: regCompany.trim(),
        name: regName.trim(),
        email: regEmail.trim(),
        phone: regPhone.trim(),
        password: regPassword,
        turnstile_token: turnstileToken || undefined,
        captcha_token: turnstileToken || undefined,
      });
      if (!r.ok) setRegErr(r.error);
      else if (r.pendingVerification) {
        setRegistrationSuccess({ message: r.message, email: r.email || regEmail.trim() });
        setResendKey((k) => k + 1);
      }
    } finally {
      registerSubmitLockRef.current = false;
      setRegLoading(false);
    }
  };

  const submitRegisterSubscriber = async (e) => {
    e.preventDefault();
    if (registerSubmitLockRef.current || regLoading) return;
    registerSubmitLockRef.current = true;
    try {
      setRegErr("");
      if (TURNSTILE_SITE_KEY && !String(turnstileToken || "").trim()) {
        setRegErr(t("auth.turnstileRequiredRegister"));
        return;
      }
      const pv = validatePasswordClient(regPassword);
      if (!pv.ok) {
        setRegErr(pv.message);
        return;
      }
      if (regPassword !== regPassword2) {
        setRegErr(t("auth.passwordMismatch"));
        return;
      }
      setRegLoading(true);
      const r = await register({
        company: regCompany.trim(),
        name: regName.trim(),
        email: regEmail.trim(),
        phone: regPhone.trim(),
        password: regPassword,
        registration_code: regCode.trim(),
        turnstile_token: turnstileToken || undefined,
        captcha_token: turnstileToken || undefined,
      });
      if (!r.ok) setRegErr(r.error);
      else if (r.pendingVerification) {
        setRegistrationSuccess({ message: r.message, email: r.email || regEmail.trim() });
        setResendKey((k) => k + 1);
      }
    } finally {
      registerSubmitLockRef.current = false;
      setRegLoading(false);
    }
  };

  const submitResendAfterRegister = async (e) => {
    e.preventDefault();
    if (TURNSTILE_SITE_KEY && !String(resendTurnstileToken || "").trim()) {
      toast.error(t("auth.turnstileRequiredRegister"));
      return;
    }
    setResendLoading(true);
    try {
      await api.post("/auth/resend-verification", {
        email: (registrationSuccess?.email || "").trim(),
        turnstile_token: resendTurnstileToken || undefined,
        captcha_token: resendTurnstileToken || undefined,
      });
      toast.success(t("auth.resendSuccess"));
      setResendTurnstileToken("");
      setResendKey((k) => k + 1);
    } catch {
      toast.error(t("auth.resendFailed"));
    } finally {
      setResendLoading(false);
    }
  };

  return (
    <div
      data-testid="login-modal"
      className="fixed inset-0 z-50 backdrop-blur-sm grid place-items-center p-4"
      style={{ background: "hsl(218 55% 20% / 0.45)" }}
      onClick={closeLogin}
    >
      <div
        className="soft-card w-full max-w-lg max-h-[min(92vh,720px)] flex flex-col"
        style={{ boxShadow: "0 24px 48px hsl(218 55% 30% / 0.25)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-5 sm:p-6 border-b border-border/50 flex items-start justify-between shrink-0">
          <div className="flex items-center gap-3 min-w-0">
            <div
              className="h-9 w-9 rounded-xl grid place-items-center text-white shrink-0"
              style={{
                background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
              }}
            >
              <Hexagon className="h-4 w-4" strokeWidth={1.5} />
            </div>
            <div className="min-w-0">
              <h2
                className="font-serif text-[22px] sm:text-[26px] leading-tight"
                data-testid="login-modal-title"
              >
                {t("auth.title")}
              </h2>
            </div>
          </div>
          <button
            data-testid="login-close"
            type="button"
            onClick={closeLogin}
            className="text-slate-400 hover:text-slate-800 p-2 rounded-md transition-colors min-h-[44px] min-w-[44px] grid place-items-center"
            aria-label={t("common.close")}
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div
          role="tablist"
          className="flex border-b border-border/50 px-2 sm:px-4 gap-0 flex-wrap shrink-0 bg-slate-50/50"
        >
          {[
            { id: "login", labelKey: "auth.tabLogin" },
            { id: "register-free", labelKey: "auth.tabRegisterFree" },
            { id: "register-sub", labelKey: "auth.tabRegisterSub" },
          ].map(({ id, labelKey }) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={tab === id}
              data-testid={`auth-tab-${id}`}
              onClick={() => switchTab(id)}
              className={`min-h-[48px] px-3 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors ${
                tab === id
                  ? "border-[hsl(var(--primary))] text-[hsl(var(--primary-deep))]"
                  : "border-transparent text-slate-500 hover:text-slate-800"
              }`}
            >
              {t(labelKey)}
            </button>
          ))}
        </div>

        <div className="overflow-y-auto flex-1 min-h-0">
          {tab === "login" ? (
            <form onSubmit={submitLogin} noValidate className="p-5 sm:p-6 space-y-4">
              <p className="text-sm text-slate-600 leading-relaxed">{t("auth.loginIntro")}</p>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">
                  {t("common.email")}
                </label>
                <input
                  data-testid="login-email-input"
                  type="email"
                  autoComplete="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                  className="mt-2 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm font-mono focus:outline-none focus:border-primary"
                  style={{ transition: "border-color .15s" }}
                />
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">
                  {t("common.password")}
                </label>
                <div className="relative mt-2">
                  <input
                    data-testid="login-password-input"
                    type={showPwd ? "text" : "password"}
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                    className="w-full min-h-[44px] border border-border/70 rounded-md px-3 pr-11 text-sm font-mono focus:outline-none focus:border-primary"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPwd((v) => !v)}
                    className="absolute right-2 top-1/2 -translate-y-1/2 p-2 text-slate-400 hover:text-slate-700 rounded-md transition-colors"
                    aria-label={showPwd ? t("common.hidePassword") : t("common.showPassword")}
                  >
                    {showPwd ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
                <div className="mt-2 text-right">
                  <Link
                    to={email.trim() ? `/reset-password?email=${encodeURIComponent(email.trim())}` : "/reset-password"}
                    onClick={closeLogin}
                    className="text-xs text-[hsl(var(--primary))] font-medium hover:underline"
                  >
                    {t("auth.forgotPassword")}
                  </Link>
                </div>
              </div>

              {loginErr && (
                <div data-testid="login-error" className="chip-rose text-sm p-3 rounded-md">
                  {loginErr}
                </div>
              )}

              {loginErrorCode === "email_not_verified" && (
                <div className="space-y-3 p-3 rounded-md border border-amber-200/80 bg-amber-50/80">
                  <p className="text-xs text-amber-950/80 leading-relaxed">
                    {t("auth.emailNotVerifiedHint")}
                  </p>
                  <TurnstileField
                    key={loginResendKey}
                    onToken={setLoginResendTurnstile}
                    className="pt-0"
                  />
                  <button
                    type="button"
                    onClick={submitLoginResend}
                    disabled={loginResendLoading}
                    className="w-full min-h-[44px] text-sm font-medium rounded-md border border-amber-300 bg-white text-amber-950 hover:bg-amber-100/60 transition-opacity disabled:opacity-60"
                  >
                    {loginResendLoading ? t("auth.sending") : t("auth.resendVerification")}
                  </button>
                </div>
              )}

              <button
                data-testid="login-submit-button"
                type="submit"
                disabled={loginLoading}
                className="w-full min-h-[48px] text-white text-sm font-medium rounded-md transition-opacity disabled:opacity-60"
                style={{
                  background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                  boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
                }}
              >
                {loginLoading ? t("auth.loggingIn") : t("auth.submitLogin")}
              </button>

              {IS_DEV && (
                <div className="text-xs text-slate-500 font-mono border-t border-dashed border-border/60 pt-3 mt-2">
                  <div className="uppercase tracking-[0.14em] text-slate-400 text-[10px] mb-1 font-medium">
                    {t("auth.devDemoHint")}
                  </div>
                  admin@bankintel.local · admin123
                </div>
              )}
            </form>
          ) : null}

          {tab === "register-free" && registrationSuccess ? (
            <div className="p-5 sm:p-6 space-y-4" data-testid="register-free-success">
              <p className="text-slate-800 leading-relaxed">{registrationSuccess.message}</p>
              <div className="space-y-2">
                <p className="text-xs text-slate-600">{t("auth.emailNotReceived")}</p>
                <TurnstileField key={`rsend-free-${resendKey}`} onToken={setResendTurnstileToken} className="pt-1" />
                <button
                  type="button"
                  onClick={submitResendAfterRegister}
                  disabled={resendLoading}
                  className="w-full min-h-[44px] text-sm font-medium rounded-md border border-border/70 bg-white text-slate-900 hover:bg-slate-50 transition-opacity disabled:opacity-60"
                >
                  {resendLoading ? t("auth.sending") : t("auth.resendVerification")}
                </button>
              </div>
            </div>
          ) : null}

          {tab === "register-free" && !registrationSuccess ? (
            <form onSubmit={submitRegisterFree} className="p-5 sm:p-6 space-y-3" data-testid="register-free-form">
              <div>
                <h3 className="font-serif text-lg text-slate-900">{t("auth.registerFreeTitle")}</h3>
                <p className="text-sm text-slate-600 mt-2 leading-relaxed">
                  {t("auth.registerFreeIntro")}
                </p>
              </div>

              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.company")}</label>
                <input
                  data-testid="register-free-company"
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm focus:outline-none focus:border-primary"
                  value={regCompany}
                  onChange={(e) => setRegCompany(e.target.value)}
                  required
                  autoComplete="organization"
                />
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.name")}</label>
                <input
                  data-testid="register-free-name"
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm focus:outline-none focus:border-primary"
                  value={regName}
                  onChange={(e) => setRegName(e.target.value)}
                  required
                  autoComplete="name"
                />
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.email")}</label>
                <input
                  data-testid="register-free-email"
                  type="email"
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm font-mono focus:outline-none focus:border-primary"
                  value={regEmail}
                  onChange={(e) => setRegEmail(e.target.value)}
                  required
                  autoComplete="email"
                />
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.phone")}</label>
                <input
                  data-testid="register-free-phone"
                  type="tel"
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm focus:outline-none focus:border-primary"
                  value={regPhone}
                  onChange={(e) => setRegPhone(e.target.value)}
                  required
                  autoComplete="tel"
                />
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.password")}</label>
                <div className="relative mt-1.5">
                  <input
                    data-testid="register-free-password"
                    type={showRegPwd ? "text" : "password"}
                    autoComplete="new-password"
                    minLength={8}
                    className="w-full min-h-[44px] border border-border/70 rounded-md px-3 pr-11 text-sm focus:outline-none focus:border-primary"
                    value={regPassword}
                    onChange={(e) => setRegPassword(e.target.value)}
                    required
                  />
                  <button
                    type="button"
                    onClick={() => setShowRegPwd((v) => !v)}
                    className="absolute right-2 top-1/2 -translate-y-1/2 p-2 text-slate-400 hover:text-slate-700 rounded-md"
                    aria-label={showRegPwd ? t("common.hidePassword") : t("common.showPassword")}
                  >
                    {showRegPwd ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
                <p className="text-[10px] text-slate-400 mt-1">{PASSWORD_POLICY_HINT}</p>
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.confirmPassword")}</label>
                <input
                  data-testid="register-free-password2"
                  type="password"
                  autoComplete="new-password"
                  minLength={8}
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm focus:outline-none focus:border-primary"
                  value={regPassword2}
                  onChange={(e) => setRegPassword2(e.target.value)}
                  required
                />
              </div>
              <TurnstileField onToken={setTurnstileToken} className="pt-1" />

              {regErr && (
                <div data-testid="register-free-error" className="chip-rose text-sm p-3 rounded-md">
                  {regErr}
                </div>
              )}

              <button
                data-testid="register-free-submit"
                type="submit"
                disabled={regLoading || (Boolean(TURNSTILE_SITE_KEY) && !String(turnstileToken || "").trim())}
                className="w-full min-h-[48px] text-white text-sm font-medium rounded-md transition-opacity disabled:opacity-60"
                style={{
                  background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                  boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
                }}
              >
                {regLoading ? t("auth.creatingAccount") : t("auth.createFreeAccount")}
              </button>
            </form>
          ) : null}

          {tab === "register-sub" && registrationSuccess ? (
            <div className="p-5 sm:p-6 space-y-4" data-testid="register-sub-success">
              <p className="text-slate-800 leading-relaxed">{registrationSuccess.message}</p>
              <div className="space-y-2">
                <p className="text-xs text-slate-600">{t("auth.emailNotReceived")}</p>
                <TurnstileField key={`rsend-sub-${resendKey}`} onToken={setResendTurnstileToken} className="pt-1" />
                <button
                  type="button"
                  onClick={submitResendAfterRegister}
                  disabled={resendLoading}
                  className="w-full min-h-[44px] text-sm font-medium rounded-md border border-border/70 bg-white text-slate-900 hover:bg-slate-50 transition-opacity disabled:opacity-60"
                >
                  {resendLoading ? t("auth.sending") : t("auth.resendVerification")}
                </button>
              </div>
            </div>
          ) : null}

          {tab === "register-sub" && !registrationSuccess ? (
            <form
              onSubmit={submitRegisterSubscriber}
              className="p-5 sm:p-6 space-y-3"
              data-testid="register-subscriber-form"
            >
              <div>
                <h3 className="font-serif text-lg text-slate-900">{t("auth.registerSubTitle")}</h3>
                <p className="text-sm text-slate-600 mt-2 leading-relaxed">
                  {t("auth.registerSubIntro")}
                </p>
              </div>

              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.company")}</label>
                <input
                  data-testid="register-sub-company"
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm focus:outline-none focus:border-primary"
                  value={regCompany}
                  onChange={(e) => setRegCompany(e.target.value)}
                  required
                  autoComplete="organization"
                />
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.name")}</label>
                <input
                  data-testid="register-sub-name"
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm focus:outline-none focus:border-primary"
                  value={regName}
                  onChange={(e) => setRegName(e.target.value)}
                  required
                  autoComplete="name"
                />
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.email")}</label>
                <input
                  data-testid="register-sub-email"
                  type="email"
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm font-mono focus:outline-none focus:border-primary"
                  value={regEmail}
                  onChange={(e) => setRegEmail(e.target.value)}
                  required
                  autoComplete="email"
                />
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.phone")}</label>
                <input
                  data-testid="register-sub-phone"
                  type="tel"
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm focus:outline-none focus:border-primary"
                  value={regPhone}
                  onChange={(e) => setRegPhone(e.target.value)}
                  required
                  autoComplete="tel"
                />
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.password")}</label>
                <div className="relative mt-1.5">
                  <input
                    data-testid="register-sub-password"
                    type={showRegPwd ? "text" : "password"}
                    autoComplete="new-password"
                    minLength={8}
                    className="w-full min-h-[44px] border border-border/70 rounded-md px-3 pr-11 text-sm focus:outline-none focus:border-primary"
                    value={regPassword}
                    onChange={(e) => setRegPassword(e.target.value)}
                    required
                  />
                  <button
                    type="button"
                    onClick={() => setShowRegPwd((v) => !v)}
                    className="absolute right-2 top-1/2 -translate-y-1/2 p-2 text-slate-400 hover:text-slate-700 rounded-md"
                    aria-label={showRegPwd ? t("common.hidePassword") : t("common.showPassword")}
                  >
                    {showRegPwd ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
                <p className="text-[10px] text-slate-400 mt-1">{PASSWORD_POLICY_HINT}</p>
              </div>
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">{t("common.confirmPassword")}</label>
                <input
                  data-testid="register-sub-password2"
                  type="password"
                  autoComplete="new-password"
                  minLength={8}
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm focus:outline-none focus:border-primary"
                  value={regPassword2}
                  onChange={(e) => setRegPassword2(e.target.value)}
                  required
                />
              </div>
              <TurnstileField onToken={setTurnstileToken} className="pt-1" />
              <div>
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">
                  {t("auth.registrationCode")}
                </label>
                <input
                  data-testid="register-code"
                  type="password"
                  autoComplete="off"
                  autoCorrect="off"
                  spellCheck={false}
                  className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm font-mono focus:outline-none focus:border-primary"
                  value={regCode}
                  onChange={(e) => setRegCode(e.target.value)}
                  required
                />
              </div>

              {regErr && (
                <div data-testid="register-error" className="chip-rose text-sm p-3 rounded-md">
                  {regErr}
                </div>
              )}

              <button
                data-testid="register-submit-button"
                type="submit"
                disabled={regLoading || (Boolean(TURNSTILE_SITE_KEY) && !String(turnstileToken || "").trim())}
                className="w-full min-h-[48px] text-white text-sm font-medium rounded-md transition-opacity disabled:opacity-60"
                style={{
                  background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                  boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
                }}
              >
                {regLoading ? t("auth.creatingAccount") : t("auth.createSubscriberAccount")}
              </button>

              <p className="text-xs text-slate-500 pt-2 leading-relaxed">
                <Link
                  to="/predplatne"
                  onClick={closeLogin}
                  className="text-[hsl(var(--primary))] font-medium underline mr-1"
                >
                  {t("auth.subscriptionBenefitsLink")}
                </Link>
                · {t("auth.external")}:{" "}
                <a
                  href={SUBSCRIBE_INFO_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-[hsl(var(--primary))] font-medium underline"
                >
                  Bankovnictví Online
                </a>
                .
              </p>
            </form>
          ) : null}
        </div>
      </div>
    </div>
  );
}
