import React, { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Cookie, ChevronDown, ChevronUp, X } from "lucide-react";

/**
 * GDPR-friendly cookie consent banner.
 *
 * Stores the visitor's choice in localStorage as JSON:
 *   { ts: ISO-string, version: 1, categories: { necessary: true, preferences: bool, analytics: bool } }
 *
 * Necessary cookies are always on (login/admin session, language). Other
 * categories are opt-in. The banner re-appears whenever CONSENT_VERSION
 * is bumped (e.g. when we add a new category).
 *
 * Other parts of the app can read consent via `getCookieConsent()` and
 * subscribe to changes through the `bankovnictvi:cookie-consent` window
 * event so analytics/preferences scripts can lazy-load only with consent.
 */
const STORAGE_KEY = "bankovnictvi.cookieConsent";
const CONSENT_VERSION = 1;
const EVENT_NAME = "bankovnictvi:cookie-consent";

const DEFAULT_CONSENT = {
  necessary: true,
  preferences: false,
  analytics: false,
};

export function getCookieConsent() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (parsed?.version !== CONSENT_VERSION) return null;
    return parsed;
  } catch {
    return null;
  }
}

function persistConsent(categories) {
  const payload = {
    ts: new Date().toISOString(),
    version: CONSENT_VERSION,
    categories: { ...DEFAULT_CONSENT, ...categories, necessary: true },
  };
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
  } catch {
    // localStorage disabled — silently ignore; banner will reappear next load.
  }
  window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: payload }));
  return payload;
}

export function clearCookieConsent() {
  try { localStorage.removeItem(STORAGE_KEY); } catch {}
  window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: null }));
}

