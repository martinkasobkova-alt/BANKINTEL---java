/**
 * HeadlineKpiPicker — clean 3-tab indicator picker for Headline KPI configuration.
 *
 * Tabs:
 *  "arad"     — searchable ARAD / database source picker
 *  "mydata"   — user-uploaded file + column picker
 *  "computed" — saved computed indicator picker
 *
 * Props:
 *  onSelect(item)  — called with { title, type, config } when admin confirms
 *  disabled        — disables action buttons during save
 */
import React, { useState, useEffect, useRef, useCallback } from "react";
import {
  Search, Loader2, ChevronRight, Database, FolderOpen, Calculator,
  ArrowLeft, TrendingUp,
} from "lucide-react";
import {
  LineChart, Line, ResponsiveContainer, Tooltip as ReTooltip,
} from "recharts";
import api from "@/lib/api";
import PersonalCatalogChartForm from "@/components/myDashboard/PersonalCatalogChartForm";
import { indicatorSelectOptions, resolveIndicatorLabel } from "@/lib/indicatorLabels";

const TABS = [
  { id: "arad",     label: "Z databáze",   Icon: Database    },
  { id: "catalog",  label: "Z katalogu",   Icon: Search      },
  { id: "mydata",   label: "Moje data",    Icon: FolderOpen  },
  { id: "computed", label: "Výpočet",      Icon: Calculator  },
];

/** Short human-readable label for a source_type */
function sourceTypeLabel(t) {
  const map = {
    arad: "ARAD", eurostat: "Eurostat", csu: "ČSÚ", ecb: "ECB",
    fred: "FRED", alphavantage: "AlphaVantage", worldbank: "World Bank",
    world_bank: "World Bank", world_bank_data360: "World Bank",
    bis: "BIS", imf: "IMF", oecd: "OECD",
  };
  return map[String(t || "").toLowerCase()] || String(t || "").toUpperCase();
}

