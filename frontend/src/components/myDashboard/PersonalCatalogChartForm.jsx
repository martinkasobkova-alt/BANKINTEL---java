import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Plus, X as XIcon, Loader2, ChevronRight, ChevronDown, Folder, FileBarChart2 } from "lucide-react";
import {
  Bar, BarChart, CartesianGrid, Cell, Line, LineChart, ResponsiveContainer, Tooltip as ReTooltip, XAxis, YAxis,
} from "recharts";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import {
  buildExternalCatalogChartConfig,
  buildPersonalChartConfigFromPreview,
} from "@/lib/catalogPersonalDashboard";
import { CATALOGS, WB_DEFAULT_COUNTRY } from "@/lib/catalogDefinitions";
import { buildSourceByKey, rowExistingKey } from "@/lib/catalogStubKeys";
import { buildCatalogAddSourceBody } from "@/lib/catalogAddSourceBody";
import { buildCatalogPreviewBody } from "@/lib/catalogPreviewBody";
import {
  flattenCatalogCategoriesBestEffort,
  parseSearchKeywords,
  buildFilteredPaths,
  buildPathIndex,
  MAX_CATALOG_FILTER_ROWS,
} from "@/lib/catalogTree";
import { LoadingInline } from "@/components/ui/loading";
import { CHART_KINDS } from "@/lib/chartKindCatalog";

const LIMIT_PRESETS = [
  { value: 0, label: "Všechna dostupná data" },
  { value: 200, label: "Posledních ~200 bodů" },
  { value: 80, label: "Posledních ~80 bodů" },
  { value: 40, label: "Posledních ~40 bodů" },
];

const CHART_TYPES = CHART_KINDS.map(({ id, label }) => ({ id, label }));

const BAR_ORIENTATIONS = [
  { id: "vertical", label: "Svislé sloupce" },
  { id: "horizontal", label: "Vodorovné pruhy" },
];

const PIE_VARIANTS = [
  { id: "donut", label: "Kolečko" },
  { id: "full", label: "Plný koláč" },
];

const PREVIEW_COLORS = ["#1f8cdb", "#2563eb", "#60a5fa", "#14b8a6", "#8b5cf6", "#ec4899", "#f97316", "#84cc16"];

function useDebounced(value, ms) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

/** Guess x / y field names from the available fields + first data row. */
function guessXY(fields = [], rows = []) {
  const lower = fields.map((f) => String(f).toLowerCase());
  const xIdx = lower.findIndex((f) => /^(date|time|period|obs_time|timestamp|TIME_PERIOD|TIME|month|year)$/i.test(f));
  const yIdx = lower.findIndex((f) => /^(value|values|obs_value|amount|OBS_VALUE|val)$/i.test(f));
  let x = xIdx >= 0 ? fields[xIdx] : fields[0] || null;
  let y = yIdx >= 0 ? fields[yIdx] : null;
  if (!y) {
    const row = rows[0];
    y = fields.find((f) => f !== x && row && !Number.isNaN(Number(String(row[f] ?? "").replace(",", "."))));
    if (!y && fields.length > 1) y = fields.find((f) => f !== x) || null;
  }
  return { x, y };
}

function periodSortValue(value) {
  const s = String(value ?? "").trim();
  if (!s) return 0;
  const compact = s.match(/^(\d{4})(\d{2})(\d{2})$/);
  if (compact) return Number(`${compact[1]}${compact[2]}${compact[3]}`);
  const ym = s.match(/^(\d{4})[-/.](\d{1,2})$/);
  if (ym) return Number(`${ym[1]}${String(ym[2]).padStart(2, "0")}00`);
  const yq = s.match(/^(\d{4})[- ]?Q([1-4])$/i) || s.match(/^Q([1-4])[- ]?(\d{4})$/i);
  if (yq) {
    const year = yq[2]?.length === 4 ? yq[2] : yq[1];
    const q = yq[2]?.length === 4 ? yq[1] : yq[2];
    return Number(`${year}${String(Number(q) * 3).padStart(2, "0")}00`);
  }
  const y = s.match(/^(\d{4})$/);
  if (y) return Number(`${y[1]}0000`);
  const t = Date.parse(s);
  if (!Number.isNaN(t)) return t;
  return s.toLowerCase();
}

function comparePeriods(a, b) {
  const av = periodSortValue(a);
  const bv = periodSortValue(b);
  if (typeof av === "number" && typeof bv === "number") return av - bv;
  return String(av).localeCompare(String(bv), "cs");
}

function normalizeDimToken(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-zA-Z0-9]/g, "")
    .toLowerCase();
}

