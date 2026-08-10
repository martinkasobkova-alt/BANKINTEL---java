import React from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import AppShell from "@/components/layout/AppShell";
import { Cookie, Settings } from "lucide-react";
import { openCookieSettings, getCookieConsent, clearCookieConsent } from "@/components/CookieBanner";

export default function CookiesPage() {
  const { t } = useTranslation();
  const consent = getCookieConsent();
  const fmt = (b) => (b ? "✓ povoleno" : "✗ zakázáno");

  const reset = () => {
    clearCookieConsent();
    openCookieSettings();
  };

  return (
    <AppShell title={t("pages.legal.cookiesTitle")} subtitle={t("pages.legal.cookiesSubtitle")}>
      <div className="max-w-3xl space-y-8 pb-12">
        <Section title="Co jsou cookies?" icon={<Cookie className="h-4 w-4" />}>
          <p>
            Cookies jsou malé textové soubory, které si webová aplikace ukládá ve
            vašem prohlížeči. Slouží k zapamatování vaší relace (např. přihlášení),
            preferencí v rozhraní (rozbalený náhled, zvolená frekvence grafu) a
            volitelně i k anonymní statistice návštěvnosti.
          </p>
        </Section>

        <Section title="Které kategorie používáme?">
          <Category
            name="Nezbytné"
            necessary
            purpose="Přihlášení a bezpečnost: cookies access_token a refresh_token jsou uloženy jako HttpOnly (JavaScript k nim nemá přímý přístup) a slouží k bezpečné relaci a obnově přihlášení. Cookie csrf_token chrání formuláře a mutační požadavky proti CSRF; není HttpOnly, protože se musí číst v prohlížeči a posílat v hlavičce X-CSRF-Token. Dále preferenční volby UI."
            duration="Obvykle po dobu relace a dle max-age cookie (až cca 7 dní u obnovy relace), dle nastavení prohlížeče."
            third="Žádné třetí strany u samotného přihlášení."
          />
          <Category
            name="Preferenční"
            purpose="Pamatuje si volby v UI (rozbalený náhled v editoru, zvolená frekvence grafu, naposledy zobrazená sekce)."
            duration="Trvale v localStorage, dokud nesmažete cache prohlížeče."
            third="Žádné třetí strany."
          />
          <Category
            name="Analytické"
            purpose="Anonymní statistika návštěvnosti pro vylepšování aplikace. Aktuálně se v aplikaci nepoužívají žádné externí analytické nástroje."
            duration="—"
            third="—"
          />
        </Section>

        <Section title="Jak svůj souhlas spravovat?" icon={<Settings className="h-4 w-4" />}>
          <p>
            Svůj souhlas můžete kdykoliv změnit kliknutím na tlačítko níže.
            Změna se projeví okamžitě a nezbytné cookies (přihlášení) zůstanou
            vždy aktivní.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <button
              type="button"
              data-testid="reopen-cookie-settings"
              onClick={openCookieSettings}
              className="px-4 h-10 text-sm rounded-md text-white hover:opacity-90"
              style={{
                background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
              }}
            >
              Změnit nastavení cookies
            </button>
            <button
              type="button"
              onClick={reset}
              className="px-4 h-10 text-sm rounded-md border border-border bg-white hover:bg-slate-50"
            >
              Vymazat souhlas a zobrazit lištu
            </button>
          </div>
        </Section>

        {consent && (
          <Section title="Váš aktuální souhlas">
            <div className="rounded-lg border border-border/60 bg-white/70 p-4 text-sm font-mono">
              <Row k="Uloženo" v={new Date(consent.ts).toLocaleString("cs-CZ")} />
              <Row k="Nezbytné" v={fmt(consent.categories?.necessary)} />
              <Row k="Preferenční" v={fmt(consent.categories?.preferences)} />
              <Row k="Analytické" v={fmt(consent.categories?.analytics)} />
            </div>
          </Section>
        )}

        <div className="text-xs text-slate-500 pt-6 border-t border-border/50 flex flex-wrap gap-x-4 gap-y-2">
          <Link to="/ochrana-osobnich-udaju" className="underline hover:text-[hsl(var(--primary))]">
            Ochrana osobních údajů
          </Link>
          <Link to="/obchodni-podminky" className="underline hover:text-[hsl(var(--primary))]">
            Obchodní podmínky
          </Link>
          <Link to="/" className="underline hover:text-[hsl(var(--primary))]">
            ← Zpět na úvod
          </Link>
        </div>
      </div>
    </AppShell>
  );
}

function Section({ title, icon, children }) {
  return (
    <section>
      <h2 className="font-serif text-2xl flex items-center gap-2 mb-3">
        {icon && (
          <span
            className="w-7 h-7 rounded-md grid place-items-center"
            style={{ background: "hsl(var(--primary-soft))", color: "hsl(var(--primary-deep))" }}
          >
            {icon}
          </span>
        )}
        {title}
      </h2>
      <div className="text-sm text-slate-700 leading-relaxed space-y-2">{children}</div>
    </section>
  );
}

function Category({ name, necessary, purpose, duration, third }) {
  return (
    <div className="rounded-lg border border-border/60 bg-white/70 p-4 mb-3">
      <div className="flex items-center justify-between mb-2">
        <div className="font-medium text-slate-800">{name}</div>
        {necessary && (
          <span className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-mono">
            vždy aktivní
          </span>
        )}
      </div>
      <div className="text-xs text-slate-600 space-y-1">
        <div><span className="text-slate-500">Účel: </span>{purpose}</div>
        <div><span className="text-slate-500">Trvání: </span>{duration}</div>
        <div><span className="text-slate-500">Třetí strany: </span>{third}</div>
      </div>
    </div>
  );
}

function Row({ k, v }) {
  return (
    <div className="flex items-center justify-between py-1 border-b border-border/40 last:border-0">
      <span className="text-slate-500">{k}</span>
      <span className="text-slate-800">{v}</span>
    </div>
  );
}