/* ── tiny sparkline ─────────────────────────────────────── */
function MiniSparkline({ points }) {
  if (!points || points.length < 2) return null;
  return (
    <div className="h-12 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={points}>
          <Line
            type="monotone"
            dataKey="value"
            dot={false}
            strokeWidth={2}
            stroke="var(--color-primary, #3b82f6)"
            isAnimationActive={false}
          />
          <ReTooltip
            contentStyle={{ fontSize: 10, padding: "2px 6px" }}
            formatter={(v) => [typeof v === "number" ? v.toLocaleString("cs-CZ", { maximumFractionDigits: 2 }) : v, ""]}
            labelFormatter={(l) => l}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

/* ── preview card shown after selection ─────────────────── */
function PreviewCard({ preview, onConfirm, onBack, disabled }) {
  const { loading, points, latest, period, title, item } = preview;
  return (
    <div className="space-y-3 rounded-xl border border-primary/30 bg-primary/5 p-3">
      <button
        type="button"
        onClick={onBack}
        className="flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
      >
        <ArrowLeft className="h-3 w-3" /> Zpět na výběr
      </button>

      <p className="text-xs font-semibold text-foreground leading-snug">{title}</p>

      {loading ? (
        <div className="flex items-center justify-center py-4">
          <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
        </div>
      ) : (
        <>
          {points && points.length > 0 ? (
            <MiniSparkline points={points} />
          ) : (
            <p className="text-[11px] text-muted-foreground text-center py-2">
              Náhled dat není k dispozici.
            </p>
          )}
          {latest != null && (
            <div className="flex items-end gap-2">
              <span className="text-2xl font-bold text-foreground leading-none">
                {typeof latest === "number"
                  ? latest.toLocaleString("cs-CZ", { maximumFractionDigits: 2 })
                  : latest}
              </span>
              {period && (
                <span className="text-[11px] text-muted-foreground mb-0.5">{period}</span>
              )}
            </div>
          )}
        </>
      )}

      <button
        type="button"
        disabled={disabled || loading}
        onClick={() => onConfirm(item)}
        className="w-full h-8 rounded-lg bg-primary text-primary-foreground text-xs font-semibold hover:bg-primary/90 transition-colors disabled:opacity-40 flex items-center justify-center gap-1.5"
      >
        <TrendingUp className="h-3.5 w-3.5" />
        Přidat jako KPI
      </button>
    </div>
  );
}

/* ── helpers to parse rows → [{period, value}] ──────────── */
function parseRows(rows) {
  if (!Array.isArray(rows) || rows.length === 0) return [];
  const sample = rows.find((r) => typeof r === "object" && r !== null) || {};
  const keys = Object.keys(sample);
  const periodKey = keys.find((k) => /^(period|date|rok|year|cas|datum|time)/i.test(k)) || keys[0];
  const valueKey  = keys.find((k) => /^(value|hodnota|val|amount|castka)/i.test(k) && k !== periodKey)
    || keys.find((k) => k !== periodKey);
  if (!periodKey || !valueKey) return [];
  return rows
    .map((r) => {
      const v = parseFloat(String(r[valueKey] ?? "").replace(",", "."));
      return isNaN(v) ? null : { period: String(r[periodKey] ?? ""), value: v };
    })
    .filter(Boolean);
}

export default function HeadlineKpiPicker({ onSelect, disabled }) {
  const [tab, setTab] = useState("arad");
  const [preview, setPreview] = useState(null); // PreviewCard state

  /* ── ARAD tab ─────────────────────────────────────────── */
  const [sources,     setSources]     = useState([]);
  const [sourceId,    setSourceId]    = useState("");
  const [indicators,  setIndicators]  = useState([]);
  const [indSearch,   setIndSearch]   = useState("");
  const [loadingInds, setLoadingInds] = useState(false);
  const prevSourceRef = useRef(null);

  /* ── My data tab ──────────────────────────────────────── */
  const [uploads,     setUploads]     = useState([]);
  const [loadingUpl,  setLoadingUpl]  = useState(false);
  const [uploadId,    setUploadId]    = useState("");
  const [columns,     setColumns]     = useState([]);
  const [xField,      setXField]      = useState("");
  const [yField,      setYField]      = useState("");
  const [loadingCols, setLoadingCols] = useState(false);
  const [uploadRows,  setUploadRows]  = useState([]);

  /* ── Computed tab ─────────────────────────────────────── */
  const [computed,        setComputed]        = useState([]);
  const [loadingComputed, setLoadingComputed] = useState(false);
  const [compSearch,      setCompSearch]      = useState("");

  /* load all catalog sources once */
  useEffect(() => {
    api.get("/sources/catalog-stubs")
      .then(({ data }) => {
        const stubs = Array.isArray(data) ? data : (data?.items || []);
        // All sources with indicators — sort: ARAD first, then alphabetically by type+name
        const sorted = [...stubs].sort((a, b) => {
          const ta = a.source_type || "";
          const tb = b.source_type || "";
          if (ta === "arad" && tb !== "arad") return -1;
          if (tb === "arad" && ta !== "arad") return 1;
          return ta.localeCompare(tb) || (a.name || "").localeCompare(b.name || "");
        });
        setSources(sorted);
      })
      .catch(() => {});
  }, []);

  /* load indicators when sourceId changes */
  useEffect(() => {
    if (!sourceId) { setIndicators([]); return; }
    if (sourceId === prevSourceRef.current) return;
    prevSourceRef.current = sourceId;
    setLoadingInds(true);
    setIndSearch("");
    setPreview(null);
    api.get(`/sources/${sourceId}/preview`, { params: { limit: 1 } })
      .then(({ data }) => setIndicators(Array.isArray(data?.indicators) ? data.indicators : []))
      .catch(() => setIndicators([]))
      .finally(() => setLoadingInds(false));
  }, [sourceId]);

  /* load uploads when switching to mydata tab */
  useEffect(() => {
    if (tab !== "mydata" || uploads.length > 0) return;
    setLoadingUpl(true);
    api.get("/me/uploads")
      .then(({ data }) => setUploads(Array.isArray(data) ? data : []))
      .catch(() => {})
      .finally(() => setLoadingUpl(false));
  }, [tab]); // eslint-disable-line react-hooks/exhaustive-deps

  /* load columns + preview rows when uploadId changes */
  useEffect(() => {
    if (!uploadId) { setColumns([]); setXField(""); setYField(""); setUploadRows([]); return; }
    setLoadingCols(true);
    api.get(`/me/uploads/${uploadId}/preview`)
      .then(({ data }) => {
        const cols = Array.isArray(data?.columns)
          ? data.columns
          : Object.keys((data?.rows || [{}])[0] || {});
        setColumns(cols);
        const dateCol = cols.find((c) => /dat|period|rok|year|mes|month|cas/i.test(c));
        const nonDate = cols.filter((c) => c !== (dateCol || ""));
        setXField(dateCol || cols[0] || "");
        setYField(nonDate[0] || cols[1] || "");
        setUploadRows(Array.isArray(data?.rows) ? data.rows : []);
      })
      .catch(() => setColumns([]))
      .finally(() => setLoadingCols(false));
  }, [uploadId]);

  /* load computed when tab = computed */
  useEffect(() => {
    if (tab !== "computed" || computed.length > 0) return;
    setLoadingComputed(true);
    api.get("/computed")
      .then(({ data }) => setComputed(Array.isArray(data) ? data : []))
      .catch(() => {})
      .finally(() => setLoadingComputed(false));
  }, [tab]); // eslint-disable-line react-hooks/exhaustive-deps

  /* reset preview when tab changes */
  useEffect(() => { setPreview(null); }, [tab]);

  /* ── open ARAD preview ───────────────────────────────── */
  const handleAradClick = useCallback((ind) => {
    const src = sources.find((s) => s.id === sourceId);
    // Map source_type to the engine widget type
    const typeMap = {
      arad: "arad_view", eurostat: "eurostat_view", csu: "csu_view",
      ecb: "ecb_view", fred: "fred_view", alphavantage: "alphavantage_view",
      alpha_vantage: "alphavantage_view", worldbank: "worldbank_view",
      world_bank: "worldbank_view", world_bank_data360: "world_bank_data360_view",
      bis: "bis_view", imf: "imf_view", oecd: "oecd_view",
    };
    const engineType = typeMap[String(src?.source_type || "").toLowerCase()] || "external_catalog_chart";
    const item = {
      title: indicatorLabelById[String(ind.id)] || resolveIndicatorLabel(ind),
      type: engineType,
      config: {
        source_id: sourceId,
        source_name: src?.name || "",
        indicator_id: String(ind.id || ""),
        indicator_name: ind.name || "",
        source_type: src?.source_type || "",
      },
    };
    setPreview({ loading: true, title: indicatorLabelById[String(ind.id)] || resolveIndicatorLabel(ind), item, points: null, latest: null, period: null });
    api.get(`/sources/${sourceId}/preview`, { params: { limit: 20, indicator_id: ind.id } })
      .then(({ data }) => {
        const pts = parseRows(data?.rows || []);
        const last = pts[pts.length - 1];
        setPreview((p) => ({
          ...p,
          loading: false,
          points: pts,
          latest: last?.value ?? null,
          period: last?.period ?? null,
        }));
      })
      .catch(() => setPreview((p) => ({ ...p, loading: false })));
  }, [sourceId, sources]);

  /* ── open Computed preview ───────────────────────────── */
  const handleComputedClick = useCallback((comp) => {
    const item = {
      title: comp.name,
      type: "computed_view",
      config: { computed_id: comp.id },
    };
    setPreview({ loading: true, title: comp.name, item, points: null, latest: null, period: null });
    api.get(`/computed/${comp.id}/run`)
      .then(({ data }) => {
        const rows = data?.rows || [];
        const pts = rows
          .map((r) => ({ period: String(r.period ?? ""), value: r.value }))
          .filter((r) => r.value != null);
        const last = pts[pts.length - 1];
        setPreview((p) => ({
          ...p,
          loading: false,
          points: pts.slice(-20),
          latest: last?.value ?? null,
          period: last?.period ?? null,
        }));
      })
      .catch(() => setPreview((p) => ({ ...p, loading: false })));
  }, []);

  /* ── My data confirm ─────────────────────────────────── */
  const handleMyDataConfirm = () => {
    if (!uploadId || !yField) return;
    const upload = uploads.find((u) => u.id === uploadId);
    const pts = parseRows(uploadRows.map((r) => ({ period: r[xField], value: r[yField] })));
    const last = pts[pts.length - 1];
    setPreview({
      loading: false,
      title: (upload?.original_name?.replace(/\.[^.]+$/, "") ?? "") + ` — ${yField}`,
      points: pts.slice(-20),
      latest: last?.value ?? null,
      period: last?.period ?? null,
      item: {
        title: (upload?.original_name?.replace(/\.[^.]+$/, "") ?? yField) + ` — ${yField}`,
        type: "user_upload_chart",
        config: { user_upload_id: uploadId, x_field: xField, y_field: yField },
      },
    });
  };

  /* ── filtered lists ──────────────────────────────────── */
  const filteredInds = indicators.filter((ind) => {
    if (!indSearch.trim()) return true;
    const q = indSearch.trim().toLowerCase();
    return (
      String(ind.name || "").toLowerCase().includes(q) ||
      String(ind.id   || "").toLowerCase().includes(q)
    );
  });
  // Popisky se rozliší, i když zdroj pošle u víc řad ten samý název (ARAD dává
  // často jen měnu) — jinak by v seznamu bylo dvacet položek „USD" a nešlo by vybrat.
  const indicatorLabelById = Object.fromEntries(
    indicatorSelectOptions(filteredInds).map(({ id, label }) => [id, label])
  );
  const filteredComputed = computed.filter((c) => {
    if (!compSearch.trim()) return true;
    const q = compSearch.trim().toLowerCase();
    return (
      String(c.name || "").toLowerCase().includes(q) ||
      String(c.operation || "").toLowerCase().includes(q)
    );
  });

  /* ── render ───────────────────────────────────────────── */
  return (
    <div className="space-y-3">
      {/* Tab bar */}
      <div className="flex rounded-lg border border-border overflow-hidden text-[11px] font-medium">
        {TABS.map(({ id, label, Icon }) => (
          <button
            key={id}
            type="button"
            onClick={() => setTab(id)}
            className={[
              "flex-1 flex items-center justify-center gap-1.5 px-2 py-2 transition-colors",
              tab === id
                ? "bg-primary text-primary-foreground"
                : "bg-card text-muted-foreground hover:text-foreground hover:bg-muted/50",
            ].join(" ")}
          >
            <Icon className="h-3 w-3 shrink-0" />
            {label}
          </button>
        ))}
      </div>

      {/* ── Preview card ──────────────────────────────────── */}
      {preview && (
        <PreviewCard
          preview={preview}
          onConfirm={onSelect}
          onBack={() => setPreview(null)}
          disabled={disabled}
        />
      )}

      {/* ── ARAD / database tab ──────────────────────────── */}
      {!preview && tab === "arad" && (
        <div className="space-y-2">
          <select
            value={sourceId}
            onChange={(e) => { setSourceId(e.target.value); prevSourceRef.current = null; }}
            className="w-full h-8 border border-border rounded-lg px-2.5 text-xs bg-card text-foreground focus:outline-none focus:ring-1 focus:ring-primary/50"
          >
            <option value="">— vyberte zdrojovou databázi —</option>
            {sources.map((s) => (
              <option key={s.id} value={s.id}>
                [{sourceTypeLabel(s.source_type)}] {s.name || s.id}
              </option>
            ))}
          </select>

          {sourceId && (
            <div className="relative">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3 w-3 text-muted-foreground pointer-events-none" />
              <input
                type="text"
                value={indSearch}
                onChange={(e) => setIndSearch(e.target.value)}
                placeholder="Hledat ukazatel podle názvu nebo kódu…"
                className="w-full h-8 border border-border rounded-lg pl-7 pr-3 text-xs bg-card text-foreground focus:outline-none focus:ring-1 focus:ring-primary/50"
              />
            </div>
          )}

          {loadingInds ? (
            <div className="flex items-center justify-center py-6">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            </div>
          ) : sourceId ? (
            <div className="max-h-52 overflow-y-auto rounded-lg border border-border divide-y divide-border/40 bg-card">
              {filteredInds.length === 0 && (
                <p className="text-xs text-muted-foreground text-center py-5">
                  {indSearch ? "Žádný ukazatel neodpovídá hledání." : "Tento zdroj neobsahuje ukazatele."}
                </p>
              )}
              {filteredInds.slice(0, 150).map((ind) => (
                <button
                  key={ind.id}
                  type="button"
                  disabled={disabled}
                  onClick={() => handleAradClick(ind)}
                  className="w-full flex items-center justify-between gap-2 px-3 py-2 text-left hover:bg-primary/5 active:bg-primary/10 transition-colors group"
                >
                  <div className="min-w-0">
                    <p className="text-xs font-medium text-foreground truncate leading-snug">
                      {indicatorLabelById[String(ind.id)] || resolveIndicatorLabel(ind)}
                    </p>
                    {ind.name && String(ind.id) !== ind.name && (
                      <p className="text-[10px] text-muted-foreground truncate font-mono">
                        {ind.id}
                      </p>
                    )}
                  </div>
                  <ChevronRight className="h-3.5 w-3.5 text-muted-foreground group-hover:text-primary shrink-0 transition-colors" />
                </button>
              ))}
              {filteredInds.length > 150 && (
                <p className="text-[10px] text-muted-foreground text-center py-2 px-3">
                  Zobrazeno 150 z {filteredInds.length} výsledků — upřesněte hledání.
                </p>
              )}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground text-center py-4">
              Vyberte zdrojovou databázi pro zobrazení dostupných ukazatelů.
            </p>
          )}
        </div>
      )}

      {/* ── My data tab ──────────────────────────────────── */}
      {!preview && tab === "mydata" && (
        <div className="space-y-2">
          {loadingUpl ? (
            <div className="flex items-center justify-center py-6">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            </div>
          ) : uploads.length === 0 ? (
            <p className="text-xs text-muted-foreground text-center py-5">
              Nemáte žádné nahrané datové soubory.
            </p>
          ) : (
            <>
              <select
                value={uploadId}
                onChange={(e) => setUploadId(e.target.value)}
                className="w-full h-8 border border-border rounded-lg px-2.5 text-xs bg-card text-foreground focus:outline-none focus:ring-1 focus:ring-primary/50"
              >
                <option value="">— vyberte soubor —</option>
                {uploads.map((u) => (
                  <option key={u.id} value={u.id}>{u.original_name || u.id}</option>
                ))}
              </select>

              {loadingCols && (
                <div className="flex justify-center py-3">
                  <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                </div>
              )}

              {uploadId && !loadingCols && columns.length > 0 && (
                <div className="space-y-2 rounded-lg border border-border/60 bg-muted/10 p-3">
                  <div className="flex gap-2">
                    <div className="flex-1">
                      <label className="text-[10px] text-muted-foreground mb-1 block font-medium">
                        Osa X — datum / období
                      </label>
                      <select
                        value={xField}
                        onChange={(e) => setXField(e.target.value)}
                        className="w-full h-7 border border-border rounded-md px-1.5 text-xs bg-card text-foreground focus:outline-none"
                      >
                        {columns.map((c) => <option key={c} value={c}>{c}</option>)}
                      </select>
                    </div>
                    <div className="flex-1">
                      <label className="text-[10px] text-muted-foreground mb-1 block font-medium">
                        Hodnota — osa Y (KPI)
                      </label>
                      <select
                        value={yField}
                        onChange={(e) => setYField(e.target.value)}
                        className="w-full h-7 border border-border rounded-md px-1.5 text-xs bg-card text-foreground focus:outline-none"
                      >
                        {columns.filter((c) => c !== xField).map((c) => (
                          <option key={c} value={c}>{c}</option>
                        ))}
                      </select>
                    </div>
                  </div>
                  <button
                    type="button"
                    disabled={disabled || !yField}
                    onClick={handleMyDataConfirm}
                    className="w-full h-8 rounded-lg bg-muted text-foreground text-xs font-medium border border-border hover:bg-primary/10 hover:text-primary transition-colors disabled:opacity-40 flex items-center justify-center gap-1.5"
                  >
                    <Search className="h-3.5 w-3.5" />
                    Zobrazit náhled
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* ── Computed indicators tab ──────────────────────── */}
      {!preview && tab === "catalog" && (
        <div className="space-y-2">
          <p className="text-[11px] text-muted-foreground">
            Vyberte řadu z katalogu. Hodnota se uloží teď a na dlaždici bude vidět, ke kterému
            období platí.
          </p>
          <PersonalCatalogChartForm
            kpiMode
            onApply={({ title, config }) => {
              // Config nese `chart_primary_snapshot`, takže se přehled otevře bez volání katalogu.
              const item = { title, type: "external_catalog_chart", config };
              const rows = Array.isArray(config?.chart_primary_snapshot?.rows)
                ? config.chart_primary_snapshot.rows
                : [];
              const pts = parseRows(rows);
              const last = pts[pts.length - 1];
              setPreview({
                loading: false,
                title,
                item,
                points: pts.slice(-20),
                latest: last?.value ?? null,
                period: last?.period ?? null,
              });
            }}
          />
        </div>
      )}

      {!preview && tab === "computed" && (
        <div className="space-y-2">
          {loadingComputed ? (
            <div className="flex items-center justify-center py-6">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            </div>
          ) : computed.length === 0 ? (
            <p className="text-xs text-muted-foreground text-center py-5">
              Nejsou k dispozici žádné uložené výpočty.
            </p>
          ) : (
            <>
              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3 w-3 text-muted-foreground pointer-events-none" />
                <input
                  type="text"
                  value={compSearch}
                  onChange={(e) => setCompSearch(e.target.value)}
                  placeholder="Hledat výpočet…"
                  className="w-full h-8 border border-border rounded-lg pl-7 pr-3 text-xs bg-card text-foreground focus:outline-none focus:ring-1 focus:ring-primary/50"
                />
              </div>
              <div className="max-h-52 overflow-y-auto rounded-lg border border-border divide-y divide-border/40 bg-card">
                {filteredComputed.length === 0 && (
                  <p className="text-xs text-muted-foreground text-center py-5">
                    Žádný výpočet neodpovídá hledání.
                  </p>
                )}
                {filteredComputed.map((comp) => (
                  <button
                    key={comp.id}
                    type="button"
                    disabled={disabled}
                    onClick={() => handleComputedClick(comp)}
                    className="w-full flex items-center justify-between gap-2 px-3 py-2 text-left hover:bg-primary/5 active:bg-primary/10 transition-colors group"
                  >
                    <div className="min-w-0">
                      <p className="text-xs font-medium text-foreground truncate leading-snug">
                        {comp.name}
                      </p>
                      {comp.operation && (
                        <p className="text-[10px] text-muted-foreground capitalize">
                          {comp.operation}
                        </p>
                      )}
                    </div>
                    <ChevronRight className="h-3.5 w-3.5 text-muted-foreground group-hover:text-primary shrink-0 transition-colors" />
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