export default function CookieBanner() {
  const { t } = useTranslation();
  const [consent, setConsent] = useState(() => getCookieConsent());
  const [showSettings, setShowSettings] = useState(false);
  const [draft, setDraft] = useState(DEFAULT_CONSENT);

  // Listen to "open settings" requests from the footer link or admin panel.
  useEffect(() => {
    const onOpen = () => {
      setDraft(consent?.categories || DEFAULT_CONSENT);
      setShowSettings(true);
      setConsent(null);
    };
    window.addEventListener("bankovnictvi:open-cookie-settings", onOpen);
    return () => window.removeEventListener("bankovnictvi:open-cookie-settings", onOpen);
  }, [consent]);

  // Visible only when no decision is recorded.
  const visible = useMemo(() => consent === null, [consent]);

  if (!visible) return null;

  const acceptAll = () => {
    const next = persistConsent({ preferences: true, analytics: true });
    setConsent(next);
    setShowSettings(false);
  };
  const acceptNecessary = () => {
    const next = persistConsent({ preferences: false, analytics: false });
    setConsent(next);
    setShowSettings(false);
  };
  const saveCustom = () => {
    const next = persistConsent(draft);
    setConsent(next);
    setShowSettings(false);
  };

  return (
    <div
      role="dialog"
      aria-modal="false"
      aria-label={t("cookies.dialogLabel")}
      data-testid="cookie-banner"
      className="fixed inset-x-0 bottom-0 z-[300] px-3 sm:px-6 pb-3 sm:pb-6 pointer-events-none"
    >
      <div
        className="max-w-3xl mx-auto pointer-events-auto rounded-2xl shadow-2xl border border-border/60 bg-white/95 backdrop-blur"
        style={{ boxShadow: "0 12px 40px hsl(218 60% 20% / 0.18)" }}
      >
        <div className="px-5 sm:px-6 py-5">
          <div className="flex items-start gap-4">
            <div
              className="shrink-0 w-10 h-10 rounded-xl grid place-items-center"
              style={{ background: "hsl(var(--primary-soft))", color: "hsl(var(--primary-deep))" }}
            >
              <Cookie className="h-5 w-5" strokeWidth={1.6} />
            </div>
            <div className="min-w-0 flex-1">
              <h2 className="font-serif text-lg leading-tight">
                {t("cookies.title")}
              </h2>
              <p className="text-sm text-slate-600 mt-1.5 leading-relaxed">
                {t("cookies.intro")}{" "}
                <Link
                  to="/cookies"
                  className="underline hover:text-[hsl(var(--primary))]"
                  onClick={() => acceptNecessary()}
                >
                  {t("shell.cookiesPolicy")}
                </Link>
                .
              </p>
            </div>
            {showSettings && (
              <button
                type="button"
                onClick={() => setShowSettings(false)}
                className="text-slate-400 hover:text-slate-700 p-1"
                aria-label="Zavřít nastavení"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>

          {showSettings && (
            <div className="mt-4 pt-4 border-t border-border/60 space-y-3">
              <CategoryRow
                label="Nezbytné"
                description="Přihlášení uživatelů, CSRF, jazykové preference. Tyto cookies nelze vypnout — bez nich aplikace nefunguje."
                checked
                disabled
                onChange={() => {}}
              />
              <CategoryRow
                label="Preferenční"
                description="Pamatuje si volby v UI (rozbalený náhled, zvolená frekvence grafu)."
                checked={draft.preferences}
                onChange={(v) => setDraft((d) => ({ ...d, preferences: v }))}
              />
              <CategoryRow
                label="Analytické"
                description="Anonymní statistika návštěvnosti pro vylepšování aplikace. Aktuálně se v aplikaci nepoužívají žádné externí trackery — pokud je v budoucnu přidáme, váš souhlas bude respektován."
                checked={draft.analytics}
                onChange={(v) => setDraft((d) => ({ ...d, analytics: v }))}
              />
            </div>
          )}

          <div className="mt-4 flex flex-col-reverse sm:flex-row sm:items-center sm:justify-between gap-2">
            {!showSettings ? (
              <button
                type="button"
                onClick={() => setShowSettings(true)}
                data-testid="cookie-settings-btn"
                className="flex items-center justify-center gap-1.5 text-xs text-slate-600 hover:text-slate-900 px-3 py-2"
              >
                <ChevronDown className="h-3.5 w-3.5" /> Nastavit jednotlivě
              </button>
            ) : (
              <button
                type="button"
                onClick={() => setShowSettings(false)}
                className="flex items-center justify-center gap-1.5 text-xs text-slate-600 hover:text-slate-900 px-3 py-2"
              >
                <ChevronUp className="h-3.5 w-3.5" /> Skrýt nastavení
              </button>
            )}
            <div className="flex flex-col sm:flex-row gap-2 sm:gap-2">
              <button
                type="button"
                onClick={acceptNecessary}
                data-testid="cookie-reject-btn"
                className="px-4 h-10 text-sm rounded-md border border-border bg-white hover:bg-slate-50"
              >
                {t("cookies.necessaryOnly")}
              </button>
              {showSettings ? (
                <button
                  type="button"
                  onClick={saveCustom}
                  data-testid="cookie-save-btn"
                  className="px-5 h-10 text-sm rounded-md text-white hover:opacity-90"
                  style={{
                    background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                    boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
                  }}
                >
                  {t("cookies.saveSelection")}
                </button>
              ) : (
                <button
                  type="button"
                  onClick={acceptAll}
                  data-testid="cookie-accept-btn"
                  className="px-5 h-10 text-sm rounded-md text-white hover:opacity-90"
                  style={{
                    background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                    boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
                  }}
                >
                  {t("cookies.acceptAll")}
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function CategoryRow({ label, description, checked, disabled, onChange }) {
  return (
    <label
      className={`flex items-start gap-3 p-3 rounded-md border ${
        disabled ? "border-border/50 bg-slate-50/60" : "border-border/60 hover:bg-slate-50/40 cursor-pointer"
      }`}
    >
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange?.(e.target.checked)}
        className="mt-1 h-4 w-4 accent-[hsl(var(--primary))]"
      />
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium text-slate-800 flex items-center gap-2">
          {label}
          {disabled && (
            <span className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-mono">
              vždy aktivní
            </span>
          )}
        </div>
        <div className="text-xs text-slate-500 mt-1 leading-relaxed">{description}</div>
      </div>
    </label>
  );
}

/**
 * Helper for the footer link / admin panel: re-opens the banner so the user
 * can change their decision later.
 */
export function openCookieSettings() {
  window.dispatchEvent(new CustomEvent("bankovnictvi:open-cookie-settings"));
}
