import React, { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Eye, EyeOff } from "lucide-react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import TurnstileField from "@/components/auth/TurnstileField";
import { PASSWORD_POLICY_HINT } from "@/lib/passwordPolicy";

const TURNSTILE_SITE_KEY = (process.env.REACT_APP_TURNSTILE_SITE_KEY || "").trim();

export default function ResetPasswordPage() {
  const [search] = useSearchParams();
  const token = useMemo(() => String(search.get("token") || "").trim(), [search]);
  const emailPrefill = useMemo(() => String(search.get("email") || "").trim(), [search]);

  const [email, setEmail] = useState("");
  const [turnstileToken, setTurnstileToken] = useState("");
  const [requestLoading, setRequestLoading] = useState(false);
  const [requestMsg, setRequestMsg] = useState("");
  const [requestErr, setRequestErr] = useState("");

  const [password, setPassword] = useState("");
  const [password2, setPassword2] = useState("");
  const [showPwd, setShowPwd] = useState(false);
  const [resetLoading, setResetLoading] = useState(false);
  const [resetMsg, setResetMsg] = useState("");
  const [resetErr, setResetErr] = useState("");

  useEffect(() => {
    if (!token && emailPrefill) setEmail(emailPrefill);
  }, [token, emailPrefill]);

  const submitRequest = async (e) => {
    e.preventDefault();
    setRequestMsg("");
    setRequestErr("");
    setRequestLoading(true);
    try {
      if (TURNSTILE_SITE_KEY && !String(turnstileToken || "").trim()) {
        setRequestErr("Dokončete ověření proti spamu.");
        return;
      }
      const { data } = await api.post("/auth/forgot-password", {
        email: email.trim(),
        turnstile_token: turnstileToken || undefined,
        captcha_token: turnstileToken || undefined,
      });
      setRequestMsg(data?.message || "Pokud účet existuje, byl odeslán e-mail s odkazem pro obnovu hesla.");
      setTurnstileToken("");
    } catch (err) {
      setRequestErr(formatApiErrorFromAxios(err));
    } finally {
      setRequestLoading(false);
    }
  };

  const submitReset = async (e) => {
    e.preventDefault();
    setResetMsg("");
    setResetErr("");
    if (password !== password2) {
      setResetErr("Hesla se neshodují.");
      return;
    }
    setResetLoading(true);
    try {
      const { data } = await api.post("/auth/reset-password", {
        token,
        new_password: password,
      });
      setResetMsg(data?.message || "Heslo bylo změněno.");
      setPassword("");
      setPassword2("");
    } catch (err) {
      setResetErr(formatApiErrorFromAxios(err));
    } finally {
      setResetLoading(false);
    }
  };

  return (
    <div className="min-h-[55vh] flex items-center justify-center p-6">
      <div className="soft-card max-w-lg w-full p-6 sm:p-8 space-y-4">
        <h1 className="font-serif text-2xl text-slate-900">Obnovení hesla</h1>
        {!token ? (
          <form className="space-y-3" onSubmit={submitRequest}>
            <p className="text-sm text-slate-600 leading-relaxed">
              Zadejte e-mail účtu a pošleme vám odkaz pro nastavení nového hesla.
            </p>
            <div>
              <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">E-mail</label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm font-mono focus:outline-none focus:border-primary"
              />
            </div>
            <TurnstileField onToken={setTurnstileToken} className="pt-1" />
            {requestErr ? <p className="text-sm text-rose-700">{requestErr}</p> : null}
            {requestMsg ? <p className="text-sm text-emerald-700">{requestMsg}</p> : null}
            <button
              type="submit"
              disabled={requestLoading || (Boolean(TURNSTILE_SITE_KEY) && !String(turnstileToken || "").trim())}
              className="w-full min-h-[48px] text-white text-sm font-medium rounded-md transition-opacity disabled:opacity-60"
              style={{
                background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
              }}
            >
              {requestLoading ? "Odesílám…" : "Poslat odkaz pro obnovu"}
            </button>
          </form>
        ) : (
          <form className="space-y-3" onSubmit={submitReset}>
            <p className="text-sm text-slate-600 leading-relaxed">
              Nastavte nové heslo pro svůj účet.
            </p>
            <div>
              <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">Nové heslo</label>
              <div className="relative mt-1.5">
                <input
                  type={showPwd ? "text" : "password"}
                  autoComplete="new-password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full min-h-[44px] border border-border/70 rounded-md px-3 pr-11 text-sm focus:outline-none focus:border-primary"
                />
                <button
                  type="button"
                  onClick={() => setShowPwd((v) => !v)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 p-2 text-slate-400 hover:text-slate-700 rounded-md"
                  aria-label={showPwd ? "Skrýt heslo" : "Zobrazit heslo"}
                >
                  {showPwd ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              <p className="text-[10px] text-slate-400 mt-1">{PASSWORD_POLICY_HINT}</p>
            </div>
            <div>
              <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">Potvrzení hesla</label>
              <input
                type={showPwd ? "text" : "password"}
                autoComplete="new-password"
                required
                value={password2}
                onChange={(e) => setPassword2(e.target.value)}
                className="mt-1.5 w-full min-h-[44px] border border-border/70 rounded-md px-3 text-sm focus:outline-none focus:border-primary"
              />
            </div>
            {resetErr ? <p className="text-sm text-rose-700">{resetErr}</p> : null}
            {resetMsg ? <p className="text-sm text-emerald-700">{resetMsg}</p> : null}
            <button
              type="submit"
              disabled={resetLoading}
              className="w-full min-h-[48px] text-white text-sm font-medium rounded-md transition-opacity disabled:opacity-60"
              style={{
                background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
              }}
            >
              {resetLoading ? "Ukládám…" : "Nastavit nové heslo"}
            </button>
          </form>
        )}

        <div className="pt-1">
          <Link to="/" className="inline-flex items-center text-[hsl(var(--primary))] font-medium underline">
            Zpět na úvod
          </Link>
        </div>
      </div>
    </div>
  );
}
