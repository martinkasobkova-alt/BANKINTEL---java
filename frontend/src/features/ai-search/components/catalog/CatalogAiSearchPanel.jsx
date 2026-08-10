import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Loader2, Search, Layers, ChevronRight, BarChart3, Globe2, Download } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios, normalizeApiFailure } from "@/lib/api";
import ChatMarkdownText from "@/components/common/ChatMarkdownText";
import {
  buildCatalogDeepSearchBody,
  CATALOG_DEEP_SEARCH_TIMEOUT_MS,
  extractDeepSearchPipelineDiagnostics,
  normalizeDeepSearchResultRows,
} from "@/lib/catalogDeepSearchClient";
import { CATALOGS, CATALOGS_DEFAULT_SELECTED_IDS } from "@/lib/catalogDefinitions";
import { LoadingSpinner } from "@/components/ui/loading";
import { buildCatalogPreviewBody } from "@/lib/catalogPreviewBody";
import VoiceInputButton from "@/components/common/VoiceInputButton";
import { getEcbAiPanelIntentHint, getEcbFlowPreset, ECB_EXR_QUICK_TEMPLATES } from "@/lib/ecbTopicPresets";
import { buildPublicCatalogPath } from "@/lib/catalogRowPrimaryAction";

const ROUTE_FOR_CATALOG = {
  arad: buildPublicCatalogPath("arad"),
  csu: buildPublicCatalogPath("csu"),
  eurostat: buildPublicCatalogPath("eurostat"),
  bis: buildPublicCatalogPath("bis"),
  imf: buildPublicCatalogPath("imf"),
  fred: buildPublicCatalogPath("fred"),
  data360: buildPublicCatalogPath("data360"),
};

function catalogRoute(catalogId, query = "") {
  return buildPublicCatalogPath(catalogId, { q: query }) || "/search/catalog";
}

function TierBadge({ tier }) {
  const t = String(tier || "").toLowerCase();
  const cls =
    t === "verified"
      ? "border-emerald-300/70 bg-emerald-50 text-emerald-950"
      : t === "beta" || t === "unavailable"
        ? "border-amber-300/80 bg-amber-50 text-amber-950"
        : "border-slate-200 bg-slate-50 text-slate-800";
  const lab =
    t === "verified"
      ? "ověřeno náhledem"
      : t === "beta"
        ? "beta"
        : t === "unavailable"
          ? "nedostupné"
          : "kandidát";
  return (
    <span className={`text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded-md border shrink-0 font-semibold ${cls}`}>{lab}</span>
  );
}

function nextStepLabel(step) {
  const s = String(step || "");
  const map = {
    preview: "Náhled",
    preview_family: "Ověřená sada řad",
    select_country: "Vybrat zemi",
    select_country_group: "Vybrat skupinu zemí",
    select_dimensions: "Vybrat dimenze",
    open_catalog: "Otevřít katalog",
    unsupported_beta: "Beta",
  };
  return map[s] || s || "Akce";
}

/** Rychlé filtry ISO3 řad vrácených z backendu SeriesFamily — zarovnané na catalog_country_groups.PRESET_ISO3 */
const SERIES_FAMILY_PRESETS = [
  { id: "ALL", label: "Vše" },
  { id: "V4", label: "V4", iso3: new Set(["CZE", "SVK", "POL", "HUN"]) },
  { id: "EU_BIG5", label: "EU – největší", iso3: new Set(["DEU", "FRA", "ITA", "ESP", "POL"]) },
  {
    id: "CR_NEIGH",
    label: "Sousedé ČR",
    iso3: new Set(["DEU", "AUT", "SVK", "POL"]),
  },
  {
    id: "G20_SUB",
    label: "Globálně (G20+)",
    iso3: new Set(["USA", "CHN", "DEU", "JPN", "IND", "GBR", "FRA", "ITA", "CAN", "AUS", "RUS", "BRA"]),
  },
];

function downloadSeriesFamilyCsv(family) {
  const rows = [["country_iso3", "country_code", "period", "value"]];
  for (const s of family.series || []) {
    for (const p of s.points || []) {
      rows.push([s.country_code_iso3 || "", s.country_code || "", String(p.date), String(p.value)]);
    }
  }
  const csv = rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(",")).join("\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${(family.indicator_id || "series_family").replace(/[^a-z0-9._-]/gi, "_")}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function filterSeriesForPreset(seriesList, presetId) {
  const def = SERIES_FAMILY_PRESETS.find((x) => x.id === presetId);
  if (!def || presetId === "ALL" || !def.iso3) return seriesList;
  return seriesList.filter((s) => def.iso3.has(String(s.country_code_iso3 || "").toUpperCase()));
}

