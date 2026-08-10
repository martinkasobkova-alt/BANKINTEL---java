import React from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { setAppLocale } from "@/i18n";

const LOCALES = [
  { id: "cs", labelKey: "locale.switchToCs" },
  { id: "en", labelKey: "locale.switchToEn" },
];

function CzechFlagIcon({ className }) {
  return (
    <svg
      viewBox="0 0 24 16"
      className={className}
      aria-hidden
      xmlns="http://www.w3.org/2000/svg"
    >
      <rect width="24" height="8" fill="#ffffff" />
      <rect y="8" width="24" height="8" fill="#d7141a" />
      <path d="M0 0 L0 16 L12 8 Z" fill="#11457e" />
    </svg>
  );
}

function UkFlagIcon({ className }) {
  return (
    <svg
      viewBox="0 0 24 16"
      className={className}
      aria-hidden
      xmlns="http://www.w3.org/2000/svg"
    >
      <rect width="24" height="16" fill="#012169" />
      <path d="M0 0 L24 16 M24 0 L0 16" stroke="#ffffff" strokeWidth="3" />
      <path d="M0 0 L24 16 M24 0 L0 16" stroke="#c8102e" strokeWidth="1.5" />
      <path d="M12 0 V16 M0 8 H24" stroke="#ffffff" strokeWidth="5" />
      <path d="M12 0 V16 M0 8 H24" stroke="#c8102e" strokeWidth="3" />
    </svg>
  );
}

function LocaleFlag({ localeId, className }) {
  if (localeId === "cs") return <CzechFlagIcon className={className} />;
  return <UkFlagIcon className={className} />;
}

/**
 * @param {object} props
 * @param {"header" | "sidebar"} [props.variant]
 * @param {string} [props.className]
 */
export default function LocaleSwitcher({ variant = "header", className }) {
  const { t, i18n } = useTranslation();
  const current = i18n.language?.startsWith("en") ? "en" : "cs";

  return (
    <div
      className={cn(
        "inline-flex items-center rounded-xl border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.88)] p-0.5 shadow-sm",
        variant === "sidebar" && "w-full justify-center",
        className
      )}
      role="group"
      aria-label={t("locale.label")}
      data-testid="locale-switcher"
    >
      {LOCALES.map(({ id, labelKey }) => {
        const active = current === id;
        return (
          <button
            key={id}
            type="button"
            onClick={() => setAppLocale(id)}
            aria-label={t(labelKey)}
            aria-pressed={active}
            data-testid={`locale-switch-${id}`}
            className={cn(
              "inline-flex h-7 w-7 items-center justify-center rounded-[0.5rem] transition overflow-hidden",
              active
                ? "bg-[hsl(var(--primary-soft))] ring-1 ring-[hsl(var(--primary)/0.35)] shadow-sm"
                : "opacity-80 hover:opacity-100 hover:bg-[hsl(var(--muted)/0.45)]"
            )}
            title={t(labelKey)}
          >
            <LocaleFlag localeId={id} className="h-[13px] w-[19px] rounded-[2px] border border-black/10 shadow-sm" />
          </button>
        );
      })}
    </div>
  );
}