function normalizeSeriesIds(input) {
  if (!Array.isArray(input)) return [];
  const out = [];
  const seen = new Set();
  for (const raw of input) {
    const id = String(raw ?? "").trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}

function groupFieldLabel(field) {
  const gf = String(field || "").trim().toLowerCase();
  if (gf === "geo" || gf === "ref_area" || /country|state|zem/.test(gf)) return "země";
  if (/kraj|region/.test(gf)) return "regiony";
  if (/indicator|ukazatel|typ|category/.test(gf)) return "ukazatele";
  return "řady";
}

function pickSplitDimension(extraDims = [], isCsuSource = false) {
  const candidates = extraDims
    .filter((dim) => dim?.field)
    .filter((dim) => !Array.isArray(dim.values) || dim.values.length !== 1);
  const regional = candidates.find((dim) => {
    const t = normalizeDimToken(dim.field);
    return t.includes("kraj") || t.includes("region") || t.includes("uzemikraj");
  });
  return regional || candidates[0] || (isCsuSource ? { field: "ÚZEMÍ-KRAJ", values: [] } : null);
}

/** Mini preview of catalog data shown after loading. */
function CatalogDataPreview({ rows, fields, groupField, selectedIndicator, dataMode = "history", sortOrder = "source", seriesLabels = {} }) {
  const pts = useMemo(() => {
    if (!Array.isArray(rows) || !rows.length) return [];
    let data = rows;
    if (groupField && selectedIndicator) {
      data = rows.filter((r) => String(r[groupField] ?? "") === String(selectedIndicator));
    }
    if (!data.length) data = rows;
    const { x, y } = guessXY(fields || [], data);
    if (!x || !y) return [];
    const ordered = [...data].sort((a, b) => comparePeriods(a?.[x], b?.[x]));
    const pts = ordered.map((r) => {
      const xv = r[x];
      const raw = String(r[y] ?? "").replace(",", ".");
      const yv = Number(raw);
      return { period: xv, value: Number.isFinite(yv) ? yv : null };
    }).filter((p) => p.value != null);
    return pts;
  }, [rows, fields, groupField, selectedIndicator]);

  const latestPts = useMemo(() => {
    if (dataMode !== "latest" || !Array.isArray(rows) || !rows.length || !groupField || selectedIndicator) return [];
    const { x, y } = guessXY(fields || [], rows);
    if (!x || !y) return [];
    const byGroup = new Map();
    for (const row of rows) {
      const id = String(row[groupField] ?? "").trim();
      if (!id) continue;
      if (!byGroup.has(id)) byGroup.set(id, []);
      byGroup.get(id).push(row);
    }
    const out = [];
    for (const [id, groupRows] of byGroup.entries()) {
      const ordered = [...groupRows].sort((a, b) => comparePeriods(a?.[x], b?.[x]));
      const latest = ordered[ordered.length - 1];
      const val = Number(String(latest?.[y] ?? "").replace(",", "."));
      if (!Number.isFinite(val)) continue;
      out.push({
        id,
        label: seriesLabels[id] || id,
        period: latest?.[x],
        value: val,
      });
    }
    if (sortOrder === "asc") out.sort((a, b) => a.value - b.value);
    else if (sortOrder === "desc") out.sort((a, b) => b.value - a.value);
    else out.sort((a, b) => a.label.localeCompare(b.label, "cs"));
    return out;
  }, [dataMode, rows, fields, groupField, selectedIndicator, sortOrder, seriesLabels]);

  const historySeriesPreview = useMemo(() => {
    if (dataMode !== "history" || !Array.isArray(rows) || !rows.length || !groupField || selectedIndicator) return null;
    const { x, y } = guessXY(fields || [], rows);
    if (!x || !y) return null;
    const byGroup = new Map();
    for (const row of rows) {
      const id = String(row[groupField] ?? "").trim();
      if (!id) continue;
      if (!byGroup.has(id)) byGroup.set(id, []);
      byGroup.get(id).push(row);
    }
    const groups = [...byGroup.entries()]
      .map(([id, groupRows]) => ({ id, label: seriesLabels[id] || id, rows: groupRows }))
      .filter((g) => g.rows.length >= 2)
      .sort((a, b) => a.label.localeCompare(b.label, "cs"));
    if (groups.length < 2) return null;

    const shown = groups.slice(0, 8);
    const periods = [...new Set(shown.flatMap((g) => g.rows.map((r) => String(r[x] ?? "").trim()).filter(Boolean)))]
      .sort(comparePeriods);
    if (periods.length < 2) return null;

    const periodSet = new Set(periods);
    const valueMaps = shown.map((g) => {
      const m = new Map();
      for (const row of g.rows) {
        const p = String(row[x] ?? "").trim();
        if (!periodSet.has(p)) continue;
        const v = Number(String(row[y] ?? "").replace(",", "."));
        if (Number.isFinite(v)) m.set(p, v);
      }
      return m;
    });
    const chartRows = periods.map((period) => {
      const out = { period };
      valueMaps.forEach((m, idx) => {
        if (m.has(period)) out[`s${idx}`] = m.get(period);
      });
      return out;
    });
    return { rows: chartRows, series: shown, total: groups.length };
  }, [dataMode, rows, fields, groupField, selectedIndicator, seriesLabels]);

  if (dataMode === "latest" && latestPts.length) {
    const latestPeriod = latestPts.find((p) => p.period)?.period;
    return (
      <div className="rounded-lg border border-emerald-200 bg-emerald-50/60 p-2 space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <span className="text-[10px] uppercase tracking-wider font-semibold text-emerald-700">Náhled posledních údajů</span>
          <span className="text-[10px] text-muted-foreground">{latestPeriod ? `poslední: ${latestPeriod}` : ""}</span>
        </div>
        <div className="h-24">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={latestPts} margin={{ top: 4, right: 4, left: 0, bottom: 8 }}>
              <CartesianGrid vertical={false} strokeDasharray="2 4" opacity={0.45} />
              <XAxis dataKey="label" hide />
              <YAxis hide />
              <ReTooltip
                contentStyle={{ fontSize: 10, padding: "2px 6px" }}
                formatter={(v) => [typeof v === "number" ? v.toLocaleString("cs-CZ", { maximumFractionDigits: 2 }) : v, "hodnota"]}
                labelFormatter={(l) => String(l)}
              />
              <Bar dataKey="value" radius={[4, 4, 0, 0]}>
                {latestPts.map((_, i) => (
                  <Cell key={i} fill={PREVIEW_COLORS[i % PREVIEW_COLORS.length]} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
        <div className="flex flex-wrap gap-x-2 gap-y-1 text-[10px] text-slate-600">
          {latestPts.slice(0, 12).map((p, i) => (
            <span key={p.id} className="inline-flex items-center gap-1 min-w-0">
              <span className="h-2 w-2 rounded-sm shrink-0" style={{ backgroundColor: PREVIEW_COLORS[i % PREVIEW_COLORS.length] }} />
              <span className="max-w-[5rem] truncate" title={p.label}>{p.label}</span>
            </span>
          ))}
          {latestPts.length > 12 ? <span className="text-slate-400">+{latestPts.length - 12}</span> : null}
        </div>
      </div>
    );
  }

  if (historySeriesPreview?.rows?.length) {
    return (
      <div className="rounded-lg border border-emerald-200 bg-emerald-50/60 p-2 space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <span className="text-[10px] uppercase tracking-wider font-semibold text-emerald-700">Náhled historie více řad</span>
          <span className="text-[10px] text-muted-foreground">
            {historySeriesPreview.rows.length} období · {historySeriesPreview.total} řad
          </span>
        </div>
        <div className="h-24">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={historySeriesPreview.rows} margin={{ top: 4, right: 4, left: 4, bottom: 2 }}>
              <XAxis dataKey="period" hide />
              <ReTooltip
                contentStyle={{ fontSize: 10, padding: "2px 6px" }}
                formatter={(v, name) => [
                  typeof v === "number" ? v.toLocaleString("cs-CZ", { maximumFractionDigits: 2 }) : v,
                  String(name || ""),
                ]}
                labelFormatter={(l) => String(l)}
              />
              {historySeriesPreview.series.map((s, idx) => (
                <Line
                  key={s.id}
                  type="monotone"
                  dataKey={`s${idx}`}
                  name={s.label}
                  dot={false}
                  strokeWidth={1.7}
                  stroke={PREVIEW_COLORS[idx % PREVIEW_COLORS.length]}
                  isAnimationActive={false}
                  connectNulls
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </div>
        <div className="flex flex-wrap gap-x-2 gap-y-1 text-[10px] text-slate-600">
          {historySeriesPreview.series.map((s, i) => (
            <span key={s.id} className="inline-flex items-center gap-1 min-w-0">
              <span className="h-2 w-2 rounded-sm shrink-0" style={{ backgroundColor: PREVIEW_COLORS[i % PREVIEW_COLORS.length] }} />
              <span className="max-w-[5rem] truncate" title={s.label}>{s.label}</span>
            </span>
          ))}
          {historySeriesPreview.total > historySeriesPreview.series.length ? (
            <span className="text-slate-400">+{historySeriesPreview.total - historySeriesPreview.series.length} dalších</span>
          ) : null}
        </div>
      </div>
    );
  }

  if (pts.length < 2) return null;

  const latest = pts[pts.length - 1];
  const latestFmt = typeof latest?.value === "number"
    ? latest.value.toLocaleString("cs-CZ", { maximumFractionDigits: 2 })
    : "—";

  return (
    <div className="rounded-lg border border-emerald-200 bg-emerald-50/60 p-2 space-y-1.5">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[10px] uppercase tracking-wider font-semibold text-emerald-700">Náhled dat</span>
        <span className="text-xs font-bold text-foreground">{latestFmt}</span>
      </div>
      <div className="h-20">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={pts} margin={{ top: 2, right: 4, left: 4, bottom: 2 }}>
            <XAxis dataKey="period" hide />
            <ReTooltip
              contentStyle={{ fontSize: 10, padding: "2px 6px" }}
              formatter={(v) => [typeof v === "number" ? v.toLocaleString("cs-CZ", { maximumFractionDigits: 2 }) : v, ""]}
              labelFormatter={(l) => String(l)}
            />
            <Line
              type="monotone"
              dataKey="value"
              dot={false}
              strokeWidth={2}
              stroke="hsl(var(--primary, 215 85% 50%))"
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <div className="text-[10px] text-muted-foreground text-right">{pts.length} bodů · poslední: {String(latest?.period ?? "")}</div>
    </div>
  );
}

function CatalogCompareEntryRow({
  indicators,
  compareSets = [],
  catalogDef,
  wbCountry,
  currentSetId,
  entry,
  onChange,
  onRemove,
  disabled,
}) {
  const [rowIndicators, setRowIndicators] = useState([]);
  const [loadingInds, setLoadingInds] = useState(false);
  const effectiveSetId = String(entry.set_id || currentSetId || "").trim();
  const canPickSet = Array.isArray(compareSets) && compareSets.length > 0;
  const selectedSet = canPickSet
    ? compareSets.find((row) => String(row.set_id || "") === effectiveSetId)
    : null;
  const activeIndicators = entry.set_id && rowIndicators.length ? rowIndicators : indicators;

  useEffect(() => {
    if (!canPickSet || !entry.set_id || !selectedSet?.set_id || !catalogDef) {
      setRowIndicators([]);
      return undefined;
    }
    let cancelled = false;
    setLoadingInds(true);
    const body = buildCatalogPreviewBody(catalogDef, selectedSet, wbCountry);
    api.post("/catalog/preview", body)
      .then(({ data }) => {
        if (cancelled) return;
        const inds = Array.isArray(data?.indicators) ? data.indicators : [];
        setRowIndicators(inds);
        if (inds.length && !entry.selected_indicator) {
          const first = inds[0];
          onChange({
            ...entry,
            selected_indicator: String(first.id || ""),
            name: first.name || selectedSet.name || first.id || "",
          });
        }
      })
      .catch(() => {
        if (!cancelled) setRowIndicators([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingInds(false);
      });
    return () => {
      cancelled = true;
    };
  }, [canPickSet, entry.set_id, selectedSet?.set_id, catalogDef, wbCountry]); // eslint-disable-line react-hooks/exhaustive-deps

  const onSetChange = (setId) => {
    const nextSetId = String(setId || "").trim();
    const nextSet = compareSets.find((row) => String(row.set_id || "") === nextSetId);
    onChange({
      ...entry,
      set_id: nextSetId && nextSetId !== String(currentSetId || "") ? nextSetId : "",
      selected_indicator: "",
      name: nextSet?.name || "",
    });
  };

  const onIndicatorChange = (indicatorId) => {
    const id = String(indicatorId || "").trim();
    const ind = activeIndicators.find((item) => String(item.id) === id);
    onChange({
      ...entry,
      selected_indicator: id,
      name: ind?.name || entry.name || id,
    });
  };

  return (
    <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-border/50 bg-card p-2">
      {canPickSet ? (
        <select
          value={effectiveSetId}
          onChange={(ev) => onSetChange(ev.target.value)}
          disabled={disabled}
          className="h-7 border border-border rounded-md px-1.5 text-xs bg-card text-foreground flex-[2_1_220px] min-w-[180px]"
          title="Vybrat jinou sadu z katalogu"
        >
          {currentSetId ? <option value={currentSetId}>Aktuální sada</option> : null}
          {compareSets.map((row) => (
            <option key={row.path || row.set_id} value={String(row.set_id || "")}>
              {row.name || row.set_id}
              {row.parentPath ? ` · ${row.parentPath}` : ""}
            </option>
          ))}
        </select>
      ) : null}
      {loadingInds ? <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground shrink-0" /> : null}
      {!loadingInds && activeIndicators.length > 0 ? (
        <select
          value={entry.selected_indicator}
          onChange={(ev) => onIndicatorChange(ev.target.value)}
          disabled={disabled}
          className="h-7 border border-border rounded-md px-1.5 text-xs bg-card text-foreground flex-1 min-w-[140px]"
        >
          <option value="">— ukazatel —</option>
          {activeIndicators.map((ind) => (
            <option key={ind.id} value={String(ind.id)}>{ind.name || ind.id}</option>
          ))}
        </select>
      ) : (
        <input
          type="text"
          value={entry.selected_indicator}
          onChange={(ev) => onChange({ ...entry, selected_indicator: ev.target.value, name: entry.name || ev.target.value })}
          disabled={disabled || loadingInds}
          placeholder={entry.set_id ? "volitelný kód ukazatele" : "kód ukazatele"}
          className="h-7 border border-border rounded-md px-1.5 text-xs bg-card flex-1 min-w-[120px]"
        />
      )}
      <select
        value={entry.chart_type}
        onChange={(ev) => onChange({ ...entry, chart_type: ev.target.value })}
        disabled={disabled}
        className="h-7 border border-border rounded-md px-1.5 text-xs bg-card text-foreground w-20"
      >
        <option value="line">Čára</option>
        <option value="bar">Sloupce</option>
        <option value="area">Plocha</option>
      </select>
      <button type="button" onClick={onRemove} disabled={disabled}
        className="h-6 w-6 flex items-center justify-center rounded-full hover:bg-muted/60 text-muted-foreground shrink-0">
        <XIcon className="h-3 w-3" />
      </button>
    </div>
  );
}

/** Additional compare-series rows for external catalog multi-series at creation time. */
function ExtCompareSection({ indicators, entries, onChange, disabled, compareSets = [], catalogDef, wbCountry, currentSetId }) {
  const canAdd = entries.length < 5;
  const addEntry = () => onChange([
    ...entries,
    { id: `ext-${Date.now()}`, set_id: "", selected_indicator: "", chart_type: "line" },
  ]);
  const updateEntry = (id, patch) => onChange(entries.map((e) => e.id === id ? { ...e, ...patch } : e));
  const removeEntry = (id) => onChange(entries.filter((e) => e.id !== id));
  const canPickSet = Array.isArray(compareSets) && compareSets.length > 0;

  return (
    <div className="space-y-1.5 border-t border-border/40 pt-2">
      <div className="flex items-center justify-between">
        <span className="text-[11px] font-medium text-slate-600">
          Srovnávací řady — složený graf
        </span>
        {canAdd && (
          <button
            type="button"
            disabled={disabled}
            onClick={addEntry}
            className="flex items-center gap-1 h-6 px-2 text-[10px] rounded-full border border-primary/40 text-primary hover:bg-primary/5 transition-colors disabled:opacity-40"
          >
            <Plus className="h-3 w-3" /> Přidat řadu
          </button>
        )}
      </div>
      {entries.length === 0 ? (
        <p className="text-[10px] text-muted-foreground">
          {canPickSet
            ? "Klikněte + Přidat řadu a vyberte ukazatel klidně z jiné části ARAD katalogu."
            : indicators.length > 0
              ? "Klikněte + Přidat řadu pro zobrazení více ukazatelů v jednom grafu."
              : "Přidejte srovnávací řadu ze stejného datasetu."}
        </p>
      ) : (
        <div className="space-y-1.5">
          {entries.map((e) => (
            <CatalogCompareEntryRow
              key={e.id}
              indicators={indicators}
              compareSets={compareSets}
              catalogDef={catalogDef}
              wbCountry={wbCountry}
              currentSetId={currentSetId}
              entry={e}
              onChange={(updated) => updateEntry(e.id, updated)}
              onRemove={() => removeEntry(e.id)}
              disabled={disabled}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/** Single row in the ARAD comparison section. Loads indicators lazily per source. */
function AradCompareEntryRow({ aradSources, entry, onChange, onRemove }) {
  const [indicators, setIndicators] = useState([]);
  const [loadingInds, setLoadingInds] = useState(false);
  const prevSourceId = useRef(null);

  useEffect(() => {
    if (!entry.source_id || entry.source_id === prevSourceId.current) return;
    prevSourceId.current = entry.source_id;
    setLoadingInds(true);
    api.get(`/sources/${entry.source_id}/preview`, { params: { limit: 1 } })
      .then(({ data }) => {
        const inds = Array.isArray(data?.indicators) ? data.indicators : [];
        setIndicators(inds);
        if (inds.length && !entry.indicator_id) {
          onChange({ ...entry, indicator_id: String(inds[0].id || "") });
        }
      })
      .catch(() => setIndicators([]))
      .finally(() => setLoadingInds(false));
  }, [entry.source_id]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-border/50 bg-card p-2">
      <select
        value={entry.source_id}
        onChange={(e) => onChange({ ...entry, source_id: e.target.value, indicator_id: "" })}
        className="h-7 border border-border rounded-md px-1.5 text-xs bg-card text-foreground flex-1 min-w-[110px]"
      >
        <option value="">— zdroj —</option>
        {aradSources.map((s) => (
          <option key={s.id} value={s.id}>{s.name || s.id}</option>
        ))}
      </select>
      {loadingInds && <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground shrink-0" />}
      {!loadingInds && indicators.length > 0 && (
        <select
          value={entry.indicator_id}
          onChange={(e) => onChange({ ...entry, indicator_id: e.target.value })}
          className="h-7 border border-border rounded-md px-1.5 text-xs bg-card text-foreground flex-1 min-w-[130px]"
        >
          <option value="">— ukazatel —</option>
          {indicators.map((ind) => (
            <option key={ind.id} value={String(ind.id)}>{ind.name || ind.id}</option>
          ))}
        </select>
      )}
      {!loadingInds && !indicators.length && entry.source_id && (
        <input
          type="text"
          placeholder="ID ukazatele…"
          value={entry.indicator_id}
          onChange={(e) => onChange({ ...entry, indicator_id: e.target.value })}
          className="h-7 border border-border rounded-md px-1.5 text-xs bg-card text-foreground flex-1 min-w-[100px]"
        />
      )}
      <select
        value={entry.chart_type}
        onChange={(e) => onChange({ ...entry, chart_type: e.target.value })}
        className="h-7 border border-border rounded-md px-1.5 text-xs bg-card text-foreground w-20"
      >
        <option value="line">Čára</option>
        <option value="bar">Sloupce</option>
        <option value="area">Plocha</option>
      </select>
      <button type="button" onClick={onRemove} className="h-6 w-6 flex items-center justify-center rounded-full hover:bg-muted/60 text-muted-foreground shrink-0">
        <XIcon className="h-3 w-3" />
      </button>
    </div>
  );
}

export default function PersonalCatalogChartForm({
  onApply,
  disabled,
  /** Na veřejných stránkách (přehled / sekce) jako admin: před uložením widgetu založit globální zdroj v `/api/sources`. */
  ensureGlobalCatalogSource = false,
}) {
  const { ready, isAdmin } = useAuth();
  const canAddSources = Boolean(ready && isAdmin);
  const [catalogId, setCatalogId] = useState(() => CATALOGS[0]?.id || "arad");
  const catalogDef = useMemo(
    () => CATALOGS.find((c) => c.id === catalogId) || CATALOGS[0],
    [catalogId]
  );

  const [stubs, setStubs] = useState([]);
  const [wbCountries, setWbCountries] = useState([]);
  const [wbCountry, setWbCountry] = useState(WB_DEFAULT_COUNTRY);

  const [catalogRows, setCatalogRows] = useState([]);
  const [openPaths,   setOpenPaths]   = useState(new Set());
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");
  const [q, setQ] = useState("");
  const dq = useDebounced(q, 200);
  const [pick, setPick] = useState(null);
  /** internal = globální zdroj z /sources; external = náhled z /catalog/preview */
  const [catalogMode, setCatalogMode] = useState("internal");
  const [externalPreview, setExternalPreview] = useState(null);
  const [internalPreviewData, setInternalPreviewData] = useState(null);
  const [chartType, setChartType] = useState("line");
  const [barOrientation, setBarOrientation] = useState("vertical");
  const [pieVariant, setPieVariant] = useState("donut");
  const [dataMode, setDataMode] = useState("history");
  const [dataSortOrder, setDataSortOrder] = useState("source");
  const [limit, setLimit] = useState(0);
  const [indicators, setIndicators] = useState([]);
  const [indicatorId, setIndicatorId] = useState("");
  const [previewLoading, setPreviewLoading] = useState(false);
  const [syncBusy, setSyncBusy] = useState(false);
  const [wTitle, setWTitle] = useState("");
  const [wDesc, setWDesc] = useState("");

  const setMap = useMemo(() => buildSourceByKey(catalogDef, stubs), [catalogDef, stubs]);

  /** Comparison entries for ARAD composite chart at creation time. */
  const [compareEntries, setCompareEntries] = useState([]);
  /** Comparison entries for external-catalog multi-series (any catalog). */
  const [extCompareEntries, setExtCompareEntries] = useState([]);
  /** Extra filtrovatelné dimenze (ÚZEMÍ-STÁT, ÚZEMÍ-KRAJ, …) vrácené z preview. */
  const [extraDims, setExtraDims] = useState([]);
  /** Aktuálně zvolené hodnoty pro extra dimenze: { "ÚZEMÍ-STÁT": "Česko", … } */
  const [dimFilters, setDimFilters] = useState({});
  /** "indicator" = 1 indikátor (default) | "region" = všechny kraje jako série */
  const [seriesDimMode, setSeriesDimMode] = useState("indicator");
  /** "single" = 1 řada | "all" = všechny hodnoty group_field | "selected" = ruční multi výběr */
  const [groupSeriesMode, setGroupSeriesMode] = useState("single");
  const [selectedSeriesIds, setSelectedSeriesIds] = useState([]);

  const aradSources = useMemo(
    () => stubs.filter((s) => s.source_type === "arad"),
    [stubs]
  );
  const aradCompareSets = useMemo(
    () =>
      catalogId === "arad"
        ? catalogRows
            .filter((row) => row.kind === "set" && row.set_id)
            .sort((a, b) => String(a.name || a.set_id).localeCompare(String(b.name || b.set_id), "cs"))
        : [],
    [catalogId, catalogRows]
  );

  useEffect(() => {
    api
      .get("/sources/catalog-stubs")
      .then(({ data }) => setStubs(Array.isArray(data) ? data : []))
      .catch(() => setStubs([]));
  }, []);

  useEffect(() => {
    if (catalogDef?.needsCountry) return;
    setWbCountry(WB_DEFAULT_COUNTRY);
    setWbCountries([]);
  }, [catalogDef?.id, catalogDef?.needsCountry]);

  useEffect(() => {
    if (!catalogDef?.needsCountry) return;
    setPick(null);
    setCatalogMode("internal");
    setExternalPreview(null);
    setInternalPreviewData(null);
    setIndicators([]);
    setIndicatorId("");
      setGroupSeriesMode("single");
      setSelectedSeriesIds([]);
      setDataMode("history");
      setDataSortOrder("source");
  }, [wbCountry, catalogDef?.id, catalogDef?.needsCountry]);

  useEffect(() => {
    let cancel = false;
    (async () => {
      setLoading(true);
      setErr("");
      setPick(null);
      setCatalogMode("internal");
      setExternalPreview(null);
      setInternalPreviewData(null);
      setIndicators([]);
      setIndicatorId("");
      setGroupSeriesMode("single");
      setSelectedSeriesIds([]);
      setDataMode("history");
      setDataSortOrder("source");
      setWTitle("");
      try {
        const { data } = await api.get(catalogDef.catalogPath);
        if (cancel) return;
        if (catalogDef.needsCountry) {
          setWbCountries(Array.isArray(data?.countries) ? data.countries : []);
        } else {
          setWbCountries([]);
        }
        const cats = data?.categories || [];
        const flat = await flattenCatalogCategoriesBestEffort(cats);
        const allRows = flat || [];
        setCatalogRows(allRows);
        // Open top-level categories by default
        const topPaths = new Set(allRows.filter((r) => r.kind === "cat" && r.depth === 0).map((r) => r.path));
        setOpenPaths(topPaths);
      } catch (e) {
        if (!cancel)
          setErr(formatApiErrorFromAxios(e) || e?.message || "Chyba načtení katalogu");
      } finally {
        if (!cancel) setLoading(false);
      }
    })();
    return () => {
      cancel = true;
    };
  }, [catalogDef.catalogPath, catalogDef.id, catalogDef.needsCountry]);

  const keywords     = useMemo(() => parseSearchKeywords(dq), [dq]);
  const rowIndex     = useMemo(() => buildPathIndex(catalogRows), [catalogRows]);
  const filteredPaths = useMemo(
    () => buildFilteredPaths(catalogRows, rowIndex, keywords),
    [catalogRows, rowIndex, keywords]
  );

  /** Rows visible in the tree (respects open/collapsed folders + search). */
  const visibleRows = useMemo(() => {
    if (!catalogRows.length) return [];
    if (filteredPaths) {
      return catalogRows.filter((r) => filteredPaths.has(r.path)).slice(0, MAX_CATALOG_FILTER_ROWS);
    }
    const result = [];
    for (const r of catalogRows) {
      if (r.depth === 0) { result.push(r); continue; }
      const segs = r.kind === "set"
        ? r.parentPath.split(" > ")
        : r.path.split(" > ").slice(0, -1);
      let allOpen = true;
      const acc = [];
      for (const seg of segs) {
        acc.push(seg);
        if (!openPaths.has(acc.join(" > "))) { allOpen = false; break; }
      }
      if (allOpen) result.push(r);
    }
    return result;
  }, [catalogRows, openPaths, filteredPaths]);

  const toggleFolder = (path) => setOpenPaths((s) => {
    const n = new Set(s);
    if (n.has(path)) n.delete(path); else n.add(path);
    return n;
  });

  const loadPreviewInternal = useCallback(async (row, indicatorReload, indicatorReloadMany) => {
    if (!row?.set_id) return;
    const rk = rowExistingKey(catalogDef, row, wbCountry);
    const src = setMap.get(rk);
    if (!src?.id) return;
    setPreviewLoading(true);
    setExternalPreview(null);
    try {
      const params = { limit: 1200 };
      const st = String(catalogDef.sourceType || "").toLowerCase();
      const many = normalizeSeriesIds(indicatorReloadMany || []);
      if (st === "arad") {
        if (many.length > 1) {
          params.indicator_ids = many.join(",");
        } else if (many.length === 1) {
          params.indicator_id = many[0];
        } else if (indicatorReload != null && String(indicatorReload).trim() !== "") {
          params.indicator_id = String(indicatorReload).trim();
        }
      }
      const { data } = await api.get(`/sources/${src.id}/preview`, { params });
      setInternalPreviewData(data || null);

      const inds = Array.isArray(data?.indicators) ? data.indicators : [];
      setIndicators(inds);
      const dims = Array.isArray(data?.extra_dimensions) ? data.extra_dimensions : [];
      setExtraDims(dims);
      setDimFilters({});
      const defSel =
        (data?.selected_indicator && String(data.selected_indicator)) ||
        (inds[0] && String(inds[0].id)) ||
        "";
      const loadedMany = normalizeSeriesIds(data?.selected_indicators);
      setSelectedSeriesIds((prev) => {
        if (many.length > 1) return many;
        if (many.length === 1) return many;
        if (loadedMany.length > 1) return loadedMany;
        const stillValid = normalizeSeriesIds(prev).filter((id) => inds.some((i) => String(i.id) === id));
        if (stillValid.length) return stillValid;
        return defSel ? [defSel] : [];
      });
      setIndicatorId((prev) => {
        if (many.length) return many[0];
        if (indicatorReload != null && String(indicatorReload).trim() !== "") {
          return String(indicatorReload).trim();
        }
        if (st === "arad" && prev && inds.some((i) => String(i.id) === prev)) return prev;
        return defSel || "";
      });

      const defaultTitle =
        (row.name || row.label || row.set_id || "").toString().trim() || catalogDef.label || "Graf";
      setWTitle((t) => t || defaultTitle);
    } catch {
      setInternalPreviewData(null);
      setIndicators([]);
      setIndicatorId("");
      setSelectedSeriesIds([]);
    } finally {
      setPreviewLoading(false);
    }
  }, [catalogDef, setMap, wbCountry]);

  const loadPreviewExternal = useCallback(
    async (row, indicatorOverride, indicatorOverrides = []) => {
      if (!row?.set_id) return;
      setPreviewLoading(true);
      setExternalPreview(null);
      setInternalPreviewData(null);
      setIndicators([]);
      setIndicatorId("");
      try {
        const body = buildCatalogPreviewBody(catalogDef, row, wbCountry);
        const ov =
          indicatorOverride != null && String(indicatorOverride).trim() !== ""
            ? String(indicatorOverride).trim()
            : null;
        const many = normalizeSeriesIds(indicatorOverrides);
        if (ov) body.selected_indicator = ov;
        if (many.length > 0) {
          body.selected_indicator = many[0];
          body.selected_indicators = many;
        }
        const { data } = await api.post("/catalog/preview", body);
        setExternalPreview(data || null);
        const inds = Array.isArray(data?.indicators) ? data.indicators : [];
        setIndicators(inds);
        const dims = Array.isArray(data?.extra_dimensions) ? data.extra_dimensions : [];
        setExtraDims(dims);
        setDimFilters({});
        const defSel =
          (data?.selected_indicator && String(data.selected_indicator)) ||
          (Array.isArray(data?.selected_indicators) && data.selected_indicators[0] ? String(data.selected_indicators[0]) : "") ||
          (inds[0] && String(inds[0].id)) ||
          "";
        const loadedMany = normalizeSeriesIds(data?.selected_indicators);
        setSelectedSeriesIds((prev) => {
          if (many.length) return many;
          if (loadedMany.length > 1) return loadedMany;
          const stillValid = normalizeSeriesIds(prev).filter((id) => inds.some((i) => String(i.id) === id));
          if (stillValid.length) return stillValid;
          return defSel ? [defSel] : [];
        });
        setIndicatorId((prev) => {
          if (many.length) return many[0];
          if (ov) return ov;
          if (prev && inds.some((i) => String(i.id) === prev)) return prev;
          return defSel || "";
        });
        const defaultTitle =
          (row.name || row.label || row.set_id || "").toString().trim() || catalogDef.label || "Graf";
        setWTitle((t) => t || defaultTitle);
      } catch {
        setIndicators([]);
        setIndicatorId("");
        setExternalPreview(null);
      } finally {
        setPreviewLoading(false);
      }
    },
    [catalogDef, wbCountry]
  );

  const onSelectRow = (row) => {
    setPick(row);
    setExtCompareEntries([]);
    setExtraDims([]);
    setDimFilters({});
    setSeriesDimMode("indicator");
    setGroupSeriesMode("single");
    setSelectedSeriesIds([]);
    setDataMode("history");
    setDataSortOrder("source");
    const rk = rowExistingKey(catalogDef, row, wbCountry);
    const src = setMap.get(rk);
    if (src?.id) {
      setCatalogMode("internal");
      loadPreviewInternal(row);
    } else {
      setCatalogMode("external");
      loadPreviewExternal(row);
    }
  };

  const onIndicatorChange = (next) => {
    const v = String(next);
    setIndicatorId(v);
    setSelectedSeriesIds((prev) => (groupSeriesMode === "single" ? [v] : (prev.length ? prev : [v])));
    if (!pick) return;
    if (catalogMode === "external") loadPreviewExternal(pick, v);
    else if (catalogMode === "internal" && String(catalogDef.sourceType || "").toLowerCase() === "arad")
      loadPreviewInternal(pick, v);
  };

  const pickKey = pick ? rowExistingKey(catalogDef, pick, wbCountry) : null;
  const srcForPick = pickKey ? setMap.get(pickKey) : null;

  const handleLoadLiveCatalogPreview = useCallback(() => {
    if (!pick) return;
    if (catalogMode === "external") {
      loadPreviewExternal(pick);
      return;
    }
    setCatalogMode("external");
    loadPreviewExternal(pick);
  }, [pick, catalogMode, loadPreviewExternal]);

  const handleSyncGlobalSource = useCallback(async () => {
    if (!pick || !srcForPick?.id || disabled || syncBusy) return;
    setSyncBusy(true);
    try {
      const syncPath = canAddSources ? "sync" : "sync-public";
      await api.post(`/sources/${srcForPick.id}/${syncPath}`);
      toast.info("Synchronizace spuštěna. Krátce vyčkejte…");
      for (let i = 0; i < 25; i += 1) {
        await new Promise((resolve) => setTimeout(resolve, 3000));
        if (canAddSources) {
          try {
            const { data: s } = await api.get(`/sources/${srcForPick.id}`);
            if (s?.last_sync_status && s.last_sync_status !== "running") break;
          } catch {
            break;
          }
        } else {
          const { data: pr } = await api.get(`/sources/${srcForPick.id}/preview`, {
            params: { limit: 40 },
          });
          if (Array.isArray(pr?.rows) && pr.rows.length > 0) break;
        }
      }
      await loadPreviewInternal(pick);
      try {
        const { data } = await api.get("/sources/catalog-stubs");
        setStubs(Array.isArray(data) ? data : []);
      } catch {
        /* ignore */
      }
      toast.success("Náhled ze zdroje byl znovu načten.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Synchronizaci se nepodařilo spustit.");
    } finally {
      setSyncBusy(false);
    }
  }, [pick, srcForPick, disabled, syncBusy, canAddSources, loadPreviewInternal]);

  const previewRows =
    catalogMode === "external" ? externalPreview?.rows : internalPreviewData?.rows;
  const previewRowCount = Array.isArray(previewRows) ? previewRows.length : 0;
  const activePreviewData = catalogMode === "external" ? externalPreview : internalPreviewData;
  const activeGroupField = String(activePreviewData?.group_field || "").trim();
  const indicatorIds = useMemo(
    () => normalizeSeriesIds(indicators.map((ind) => ind?.id)),
    [indicators]
  );
  const canChooseGroupedSeries =
    previewRowCount > 0 &&
    indicatorIds.length > 1 &&
    Boolean(activeGroupField) &&
    ["eurostat", "ecb", "csu", "arad"].includes(String(catalogDef?.sourceType || "").toLowerCase());
  const effectiveSelectedSeriesIds = useMemo(() => {
    if (!canChooseGroupedSeries) return indicatorId ? [String(indicatorId)] : [];
    if (groupSeriesMode === "all") return indicatorIds;
    if (groupSeriesMode === "selected") {
      const picked = normalizeSeriesIds(selectedSeriesIds).filter((id) => indicatorIds.includes(id));
      return picked.length ? picked : (indicatorId ? [String(indicatorId)] : []);
    }
    return indicatorId ? [String(indicatorId)] : [];
  }, [canChooseGroupedSeries, groupSeriesMode, indicatorIds, selectedSeriesIds, indicatorId]);
  const seriesLabelById = useMemo(() => {
    const out = {};
    for (const ind of indicators) {
      const id = String(ind?.id || "").trim();
      if (id) out[id] = ind?.name || id;
    }
    return out;
  }, [indicators]);

  const canUse = useMemo(() => {
    if (!pick || previewLoading) return false;
    if (catalogMode === "external") {
      if (externalPreview == null) return false;
      if (indicators.length > 0 && !String(indicatorId || "").trim()) return false;
      return Boolean(
        buildExternalCatalogChartConfig(
          catalogDef,
          { ...externalPreview, selected_indicator: indicatorId },
          pick,
          wbCountry
        )
      );
    }
    if (!srcForPick?.id) return false;
    if (String(catalogDef.sourceType || "").toLowerCase() === "arad") {
      return Boolean(String(effectiveSelectedSeriesIds[0] || indicatorId || "").trim());
    }
    return Boolean(buildPersonalChartConfigFromPreview(catalogDef, srcForPick, internalPreviewData, pick));
  }, [
    pick,
    previewLoading,
    catalogMode,
    catalogDef,
    externalPreview,
    indicatorId,
    effectiveSelectedSeriesIds,
    wbCountry,
    srcForPick,
    internalPreviewData,
    indicators.length,
  ]);

  const submit = async () => {
    if (!pick?.set_id || !canUse) return;

    const rk = rowExistingKey(catalogDef, pick, wbCountry);
    let persistSrc = null;
    let persistPreview = null;

    if (catalogMode === "internal") {
      persistSrc = setMap.get(rk);
      persistPreview = internalPreviewData;
      if (!persistSrc?.id || !persistPreview) return;
    } else if (
      catalogMode === "external" &&
      ensureGlobalCatalogSource &&
      canAddSources &&
      catalogDef.addPath
    ) {
      try {
        const body = buildCatalogAddSourceBody(catalogDef, pick, wbCountry);
        try {
          await api.post(catalogDef.addPath, body);
        } catch (e) {
          if (e?.response?.status !== 409) throw e;
        }
        const { data: stubList } = await api.get("/sources/catalog-stubs");
        const list = Array.isArray(stubList) ? stubList : [];
        setStubs(list);
        const freshMap = buildSourceByKey(catalogDef, list);
        persistSrc = freshMap.get(rk);
        if (!persistSrc?.id) {
          toast.error(
            "Řadu se nepodařilo navázat na globální zdroj. Přidejte ji ze stránky Správa dat → Zdroje, případně zkuste znovu."
          );
          return;
        }
        const params = { limit: 1200 };
        const st = String(catalogDef.sourceType || "").toLowerCase();
        const many = normalizeSeriesIds(effectiveSelectedSeriesIds);
        if (st === "arad") {
          if (many.length > 1) {
            params.indicator_ids = many.join(",");
          } else if (many.length === 1) {
            params.indicator_id = many[0];
          } else if (indicatorId != null && String(indicatorId).trim() !== "") {
            params.indicator_id = String(indicatorId).trim();
          }
        }
        const { data: pv } = await api.get(`/sources/${persistSrc.id}/preview`, { params });
        persistPreview = pv || null;
        if (!persistPreview) {
          toast.error("Náhled globálního zdroje se nepodařil načíst.");
          return;
        }
        setCatalogMode("internal");
        setInternalPreviewData(persistPreview);
        setExternalPreview(null);
        toast.success("Řada je mezi datovými zdroji aplikace — synchronizaci nastavíte v administraci Zdroje.");
      } catch (e) {
        toast.error(formatApiErrorFromAxios(e) || "Globální zdroj se nepodařilo vytvořit.");
        return;
      }
    }

    if (persistSrc?.id && persistPreview) {
      const src = persistSrc;

      if (String(catalogDef.sourceType || "").toLowerCase() === "arad") {
        const primaryId = String(effectiveSelectedSeriesIds[0] || indicatorId || "").trim();
        if (!primaryId) return;
        const fromGrouped =
          canChooseGroupedSeries && effectiveSelectedSeriesIds.length > 1
            ? effectiveSelectedSeriesIds.slice(1).map((iid) => ({
                source_id: src.id,
                indicator_id: String(iid).trim(),
                chart_type: chartType || "line",
                y_axis: "left",
              }))
            : [];
        // Cross-source compare entries (AradCompareEntryRow)
        const crossSrc = compareEntries
          .filter((e) => e.source_id && e.indicator_id)
          .map((e) => ({ source_id: e.source_id, indicator_id: e.indicator_id, chart_type: e.chart_type || "line", y_axis: "left" }));
        // Same-source compare entries (ExtCompareSection — different indicator, same source)
        const sameSrc = extCompareEntries
          .filter((e) => e.selected_indicator)
          .map((e) => ({ source_id: src.id, indicator_id: e.selected_indicator, chart_type: e.chart_type || "line", y_axis: "left" }));
        const allCmp = [];
        const seen = new Set([`${src.id}:${primaryId}`]);
        for (const e of [...fromGrouped, ...sameSrc, ...crossSrc]) {
          const k = `${e.source_id}:${e.indicator_id}`;
          if (seen.has(k)) continue;
          seen.add(k);
          allCmp.push(e);
        }
        const cfg = {
          source_type: "arad",
          source_id: src.id,
          indicator_id: primaryId,
          view: "chart",
          chart_type: chartType,
          chart_bar_orientation: barOrientation,
          chart_pie_variant: pieVariant,
          limit: limit > 0 ? limit : 0,
          ...(allCmp.length > 0 ? { chart_compare_with: allCmp } : {}),
        };
        const p = onApply?.({
          title: wTitle.trim() || pick.name || "Datový graf",
          description: wDesc,
          config: cfg,
          widgetType: "chart",
        });
        if (p && typeof p.then === "function") await p;
        return;
      }

      const isCsuSource = String(catalogDef?.sourceType || "").toLowerCase() === "csu";
      const splitDimForSubmit = pickSplitDimension(extraDims, isCsuSource);
      const useMultiSeriesMode = seriesDimMode === "region" && Boolean(indicatorId && splitDimForSubmit);

      const previewWithFilters = {
        ...persistPreview,
        dimension_filters: Object.keys(dimFilters).length > 0 ? dimFilters : undefined,
      };
      const built = buildPersonalChartConfigFromPreview(catalogDef, src, previewWithFilters, pick);
      if (!built?.config) return;
      built.config.chart_type = chartType;
      built.config.chart_bar_orientation = barOrientation;
      built.config.chart_pie_variant = pieVariant;
      // Při krajovém pivot módu potřebujeme VŠECHNA data — limit by po filtraci na
      // jeden indikátor zanechal jen ~10 řádků a kraj pivot by neměl co zobrazit.
      built.config.limit = dataMode === "history" || useMultiSeriesMode ? 0 : (limit > 0 ? limit : 0);
      built.config.chart_data_mode = dataMode === "latest" ? "latest" : "history";
      built.config.chart_sort_order = dataMode === "latest" ? dataSortOrder : "source";
      built.config.chart_type = dataMode === "latest" && chartType === "line" ? "bar" : chartType;

      // Více řad: přidej chart_series_dim a nastav filtraci po vybraném ukazateli.
      if (useMultiSeriesMode) {
        built.config.chart_series_dim = splitDimForSubmit.field;
        // Ujisti se, že series_field/series_value zachytí vybraný indikátor
        const gf = persistPreview?.group_field;
        if (gf && indicatorId) {
          built.config.series_field = gf;
          built.config.series_value = indicatorId;
        }
        // Odstraň filtr pro región (chceme VŠECHNY kraje jako série)
        if (built.config.dimension_filters) {
          const cleaned = { ...built.config.dimension_filters };
          delete cleaned[splitDimForSubmit.field];
          built.config.dimension_filters = Object.keys(cleaned).length > 0 ? cleaned : undefined;
        }
        built.config.agg = "avg";
      }
      // Compare series for non-ARAD internal (series_value based for grouped datasets)
      const nonAradCmp = extCompareEntries.filter((e) => e.selected_indicator);
      if (nonAradCmp.length > 0) {
        const gf = persistPreview?.group_field;
        built.config.chart_compare_with = nonAradCmp.map((e) => ({
          source_id: src.id,
          ...(gf ? { series_field: gf, series_value: e.selected_indicator } : { indicator_id: e.selected_indicator }),
          chart_type: e.chart_type || "line",
          y_axis: "left",
        }));
      }
      const p = onApply?.({
        title: wTitle.trim() || built.title || pick.name || "Datový graf",
        description: wDesc,
        config: built.config,
        widgetType: "chart",
      });
      if (p && typeof p.then === "function") await p;
      return;
    }

    const groupedIdsForSubmit = canChooseGroupedSeries ? effectiveSelectedSeriesIds : [];
    const primarySeriesForSubmit = groupedIdsForSubmit[0] || indicatorId;
    const externalPreviewForBuild = {
      ...externalPreview,
      selected_indicator: primarySeriesForSubmit,
      ...(groupedIdsForSubmit.length > 1 ? { selected_indicators: groupedIdsForSubmit } : {}),
    };
    const built = buildExternalCatalogChartConfig(
      catalogDef,
      externalPreviewForBuild,
      pick,
      wbCountry
    );
    if (!built?.config) return;
    built.config.chart_data_mode = dataMode === "latest" ? "latest" : "history";
    built.config.chart_sort_order = dataMode === "latest" ? dataSortOrder : "source";
    built.config.chart_type = dataMode === "latest" && chartType === "line" ? "bar" : chartType;
    built.config.chart_bar_orientation = barOrientation;
    built.config.chart_pie_variant = pieVariant;

    // Více řad v external módu (ČSÚ sada s libovolnou dimenzí: kraje, odvětví, typy atd.)
    const extSplitDim = pickSplitDimension(extraDims, String(catalogDef?.sourceType || "").toLowerCase() === "csu");
    if (seriesDimMode === "region" && indicatorId && extSplitDim) {
      built.config.chart_series_dim = extSplitDim.field;
      const gf = externalPreview?.group_field;
      if (gf && indicatorId) {
        built.config.series_field = gf;
        built.config.series_value = indicatorId;
      }
      built.config.agg = "avg";
    }

    const validExtCmp = extCompareEntries.filter((e) => e.selected_indicator || e.set_id);
    if (validExtCmp.length > 0) {
      const existingCmp = Array.isArray(built.config.chart_compare_with) ? built.config.chart_compare_with : [];
      built.config.chart_compare_with = [
        ...existingCmp,
        ...validExtCmp.map((e) => ({
        selected_indicator: e.selected_indicator || "",
        chart_type: e.chart_type || "line",
        y_axis: "left",
        name:
          e.name ||
          indicators.find((i) => String(i.id) === e.selected_indicator)?.name ||
          e.selected_indicator ||
          e.set_id ||
          "",
        ...(e.set_id && e.set_id !== String(pick?.set_id ?? "") ? { set_id: e.set_id } : {}),
        })),
      ];
    }
    const p = onApply?.({
      title: wTitle.trim() || built.title || pick.name || "Datový graf",
      description: wDesc,
      config: built.config,
      widgetType: "external_catalog_chart",
    });
    if (p && typeof p.then === "function") await p;
  };

  const emptyIndicatorsHint =
    catalogMode === "external"
      ? "Data z této řady se nepodařilo načíst z katalogu — zkuste znovu načíst náhled nebo jinou řadu."
      : "Pro tuto sadu zatím nejsou v databázi k dispozici ukazatele. Spusťte synchronizaci globálního zdroje nebo si nechte zobrazit okamžitý náhled z katalogu (bez čekání na dokončení sync).";

  return (
    <div className="space-y-3 border border-border/70 rounded-xl p-3 bg-slate-50/50">
      <div className="text-xs font-medium text-slate-700">Datová řada z katalogu — graf nebo tabulka</div>
      <p className="text-[11px] text-slate-500 leading-relaxed">
        Nejdřív zvolte katalog (ARAD, ČSÚ, Eurostat, ALPHA VANTAGE — akcie / indexy, …), případně zemi u World Bank.
        Potom vyhledejte nebo vyberte
        řadu v seznamu — stejná logika jako u globálního vyhledávání v katalogu. U řad spravovaných jako globální
        zdroj použijeme data z databáze; u ostatních řadu přímo z rozhraní katalogu (osobně pro váš dashboard).
        {ensureGlobalCatalogSource ? (
          <span className="block mt-1 text-slate-600 font-medium">
            Na přehledu nebo v sekci se před uložením widgetu řada automaticky zapíše mezi datové zdroje aplikace
            (synchronizace ve správě → Zdroje).
          </span>
        ) : null}
        Po uložení widgetu přepnete zobrazení mezi <span className="text-slate-600 font-medium">grafem a tabulkou</span> přímo na dlaždici.
      </p>

      <label className="block text-[11px] text-slate-600 font-medium uppercase tracking-wide">
        Katalog
        <select
          className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm bg-white"
          value={catalogDef.id}
          onChange={(e) => setCatalogId(e.target.value)}
          disabled={disabled}
        >
          {CATALOGS.map((c) => (
            <option key={c.id} value={c.id}>
              {c.label}
            </option>
          ))}
        </select>
      </label>

      {catalogDef.needsCountry ? (
        <label className="block text-[11px] text-slate-600 font-medium uppercase tracking-wide">
          Země (World Bank)
          <select
            className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm bg-white"
            value={wbCountry}
            onChange={(e) => setWbCountry(e.target.value)}
            disabled={disabled}
          >
            {wbCountries.length === 0 ? (
              <option value={WB_DEFAULT_COUNTRY}>{WB_DEFAULT_COUNTRY}</option>
            ) : (
              wbCountries.map((c) => (
                <option key={c.code} value={c.code}>
                  {(c.label && `${c.label} (${c.code})`) || c.code}
                </option>
              ))
            )}
          </select>
        </label>
      ) : null}

      {loading && (
        <LoadingInline label="Načítám katalog…" size="sm" className="py-1" />
      )}
      {err && <div className="text-xs text-red-700">{String(err)}</div>}
      {!loading && !err && (
        <>
          <input
            type="search"
            className="w-full border rounded-lg px-2 py-1.5 text-sm"
            placeholder="Hledat v názvu nebo cestě…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <div className="max-h-56 overflow-auto border rounded-lg bg-white text-xs">
            {visibleRows.length === 0 ? (
              <div className="p-3 text-slate-500">{dq ? "Žádná shoda." : "Katalog je prázdný."}</div>
            ) : (
              visibleRows.map((row) => {
                if (row.kind === "cat") {
                  const isOpen = openPaths.has(row.path) || Boolean(filteredPaths);
                  return (
                    <button
                      key={row.path}
                      type="button"
                      onClick={() => toggleFolder(row.path)}
                      className={`w-full flex items-center gap-1.5 text-left py-1.5 border-b border-border/30 hover:bg-slate-50 ${
                        row.depth === 0 ? "bg-slate-100/70 font-semibold" : "font-medium"
                      }`}
                      style={{ paddingLeft: `${8 + row.depth * 14}px` }}
                    >
                      {isOpen
                        ? <ChevronDown  className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                        : <ChevronRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />}
                      <Folder className="h-3.5 w-3.5 shrink-0 text-amber-500/80" />
                      <span className="truncate text-slate-700">{row.name}</span>
                      <span className="ml-auto text-[9px] text-muted-foreground pr-2">{row.count}</span>
                    </button>
                  );
                }
                const rk = rowExistingKey(catalogDef, row, wbCountry);
                const hasGlobalSource = setMap.has(rk);
                return (
                  <button
                    key={row.path}
                    type="button"
                    onClick={() => onSelectRow(row)}
                    className={`w-full flex items-start gap-1.5 text-left py-1.5 border-b border-border/30 last:border-0 hover:bg-slate-50 ${
                      pick?.path === row.path ? "bg-[hsl(var(--primary)/0.08)]" : ""
                    }`}
                    style={{ paddingLeft: `${10 + row.depth * 14}px` }}
                  >
                    <FileBarChart2 className="h-3.5 w-3.5 shrink-0 text-muted-foreground mt-0.5" />
                    <div className="min-w-0">
                      <div className="font-medium text-slate-800 truncate">{row.name || row.set_id}</div>
                      <div className="text-[10px] text-slate-500">
                        {hasGlobalSource ? "Globální zdroj v aplikaci" : "Náhled přímo z katalogu (osobně)"}
                      </div>
                    </div>
                  </button>
                );
              })
            )}
          </div>

          {pick && (
            <div className="space-y-2 pt-1">
              {catalogMode === "external" ? (
                <div className="rounded-lg border border-border/60 bg-white p-3 space-y-2 shadow-sm">
                  <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-3">
                    <p className="text-[11px] text-slate-600 leading-relaxed flex-1 min-w-0">
                      Graf se ukládá jako osobní náhled z katalogu — nevyžaduje spravovaný zdroj administrátora.
                      <span className="block text-slate-500 mt-1">
                        Řada z katalogu se zobrazí až po úspěšném načtení dat — použijte tlačítko níže.
                      </span>
                    </p>
                    <button
                      type="button"
                      disabled={disabled || previewLoading}
                      className="w-full sm:w-auto shrink-0 text-xs font-semibold py-2.5 px-3 rounded-lg bg-[hsl(var(--primary))] text-white hover:opacity-95 disabled:opacity-50 disabled:pointer-events-none"
                      onClick={() => {
                        const ids =
                          groupSeriesMode === "all"
                            ? indicatorIds
                            : groupSeriesMode === "selected"
                              ? effectiveSelectedSeriesIds
                              : [];
                        loadPreviewExternal(pick, ids[0] || indicatorId || undefined, ids);
                      }}
                    >
                      {previewLoading ? "Načítám…" : "Načíst data z katalogu"}
                    </button>
                  </div>
                  {previewLoading ? (
                    <LoadingInline label="Načítám náhled…" size="sm" className="py-1" />
                  ) : indicators.length ? (
                    <>
                      <label className="block text-[11px] text-slate-600">
                        Ukazatel / řada
                        <select
                          className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                          value={indicatorId}
                          onChange={(e) => onIndicatorChange(e.target.value)}
                        >
                          {indicators.map((ind) => (
                            <option key={ind.id} value={ind.id}>
                              {ind.name || ind.id}
                            </option>
                          ))}
                        </select>
                      </label>
                      {canChooseGroupedSeries ? (
                        <div className="space-y-1.5 rounded-lg border border-blue-200/60 bg-blue-50/60 p-2">
                          <div className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                            Zobrazit {groupFieldLabel(activeGroupField)} jako série
                          </div>
                          <div className="flex flex-wrap items-center gap-1.5">
                            <button
                              type="button"
                              onClick={() => {
                                setGroupSeriesMode("single");
                                setSelectedSeriesIds(indicatorId ? [indicatorId] : []);
                                if (pick && indicatorId) loadPreviewExternal(pick, indicatorId);
                              }}
                              className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                                groupSeriesMode === "single"
                                  ? "bg-blue-600 text-white border-blue-600"
                                  : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                              }`}
                            >
                              1 řada
                            </button>
                            <button
                              type="button"
                              onClick={() => {
                                setGroupSeriesMode("all");
                                setSelectedSeriesIds(indicatorIds);
                                if (pick) loadPreviewExternal(pick, indicatorIds[0], indicatorIds);
                              }}
                              className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                                groupSeriesMode === "all"
                                  ? "bg-blue-600 text-white border-blue-600"
                                  : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                              }`}
                            >
                              Všechny ({indicatorIds.length})
                            </button>
                            <button
                              type="button"
                              onClick={() => {
                                const base = normalizeSeriesIds(selectedSeriesIds).filter((id) => indicatorIds.includes(id));
                                const next = base.length ? base : (indicatorId ? [indicatorId] : indicatorIds.slice(0, 1));
                                setGroupSeriesMode("selected");
                                setSelectedSeriesIds(next);
                                if (pick) loadPreviewExternal(pick, next[0], next);
                              }}
                              className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                                groupSeriesMode === "selected"
                                  ? "bg-blue-600 text-white border-blue-600"
                                  : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                              }`}
                            >
                              Vybrané
                            </button>
                            {effectiveSelectedSeriesIds.length > 1 ? (
                              <span className="text-[10px] text-blue-700 font-mono">
                                {effectiveSelectedSeriesIds.length} řad
                              </span>
                            ) : null}
                          </div>
                          {groupSeriesMode === "selected" ? (
                            <div className="max-h-28 overflow-y-auto rounded border border-blue-100 bg-white/80 p-1.5">
                              <div className="grid grid-cols-1 gap-1 sm:grid-cols-2">
                                {indicators.map((ind) => {
                                  const id = String(ind.id || "").trim();
                                  if (!id) return null;
                                  const checked = effectiveSelectedSeriesIds.includes(id);
                                  return (
                                    <label key={id} className="flex items-center gap-1.5 text-[10px] text-slate-700">
                                      <input
                                        type="checkbox"
                                        checked={checked}
                                        onChange={(e) => {
                                          const base = normalizeSeriesIds(selectedSeriesIds).filter((x) => indicatorIds.includes(x));
                                          let next = e.target.checked ? [...base, id] : base.filter((x) => x !== id);
                                          next = normalizeSeriesIds(next);
                                          if (!next.length) return;
                                          setSelectedSeriesIds(next);
                                          setIndicatorId(next[0]);
                                          if (pick) loadPreviewExternal(pick, next[0], next);
                                        }}
                                        className="h-3.5 w-3.5 rounded border-border"
                                      />
                                      <span className="truncate" title={ind.name || id}>
                                        {ind.name || id}
                                      </span>
                                    </label>
                                  );
                                })}
                              </div>
                            </div>
                          ) : null}
                        </div>
                      ) : null}
                      {previewRowCount > 0 && (
                        <>
                          <div className="text-[11px] text-emerald-700 font-medium">
                            ✓ Data načtena — {previewRowCount} bodů časové řady.
                          </div>
                          <CatalogDataPreview
                            rows={externalPreview?.rows}
                            fields={externalPreview?.fields}
                            groupField={externalPreview?.group_field}
                            selectedIndicator={effectiveSelectedSeriesIds.length > 1 ? "" : (indicatorId || externalPreview?.selected_indicator)}
                            dataMode={dataMode}
                            sortOrder={dataSortOrder}
                            seriesLabels={seriesLabelById}
                          />
                        </>
                      )}
                    </>
                  ) : previewRowCount > 0 ? (
                    <>
                      <div className="text-[11px] text-emerald-700 font-medium border-t border-emerald-100 pt-2">
                        ✓ Data načtena — {previewRowCount} bodů. Nyní klikněte „Použít" níže.
                      </div>
                      <CatalogDataPreview
                        rows={externalPreview?.rows}
                        fields={externalPreview?.fields}
                        groupField={externalPreview?.group_field}
                        selectedIndicator={effectiveSelectedSeriesIds.length > 1 ? "" : (indicatorId || externalPreview?.selected_indicator)}
                        dataMode={dataMode}
                        sortOrder={dataSortOrder}
                        seriesLabels={seriesLabelById}
                      />
                    </>
                  ) : externalPreview != null ? (
                    <p className="text-xs text-amber-800 border-t border-amber-100/80 pt-2">{emptyIndicatorsHint}</p>
                  ) : null}

                  {previewRowCount > 0 ? (
                    <div className="space-y-1.5 border-t border-border/40 pt-2">
                      <div className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                        Rozsah dat v grafu
                      </div>
                      <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-slate-200 bg-slate-50/70 p-2">
                        <button
                          type="button"
                          onClick={() => setDataMode("history")}
                          className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                            dataMode !== "latest"
                              ? "bg-blue-600 text-white border-blue-600"
                              : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                          }`}
                        >
                          Celá historie
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            setDataMode("latest");
                            if (chartType === "line") setChartType("bar");
                          }}
                          className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                            dataMode === "latest"
                              ? "bg-blue-600 text-white border-blue-600"
                              : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                          }`}
                        >
                          Poslední údaje
                        </button>
                        <span className="text-[10px] text-slate-500">
                          {dataMode === "latest"
                            ? "Graf zobrazí poslední dostupnou hodnotu pro každou vybranou zemi/řadu."
                            : "Graf zobrazí časový vývoj v celé dostupné historii."}
                        </span>
                      </div>
                      {dataMode === "latest" ? (
                        <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-slate-200 bg-white p-2">
                          <span className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold mr-1">
                            Seřadit
                          </span>
                          {[
                            ["source", "Abecedně"],
                            ["asc", "Od nejmenší"],
                            ["desc", "Od největší"],
                          ].map(([id, label]) => (
                            <button
                              key={id}
                              type="button"
                              onClick={() => setDataSortOrder(id)}
                              className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                                dataSortOrder === id
                                  ? "bg-blue-600 text-white border-blue-600"
                                  : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                              }`}
                            >
                              {label}
                            </button>
                          ))}
                        </div>
                      ) : null}
                    </div>
                  ) : null}

                  {/* ── Dimenze grafu: jedna řada / více řad (external mode) ── */}
                  {previewRowCount > 0 && indicatorId && String(catalogDef?.sourceType || "").toLowerCase() === "csu" && (() => {
                    const splitDim = pickSplitDimension(extraDims, true);
                    const splitCount = splitDim?.values?.length;
                    return (
                      <div className="space-y-1.5 border-t border-border/40 pt-2">
                        <div className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">Dimenze grafu</div>
                        <div className="flex items-center gap-2 p-2 rounded-lg bg-blue-50/60 border border-blue-200/60">
                          <span className="text-[11px] text-slate-600 font-medium">Zobrazit jako série:</span>
                          <button
                            type="button"
                            onClick={() => setSeriesDimMode("indicator")}
                            className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                              seriesDimMode !== "region"
                                ? "bg-blue-600 text-white border-blue-600"
                                : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                            }`}
                          >
                            Jedna řada
                          </button>
                          <button
                            type="button"
                            onClick={() => setSeriesDimMode("region")}
                            className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                              seriesDimMode === "region"
                                ? "bg-blue-600 text-white border-blue-600"
                                : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                            }`}
                          >
                            Více řad{splitCount ? ` (${splitCount})` : ""}
                          </button>
                          {seriesDimMode === "region" && (
                            <span className="text-[10px] text-blue-600 font-mono ml-1">
                              → podle {splitDim?.field || "dimenze"}
                            </span>
                          )}
                        </div>
                      </div>
                    );
                  })()}

                  {/* ── Compare series (multi-series) ── */}
                  {previewRowCount > 0 && (
                    <ExtCompareSection
                      indicators={indicators}
                      entries={extCompareEntries}
                      onChange={setExtCompareEntries}
                      disabled={disabled}
                      compareSets={catalogId === "arad" ? aradCompareSets : []}
                      catalogDef={catalogDef}
                      wbCountry={wbCountry}
                      currentSetId={pick?.set_id}
                    />
                  )}
                </div>
              ) : (
                <>
                  {previewLoading ? (
                    <LoadingInline label="Načítám náhled…" size="sm" className="py-1" />
                  ) : indicators.length ? (
                    <>
                      {!(pick && !previewLoading && previewRowCount > 0) ? (
                        <label className="block text-[11px] text-slate-600">
                          Ukazatel / řada
                          <select
                            className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                            value={indicatorId}
                            onChange={(e) => onIndicatorChange(e.target.value)}
                          >
                            {indicators.map((ind) => (
                              <option key={ind.id} value={ind.id}>
                                {ind.name || ind.id}
                              </option>
                            ))}
                          </select>
                        </label>
                      ) : null}
                      {canChooseGroupedSeries && String(catalogDef?.sourceType || "").toLowerCase() === "arad" ? (
                        <div className="space-y-1.5 rounded-lg border border-blue-200/60 bg-blue-50/60 p-2">
                          <div className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                            Zobrazit {groupFieldLabel(activeGroupField)} jako série
                          </div>
                          <div className="flex flex-wrap items-center gap-1.5">
                            <button
                              type="button"
                              onClick={() => {
                                setGroupSeriesMode("single");
                                setSelectedSeriesIds(indicatorId ? [indicatorId] : []);
                                if (pick && indicatorId) loadPreviewInternal(pick, indicatorId);
                              }}
                              className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                                groupSeriesMode === "single"
                                  ? "bg-blue-600 text-white border-blue-600"
                                  : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                              }`}
                            >
                              1 řada
                            </button>
                            <button
                              type="button"
                              onClick={() => {
                                setGroupSeriesMode("all");
                                setSelectedSeriesIds(indicatorIds);
                                if (pick) loadPreviewInternal(pick, undefined, indicatorIds);
                              }}
                              className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                                groupSeriesMode === "all"
                                  ? "bg-blue-600 text-white border-blue-600"
                                  : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                              }`}
                            >
                              Všechny ({indicatorIds.length})
                            </button>
                            <button
                              type="button"
                              onClick={() => {
                                const base = normalizeSeriesIds(selectedSeriesIds).filter((id) => indicatorIds.includes(id));
                                const next = base.length ? base : (indicatorId ? [indicatorId] : indicatorIds.slice(0, 1));
                                setGroupSeriesMode("selected");
                                setSelectedSeriesIds(next);
                                if (pick) loadPreviewInternal(pick, undefined, next);
                              }}
                              className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                                groupSeriesMode === "selected"
                                  ? "bg-blue-600 text-white border-blue-600"
                                  : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                              }`}
                            >
                              Vybrané
                            </button>
                            {effectiveSelectedSeriesIds.length > 1 ? (
                              <span className="text-[10px] text-blue-700 font-mono">
                                {effectiveSelectedSeriesIds.length} řad
                              </span>
                            ) : null}
                          </div>
                          {groupSeriesMode === "selected" ? (
                            <div className="max-h-28 overflow-y-auto rounded border border-blue-100 bg-white/80 p-1.5">
                              <div className="grid grid-cols-1 gap-1 sm:grid-cols-2">
                                {indicators.map((ind) => {
                                  const id = String(ind.id || "").trim();
                                  if (!id) return null;
                                  const checked = effectiveSelectedSeriesIds.includes(id);
                                  return (
                                    <label key={id} className="flex items-center gap-1.5 text-[10px] text-slate-700">
                                      <input
                                        type="checkbox"
                                        checked={checked}
                                        onChange={(e) => {
                                          const base = normalizeSeriesIds(selectedSeriesIds).filter((x) => indicatorIds.includes(x));
                                          let next = e.target.checked ? [...base, id] : base.filter((x) => x !== id);
                                          next = normalizeSeriesIds(next);
                                          if (!next.length) return;
                                          setSelectedSeriesIds(next);
                                          setIndicatorId(next[0]);
                                          if (pick) loadPreviewInternal(pick, undefined, next);
                                        }}
                                        className="h-3.5 w-3.5 rounded border-border"
                                      />
                                      <span className="truncate" title={ind.name || id}>
                                        {ind.name || id}
                                      </span>
                                    </label>
                                  );
                                })}
                              </div>
                            </div>
                          ) : null}
                        </div>
                      ) : null}
                    </>
                  ) : (
                    <div className="space-y-2">
                      <div className="text-xs text-amber-800">{emptyIndicatorsHint}</div>
                      <div className="flex flex-wrap gap-2">
                        {srcForPick?.id ? (
                          <button
                            type="button"
                            disabled={disabled || syncBusy || previewLoading}
                            className="text-xs py-1.5 px-2.5 rounded-lg border border-border/70 bg-white hover:bg-slate-50 disabled:opacity-50"
                            onClick={handleSyncGlobalSource}
                          >
                            {syncBusy ? "Synchronizuji…" : "Synchronizovat zdroj"}
                          </button>
                        ) : null}
                        <button
                          type="button"
                          disabled={disabled || syncBusy || previewLoading}
                          className="text-xs py-1.5 px-2.5 rounded-lg border border-border/70 bg-white hover:bg-slate-50 disabled:opacity-50"
                          onClick={handleLoadLiveCatalogPreview}
                        >
                          Náhled z katalogu
                        </button>
                      </div>
                    </div>
                  )}
                  {pick && !previewLoading && previewRowCount > 0 ? (
                    <>
                      <div className="text-[11px] text-emerald-700 font-medium">
                        ✓ Data načtena — {previewRowCount} bodů časové řady.
                      </div>
                      {indicators.length > 0 ? (
                        <label className="block text-[11px] text-slate-600 border-t border-border/40 pt-2">
                          <span className="block text-[10px] text-muted-foreground font-normal mb-0.5">
                            Ukazatel / řada — změna je vždy k dispozici (náhled se znovu načte).
                          </span>
                          <select
                            className="mt-0.5 w-full border rounded-lg px-2 py-1.5 text-sm"
                            value={indicatorId}
                            onChange={(e) => onIndicatorChange(e.target.value)}
                          >
                            {indicators.map((ind) => (
                              <option key={ind.id} value={ind.id}>
                                {ind.name || ind.id}
                              </option>
                            ))}
                          </select>
                        </label>
                      ) : null}
                      <CatalogDataPreview
                        rows={internalPreviewData?.rows}
                        fields={internalPreviewData?.fields}
                        groupField={internalPreviewData?.group_field}
                        selectedIndicator={
                          effectiveSelectedSeriesIds.length > 1
                            ? ""
                            : (indicatorId || internalPreviewData?.selected_indicator)
                        }
                        dataMode={dataMode}
                        sortOrder={dataSortOrder}
                        seriesLabels={seriesLabelById}
                      />
                      {(extraDims.length > 0 || (indicatorId && String(catalogDef?.sourceType || "").toLowerCase() === "csu")) && (() => {
                        const splitDim = pickSplitDimension(extraDims, String(catalogDef?.sourceType || "").toLowerCase() === "csu");
                        const canShowMultiSeries = Boolean(indicatorId && splitDim);
                        const splitCount = splitDim?.values?.length;
                        return (
                          <div className="space-y-1.5 border-t border-border/40 pt-2">
                            <div className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">Dimenze grafu</div>
                            {canShowMultiSeries && (
                              <div className="flex items-center gap-2 mb-1.5 p-2 rounded-lg bg-blue-50/60 border border-blue-200/60">
                                <span className="text-[11px] text-slate-600 font-medium">Zobrazit jako série:</span>
                                <button
                                  type="button"
                                  onClick={() => setSeriesDimMode("indicator")}
                                  className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                                    seriesDimMode !== "region"
                                      ? "bg-blue-600 text-white border-blue-600"
                                      : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                                  }`}
                                >
                                  Jedna řada
                                </button>
                                <button
                                  type="button"
                                  onClick={() => setSeriesDimMode("region")}
                                  className={`h-6 px-2.5 text-[10px] rounded border font-mono transition-colors ${
                                    seriesDimMode === "region"
                                      ? "bg-blue-600 text-white border-blue-600"
                                      : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                                  }`}
                                >
                                  Více řad{splitCount ? ` (${splitCount})` : ""}
                                </button>
                                {seriesDimMode === "region" && (
                                  <span className="text-[10px] text-blue-600 font-mono ml-1">
                                    → podle {splitDim?.field || "dimenze"}
                                  </span>
                                )}
                              </div>
                            )}
                            {extraDims
                              .filter((dim) => seriesDimMode === "region" ? dim.field !== splitDim?.field : true)
                              .map((dim) => (
                                <label key={dim.field} className="block text-[11px] text-slate-600">
                                  {dim.field}
                                  <select
                                    className="mt-0.5 w-full border rounded-lg px-2 py-1 text-sm bg-white"
                                    value={dimFilters[dim.field] || ""}
                                    onChange={(e) => setDimFilters((prev) => ({ ...prev, [dim.field]: e.target.value }))}
                                    disabled={disabled}
                                  >
                                    <option value="">— všechny ({dim.values.length}) —</option>
                                    {dim.values.map((v) => (
                                      <option key={v} value={v}>{v}</option>
                                    ))}
                                  </select>
                                </label>
                              ))}
                          </div>
                        );
                      })()}
                      <ExtCompareSection
                        indicators={indicators}
                        entries={extCompareEntries}
                        onChange={setExtCompareEntries}
                        disabled={disabled}
                        compareSets={catalogId === "arad" ? aradCompareSets : []}
                        catalogDef={catalogDef}
                        wbCountry={wbCountry}
                        currentSetId={pick?.set_id}
                      />
                    </>
                  ) : null}
                </>
              )}

              <label className="block text-[11px] text-slate-600">
                Typ grafu
                <select
                  className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                  value={chartType}
                  onChange={(e) => setChartType(e.target.value)}
                >
                  {CHART_TYPES.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.label}
                    </option>
                  ))}
                </select>
              </label>
              {chartType === "bar" && (
                <label className="block text-[11px] text-slate-600">
                  Varianta sloupců
                  <select
                    className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                    value={barOrientation}
                    onChange={(e) => setBarOrientation(e.target.value)}
                  >
                    {BAR_ORIENTATIONS.map((opt) => (
                      <option key={opt.id} value={opt.id}>{opt.label}</option>
                    ))}
                  </select>
                </label>
              )}
              {chartType === "pie" && (
                <label className="block text-[11px] text-slate-600">
                  Varianta koláče
                  <select
                    className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                    value={pieVariant}
                    onChange={(e) => setPieVariant(e.target.value)}
                  >
                    {PIE_VARIANTS.map((opt) => (
                      <option key={opt.id} value={opt.id}>{opt.label}</option>
                    ))}
                  </select>
                </label>
              )}
              {catalogMode === "internal" && (
                <label className="block text-[11px] text-slate-600">
                  Časový rozsah (zobrazeno jako počet posledních bodů)
                  <select
                    className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                    value={limit}
                    onChange={(e) => setLimit(Number(e.target.value))}
                  >
                    {LIMIT_PRESETS.map((l) => (
                      <option key={l.value} value={l.value}>
                        {l.label}
                      </option>
                    ))}
                  </select>
                </label>
              )}
            </div>
          )}

          {/* ARAD cross-source comparison series (different ARAD global sources) */}
          {catalogId === "arad" && catalogMode === "internal" && pick && indicatorId && (
            <div className="space-y-2 border border-dashed border-border/60 rounded-xl p-2.5 bg-muted/10">
              <div className="flex items-center justify-between">
                <span className="text-[11px] font-medium text-foreground/80">
                  Srovnat s jiným zdrojem ARAD
                </span>
                {compareEntries.length < 4 && (
                  <button
                    type="button"
                    onClick={() =>
                      setCompareEntries((prev) => [
                        ...prev,
                        { id: `cmp-${Date.now()}`, source_id: "", indicator_id: "", chart_type: "line" },
                      ])
                    }
                    className="flex items-center gap-1 h-6 px-2 text-[10px] rounded-full border border-primary/40 text-primary hover:bg-primary/5 transition-colors"
                  >
                    <Plus className="h-3 w-3" /> Přidat řadu
                  </button>
                )}
              </div>
              {compareEntries.length === 0 ? (
                <p className="text-[10px] text-muted-foreground">
                  Klikněte „Přidat řadu" pro zobrazení více indikátorů v jednom grafu.
                </p>
              ) : (
                <div className="space-y-1.5">
                  {compareEntries.map((entry) => (
                    <AradCompareEntryRow
                      key={entry.id}
                      aradSources={aradSources}
                      entry={entry}
                      onChange={(updated) =>
                        setCompareEntries((prev) =>
                          prev.map((e) => (e.id === updated.id ? updated : e))
                        )
                      }
                      onRemove={() =>
                        setCompareEntries((prev) => prev.filter((e) => e.id !== entry.id))
                      }
                    />
                  ))}
                </div>
              )}
            </div>
          )}

          <div>
            <label className="block text-[11px] text-slate-500 mb-0.5">Název widgetu</label>
            <input
              className="w-full border rounded-lg px-2 py-1.5 text-sm"
              value={wTitle}
              onChange={(e) => setWTitle(e.target.value)}
            />
          </div>
          <div>
            <label className="block text-[11px] text-slate-500 mb-0.5">Popisek (volitelné)</label>
            <textarea
              className="w-full border rounded-lg px-2 py-1.5 text-sm min-h-[52px]"
              value={wDesc}
              onChange={(e) => setWDesc(e.target.value)}
            />
          </div>
          <button
            type="button"
            disabled={disabled || !canUse}
            className="btn-primary text-sm py-1.5 px-3 disabled:opacity-50"
            onClick={submit}
          >
            Použít v mém dashboardu
          </button>
        </>
      )}
    </div>
  );
}