function SeriesFamiliesBlock({ families, navigateTo, promptQuery }) {
  if (!Array.isArray(families) || families.length === 0) return null;
  return (
    <div className="space-y-3 pt-2">
      <div className="text-xs font-semibold uppercase tracking-wide text-indigo-900">Sada časových řad</div>
      <ul className="space-y-3">
        {families.map((fam, fi) => (
          <SeriesFamilyCard key={`sf-${fam.indicator_id || fi}-${fam.country_group || fi}`} family={fam} navigateTo={navigateTo} promptQuery={promptQuery} fi={fi} />
        ))}
      </ul>
    </div>
  );
}

function SeriesFamilyCard({ family, navigateTo, promptQuery, fi }) {
  const [presetId, setPresetId] = useState("ALL");
  const [showMissing, setShowMissing] = useState(false);
  const totalReq = typeof family.countries_requested_count === "number" ? family.countries_requested_count : null;
  const withData =
    typeof family.countries_with_data_count === "number" ? family.countries_with_data_count : (family.series || []).length;
  const filtered = filterSeriesForPreset(family.series || [], presetId);
  const partialNote =
    totalReq != null && withData != null && withData < totalReq
      ? `Data nalezena pro ${withData} z ${totalReq} zemí.`
      : Array.isArray(family.missing) && family.missing.length
        ? `${family.missing.length} položek bez dat — viz níže „Zobrazit chybějící“.`
        : null;
  const freqLabel =
    family.frequency === "M"
      ? "měsíční"
      : family.frequency === "Q"
        ? "čtvrtletní"
        : family.frequency === "A"
          ? "roční"
          : "neznámá";
  return (
    <li className="rounded-2xl border border-indigo-200/70 bg-gradient-to-br from-white to-indigo-50/35 px-3 py-4 sm:px-4 shadow-md space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0 space-y-1">
          <div className="font-semibold text-slate-900 text-sm">{family.title_cs || family.title}</div>
          <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-slate-600">
            <span>
              Zdroj: <span className="font-medium">{family.source || "World Bank"}</span>
            </span>
            <span>Země s daty: {withData}{totalReq != null ? ` / ${totalReq}` : ""}</span>
            <span>Frekvence: {freqLabel}</span>
            <span>Jednotka: {family.unit || "—"}</span>
            {family.indicator_id ? <span className="font-mono text-emerald-900/85">{family.indicator_id}</span> : null}
          </div>
          {partialNote ? (
            <p className="text-[11px] text-amber-900 bg-amber-50/85 border border-amber-100 rounded-lg px-2 py-1">{partialNote}</p>
          ) : null}
          {(family.warnings || []).slice(0, 3).map((w, i) => (
            <p key={i} className="text-[11px] text-slate-600">
              {w}
            </p>
          ))}
        </div>
        <span className="text-[10px] uppercase px-2 py-0.5 rounded-md border bg-white/90 text-indigo-900 border-indigo-200">
          Ověřeno API
        </span>
      </div>
      <div className="flex flex-wrap gap-1.5 items-center">
        <span className="text-[10px] text-slate-500 mr-1">Země / presety:</span>
        {SERIES_FAMILY_PRESETS.map((pr) => (
          <button
            key={`${fi}-${pr.id}`}
            type="button"
            className={`text-[10px] px-2 py-1 rounded-lg border transition ${
              presetId === pr.id ? "border-indigo-500 bg-indigo-50 font-semibold" : "border-slate-200 bg-white hover:border-slate-300"
            }`}
            onClick={() => setPresetId(pr.id)}
          >
            {pr.label}
          </button>
        ))}
      </div>
      <div className="max-h-40 overflow-auto rounded-lg border border-slate-100 bg-white/95 text-[11px]">
        <table className="w-full text-left">
          <thead className="text-slate-500 sticky top-0 bg-white">
            <tr>
              <th className="px-2 py-1 font-medium">ISO3</th>
              <th className="px-2 py-1 font-medium">Poslední</th>
            </tr>
          </thead>
          <tbody>
            {filtered.slice(0, 40).map((s) => (
              <tr key={s.series_id || s.country_code_iso3} className="border-t border-slate-50">
                <td className="px-2 py-0.5 font-mono">{s.country_code_iso3}</td>
                <td className="px-2 py-0.5 text-slate-700">
                  {s.last_period}: {typeof s.last_value === "number" ? s.last_value.toFixed?.(4) ?? s.last_value : s.last_value}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {filtered.length > 40 ? <p className="text-[10px] text-slate-500 px-2 py-1">… zobrazeno prvních 40 řádků ({filtered.length} celkem)</p> : null}
      </div>
      {presetId !== "ALL" ? (
        <p className="text-[10px] text-slate-500">Zobrazeno {filtered.length} řad ({SERIES_FAMILY_PRESETS.find((p) => p.id === presetId)?.label || presetId}).</p>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className="btn-mint px-3 h-8 text-[11px] inline-flex items-center gap-1.5 rounded-lg"
          onClick={() =>
            navigateTo(catalogRoute("data360", family.indicator_id || promptQuery || ""), {
              state: { seriesFamilyEcho: family },
            })
          }
        >
          <BarChart3 className="h-3.5 w-3.5" /> Zobrazit graf / katalog WB
        </button>
        <button
          type="button"
          className="h-8 px-3 text-[11px] rounded-lg border border-slate-200 bg-white inline-flex items-center gap-1.5 text-slate-800 hover:bg-slate-50"
          onClick={() => downloadSeriesFamilyCsv(family)}
        >
          <Download className="h-3.5 w-3.5" /> Stáhnout CSV
        </button>
        <button
          type="button"
          className="h-8 px-3 text-[11px] rounded-lg border border-slate-200 bg-white inline-flex items-center gap-1.5 text-slate-700 hover:bg-slate-50"
          onClick={() => toast("Vyberte řady na nástěnce — sada řad je připravená ke grafickému použití.", { duration: 4000 })}
          title="Zaškrtněte konkrétní země v grafu výše"
        >
          Přidat na dashboard
        </button>
        {family.chart_recommendation ? (
          <span className="text-[10px] text-slate-500 inline-flex items-center gap-1 self-center ml-auto">
            <Globe2 className="h-3 w-3" /> Doporučení:{" "}
            {family.chart_recommendation === "bar_latest" ? "sloupcově (poslední hodnoty)" : "linky (vybrané země)"}
          </span>
        ) : null}
      </div>
      {Array.isArray(family.missing) && family.missing.length > 0 ? (
        <div className="text-[11px] text-slate-700">
          <button type="button" className="underline text-amber-900 font-medium" onClick={() => setShowMissing((v) => !v)}>
            {showMissing ? "Skrýt chybějící" : "Zobrazit chybějící"}
          </button>
          {showMissing ? (
            <ul className="mt-1 list-disc pl-4 space-y-0.5 text-slate-600">
              {(family.missing || []).slice(0, 30).map((m, mi) => (
                <li key={mi}>{m.country || "?"} — {m.reason}</li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
    </li>
  );
}

/**
 * Dominantní AI box pro složité katalogy (ECB, BIS, OECD, IMF).
 * Volá rozšířený POST `/api/catalog/deep-search`.
 * @param {{ catalogId?: string, onOpenEcbWizard?: function, className?: string,
 *   headline?: string, description?: string, inputPlaceholder?: string,
 *   crossSourceHelpText?: string }} props
 */
export default function CatalogAiSearchPanel({
  catalogId = null,
  onOpenEcbWizard,
  className = "",
  headline,
  description,
  inputPlaceholder,
  crossSourceHelpText,
}) {
  const nav = useNavigate();
  const [q, setQ] = useState("");
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [searchAll, setSearchAll] = useState(true);
  const [peek, setPeek] = useState(null);
  const requestSeqRef = useRef(0);
  const abortRef = useRef(null);

  useEffect(() => {
    return () => {
      try {
        abortRef.current?.abort?.();
      } catch  {
        /* noop */
      }
      abortRef.current = null;
    };
  }, []);

  const run = useCallback(async () => {
    const dq = String(q || "").trim();
    if (dq.length < 2) {
      toast.error("Zadejte alespoň 2 znaky.");
      return;
    }
    try {
      abortRef.current?.abort?.();
    } catch  {
      /* noop */
    }
    const seq = ++requestSeqRef.current;
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError("");
    setPeek(null);
    try {
      const sourceIds = searchAll
        ? CATALOGS_DEFAULT_SELECTED_IDS
        : catalogId
          ? [catalogId]
          : CATALOGS_DEFAULT_SELECTED_IDS;
      const { data: body } = await api.post(
        "/catalog/deep-search",
        {
          ...buildCatalogDeepSearchBody({ query: dq, sources: sourceIds, limitPerSource: 20 }),
          catalog_context: {
            catalog_id: catalogId || undefined,
            search_all_sources: searchAll,
          },
        },
        { timeout: CATALOG_DEEP_SEARCH_TIMEOUT_MS, signal: ctrl.signal }
      );
      if (seq !== requestSeqRef.current || ctrl.signal.aborted) return;
      setData(body);
      setError("");
    } catch (e) {
      if (ctrl.signal.aborted || seq !== requestSeqRef.current) return;
      const nf = normalizeApiFailure(e);
      if (nf.isCanceled) return;
      const st = e?.response?.status;
      const payload = e?.response?.data;
      if (payload && typeof payload === "object" && (payload.message_cs || payload.message || payload.error)) {
        setData(payload);
        setError("");
      } else if (st === 502 || st === 503) {
        setData(null);
        setError("AI vyhledávání je dočasně nedostupné. Zkuste základní katalogové hledání.");
      } else {
        setData(null);
        setError(formatApiErrorFromAxios(e));
      }
    } finally {
      if (seq === requestSeqRef.current) {
        setLoading(false);
      }
      if (abortRef.current === ctrl) {
        abortRef.current = null;
      }
    }
  }, [q, catalogId, searchAll]);

  const ecbIntentHint = useMemo(() => {
    if (String(catalogId || "").toLowerCase() !== "ecb") return null;
    return getEcbAiPanelIntentHint(q);
  }, [catalogId, q]);

  const normalizedRows = useMemo(() => normalizeDeepSearchResultRows(data), [data]);
  const pipelineDiag = useMemo(() => extractDeepSearchPipelineDiagnostics(data), [data]);
  const grp = data?.grouped_results || {};
  const verifiedRows = normalizedRows.verified.length
    ? normalizedRows.verified
    : grp.verified?.length
      ? grp.verified
      : data?.verified || [];
  const candidateRows = normalizedRows.candidates.length
    ? normalizedRows.candidates
    : grp.candidates?.length
      ? grp.candidates
      : (data?.possible || []).filter((x) => !x?.status || x?.status === "candidate");
  const betaRows = grp.beta?.length ? grp.beta : (data?.possible || []).filter((x) => ["beta", "unavailable"].includes(x?.status));
  const navHints = data?.catalog_navigation_hints || [];

  const handleRowAction = useCallback(
    async (row) => {
      const act = row.action || {};
      const ns = row.next_step;
      const cidNav = ((act.params && act.params.catalog_id) || row.catalog_id || "").trim().toLowerCase();

      if (act.type === "open_ecb_wizard" && typeof onOpenEcbWizard === "function" && act.params?.flow_ref) {
        onOpenEcbWizard({
          flowRef: act.params.flow_ref,
          title: act.params.title || row.title,
          suggestedDimensions: act.params.suggested_dimensions || {},
          wizardSession: Date.now() + Math.random(),
          initialValidQuery: String(q || "").trim(),
        });
        return;
      }

      if (ns === "preview" || act.type === "preview") {
        const cid = row.catalog_id;
        const def = CATALOGS.find((c) => c.id === cid || (row.source_type && c.sourceType === row.source_type));
        const pseudoRow = {
          set_id: row.set_id || row.code,
          name: row.name || row.title,
          ...row,
        };
        if (!def || !pseudoRow.set_id) {
          nav(`/search/catalog?q=${encodeURIComponent(q)}&ai=1&runDeep=1`);
          return;
        }
        try {
          const body = buildCatalogPreviewBody(def, pseudoRow);
          const { data: prev } = await api.post("/catalog/preview", body, { timeout: 45000 });
          const n =
            typeof prev?.records?.length === "number"
              ? prev.records.length
              : typeof prev?.length === "number"
                ? prev.length
                : "—";
          setPeek({ title: pseudoRow.name, count: n });
        } catch (e) {
          toast.error(formatApiErrorFromAxios(e));
        }
        return;
      }

      const target = cidNav && ROUTE_FOR_CATALOG[cidNav]
        ? catalogRoute(cidNav, q)
        : `/search/catalog?q=${encodeURIComponent(q)}&ai=1`;

      if (act.type === "open_catalog" || ns === "open_catalog" || ["select_dimensions", "unsupported_beta", "select_country", "select_country_group"].includes(ns || "")) {
        nav(target);
      } else if (cidNav && ROUTE_FOR_CATALOG[cidNav]) {
        nav(catalogRoute(cidNav, q));
      } else {
        nav(`/search/catalog?q=${encodeURIComponent(q)}&ai=1`);
      }
    },
    [nav, onOpenEcbWizard, q]
  );

  const catalogHintCross =
    crossSourceHelpText ||
    (catalogId &&
      (searchAll
        ? `Stránka ${String(catalogId).toUpperCase()} — hledání v globálním indexu napříč zdroji.`
        : `Preferovaný kontext ${String(catalogId).toUpperCase()} — backend řády z jediného zdroje zatím striktně nevynucuje.`));

  const panelTitle =
    headline || "Najít data pomocí AI";

  const panelDescription =
    description ||
    `Popište běžně, jaká data hledáte. Například: ROE bank v zemích EU, inflace v ČR, úvěry domácnostem, kapitálová
          přiměřenost bank. Aplikace zkusí najít vhodné řady napříč dostupnými indexy katalogů.`;

  const searchPlaceholder =
    inputPlaceholder ||
    `Např. ROE bank v zemích EU…`;

  return (
    <section
      className={`rounded-2xl border border-[hsl(var(--border)/0.88)] bg-gradient-to-br from-white via-emerald-50/40 to-slate-50/90 p-4 sm:p-5 shadow-md space-y-4 ${className}`}
      data-testid={`catalog-ai-search-${String(catalogId || "generic").trim().toLowerCase()}`}
    >
      <div className="space-y-2">
        <h2 className="text-lg sm:text-xl font-semibold tracking-tight text-slate-900 flex flex-wrap items-center gap-2">
          <Search className="h-5 w-5 text-emerald-700 shrink-0" />
          {panelTitle}
        </h2>
        <p className="text-[13px] sm:text-sm text-slate-600 max-w-prose leading-relaxed whitespace-pre-line">{panelDescription}</p>
      </div>

      {catalogId ? (
        <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3 rounded-xl border border-slate-100 bg-white/80 px-3 py-2.5">
          <p className="text-[11px] text-slate-600 leading-snug flex-1">{catalogHintCross}</p>
          <label className="flex items-center gap-2 text-[11px] text-slate-800 shrink-0 cursor-pointer select-none">
            <input
              type="checkbox"
              className="rounded border-border"
              checked={searchAll}
              onChange={(e) => setSearchAll(e.target.checked)}
            />
            Hledat napříč všemi zdroji
          </label>
        </div>
      ) : null}

      <div className="flex flex-col sm:flex-row gap-2">
        <div className="relative min-w-0 flex-1">
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={searchPlaceholder}
            className="h-11 w-full min-w-0 rounded-xl border border-[hsl(var(--border)/0.75)] bg-white px-3 pr-12 text-sm shadow-sm"
            onKeyDown={(e) => {
              if (e.key === "Enter") run();
            }}
          />
          <VoiceInputButton value={q} onChange={setQ} disabled={loading} className="absolute right-1.5 top-1/2 h-8 w-8 -translate-y-1/2" />
        </div>
        <button
          type="button"
          onClick={() => run()}
          disabled={loading}
          className="btn-mint shrink-0 h-11 px-4 text-sm inline-flex items-center gap-2 disabled:opacity-60"
        >
          {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Layers className="h-4 w-4" />}
          Najít datové řady
        </button>
      </div>

      {ecbIntentHint ? (
        <div
          className="rounded-xl border border-sky-200/90 bg-sky-50/80 px-3 py-2.5 text-[12px] text-sky-950 space-y-1.5"
          role="status"
          data-testid="ecb-catalog-ai-intent-hint"
        >
          <div className="font-semibold text-[13px]">{ecbIntentHint.title}</div>
          <p className="text-[11px] leading-snug text-sky-950/95">{ecbIntentHint.body}</p>
          {ecbIntentHint.primaryFlow && typeof onOpenEcbWizard === "function" ? (
            <button
              type="button"
              className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg border border-sky-300/80 bg-white text-[11px] font-medium text-sky-950 hover:bg-sky-50/90"
              onClick={() => {
                const f = String(ecbIntentHint.primaryFlow || "").trim().toUpperCase();
                if (!f) return;
                const preset = getEcbFlowPreset(f);
                let suggested = {};
                if (ecbIntentHint.openExrTemplate) {
                  const t = ECB_EXR_QUICK_TEMPLATES.find((x) => x.id === ecbIntentHint.openExrTemplate);
                  if (t) suggested = { ...t.suggestedDimensions };
                }
                onOpenEcbWizard({
                  flowRef: f,
                  title:
                    suggested && Object.keys(suggested).length
                      ? `${preset.humanTitle} · šablona`
                      : preset.humanTitle || f,
                  suggestedDimensions: suggested,
                  wizardSession: Date.now() + Math.random(),
                  autoRunPreview: Boolean(ecbIntentHint.openExrTemplate),
                  initialValidQuery: String(q || "").trim(),
                });
              }}
            >
              Otevřít průvodce ({ecbIntentHint.primaryFlow})
            </button>
          ) : null}
        </div>
      ) : null}

      {data?.catalog_focus_notice_cz ? (
        <p className="text-[11px] text-slate-600 border border-slate-100 rounded-lg px-3 py-2 bg-white/85">{data.catalog_focus_notice_cz}</p>
      ) : null}

      {data?.demographic_discovery_notice_cz ? (
        <p className="text-[11px] text-sky-950 border border-sky-200 rounded-lg px-3 py-2 bg-sky-50/90 leading-snug">
          {data.demographic_discovery_notice_cz}
        </p>
      ) : null}

      {data?.status && data.status !== "ok" && (data.message_cs || data.message) ? (
        <div
          className="rounded-xl border border-amber-200/90 bg-amber-50/90 px-3 py-2.5 text-[12px] text-amber-950 leading-snug"
          role="status"
          data-testid="catalog-ai-search-status-banner"
        >
          {data.message_cs || data.message}
        </div>
      ) : null}

      {error ? (
        <div className="text-sm rounded-xl p-4 border whitespace-pre-wrap break-words bg-rose-50/90 text-rose-950 border-rose-200 space-y-2" role="alert">
          <p>{error}</p>
          <button
            type="button"
            className="text-[12px] font-medium underline text-rose-900"
            onClick={() =>
              nav(`/search/catalog?q=${encodeURIComponent(String(q || "").trim())}`, {
                state: { preferClassicSearch: true },
              })
            }
          >
            Zkusit klasické hledání stejným dotazem (bez AI)
          </button>
        </div>
      ) : null}

      {pipelineDiag.searchedSources.length > 0 || pipelineDiag.durationMsTotal > 0 ? (
        <details className="text-[11px] text-slate-600 rounded-xl border border-slate-100 bg-white/85 px-3 py-2">
          <summary className="cursor-pointer select-none font-medium text-slate-800">Diagnostika pipeline</summary>
          <ul className="mt-2 space-y-1 list-none">
            {pipelineDiag.queryDomain ? <li>query_domain: {pipelineDiag.queryDomain}</li> : null}
            {Object.keys(pipelineDiag.resolvedGeo || {}).length > 0 ? (
              <li>resolved_geo: {JSON.stringify(pipelineDiag.resolvedGeo)}</li>
            ) : null}
            {pipelineDiag.searchedSources.length > 0 ? (
              <li>searched_sources: {pipelineDiag.searchedSources.join(", ")}</li>
            ) : null}
            {pipelineDiag.durationMsTotal > 0 ? <li>duration_ms_total: {pipelineDiag.durationMsTotal}</li> : null}
            {pipelineDiag.retrievalQueryCap > 0 ? <li>retrieval_query_cap: {pipelineDiag.retrievalQueryCap}</li> : null}
            <li>timeout_count: {pipelineDiag.timeoutCount}</li>
            <li>fallback_count: {pipelineDiag.fallbackCount}</li>
            {pipelineDiag.gptModel ? <li>gpt_model: {pipelineDiag.gptModel}</li> : null}
          </ul>
        </details>
      ) : null}

      {loading ? (
        <div className="flex items-center gap-2 text-xs text-slate-600 py-2" aria-busy="true">
          <LoadingSpinner suppressAria size="sm" /> Ověřování řad přes náhled (může trvat několik sekund)…
        </div>
      ) : null}

      {Array.isArray(data?.catalog_index_warnings) && data.catalog_index_warnings.length > 0 ? (
        <ul className="text-[11px] text-amber-950 bg-amber-50/80 border border-amber-200/90 rounded-xl px-4 py-2.5 list-disc pl-5 space-y-0.5">
          {data.catalog_index_warnings.slice(0, 8).map((w, i) => (
            <li key={i}>{w}</li>
          ))}
        </ul>
      ) : null}

      {data?.fallback_notice ? <p className="text-[11px] text-amber-900 bg-amber-50/90 px-3 py-2 rounded-lg border border-amber-100">{data.fallback_notice}</p> : null}

      {data?.fallback_used ? (
        <p
          className="text-[11px] text-slate-700 bg-slate-50/95 px-3 py-2 rounded-lg border border-slate-200/90"
          data-testid="catalog-ai-fallback-used-notice"
        >
          Používáme základní sémantické vyhledávání (AI plán nebyl plně použit nebo není v prostředí aktivní).
        </p>
      ) : null}

      {data?.fred_supplement_warnings?.length ? (
        <ul className="text-[11px] text-slate-700 px-3 py-2 rounded-lg bg-slate-50 border border-slate-100 list-disc pl-5">
          {(data.fred_supplement_warnings || []).slice(0, 6).map((x, i) => (
            <li key={i}>{x}</li>
          ))}
        </ul>
      ) : null}

      {peek ? (
        <div className="rounded-xl border border-emerald-200 bg-white px-4 py-3 text-sm text-slate-800">
          <button type="button" className="text-[11px] text-slate-500 float-right underline" onClick={() => setPeek(null)}>
            zavřít
          </button>
          <p className="font-medium text-emerald-900">{peek.title}</p>
          <p className="text-[13px] text-slate-600 mt-1">Ukázka dat (řádků): {peek.count ?? "—"}</p>
        </div>
      ) : null}

      {data?.ai_result_layer &&
      typeof data.ai_result_layer === "object" &&
      String(data.ai_result_layer.answer_cz || "").trim() ? (
        <div
          className="rounded-2xl border border-emerald-200/80 bg-gradient-to-br from-emerald-50/90 via-white to-slate-50/80 px-4 py-3.5 shadow-sm space-y-2"
          data-testid="catalog-ai-panel-result-layer"
        >
          <div className="text-[11px] font-semibold uppercase tracking-wider text-emerald-900">
            {String(data.ai_result_layer.headline_cz || "Shrnutí výsledku")}
          </div>
          <ChatMarkdownText
            text={String(data.ai_result_layer.answer_cz || "").trim()}
            className="text-[13px] text-slate-800"
          />
          {String(data.ai_result_layer.next_steps_cz || "").trim() ? (
            <p className="text-[12px] text-slate-600 leading-snug">{String(data.ai_result_layer.next_steps_cz).trim()}</p>
          ) : null}
          {candidateRows.length > 0 ? (
            <button
              type="button"
              className="inline-flex items-center gap-1.5 text-[12px] font-medium text-emerald-900 underline underline-offset-2 hover:text-emerald-950"
              onClick={() =>
                document.getElementById("catalog-ai-candidates-section")?.scrollIntoView({
                  behavior: "smooth",
                  block: "start",
                })
              }
            >
              Další návrhy (kandidáti) ↓
            </button>
          ) : null}
        </div>
      ) : null}

      <SeriesFamiliesBlock families={data?.families} navigateTo={nav} promptQuery={q} />

      <ResultSection title="Ověřené řady" rows={verifiedRows} onRow={handleRowAction} variant="verified" />

      <div id="catalog-ai-candidates-section" className="scroll-mt-4 space-y-2">
        <ResultSection title="Kandidáti k doplnění" rows={candidateRows} onRow={handleRowAction} variant="candidate" />
      </div>

      <ResultSection title="Beta / vyžaduje ruční ověření" rows={betaRows} onRow={handleRowAction} variant="beta" />

      {navHints.length ? (
        <div className="space-y-2 pt-2 border-t border-slate-100">
          <p className="text-xs font-semibold text-slate-700">Další nápověda k ECB / BIS / OECD (bez vymyšlených kódů řad)</p>
          <ul className="space-y-2">
            {navHints.map((row, idx) => (
              <HintRow key={`hint-${idx}`} row={row} nav={nav} />
            ))}
          </ul>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-[11px] text-slate-500 pt-1">
        <Link to={`/search/catalog?q=${encodeURIComponent(q.trim() || "")}&ai=1`} className="underline font-medium inline-flex items-center gap-1">
          Globální katalogové vyhledávání <ChevronRight className="h-3 w-3" />
        </Link>
      </div>
    </section>
  );
}

function ResultSection({ title, rows, onRow, variant }) {
  if (!rows?.length)
    return null;

  const tone =
    variant === "verified"
      ? "text-emerald-900"
      : variant === "beta"
        ? "text-slate-900"
        : "text-amber-950";

  return (
    <div className="space-y-2">
      <div className={`text-xs font-semibold uppercase tracking-wider ${tone}`}>{title}</div>
      <ul className="space-y-2">
        {rows.map((row, idx) => (
          <RowCard key={`${variant}-${String(row.code || row.set_id || idx)}-${idx}`} row={row} variant={variant} onRow={onRow} />
        ))}
      </ul>
    </div>
  );
}

function RowCard({ row, variant, onRow }) {
  const tierShow = variant === "beta" ? row.result_tier || row.status || "beta" : row.result_tier || row.status || "candidate";
  return (
    <li className="rounded-xl border border-slate-200/90 bg-white/95 px-3 py-3 sm:px-4 shadow-sm space-y-2">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="space-y-1 min-w-0">
          <div className="font-semibold text-slate-900 text-sm truncate" title={row.title}>
            {row.title || row.name || "—"}
          </div>
          <div className="flex flex-wrap items-center gap-2 text-[11px] text-slate-600">
            <span className="uppercase tracking-wide">{row.source || row.catalog_label || row.catalog_id || ""}</span>
            {(row.code || row.set_id) ? (
              <span className="font-mono text-emerald-900/85 bg-emerald-50/80 px-1.5 py-px rounded">{row.code || row.set_id}</span>
            ) : (
              <span className="text-slate-400">— bez plného kódu řady —</span>
            )}
            {row.final_score != null || row.score != null ? (
              <span className="text-slate-500">
                score {Number(row.final_score ?? row.score).toFixed(1)}
              </span>
            ) : null}
          </div>
          {row.full_path ? (
            <p className="text-[11px] text-slate-500 truncate" title={row.full_path}>
              {row.full_path}
            </p>
          ) : null}
          {row.reason || row.why_relevant ? (
            <p className="text-[12px] text-slate-700 leading-snug">{row.reason || row.why_relevant}</p>
          ) : null}
          {row.next_step ? <p className="text-[11px] text-slate-500">Další krok: {nextStepLabel(row.next_step)}</p> : null}
        </div>
        <TierBadge tier={tierShow} />
      </div>
      <button
        type="button"
        className="btn-mint px-3 h-8 text-[11px] inline-flex items-center gap-1.5 rounded-lg"
        onClick={() => onRow(row)}
      >
        {nextStepLabel(row.next_step)}
      </button>
    </li>
  );
}

function HintRow({ row, nav }) {
  const href =
    row.action?.params?.catalog_id && ROUTE_FOR_CATALOG[row.action.params.catalog_id]
      ? catalogRoute(row.action.params.catalog_id)
      : "/search/catalog";
  return (
    <li className="rounded-xl border border-dashed border-slate-200 bg-slate-50/80 px-3 py-2.5 text-[12px] text-slate-800 space-y-1">
      <div className="font-medium">{row.source} — {row.title}</div>
      {row.reason ? <p className="text-slate-600 text-[11px] leading-snug">{row.reason}</p> : null}
      <button
        type="button"
        className="mt-1 text-[11px] underline text-emerald-800"
        onClick={() => nav(href)}
      >
        Otevřít příslušný katalog
      </button>
    </li>
  );
}
