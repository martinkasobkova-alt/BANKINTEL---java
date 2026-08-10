import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { X, Search, Trash2, Database, FileSpreadsheet, Calculator } from "lucide-react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { LoadingSpinner, LoadingInline } from "@/components/ui/loading";
import { runStockSearch } from "@/lib/stockSearch";

export const MAX_UNIFIED_COMPARE = 8;

const CHART_TYPES = [
  { id: "line", label: "Čára" },
  { id: "bar", label: "Sloupec" },
  { id: "area", label: "Plocha" },
  { id: "dot", label: "Body" },
];

const FREQ_AGG_OPTIONS = [
  { id: "last", label: "Poslední hodnota" },
  { id: "avg", label: "Průměr" },
];

const DATE_HINT = /(date|datum|period|obdobi|čas|cas|time|year|rok|month|měs|mes|quarter|qtr)/i;
const VALUE_HINT = /(value|hodnota|amount|qty|count|index|total|sum|cena|price)/i;

let _uidSeq = 0;
const nextUid = () => `cmp-${Date.now().toString(36)}-${(_uidSeq += 1)}`;

function normChartType(v) {
  const t = String(v || "line").toLowerCase();
  return CHART_TYPES.some((c) => c.id === t) ? t : "line";
}
function normYAxis(v) {
  return String(v || "left").toLowerCase() === "right" ? "right" : "left";
}

function normFreqAgg(v) {
  return String(v || "last").toLowerCase() === "avg" ? "avg" : "last";
}

/** Map a stored chart_compare_with entry back into an editable row. */
function incomingToRow(r, currentCatalog, currentSetId) {
  const base = {
    uid: nextUid(),
    chart_type: normChartType(r?.chart_type),
    y_axis: normYAxis(r?.y_axis),
    freq_agg: normFreqAgg(r?.freq_agg),
    name: String(r?.name || r?.label || "").trim(),
  };
  if (String(r?.user_upload_id || r?.upload_id || "").trim()) {
    return {
      ...base,
      kind: "upload",
      user_upload_id: String(r.user_upload_id || r.upload_id).trim(),
      x_field: String(r?.x_field || "").trim(),
      y_field: String(r?.y_field || "").trim(),
      cols: null,
    };
  }
  if (String(r?.computed_id || "").trim()) {
    return { ...base, kind: "computed", computed_id: String(r.computed_id).trim() };
  }
  return {
    ...base,
    kind: "catalog",
    catalog: String(r?.catalog || currentCatalog || "").trim().toLowerCase(),
    set_id: String(r?.set_id || currentSetId || "").trim(),
    selected_indicator: String(r?.selected_indicator || r?.indicator_id || "").trim(),
    name: base.name || String(r?.selected_indicator || r?.set_id || "").trim(),
  };
}

function guessUploadXY(cols) {
  const c = Array.isArray(cols) ? cols : [];
  const x = c.find((k) => DATE_HINT.test(k)) || c[0] || "date";
  const pool = c.filter((k) => k !== x);
  const y = pool.find((k) => VALUE_HINT.test(k)) || pool[0] || c[0] || "value";
  return { x, y };
}

function SourceBadge({ kind, catalog }) {
  const label =
    kind === "upload" ? "UPLOAD" : kind === "computed" ? "VÝPOČET" : String(catalog || "").toUpperCase();
  const Icon = kind === "upload" ? FileSpreadsheet : kind === "computed" ? Calculator : Database;
  return (
    <span className="inline-flex items-center gap-1 rounded bg-slate-100 px-1.5 py-0.5 text-[9px] font-semibold text-slate-600">
      <Icon className="h-3 w-3" aria-hidden />
      {label}
    </span>
  );
}

/**
 * Sjednocený výběr komparačních řad pro graf z katalogu (jakýkoliv zdroj):
 * vyhledávání v katalogu (eurostat/ECB/FRED/ČSÚ/ARAD…), vlastní uploady i uložené výpočty.
 * Backend (`resolve_external_catalog_chart_data`) zarovná periodicitu na společnou osu X.
 */
