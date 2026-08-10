import React, { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import api, { formatApiError } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import { LoadingInline, LoadingSpinner } from "@/components/ui/loading";
import { toast } from "sonner";

/** Pořadí a produktové texty (Fáze 3) – technický klíč z API zůstává zdroj pravdy pro ukládání. */
const FEATURE_ORDER = [
  "export_data",
  "save_widget",
  "personal_dashboard",
  "multiple_dashboards",
  "saved_calculations",
  "upload_custom_data",
  "composite_charts",
  "ad_free_dashboard",
  "chart_period",
  "chart_type",
  "chart_time_range",
  "chart_table_toggle",
  "chart_image_export",
  "rss_monitoring",
];

function defaultAccessForMissingKey(key) {
  if (String(key || "") === "chart_image_export") return "subscriber";
  return String(key || "").startsWith("chart_") ? "registered" : "subscriber";
}

const FEATURE_COPY = {
  export_data: {
    name: "Export dat",
    desc: "Export dat z grafů, tabulek a katalogu.",
  },
  save_widget: {
    name: "Ukládání widgetů",
    desc: "Ukládání oblíbených widgetů.",
  },
  personal_dashboard: {
    name: "Osobní dashboard",
    desc: "Vlastní stránka s uloženými widgety.",
  },
  multiple_dashboards: {
    name: "Více vlastních widget stránek",
    desc: "Možnost vytvořit více vlastních dashboard stránek.",
  },
  saved_calculations: {
    name: "Uložené výpočty",
    desc: "Ukládání vlastních výpočtů nad daty.",
  },
  upload_custom_data: {
    name: "Vlastní data",
    desc: "Nahrávání vlastních datových souborů.",
  },
  composite_charts: {
    name: "Složené grafy",
    desc: "Vytváření složených grafů a kombinování datových řad.",
  },
  ad_free_dashboard: {
    name: "Dashboard bez reklam",
    desc: "Skrytí reklamních bloků pro předplatitele.",
  },
  chart_period: {
    name: "Perioda dat v grafu",
    desc: "Změna periody (ročně, měsíčně, čtvrtletně a podobně).",
  },
  chart_type: {
    name: "Typ grafu",
    desc: "Změna typu zobrazení (sloupcový, spojnicový, plošný, koláč a podobně).",
  },
  chart_time_range: {
    name: "Časové okno",
    desc: "Výběr rozsahu dat (např. 5 let, 10 let, max.).",
  },
  chart_table_toggle: {
    name: "Graf a tabulka",
    desc: "Přepínání mezi zobrazením jako graf a jako tabulka.",
  },
  chart_image_export: {
    name: "Stažení grafu (PNG/JPG)",
    desc: "Stažení aktuálního grafu jako obrázek PNG nebo JPG.",
  },
  rss_monitoring: {
    name: "RSS monitoring",
    desc: "Novinky z RSS/Atom na osobním dashboardu (globální a vlastní zdroje).",
  },
};

const LEVEL_OPTIONS = [
  { value: "public", short: "Veřejné (všichni i anonym)" },
  { value: "registered", short: "Registrovaní uživatelé" },
  { value: "subscriber", short: "Pouze předplatitelé" },
  { value: "admin", short: "Pouze administrátoři" },
];

function stateBadgeClass(level) {
  if (level === "public") return "bg-emerald-100 text-emerald-900 border border-emerald-200/90";
  if (level === "admin") return "bg-violet-100 text-violet-900 border border-violet-200/80";
  if (level === "registered") return "bg-sky-50 text-sky-900 border border-sky-200/80";
  return "bg-amber-50 text-amber-900 border border-amber-200/80";
}

function stateBadgeText(level) {
  if (level === "public") return "Veřejné";
  if (level === "admin") return "Pouze admin";
  if (level === "registered") return "Registrovaní";
  return "Předplatitelé";
}

export default function FeatureAccessPage() {
  const { t } = useTranslation();
  const [rows, setRows] = useState([]);
  const [err, setErr] = useState("");
  const [loading, setLoading] = useState(true);
  const [savingKey, setSavingKey] = useState(null);

  const load = useCallback(async () => {
    setErr("");
    setLoading(true);
    try {
      const { data } = await api.get("/feature-access");
      const byKey = Object.fromEntries((data || []).map((r) => [r.feature_key, r]));
      const ordered = FEATURE_ORDER.map((key) => {
        const r = byKey[key];
        const copy = FEATURE_COPY[key] || { name: key, desc: "" };
        if (!r) {
          return {
            feature_key: key,
            access_level: defaultAccessForMissingKey(key),
            label: copy.name,
            description: copy.desc,
            _missing: true,
          };
        }
        return {
          ...r,
          label: copy.name,
          description: copy.desc,
        };
      });
      setRows(ordered);
    } catch (e) {
      setErr(formatApiError(e.response?.data?.detail) || e.message);
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const onLevelChange = async (featureKey, newLevel) => {
    const snap = JSON.stringify(rows);
    setRows((prev) =>
      prev.map((r) => (r.feature_key === featureKey ? { ...r, access_level: newLevel } : r)),
    );
    setSavingKey(featureKey);
    try {
      const { data: saved } = await api.put(`/feature-access/${encodeURIComponent(featureKey)}`, {
        access_level: newLevel,
      });
      const copy = FEATURE_COPY[featureKey] || { name: featureKey, desc: "" };
      setRows((prev) =>
        prev.map((r) =>
          r.feature_key === featureKey
            ? {
                ...r,
                ...saved,
                access_level: saved.access_level,
                label: copy.name,
                description: copy.desc,
              }
            : r,
        ),
      );
      toast.success("Nastavení zamykání bylo uloženo.");
    } catch (e) {
      setRows(JSON.parse(snap));
      toast.error(formatApiError(e.response?.data?.detail) || e.message);
    } finally {
      setSavingKey(null);
    }
  };

  return (
    <AppShell title={t("pages.admin.featureAccessTitle")} subtitle={t("pages.admin.featureAccessSubtitle")}>
      <div className="max-w-5xl space-y-6 text-sm text-slate-800">
        <div className="soft-card rounded-2xl border-border/80 p-5 space-y-3 text-slate-700 leading-relaxed">
          <p>
            Tady nastavíte, které funkce jsou veřejné, které jsou dostupné pouze registrovaným uživatelům,
            které jen předplatitelům Bankovnictví a které pouze administrátorům.
          </p>
          <p className="text-amber-900/90 bg-amber-50/80 border border-amber-200/60 rounded-xl px-3 py-2.5 text-[13px]">
            <strong className="font-semibold">Poznámka:</strong> Pravidla se ukládají na serveru; backend je
            zdroj pravdy u API a exportů.
          </p>
        </div>

        <div className="text-xs text-slate-500 space-y-1 border-l-2 border-mint-600/40 pl-3">
          <p>
            <strong className="text-slate-600">Veřejné</strong> – dostupné všem včetně anonymních návštěvníků.
          </p>
          <p>
            <strong className="text-slate-600">Registrovaní uživatelé</strong> – pouze přihlášení (free i
            předplatitelé + admin).
          </p>
          <p>
            <strong className="text-slate-600">Pouze předplatitelé</strong> – předplatitelé Bankovnictví a
            administrátoři.
          </p>
          <p>
            <strong className="text-slate-600">Pouze administrátoři</strong> – jen role admin.
          </p>
        </div>

        {err && !loading && (
          <div className="text-destructive text-sm border border-destructive/30 rounded-xl px-3 py-2 bg-destructive/5">
            {err}
          </div>
        )}

        {loading ? (
          <div className="py-8 w-full flex justify-center">
            <LoadingInline label="Načítám pravidla…" size="md" muted />
          </div>
        ) : (
          <div className="soft-card rounded-2xl border-border/80 overflow-hidden">
            <div className="overflow-x-auto [scrollbar-gutter:stable]">
              <table
                className="data-table min-w-[900px] [&_thead_th]:px-3 [&_thead_th]:py-2.5 [&_tbody_td]:px-3 [&_tbody_td]:py-2.5 [&_tbody_td]:align-middle [&_thead_th]:!bg-[hsl(205_76%_96%)]"
                data-testid="feature-access-table"
              >
                <thead>
                  <tr>
                    <th className="rounded-tl-2xl">Funkce</th>
                    <th>Technický klíč</th>
                    <th>Popis</th>
                    <th>Aktuální stav</th>
                    <th className="rounded-tr-2xl min-w-[220px]">Zamykání</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.feature_key} data-testid={`feature-access-row-${r.feature_key}`}>
                      <td className="font-medium text-slate-900 whitespace-nowrap">{r.label}</td>
                      <td className="font-mono text-xs text-slate-600">{r.feature_key}</td>
                      <td className="text-slate-600 max-w-md">{r.description}</td>
                      <td>
                        <span
                          className={`text-[10px] uppercase tracking-[0.1em] px-2.5 py-1 rounded-full font-semibold ${stateBadgeClass(r.access_level)}`}
                        >
                          {stateBadgeText(r.access_level)}
                        </span>
                      </td>
                      <td>
                        <div className="flex items-center gap-2">
                          <select
                            className="h-9 min-w-[200px] rounded-lg border border-border/90 bg-white px-2 text-sm text-slate-800 focus:outline-none focus:ring-1 focus:ring-mint-600/50"
                            value={r.access_level}
                            disabled={savingKey === r.feature_key}
                            onChange={(e) => {
                              const v = e.target.value;
                              if (v === r.access_level) return;
                              void onLevelChange(r.feature_key, v);
                            }}
                            aria-label={`Zamčení: ${r.label}`}
                            data-testid={`feature-access-select-${r.feature_key}`}
                          >
                            {LEVEL_OPTIONS.map((opt) => (
                              <option key={opt.value} value={opt.value}>
                                {opt.short}
                              </option>
                            ))}
                          </select>
                          {savingKey === r.feature_key && (
                            <LoadingSpinner suppressAria size="sm" className="text-slate-400" aria-label="" />
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
      <style>{`
        .data-table tbody tr:hover { background: rgba(248, 250, 252, 0.9); }
      `}</style>
    </AppShell>
  );
}
