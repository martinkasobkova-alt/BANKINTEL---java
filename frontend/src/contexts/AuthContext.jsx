import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import api, {
  formatApiError,
  formatApiErrorFromAxios,
  setUnauthorizedHandler,
  API_TIMEOUT_MS,
} from "@/lib/api";
import { mapRegisterHttpError } from "@/lib/registerErrorMap";
import { toast } from "sonner";

/** @param {unknown} r */
function roleIsAdmin(r) {
  return String(r ?? "").trim().toLowerCase() === "admin";
}

function roleIsEditor(r) {
  return String(r ?? "").trim().toLowerCase() === "editor";
}

function roleCanEditContent(r) {
  const x = String(r ?? "").trim().toLowerCase();
  return x === "admin" || x === "editor";
}

const AuthContext = createContext(null);
/** Kratší než globální API timeout, aby se UI nezaseklo na „Načítám účet…“ až 2 min. */
const AUTH_ME_INIT_TIMEOUT_MS = Math.min(20_000, API_TIMEOUT_MS);

function isAbortLike(e) {
  if (!e) return false;
  if (e.code === "ERR_CANCELED" || e.name === "CanceledError" || e.name === "AbortError") return true;
  const m = String(e.message || "");
  return /cancell?ed/i.test(m) && !e.response;
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [ready, setReady] = useState(false);
  const [loginOpen, setLoginOpen] = useState(false);
  const unauthorizedToastDone = useRef(false);
  const bootstrapErrToastDone = useRef(false);

  useEffect(() => {
    const ac = new AbortController();
    let alive = true;
    (async () => {
      try {
        const { data } = await api.get("/auth/me", {
          signal: ac.signal,
          timeout: AUTH_ME_INIT_TIMEOUT_MS,
        });
        if (alive) setUser(data);
      } catch (e) {
        if (!alive) return;
        setUser(false);
        if (isAbortLike(e)) return;
        const st = e?.response?.status;
        if (st === 401 || st === 403) return;
        if (!e?.response) {
          if (!bootstrapErrToastDone.current) {
            bootstrapErrToastDone.current = true;
            const msg = formatApiErrorFromAxios(e);
            if (msg) {
              toast.error(msg);
            } else {
              toast.error("Nelze se spojit s backendem.");
            }
          }
          return;
        }
        if (st >= 500) {
          if (!bootstrapErrToastDone.current) {
            bootstrapErrToastDone.current = true;
            toast.error("Server neodpovídá. Zkuste to za chvíli znovu.");
          }
        }
      } finally {
        if (alive) setReady(true);
      }
    })();
    return () => {
      alive = false;
      ac.abort();
    };
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser((prev) => {
        if (prev && prev !== false) {
          if (!unauthorizedToastDone.current) {
            unauthorizedToastDone.current = true;
            toast.error("Vypršelo přihlášení — přihlas se prosím znovu.");
          }
          setLoginOpen(true);
          return false;
        }
        return prev;
      });
    });
    return () => setUnauthorizedHandler(null);
  }, []);

  const login = async (email, password) => {
    try {
      await api.post("/auth/login", { email, password });
      unauthorizedToastDone.current = false;
      /** Ověření, že cookie session funguje (cross-origin Vercel → Render vyžaduje SameSite=None; Secure). */
      let sessionUser = null;
      try {
        const meRes = await api.get("/auth/me");
        sessionUser = meRes.data;
        setUser(sessionUser);
      } catch (e2) {
        setUser(false);
        const st = e2?.response?.status;
        if (st === 401 || st === 403) {
          if (typeof console !== "undefined" && typeof console.debug === "function") {
            // eslint-disable-next-line no-console
            console.debug(
              "[Bankoapp auth] /auth/me po přihlášení vrátilo %s — cookies pravděpodobně nešly přenést (CORS/SameSite).",
              st
            );
          }
          toast.error(
            st === 403
              ? "Účet není způsobilý k přístupu (např. neověřený e-mail)."
              : "Přihlášení odpovědělo, ale prohlížeč neuložil platnou session. Zkuste jiný prohlížeč nebo zkontrolujte na API: COOKIE_SAMESITE=none, COOKIE_SECURE=true, CORS_ORIGINS=https://bankoapp.vercel.app"
          );
          return { ok: false, error: "Session cookie se nepodařilo použít.", code: "session_not_established" };
        }
        throw e2;
      }
      setLoginOpen(false);
      // open_personal_dashboard_on_login: DB název; znamená „otevřít osobní dashboard při startu na /“, ne jen při loginu.
      if (
        !roleIsAdmin(sessionUser?.role) &&
        sessionUser?.open_personal_dashboard_on_login &&
        sessionUser?.default_dashboard_page_id &&
        sessionUser?.is_subscriber
      ) {
        const path = window.location?.pathname || "/";
        if (path === "/" || path === "") {
          window.setTimeout(() => {
            window.location.assign("/my-dashboard");
          }, 0);
        }
      }
      return { ok: true };
    } catch (e) {
      const st = e?.response?.status;
      const d = e?.response?.data?.detail;
      if (st === 403 && d && typeof d === "object" && d.message) {
        return {
          ok: false,
          error: d.message,
          code: d.code || null,
        };
      }
      return { ok: false, error: formatApiError(d) || e.message, code: null };
    }
  };

  const register = async (payload) => {
    try {
      const body = {
        company: payload.company,
        name: payload.name,
        email: payload.email,
        phone: payload.phone,
        password: payload.password,
      };
      const code = (payload.registration_code || "").trim();
      if (code) body.registration_code = code;
      if (payload.turnstile_token) body.turnstile_token = payload.turnstile_token;
      if (payload.captcha_token) body.captcha_token = payload.captcha_token;
      const { data } = await api.post("/auth/register", body);
      unauthorizedToastDone.current = false;
      if (data?.status === "pending_verification") {
        return {
          ok: true,
          pendingVerification: true,
          message: data.message,
          email: data.email,
        };
      }
      let regUser = null;
      try {
        const meRes = await api.get("/auth/me");
        regUser = meRes.data;
        setUser(regUser);
      } catch {
        setUser(false);
        toast.error("Registrace proběhla, ale session se neuložila. Zkuste se přihlásit ručně.");
        return { ok: true, pendingVerification: false };
      }
      setLoginOpen(false);
      toast.success("Registrace proběhla úspěšně. Jste přihlášeni.");
      if (
        !roleIsAdmin(regUser?.role) &&
        regUser?.open_personal_dashboard_on_login &&
        regUser?.default_dashboard_page_id &&
        regUser?.is_subscriber
      ) {
        const path = window.location?.pathname || "/";
        if (path === "/" || path === "") {
          window.setTimeout(() => {
            window.location.assign("/my-dashboard");
          }, 0);
        }
      }
      return { ok: true, pendingVerification: false };
    } catch (e) {
      return { ok: false, error: mapRegisterHttpError(e) };
    }
  };

  const logout = async () => {
    try {
      await api.post("/auth/logout");
    } catch {
      /* ignore */
    }
    unauthorizedToastDone.current = false;
    setUser(false);
  };

  const refreshUser = useCallback(async () => {
    try {
      const { data } = await api.get("/auth/me");
      setUser(data);
    } catch {
      setUser(false);
    }
  }, []);

  return (
    <AuthContext.Provider
      value={{
        user,
        ready,
        login,
        register,
        logout,
        refreshUser,
        isAdmin: Boolean(user && user !== false && roleIsAdmin(user.role)),
        isEditor: Boolean(user && user !== false && roleIsEditor(user.role)),
        canEditContent: Boolean(user && user !== false && roleCanEditContent(user.role)),
        isSubscriber:
          Boolean(user && user !== false) &&
          (user.is_subscriber === true || roleIsAdmin(user.role) || roleIsEditor(user.role)),
        openLogin: () => setLoginOpen(true),
        closeLogin: () => setLoginOpen(false),
        loginOpen,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