export default function UnifiedChartCompareModal({
  open,
  onClose,
  mainLabel = "",
  currentCatalog = "",
  currentSetId = "",
  initialCompareList,
  initialSearchQuery = "",
  initialPrimaryYAxis = "left",
  onSave,
  compositeAllowed = true,
  compositeMessage = "",
}) {
  const [rows, setRows] = useState([]);
  const [primaryYAxis, setPrimaryYAxis] = useState("left");
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");

  const [tab, setTab] = useState("catalog");
  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const [uploads, setUploads] = useState([]);
  const [computed, setComputed] = useState([]);
  const searchRef = useRef(null);

  useEffect(() => {
    if (!open) return;
    const raw = Array.isArray(initialCompareList) ? initialCompareList : [];
    setRows(raw.map((r) => incomingToRow(r, currentCatalog, currentSetId)));
    setPrimaryYAxis(normYAxis(initialPrimaryYAxis));
    setErr("");
    setQuery(String(initialSearchQuery || "").trim());
    setResults([]);
    setTab("catalog");
  }, [open, initialCompareList, initialSearchQuery, initialPrimaryYAxis, currentCatalog, currentSetId]);

  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    api.get("/me/uploads").then(({ data }) => {
      if (!cancelled) setUploads(Array.isArray(data) ? data : []);
    }).catch(() => {});
    api.get("/computed").then(({ data }) => {
      if (!cancelled) setComputed(Array.isArray(data) ? data : []);
    }).catch(() => {});
    return () => { cancelled = true; };
  }, [open]);

  useEffect(() => {
    if (!open || tab !== "catalog") return undefined;
    const q = query.trim();
    if (q.length < 2) {
      setResults([]);
      setSearching(false);
      return undefined;
    }
    let cancelled = false;
    setSearching(true);
    const t = setTimeout(() => {
      // Akcie (yahoo_finance/alphavantage) maji vlastni backend a jsou schvalne mimo
      // /catalog/suggest (usableForAiSearch: false) - bez samostatneho dotazu by "google"
      // v tomhle vyhledavani nikdy nenaslo Alphabet Inc., jen nesouvisejici FRED/Data360 shody.
      Promise.all([
        api.get("/catalog/suggest", { params: { q, limit: 14 } })
          .then(({ data }) => (Array.isArray(data?.suggestions) ? data.suggestions : []))
          .catch(() => []),
        runStockSearch(api, { query: q, limit: 6 })
          .then((r) => (Array.isArray(r?.results) ? r.results : []))
          .catch(() => []),
      ]).then(([catalogRows, stockRows]) => {
        if (cancelled) return;
        const stockSuggestions = stockRows.map((row) => ({
          source: String(row?.source || row?.catalog_id || row?.source_type || "yahoo_finance").toLowerCase(),
          set_id: String(row?.set_id || row?.ticker || "").trim(),
          indicator_id: "",
          title: String(row?.name || row?.ticker || "").trim(),
          full_path: String(row?.full_path || "").trim(),
        }));
        // Katalogové datové řady mají přednost před akciemi — u dotazů jako "HDP"/"inflace"
        // dřív akcie (Yahoo Finance) vytlačily relevantní řady na konec. Akcie zůstávají níže
        // pro tickerové dotazy ("google"), kde katalog nic nemá.
        setResults([...catalogRows, ...stockSuggestions]);
      }).finally(() => {
        if (!cancelled) setSearching(false);
      });
    }, 220);
    return () => { cancelled = true; clearTimeout(t); };
  }, [open, tab, query]);

  const atCap = rows.length >= MAX_UNIFIED_COMPARE;

  const updateRow = useCallback((uid, patch) => {
    setRows((prev) => prev.map((r) => (r.uid === uid ? { ...r, ...patch } : r)));
  }, []);
  const removeRow = useCallback((uid) => {
    setRows((prev) => prev.filter((r) => r.uid !== uid));
  }, []);

  const addCatalog = useCallback((s) => {
    setRows((prev) => {
      if (prev.length >= MAX_UNIFIED_COMPARE) return prev;
      return [
        ...prev,
        {
          uid: nextUid(),
          kind: "catalog",
          catalog: String(s.source || "").toLowerCase(),
          set_id: String(s.set_id || "").trim(),
          selected_indicator: String(s.indicator_id || "").trim(),
          name: String(s.title || s.set_id || "").trim(),
          chart_type: "line",
          y_axis: "left",
          freq_agg: "last",
        },
      ];
    });
  }, []);

  const addUpload = useCallback(async (u) => {
    const uid = nextUid();
    setRows((prev) => {
      if (prev.length >= MAX_UNIFIED_COMPARE) return prev;
      return [
        ...prev,
        {
          uid,
          kind: "upload",
          user_upload_id: u.id,
          x_field: "",
          y_field: "",
          name: u.original_name || u.id,
          chart_type: "line",
          y_axis: "left",
          freq_agg: "last",
          cols: null,
          loadingCols: true,
        },
      ];
    });
    try {
      const { data } = await api.get(`/me/uploads/${u.id}/preview`);
      const cols = Array.isArray(data?.columns) ? data.columns : [];
      const { x, y } = guessUploadXY(cols);
      setRows((prev) => prev.map((r) => (r.uid === uid ? { ...r, cols, x_field: x, y_field: y, loadingCols: false } : r)));
    } catch {
      setRows((prev) => prev.map((r) => (r.uid === uid ? { ...r, cols: [], loadingCols: false } : r)));
    }
  }, []);

  const addComputed = useCallback((c) => {
    setRows((prev) => {
      if (prev.length >= MAX_UNIFIED_COMPARE) return prev;
      return [
        ...prev,
        {
          uid: nextUid(),
          kind: "computed",
          computed_id: c.id,
          name: c.name || c.id,
          chart_type: "line",
          y_axis: "left",
          freq_agg: "last",
        },
      ];
    });
  }, []);

  const buildEntries = useCallback(() => {
    const out = [];
    for (const r of rows) {
      const common = {
        chart_type: normChartType(r.chart_type),
        y_axis: normYAxis(r.y_axis),
        freq_agg: normFreqAgg(r.freq_agg),
      };
      const nm = String(r.name || "").trim();
      if (r.kind === "upload") {
        if (!r.user_upload_id || !r.x_field || !r.y_field) continue;
        out.push({ ...common, user_upload_id: r.user_upload_id, x_field: r.x_field, y_field: r.y_field, ...(nm ? { name: nm } : {}) });
      } else if (r.kind === "computed") {
        if (!r.computed_id) continue;
        out.push({ ...common, computed_id: r.computed_id, ...(nm ? { name: nm } : {}) });
      } else {
        if (!r.set_id && !r.selected_indicator) continue;
        const e = { ...common, catalog: String(r.catalog || "").toLowerCase(), set_id: String(r.set_id || "").trim() };
        if (r.selected_indicator) e.selected_indicator = String(r.selected_indicator).trim();
        if (nm) e.name = nm;
        out.push(e);
      }
      if (out.length >= MAX_UNIFIED_COMPARE) break;
    }
    return out;
  }, [rows]);

  const handleSave = async () => {
    if (!compositeAllowed) return;
    setErr("");
    const entries = buildEntries();
    setSaving(true);
    try {
      await onSave({ chart_compare_with: entries, primary_y_axis: primaryYAxis === "right" ? "right" : "left" });
      onClose();
    } catch (e) {
      setErr(formatApiErrorFromAxios(e) || "Uložení se nepodařilo.");
    } finally {
      setSaving(false);
    }
  };

  const filteredUploads = useMemo(() => uploads.slice(0, 50), [uploads]);
  const filteredComputed = useMemo(() => computed.slice(0, 50), [computed]);

  if (!open) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-[260] flex items-end justify-center sm:items-center p-3 sm:p-6 bg-slate-900/45 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="unified-compare-title"
      onClick={(e) => { if (e.target === e.currentTarget && !saving) onClose(); }}
    >
      <div className="w-full max-w-2xl rounded-2xl border border-border/80 bg-white shadow-2xl flex flex-col max-h-[min(92vh,820px)]">
        <div className="flex items-start justify-between gap-3 px-4 py-3 border-b border-border/60 shrink-0">
          <div>
            <h2 id="unified-compare-title" className="text-base font-semibold text-slate-900">
              Porovnání v grafu — přidat řadu odkudkoliv
            </h2>
            <p className="text-[11px] text-slate-600 mt-0.5 leading-snug">
              Přidejte až {MAX_UNIFIED_COMPARE} řad z libovolného zdroje — katalog (eurostat, ČNB/ARAD, ECB, FRED, ČSÚ…),
              vlastní nahraná data nebo uložené výpočty. Různé periodicity se zarovnají na společnou osu X.
            </p>
            {mainLabel ? (
              <p className="text-[10px] text-slate-500 mt-1">Hlavní řada: <span className="font-medium">{mainLabel}</span></p>
            ) : null}
          </div>
          <button
            type="button"
            onClick={() => !saving && onClose()}
            className="h-8 w-8 shrink-0 rounded-lg border border-border/70 bg-white text-slate-600 hover:bg-slate-50 grid place-items-center"
            aria-label="Zavřít"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {!compositeAllowed ? (
          <div className="px-4 py-3">
            <p className="text-[11px] text-amber-900 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
              {compositeMessage || "Kombinace více řad je dostupná s předplatným."}
            </p>
          </div>
        ) : (
          <div className="flex flex-col min-h-0 flex-1">
            {/* Picker */}
            <div className="px-4 pt-3 shrink-0">
              <div className="flex items-center gap-1 mb-2">
                {[
                  { id: "catalog", label: "Katalog" },
                  { id: "upload", label: `Moje data${uploads.length ? ` (${uploads.length})` : ""}` },
                  { id: "computed", label: `Výpočty${computed.length ? ` (${computed.length})` : ""}` },
                ].map((tb) => (
                  <button
                    key={tb.id}
                    type="button"
                    onClick={() => setTab(tb.id)}
                    className={`px-2.5 h-7 text-[11px] rounded-md border ${
                      tab === tb.id
                        ? "border-[hsl(var(--primary))] bg-[hsl(var(--primary))]/10 text-[hsl(var(--primary))] font-medium"
                        : "border-border/70 bg-white text-slate-600 hover:bg-slate-50"
                    }`}
                  >
                    {tb.label}
                  </button>
                ))}
              </div>

              {tab === "catalog" ? (
                <div>
                  <div className="relative">
                    <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400" aria-hidden />
                    <input
                      ref={searchRef}
                      className="input w-full text-sm pl-7"
                      placeholder="Hledat ukazatel ve všech zdrojích… (např. inflace, HDP, sazba ČNB)"
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      disabled={atCap}
                    />
                  </div>
                  {query.trim().length >= 2 ? (
                    <div className="mt-1 max-h-44 overflow-y-auto rounded-lg border border-border/60 bg-white divide-y divide-border/40">
                      {searching ? (
                        <div className="px-3 py-2"><LoadingInline label="Hledám…" size="sm" /></div>
                      ) : results.length === 0 ? (
                        <div className="px-3 py-2 text-[11px] text-slate-500">Nic nenalezeno.</div>
                      ) : (
                        results.map((s, i) => (
                          <button
                            key={`${s.source}-${s.set_id}-${s.indicator_id || i}`}
                            type="button"
                            disabled={atCap}
                            onClick={() => addCatalog(s)}
                            className="w-full text-left px-3 py-1.5 hover:bg-slate-50 disabled:opacity-50 flex items-start gap-2"
                          >
                            <SourceBadge kind="catalog" catalog={s.source} />
                            <span className="min-w-0">
                              <span className="block text-xs text-slate-800 truncate">{s.title || s.set_id}</span>
                              {s.full_path ? <span className="block text-[9px] text-slate-400 truncate">{s.full_path}</span> : null}
                            </span>
                          </button>
                        ))
                      )}
                    </div>
                  ) : null}
                </div>
              ) : null}

              {tab === "upload" ? (
                <div className="max-h-44 overflow-y-auto rounded-lg border border-border/60 bg-white divide-y divide-border/40">
                  {filteredUploads.length === 0 ? (
                    <div className="px-3 py-2 text-[11px] text-slate-500">Žádné nahrané soubory.</div>
                  ) : (
                    filteredUploads.map((u) => (
                      <button
                        key={u.id}
                        type="button"
                        disabled={atCap}
                        onClick={() => void addUpload(u)}
                        className="w-full text-left px-3 py-1.5 hover:bg-slate-50 disabled:opacity-50 flex items-center gap-2"
                      >
                        <SourceBadge kind="upload" />
                        <span className="text-xs text-slate-800 truncate">{u.original_name || u.id}</span>
                      </button>
                    ))
                  )}
                </div>
              ) : null}

              {tab === "computed" ? (
                <div className="max-h-44 overflow-y-auto rounded-lg border border-border/60 bg-white divide-y divide-border/40">
                  {filteredComputed.length === 0 ? (
                    <div className="px-3 py-2 text-[11px] text-slate-500">Žádné uložené výpočty.</div>
                  ) : (
                    filteredComputed.map((c) => (
                      <button
                        key={c.id}
                        type="button"
                        disabled={atCap}
                        onClick={() => addComputed(c)}
                        className="w-full text-left px-3 py-1.5 hover:bg-slate-50 disabled:opacity-50 flex items-center gap-2"
                      >
                        <SourceBadge kind="computed" />
                        <span className="text-xs text-slate-800 truncate">{c.name || c.id}</span>
                      </button>
                    ))
                  )}
                </div>
              ) : null}
              {atCap ? (
                <p className="text-[10px] text-amber-700 mt-1">Dosažen limit {MAX_UNIFIED_COMPARE} řad.</p>
              ) : null}
            </div>

            {/* Selected rows */}
            <div className="overflow-y-auto px-4 py-3 space-y-2 flex-1 min-h-0">
              <div className="text-[11px] font-medium text-slate-700">Vybrané řady ({rows.length})</div>
              {rows.length === 0 ? (
                <p className="text-[11px] text-slate-500">Zatím nic. Vyberte řady výše.</p>
              ) : (
                rows.map((r) => (
                  <div key={r.uid} className="rounded-lg border border-border/70 bg-white p-2.5 space-y-2">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 min-w-0">
                        <SourceBadge kind={r.kind} catalog={r.catalog} />
                        <span className="text-xs text-slate-800 truncate">{r.name || r.set_id || r.user_upload_id || r.computed_id}</span>
                      </div>
                      <button type="button" onClick={() => removeRow(r.uid)} className="text-rose-600 hover:text-rose-700 shrink-0" aria-label="Odebrat">
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                    {r.kind === "upload" ? (
                      <div className="grid grid-cols-2 gap-2">
                        <label className="text-[10px] text-slate-600 space-y-0.5">
                          <span className="font-medium">Sloupec X</span>
                          {r.loadingCols ? (
                            <div className="text-[10px] text-slate-400 py-1">Načítám…</div>
                          ) : (
                            <select className="input w-full text-xs" value={r.x_field || ""} onChange={(e) => updateRow(r.uid, { x_field: e.target.value })}>
                              {(r.cols || []).map((c) => (<option key={c} value={c}>{c}</option>))}
                            </select>
                          )}
                        </label>
                        <label className="text-[10px] text-slate-600 space-y-0.5">
                          <span className="font-medium">Sloupec Y</span>
                          {r.loadingCols ? (
                            <div className="text-[10px] text-slate-400 py-1">Načítám…</div>
                          ) : (
                            <select className="input w-full text-xs" value={r.y_field || ""} onChange={(e) => updateRow(r.uid, { y_field: e.target.value })}>
                              {(r.cols || []).map((c) => (<option key={c} value={c}>{c}</option>))}
                            </select>
                          )}
                        </label>
                      </div>
                    ) : null}
                    <div className="grid grid-cols-3 gap-2">
                      <label className="text-[10px] text-slate-600 space-y-0.5">
                        <span className="font-medium">Typ v grafu</span>
                        <select className="input w-full text-xs" value={r.chart_type} onChange={(e) => updateRow(r.uid, { chart_type: e.target.value })}>
                          {CHART_TYPES.map((c) => (<option key={c.id} value={c.id}>{c.label}</option>))}
                        </select>
                      </label>
                      <label className="text-[10px] text-slate-600 space-y-0.5">
                        <span className="font-medium">Osa Y</span>
                        <select className="input w-full text-xs" value={r.y_axis} onChange={(e) => updateRow(r.uid, { y_axis: e.target.value })}>
                          <option value="left">Vlevo</option>
                          <option value="right">Vpravo</option>
                        </select>
                      </label>
                      <label className="text-[10px] text-slate-600 space-y-0.5">
                        <span className="font-medium">Konverze</span>
                        <select className="input w-full text-xs" value={r.freq_agg || "last"} onChange={(e) => updateRow(r.uid, { freq_agg: e.target.value })}>
                          {FREQ_AGG_OPTIONS.map((o) => (<option key={o.id} value={o.id}>{o.label}</option>))}
                        </select>
                      </label>
                    </div>
                  </div>
                ))
              )}
              {err ? <p className="text-[11px] text-rose-700">{err}</p> : null}
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 border-t border-border/60 shrink-0">
              <label className="inline-flex items-center gap-1.5 text-[10px] text-slate-600">
                <span>Hlavní řada — osa Y</span>
                <select className="input h-7 text-xs" value={primaryYAxis} onChange={(e) => setPrimaryYAxis(e.target.value)} disabled={saving}>
                  <option value="left">Vlevo</option>
                  <option value="right">Vpravo</option>
                </select>
              </label>
              <div className="flex items-center gap-2">
                <button type="button" onClick={onClose} disabled={saving} className="btn-secondary text-xs h-8 px-3">Zrušit</button>
                <button type="button" onClick={() => void handleSave()} disabled={saving} className="btn-primary text-xs h-8 px-3 inline-flex items-center gap-1.5">
                  {saving ? <LoadingSpinner suppressAria size="xs" aria-label="" /> : null}
                  Uložit a přenačíst graf
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>,
    document.body
  );
}
