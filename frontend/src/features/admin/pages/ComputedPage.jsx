import React, { useEffect, useMemo, useState } from "react";
import {
  Plus,
  Trash2,
  Play,
  X,
  RefreshCw,
  Pencil,
  Calculator,
  ChevronRight,
  ChevronLeft,
} from "lucide-react";
import { toast } from "sonner";
import {
  ResponsiveContainer,
  LineChart,
  Line,
  Bar,
  Area,
  ComposedChart,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
} from "recharts";
import api, { formatApiError, safeFormatApiDetail } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import FeatureLock from "@/components/FeatureLock";
import { fmtCompact, fmtNumber, fmtPeriod, parseNumber } from "@/lib/format";
import { mergeRechartsTooltipProps } from "@/lib/rechartsTooltipShared";

/** Stabilní prázdné pole — `|| []` v renderu je nová reference každý snímek. */
const EMPTY_ROWS = [];

/** Uložené vlastní výpočty (homepage) — ARAD dvojice nebo složený graf · jednoduché operace. */
const OP_LABELS = {
  ratio: "Poměr A ÷ B",
  sum: "Součet A + B",
  diff: "Rozdíl A − B",
  mult: "Součin A × B",
  pct: "Procentuální poměr (A ÷ B) × 100",
  multi: "Složený graf (více řad)",
};

/** Zrcadlo backend EXTRA_OPERATION_LABELS — zobrazení v seznamu / průvodci. */
const EXTENDED_OPERATION_LABELS = {
  pct_points: "Rozdíl v procentních bodech (A − B)",
  log_a: "Přirozený logaritmus řady A",
  index_100_first: "Index báze 100 — první období řady A",
  index_b100_first: "Index báze 100 — první období řady B",
  yoy_pct_auto: "YoY % (automatický krok dle periody)",
  yoy_abs_auto: "YoY absolutní změna řady A",
  mom_pct_auto: "Změna v % vs. předchozí krok",
  qoq_pct_auto: "Změna v % vs. předchozí dostupný krok (QoQ‑like)",
  roll_mean: "Klouzavý průměr řady A (okno ve volbách)",
  cumsum: "Kumulativní součet řady A",
  volatility_ret: "Směrodatná odchylka relativních mezirozdílů",
  zscore: "Z‑skóre hodnot řady A",
  drawdown_pct: "Drawdown od maxima (%)",
  cagr_range: "CAGR (hrubě z délky řady)",
  corr_pearson: "Pearsonova korelace A vs B",
  regress_ols: "OLS regrese A ~ B",
  index_vs_b_pct: "Indexové vyjádření (A ÷ B) × 100",
  real_div_infl: "A/B×100 jako zjednodušený deflátor",
  cum_change_first: "Kumulativní změna od začátku řady A",
  pct_rank_hist: "Percentil v historii řady A",
  trend_linear_time: "Lineární trend v čase (OLS na pořadí období)",
};

const OP_HINT = {
  ratio: "Podíl A k B na společných obdobích — pozor na nulové B.",
  sum: "Sečíst lze jen souhlasné měřítkové řady.",
  diff: "Rozdíl A−B jako spread podobných veličin.",
  mult: "Skládání intenzit — jednotky se násobí.",
  pct: "(A÷B)×100 — interpretace jako procentní podíl.",
};

/** Kategorie kroků 3 · „Výpočet z datových řad“ — id odpovídá hodnotám `operation`. */
const COMPUTED_OP_GROUPS = [
  {
    category: "Základní výpočty",
    keys: ["sum", "diff", "mult", "ratio", "pct", "pct_points", "index_vs_b_pct", "real_div_infl"],
  },
  {
    category: "Časové změny",
    keys: ["yoy_pct_auto", "yoy_abs_auto", "mom_pct_auto", "qoq_pct_auto", "cumsum"],
  },
  {
    category: "Indexy a normalizace",
    keys: ["index_100_first", "index_b100_first", "log_a"],
  },
  {
    category: "Statistika a regrese",
    keys: ["roll_mean", "volatility_ret", "zscore", "corr_pearson", "regress_ols", "pct_rank_hist", "trend_linear_time"],
  },
  {
    category: "Finanční / ekonomické pohledy",
    keys: ["drawdown_pct", "cagr_range", "cum_change_first"],
  },
];

const unarySeriesA = new Set([
  "log_a",
  "index_100_first",
  "yoy_pct_auto",
  "yoy_abs_auto",
  "mom_pct_auto",
  "qoq_pct_auto",
  "roll_mean",
  "cumsum",
  "volatility_ret",
  "zscore",
  "drawdown_pct",
  "cagr_range",
  "cum_change_first",
  "pct_rank_hist",
  "trend_linear_time",
]);
const unarySeriesB = new Set(["index_b100_first"]);

function refOkForComputed(r) {
  if (!r || typeof r !== "object" || !(r.source_id || "").trim()) return false;
  if ((r.indicator_id || "").trim()) return true;
  return !!(r.x_field || "").trim() && !!(r.y_field || "").trim();
}

function summarizeSeriesRef(ref) {
  const id = (ref?.indicator_id || "").trim();
  if (id) return id;
  const xf = (ref?.x_field || "").trim();
  const yf = (ref?.y_field || "").trim();
  if (xf && yf) return `${yf} / ${xf}`;
  return "—";
}

function packComputedRefPayload(r) {
  return {
    source_id: (r?.source_id || "").trim(),
    indicator_id: (r?.indicator_id || "").trim(),
    x_field: (r?.x_field || "").trim(),
    y_field: (r?.y_field || "").trim(),
    name: r?.name || "",
  };
}

function humanUnknown(v) {
  const s = (v ?? "").toString().trim();
  if (!s || s.toLowerCase() === "unknown") return "nezjištěno";
  return s;
}

function freqLabelCs(code) {
  const c = (code ?? "").toString().toUpperCase();
  const map = {
    M: "měsíční",
    Q: "kvartální",
    Y: "roční",
    A: "roční",
    D: "denní",
    W: "týdenní",
    H: "pololetní",
    S: "pololetní",
  };
  if (map[c]) return map[c];
  return humanUnknown(code);
}

/** Z náhledového řádku Mongo `data` vytáhni období a hodnotu (ARAD / obecné). */
function extractPreviewPeriodValue(row) {
  if (!row || typeof row !== "object") return { period: null, valueRaw: null };
  const keys = Object.keys(row);
  const periodKey =
    keys.find((k) => k.toLowerCase() === "period") ||
    keys.find((k) => k.toLowerCase() === "date") ||
    keys.find((k) => k.toLowerCase().includes("obdobi")) ||
    keys.find((k) => /time/i.test(k));
  const valKey =
    keys.find((k) => k.toLowerCase() === "value") ||
    keys.find((k) => k.toLowerCase() === "amount") ||
    keys.find((k) => k.toLowerCase() === "hodnota");
  const period = periodKey ? String(row[periodKey] ?? "").trim() || null : null;
  const valueRaw = valKey !== undefined ? row[valKey] : null;
  return { period, valueRaw };
}

async function fetchSourcePreviewSeries(sourceId, indicatorId, limit = 400) {
  if (!sourceId || !indicatorId) return { rows: [], fields: [], message: null };
  try {
    const { data } = await api.get(`/sources/${sourceId}/preview`, {
      params: { indicator_id: indicatorId, limit },
    });
    return {
      rows: Array.isArray(data?.rows) ? data.rows : [],
      fields: Array.isArray(data?.fields) ? data.fields : [],
      message: data?.message || null,
    };
  } catch {
    return { rows: [], fields: [], message: null };
  }
}

/** Náhled řady z `indicator_id` nebo tabulárního páru x_field/y_field — shodně s backend výpočtem. */
async function fetchSourcePreviewForRef(ref, limit = 400) {
  if (!ref?.source_id) return { rows: [], fields: [], message: null };
  const ind = (ref.indicator_id ?? "").toString().trim();
  if (ind) return fetchSourcePreviewSeries(ref.source_id, ind, limit);
  const xf = (ref.x_field ?? "").toString().trim();
  const yf = (ref.y_field ?? "").toString().trim();
  if (!xf || !yf) return { rows: [], fields: [], message: null };
  try {
    const cap = Math.max(80, Math.min(limit, 1500));
    const { data } = await api.get(`/sources/${ref.source_id}/preview`, { params: { limit: cap } });
    const rowsRaw = Array.isArray(data?.rows) ? data.rows : [];
    const rows = rowsRaw
      .map((raw) => {
        if (!raw || typeof raw !== "object") return null;
        const tk =
          xf in raw ? xf : Object.keys(raw).find((k) => k.toLowerCase() === xf.toLowerCase());
        const vk =
          yf in raw ? yf : Object.keys(raw).find((k) => k.toLowerCase() === yf.toLowerCase());
        const period = tk != null ? String(raw[tk] ?? "").trim() || null : null;
        const valueRaw = vk != null ? raw[vk] : null;
        if (!period) return null;
        return { ...raw, period, value: valueRaw, TIME_PERIOD: period };
      })
      .filter(Boolean);
    return {
      rows,
      fields: Array.isArray(data?.fields) ? data.fields : [],
      message: data?.message || null,
    };
  } catch {
    return { rows: [], fields: [], message: null };
  }
}

/** period -> raw value z náhledových řádků */
function previewRowsToMap(rows) {
  const m = new Map();
  for (const row of rows || []) {
    const { period, valueRaw } = extractPreviewPeriodValue(row);
    if (!period) continue;
    m.set(period, valueRaw);
  }
  return m;
}

function previewStats(rows) {
  const pairs = [];
  for (const row of rows || []) {
    const { period, valueRaw } = extractPreviewPeriodValue(row);
    if (!period) continue;
    const n = parseNumber(valueRaw);
    pairs.push({ period, n, raw: valueRaw });
  }
  const withNum = pairs.filter((p) => p.n !== null);
  const sorted = [...pairs].sort((a, b) =>
    String(a.period).localeCompare(String(b.period), undefined, { numeric: true }),
  );
  return {
    count: pairs.length,
    countNumeric: withNum.length,
    first: sorted[0]?.period ?? null,
    last: sorted[sorted.length - 1]?.period ?? null,
    last10: sorted.slice(-10).reverse(),
  };
}

function SeriesEvidencePanel({ letter, sourceName, indicatorRef, meta, previewRows, loading, previewMessage }) {
  const stats = previewStats(previewRows || []);
  const freq = meta?.frequency_code ?? meta?.frequency;
  const unitDisp = humanUnknown(meta?.unit);

  const picked = !!(indicatorRef?.indicator_id || (indicatorRef?.x_field && indicatorRef?.y_field));

  return (
    <div className="overflow-hidden rounded-lg border border-[hsl(var(--border)/0.58)] bg-muted/15 p-3 space-y-2">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="text-[11px] font-semibold text-foreground">Řada {letter}</div>
        {picked ? (
          <span className="text-[11px] px-2 py-1 rounded-md border border-border bg-muted/40 text-muted-foreground">
            Vybráno
          </span>
        ) : (
          <span className="text-[11px] text-muted-foreground">Vyberte datovou řadu výše.</span>
        )}
      </div>
      {!picked ? (
        <p className="text-xs text-muted-foreground">Po výběru zde uvidíte název, zdroj a náhled hodnot.</p>
      ) : (
        <>
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1 text-xs">
            <div><dt className="text-muted-foreground inline">Název: </dt><dd className="inline font-medium">{meta?.name || indicatorRef.name || "—"}</dd></div>
            <div><dt className="text-muted-foreground inline">Zdroj: </dt><dd className="inline">{sourceName || "—"}</dd></div>
            <div><dt className="text-muted-foreground inline">Jednotka: </dt><dd className="inline">{unitDisp}</dd></div>
            <div><dt className="text-muted-foreground inline">Periodicita: </dt><dd className="inline">{freqLabelCs(freq)} ({humanUnknown(freq)})</dd></div>
            <div><dt className="text-muted-foreground inline">Počet pozorování: </dt><dd className="inline tabular-nums">{stats.countNumeric} / {stats.count}</dd></div>
            <div><dt className="text-muted-foreground inline">Rozsah: </dt><dd className="inline font-mono">{stats.first ? `${fmtPeriod(stats.first, { variant: "axis" })} → ${fmtPeriod(stats.last, { variant: "axis" })}` : "—"}</dd></div>
          </dl>
          {previewMessage ? (
            <p className="text-xs text-amber-800 bg-amber-50 border border-amber-200 rounded px-2 py-1">{previewMessage}</p>
          ) : null}
          <div className="text-[11px] font-semibold text-muted-foreground pt-1">Posledních 10 hodnot (náhled)</div>
          {loading ? (
            <p className="text-xs text-muted-foreground">Načítám náhled dat…</p>
          ) : (
            <div className="data-table-shell">
              <div className="overflow-x-auto max-h-40">
              <table className="data-table text-[11px]">
                <thead>
                  <tr>
                    <th>Období</th>
                    <th className="num">Hodnota</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.last10.length === 0 ? (
                    <tr><td colSpan={2} className="text-muted-foreground px-2 py-2">Žádná data v náhledu.</td></tr>
                  ) : (
                    stats.last10.map((r) => (
                      <tr key={r.period}>
                        <td className="mono">{fmtPeriod(r.period, { variant: "full" })}</td>
                        <td className="num mono">{r.n !== null ? fmtNumber(r.n) : String(r.raw ?? "—")}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function CompatibilitySummary({ metaLeft, metaRight, rowsLeft, rowsRight }) {
  const setL = new Set();
  const setR = new Set();
  for (const row of rowsLeft || []) {
    const { period } = extractPreviewPeriodValue(row);
    if (period) setL.add(period);
  }
  for (const row of rowsRight || []) {
    const { period } = extractPreviewPeriodValue(row);
    if (period) setR.add(period);
  }
  const inter = [...setL].filter((p) => setR.has(p));
  const fqA = metaLeft?.frequency_code ?? metaLeft?.frequency;
  const fqB = metaRight?.frequency_code ?? metaRight?.frequency;
  const uA = metaLeft?.unit;
  const uB = metaRight?.unit;

  const periodMsg =
    inter.length === 0
      ? "Řady nemají žádná společná období v náhledu."
      : inter.length < Math.min(setL.size, setR.size)
        ? `Řady mají pouze ${inter.length} společných období v náhledu.`
        : `V náhledu se shodují období (${inter.length} průniků).`;

  const freqWarn =
    fqA && fqB && String(fqA).toUpperCase() !== String(fqB).toUpperCase()
      ? `Periodicita se liší: A = ${freqLabelCs(fqA)}, B = ${freqLabelCs(fqB)}.`
      : null;

  const unitWarn =
    uA && uB && String(uA).trim() && String(uB).trim() && String(uA).trim() !== String(uB).trim()
      ? `Jednotky se liší: A = ${humanUnknown(uA)}, B = ${humanUnknown(uB)}.`
      : null;

  return (
    <div className="rounded-lg border border-border/70 bg-muted/15 p-4 space-y-3 text-sm">
      <h4 className="text-sm font-semibold">Krok 2 · Zkontrolujte kompatibilitu</h4>
      <p className="text-muted-foreground text-xs leading-relaxed">{periodMsg}</p>
      {freqWarn ? <p className="text-amber-900 text-xs bg-amber-50 border border-amber-100 rounded px-2 py-1.5">{freqWarn}</p> : null}
      {unitWarn ? <p className="text-amber-900 text-xs bg-amber-50 border border-amber-100 rounded px-2 py-1.5">{unitWarn}</p> : null}
      <div className="data-table-shell">
        <div className="overflow-x-auto max-h-48">
        <table className="data-table text-[11px]">
          <thead>
            <tr>
              <th>Období</th>
              <th className="num">Hodnota A</th>
              <th className="num">Hodnota B</th>
            </tr>
          </thead>
          <tbody>
            {[...inter].sort((a, b) => String(a).localeCompare(String(b), undefined, { numeric: true })).slice(-12).map((p) => {
              const mapL = previewRowsToMap(rowsLeft);
              const mapR = previewRowsToMap(rowsRight);
              const vl = parseNumber(mapL.get(p));
              const vr = parseNumber(mapR.get(p));
              return (
                <tr key={p}>
                  <td className="mono">{fmtPeriod(p, { variant: "full" })}</td>
                  <td className="num mono">{vl !== null ? fmtNumber(vl) : String(mapL.get(p) ?? "—")}</td>
                  <td className="num mono">{vr !== null ? fmtNumber(vr) : String(mapR.get(p) ?? "—")}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {inter.length === 0 ? (
          <div className="px-2 py-3 text-xs text-muted-foreground">Nelze vyplnit řádky — prázdný průnik období.</div>
        ) : null}
        </div>
      </div>
    </div>
  );
}

function WizardPreviewChartMaybe({ data }) {
  const rows = data?.rows || [];
  const chart = rows
    .map((r) => {
      const y = parseNumber(r.value);
      return { x: r.period, y: y !== null ? y : NaN };
    })
    .filter((c) => typeof c.y === "number" && Number.isFinite(c.y));
  if (chart.length === 0) return null;
  return (
    <div className="h-56 mt-3 border border-border/50 rounded-lg p-2 bg-muted/15 min-w-0 overflow-x-hidden">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={chart} margin={{ top: 6, right: 12, bottom: 4, left: 4 }}>
          <CartesianGrid vertical={false} stroke="#E6EEE9" strokeDasharray="2 4" />
          <XAxis
            dataKey="x"
            tick={{ fontSize: 9, fill: "#5878A0", fontFamily: "JetBrains Mono" }}
            tickFormatter={(v) => fmtPeriod(v, { variant: "axis" })}
          />
          <YAxis
            width={52}
            tick={{ fontSize: 9 }}
            tickFormatter={(v) => fmtCompact(v)}
          />
          <Tooltip
            {...mergeRechartsTooltipProps({
              contentStyle: { borderRadius: 6, fontSize: 11 },
              formatter: (v) => (typeof v === "number" ? fmtNumber(v) : v),
            })}
          />
          <Line type="monotone" dataKey="y" stroke="hsl(208 75% 45%)" strokeWidth={2} dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

function WizardPreviewMergedTable({ preview, form }) {
  const rows = Array.isArray(preview?.rows) ? preview.rows : EMPTY_ROWS;
  const previewFingerprint = useMemo(
    () => rows.map((r) => `${r.period}:${String(r.value ?? "")}`).join("|"),
    [rows],
  );
  const [mergeAB, setMergeAB] = useState(null);

  useEffect(() => {
    if (!form?.left?.source_id || !form?.left?.indicator_id || !form?.right?.source_id || !form?.right?.indicator_id) {
      setMergeAB(null);
      return undefined;
    }
    let cancel = false;
    (async () => {
      const [pa, pb] = await Promise.all([
        fetchSourcePreviewSeries(form.left.source_id, form.left.indicator_id),
        fetchSourcePreviewSeries(form.right.source_id, form.right.indicator_id),
      ]);
      if (cancel) return;
      const ma = previewRowsToMap(pa.rows);
      const mb = previewRowsToMap(pb.rows);
      const merged = rows.map((r) => ({
        period: r.period,
        a: ma.get(r.period),
        b: mb.get(r.period),
        out: r.value,
      }));
      setMergeAB(merged);
    })();
    return () => {
      cancel = true;
    };
  }, [
    form.left.source_id,
    form.left.indicator_id,
    form.right.source_id,
    form.right.indicator_id,
    previewFingerprint,
  ]);

  return (
    <div className="data-table-shell mt-3">
      <div className="overflow-x-auto max-h-[min(52vh,400px)]">
      <table className="data-table text-[11px]">
        <thead>
          <tr>
            <th>Období</th>
            <th className="num">A</th>
            <th className="num">B</th>
            <th className="num">Výsledek</th>
          </tr>
        </thead>
        <tbody>
          {[...rows].reverse().map((r, i) => {
            const mr = mergeAB?.find((x) => x.period === r.period);
            return (
              <tr key={i}>
                <td className="mono">{fmtPeriod(r.period, { variant: "full" })}</td>
                <td className="num mono">
                  {mr ? (parseNumber(mr.a) !== null ? fmtNumber(parseNumber(mr.a)) : String(mr.a ?? "—")) : "—"}
                </td>
                <td className="num mono">
                  {mr ? (parseNumber(mr.b) !== null ? fmtNumber(parseNumber(mr.b)) : String(mr.b ?? "—")) : "—"}
                </td>
                <td className="num mono font-semibold">
                  {(() => {
                    const n = parseNumber(r.value);
                    return n !== null ? fmtNumber(n) : "—";
                  })()}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      </div>
    </div>
  );
}

const MULTI_COLORS = [
  "#1f8cdb",
  "#2e7ec8",
  "#5fb8a4",
  "#e89da7",
  "#f4b860",
  "#8b8cc8",
  "#e08060",
  "#6b7280",
  "#0f766e",
  "#7c3aed",
  "#dc2626",
  "#0369a1",
];

export default function ComputedPage() {
  const { isAdmin } = useAuth();
  const [items, setItems] = useState([]);
  const [sources, setSources] = useState([]);
  // `editorMode` is either null (closed), "create" or an object { mode:"edit", item }.
  const [editorMode, setEditorMode] = useState(null);
  const [active, setActive] = useState(null);
  const [result, setResult] = useState(null);
  const [running, setRunning] = useState(false);

  const load = async () => {
    const [{ data: list }, { data: srcs }] = await Promise.all([
      api.get("/computed"),
      api.get("/sources"),
    ]);
    setItems(list || []);
    setSources(srcs || []);
  };
  useEffect(() => {
    load();
  }, []);

  const run = async (id) => {
    setRunning(true);
    setActive(id);
    try {
      const { data } = await api.get(`/computed/${id}/run`);
      setResult(data);
    } catch (e) {
      toast.error(safeFormatApiDetail(e.response?.data?.detail) || e.message);
    }
    setRunning(false);
  };

  const del = async (id) => {
    if (!window.confirm("Opravdu smazat tento výpočet?")) return;
    await api.delete(`/computed/${id}`);
    if (active === id) {
      setActive(null);
      setResult(null);
    }
    load();
  };

  return (
    <AppShell
      title="Vlastní výpočty"
      subtitle="Vytvářejte vlastní metriky z datových řad, například poměr, rozdíl, index nebo meziroční změnu."
      actions={
        isAdmin ? (
          <button
            data-testid="computed-new-btn"
            onClick={() => setEditorMode({ mode: "create" })}
            className="flex items-center gap-2 px-4 min-h-9 text-sm rounded-xl text-white transition-opacity hover:opacity-90"
            style={{
              background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
              boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
            }}
          >
            <Plus className="h-4 w-4" strokeWidth={1.8} /> Nový výpočet
          </button>
        ) : null
      }
    >
      <>
        <div className="max-w-3xl mb-6">
          <p className="text-sm text-muted-foreground leading-relaxed">
            Nejprve vyberte uložený výpočet v seznamu, nebo vytvořte nový vlastní výpočet z dvou datových řad.
          </p>
        </div>
        <div className="grid grid-cols-1 xl:grid-cols-[minmax(280px,380px)_1fr] gap-6 items-start">
        <aside className="soft-card overflow-hidden shrink-0">
          <div className="px-5 py-4 border-b border-border/50 kpi-label">Definované výpočty</div>
          {items.length === 0 ? (
            <div className="p-8 text-sm text-muted-foreground text-center leading-relaxed">
              Zatím nemáte žádný vlastní výpočet.
              <br />
              Začněte kliknutím na <strong>Nový výpočet</strong>.
            </div>
          ) : (
            <ul>
              {items.map((c) => (
                <li
                  key={c.id}
                  className={`px-5 py-4 border-b border-border/40 cursor-pointer transition-colors ${
                    active === c.id
                      ? "bg-[hsl(var(--primary-soft))]"
                      : "hover:bg-[hsl(var(--primary-soft)/0.5)]"
                  }`}
                  onClick={() => run(c.id)}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="font-medium text-foreground">{c.name}</div>
                      <div className="text-[10px] uppercase tracking-[0.14em] text-muted-foreground mt-1.5 font-medium">
                        {displayOperationLabel(c.operation)}
                      </div>
                      <div className="text-[11px] font-mono text-muted-foreground mt-1 truncate">
                        {c.operation === "multi"
                          ? `${(c.series || []).length || 0} řad`
                          : `A: ${summarizeSeriesRef(c.left)} · B: ${summarizeSeriesRef(c.right)}`}
                      </div>
                    </div>
                    {isAdmin ? (
                      <div className="flex items-center gap-1 shrink-0">
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setEditorMode({ mode: "edit", item: c });
                          }}
                          className="text-muted-foreground hover:text-foreground p-1"
                          title="Upravit výpočet"
                          data-testid={`cmp-edit-${c.id}`}
                        >
                          <Pencil className="h-4 w-4" />
                        </button>
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            del(c.id);
                          }}
                          className="text-muted-foreground hover:text-red-600 p-1"
                          title="Smazat výpočet"
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </div>
                    ) : null}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </aside>

        <section className="space-y-4 min-w-0">
          <div className="soft-card overflow-hidden">
            <div className="px-5 py-4 border-b border-border/50 kpi-label">Poslední výsledky</div>
          {!result ? (
            <div className="p-12 text-center text-sm text-muted-foreground leading-relaxed">
              {items.length === 0
                ? "Po vytvoření výpočtu se jeho výsledek zobrazí zde."
                : "Vyberte výpočet v seznamu vlevo pro zobrazení tabulky a grafu."}
            </div>
          ) : (
            <ResultPanel result={result} running={running} onRefresh={() => run(result.id)} />
          )}
          </div>

          <details className="soft-card overflow-hidden rounded-xl border border-border/60 bg-muted/20">
            <summary className="px-5 py-3 cursor-pointer text-sm font-medium text-muted-foreground select-none list-none flex items-center gap-2">
              <Calculator className="h-4 w-4 shrink-0" aria-hidden />
              Technické detaily (pokročilý náhled · AI · strukturovaný výstup)
            </summary>
            <div className="px-5 pb-5 pt-2 border-t border-border/40">
              <TechnicalCalculationsSection sources={sources} />
            </div>
          </details>
        </section>
      </div>
      </>

      {editorMode && (
        <EditorModal
          mode={editorMode.mode}
          initial={editorMode.item}
          sources={sources}
          onClose={() => setEditorMode(null)}
          onSaved={async (saved, savedMode) => {
            setEditorMode(null);
            await load();
            if (savedMode === "edit") {
              toast.success("Výpočet uložen");
              if (saved?.id) run(saved.id);
            } else {
              toast.success("Výpočet vytvořen");
            }
          }}
        />
      )}
    </AppShell>
  );
}

function ResultPanel({ result, running, onRefresh }) {
  const rows = Array.isArray(result?.rows) ? result.rows : EMPTY_ROWS;
  const isMulti = result?.operation === "multi";
  const series = Array.isArray(result?.series) ? result.series : [];
  const rowsFingerprint = useMemo(() => rows.map((r) => `${r.period}:${JSON.stringify(r)}`).join("|"), [rows]);
  const [mergeAB, setMergeAB] = useState(null);

  useEffect(() => {
    if (isMulti || !result?.left?.source_id || !result?.left?.indicator_id || !result?.right?.source_id || !result?.right?.indicator_id) {
      setMergeAB(null);
      return;
    }
    let cancel = false;
    (async () => {
      try {
        const [pa, pb] = await Promise.all([
          fetchSourcePreviewSeries(result.left.source_id, result.left.indicator_id),
          fetchSourcePreviewSeries(result.right.source_id, result.right.indicator_id),
        ]);
        if (cancel) return;
        const ma = previewRowsToMap(pa.rows);
        const mb = previewRowsToMap(pb.rows);
        const merged = rows.map((r) => ({
          period: r.period,
          a: ma.get(r.period),
          b: mb.get(r.period),
          out: r.value,
        }));
        setMergeAB({ merged });
      } catch {
        if (!cancel) setMergeAB(null);
      }
    })();
    return () => {
      cancel = true;
    };
  }, [
    isMulti,
    result?.id,
    result?.left?.source_id,
    result?.left?.indicator_id,
    result?.right?.source_id,
    result?.right?.indicator_id,
    rowsFingerprint,
  ]);

  const chart = isMulti
    ? rows.map((r) => {
        const o = { x: r.period };
        for (const s of series) {
          const k = s.key;
          if (!k) continue;
          const n = parseNumber(r[k]);
          if (n !== null) o[k] = n;
        }
        return o;
      })
    : rows.map((r) => {
        const y = parseNumber(r.value);
        return { x: r.period, y: y !== null ? y : NaN };
      });

  const binaryNumericPoints = !isMulti ? chart.filter((c) => typeof c.y === "number" && Number.isFinite(c.y)) : [];
  const multiHasPoints =
    isMulti &&
    chart.some((row) =>
      series.some((s) => {
        const k = s.key;
        if (!k) return false;
        const n = parseNumber(row[k]);
        return n !== null && Number.isFinite(n);
      }),
    );
  const showChart = isMulti ? multiHasPoints : binaryNumericPoints.length > 0;

  let binaryBlockReason = null;
  if (!isMulti && rows.length === 0 && result?.left?.indicator_id && result?.right?.indicator_id) {
    binaryBlockReason =
      "Výpočet nelze provést, protože řady nemají žádná společná období (nebo chybí hodnoty pro porovnání).";
  }
  if (!isMulti && mergeAB?.merged && ["ratio", "pct"].includes(result?.operation)) {
    const zb = mergeAB.merged.some((m) => parseNumber(m.b) === 0);
    if (zb) {
      binaryBlockReason =
        binaryBlockReason ||
        "V některých obdobích je hodnota řady B nulová — poměr nelze spočítat (dělení nulou).";
    }
  }

  const last = rows[rows.length - 1];
  return (
    <div className="soft-card overflow-hidden">
      <div className="p-6 border-b border-border/50 flex items-start justify-between gap-4 flex-wrap">
        <div>
          <div className="kpi-label">{result.name}</div>
          <div className="text-sm mt-2 text-foreground/90">{result.operation_label}</div>
          {!isMulti ? (
            <>
              <div className="text-[11px] text-muted-foreground mt-1.5">
                Řada A · {result.left?.indicator_id}{result.left?.name ? ` (${result.left.name})` : ""}
              </div>
              <div className="text-[11px] text-muted-foreground">
                Řada B · {result.right?.indicator_id}{result.right?.name ? ` (${result.right.name})` : ""}
              </div>
            </>
          ) : (
            <div className="text-[11px] text-muted-foreground mt-1.5">
              Počet řad v grafu: {series.length}
            </div>
          )}
        </div>
        {last && rows.length > 0 ? (
          <div className="text-right">
            <div className="kpi-label">Poslední hodnota ({fmtPeriod(last.period, { variant: "full" })})</div>
            <div className="font-serif text-[36px] mt-2">
              {isMulti
                ? "—"
                : (() => {
                    const n = parseNumber(last.value);
                    return n !== null ? fmtNumber(n) : "—";
                  })()}
            </div>
            {!isMulti && result.unit && <div className="text-xs text-muted-foreground font-mono">{result.unit}</div>}
          </div>
        ) : null}
        <button
          type="button"
          onClick={onRefresh}
          disabled={running}
          className="flex items-center gap-1.5 px-4 min-h-9 text-xs rounded-md border border-border/70 transition-colors hover:bg-[hsl(var(--primary-soft))] disabled:opacity-60"
        >
          <Play className={`h-4 w-4 ${running ? "animate-pulse" : ""}`} strokeWidth={1.6} aria-hidden />
          {running ? "Načítám…" : "Obnovit výsledek"}
        </button>
      </div>

      {rows.length === 0 ? (
        <div className="p-10 text-center text-sm text-muted-foreground leading-relaxed space-y-2">
          <p>{binaryBlockReason || "Výpočet zatím nemá žádné řádky — zkontrolujte vstupní řady a synchronizaci dat."}</p>
          {!isMulti && (
            <div className="text-left max-w-xl mx-auto mt-4 text-xs border border-border/60 rounded-lg p-3 bg-muted/20">
              <div className="font-semibold mb-1">Vaše řady</div>
              <div>Řada A: {result.left?.name || result.left?.indicator_id || "nezjištěno"}</div>
              <div>Řada B: {result.right?.name || result.right?.indicator_id || "nezjištěno"}</div>
            </div>
          )}
        </div>
      ) : (
        <>
          {binaryBlockReason ? (
            <div className="mx-6 mt-4 text-sm text-amber-900 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
              {binaryBlockReason}
            </div>
          ) : null}

          {showChart ? (
            <div className="h-72 p-4 border-b border-border/50 min-w-0 overflow-x-hidden">
              <ResponsiveContainer width="100%" height="100%">
                {isMulti ? (
                  <ComposedChart data={chart} margin={{ top: 10, right: 20, bottom: 10, left: 10 }}>
                    <CartesianGrid vertical={false} stroke="#E6EEE9" strokeDasharray="2 4" />
                    <XAxis
                      dataKey="x"
                      tick={{ fontSize: 10, fill: "#8FA69E", fontFamily: "JetBrains Mono" }}
                      tickLine={false}
                      axisLine={{ stroke: "#E6EEE9" }}
                      interval="preserveStartEnd"
                      minTickGap={40}
                      tickFormatter={(v) => fmtPeriod(v, { variant: "axis" })}
                    />
                    <YAxis
                      width={64}
                      tick={{ fontSize: 10, fill: "#8FA69E", fontFamily: "JetBrains Mono" }}
                      tickLine={false}
                      axisLine={false}
                      tickFormatter={(v) => fmtCompact(v)}
                    />
                    <Tooltip
                      {...mergeRechartsTooltipProps({
                        contentStyle: { fontSize: 12 },
                        formatter: (v) => {
                          if (typeof v === "number") return fmtNumber(v);
                          const n = parseNumber(v);
                          return n !== null ? fmtNumber(n) : String(v ?? "");
                        },
                        labelFormatter: (l) => `Období: ${fmtPeriod(l, { variant: "full" })}`,
                      })}
                    />
                    <Legend wrapperStyle={{ fontSize: 11, fontFamily: "JetBrains Mono" }} />
                    {series.map((s, idx) => {
                      const kind = (s.chart_type || "line").toLowerCase();
                      const color = MULTI_COLORS[idx % MULTI_COLORS.length];
                      const name = s.name || s.indicator_id || `Řada ${idx + 1}`;
                      const k = s.key;
                      if (!k) return null;
                      if (kind === "bar") {
                        return <Bar key={k} dataKey={k} name={name} fill={color} radius={[4, 4, 0, 0]} />;
                      }
                      if (kind === "area") {
                        return (
                          <Area
                            key={k}
                            type="monotone"
                            dataKey={k}
                            name={name}
                            stroke={color}
                            fill={color}
                            fillOpacity={0.12}
                            strokeWidth={2}
                            dot={false}
                            connectNulls
                          />
                        );
                      }
                      return (
                        <Line
                          key={k}
                          type="monotone"
                          dataKey={k}
                          name={name}
                          stroke={color}
                          strokeWidth={2}
                          dot={false}
                          connectNulls
                        />
                      );
                    })}
                  </ComposedChart>
                ) : (
                  <LineChart data={binaryNumericPoints} margin={{ top: 10, right: 20, bottom: 10, left: 10 }}>
                    <CartesianGrid vertical={false} stroke="#E6EEE9" strokeDasharray="2 4" />
                    <XAxis
                      dataKey="x"
                      tick={{ fontSize: 10, fill: "#8FA69E", fontFamily: "JetBrains Mono" }}
                      tickLine={false}
                      axisLine={{ stroke: "#E6EEE9" }}
                      interval="preserveStartEnd"
                      minTickGap={40}
                      tickFormatter={(v) => fmtPeriod(v, { variant: "axis" })}
                    />
                    <YAxis
                      width={64}
                      tick={{ fontSize: 10, fill: "#8FA69E", fontFamily: "JetBrains Mono" }}
                      tickLine={false}
                      axisLine={false}
                      tickFormatter={(v) => fmtCompact(v)}
                    />
                    <Tooltip
                      {...mergeRechartsTooltipProps({
                        contentStyle: { fontSize: 12 },
                        formatter: (v) => {
                          if (typeof v === "number") return fmtNumber(v);
                          const n = parseNumber(v);
                          return n !== null ? fmtNumber(n) : String(v ?? "");
                        },
                        labelFormatter: (l) => `Období: ${fmtPeriod(l, { variant: "full" })}`,
                      })}
                    />
                    <Line type="monotone" dataKey="y" stroke="hsl(208 75% 45%)" strokeWidth={2.25} dot={false} />
                  </LineChart>
                )}
              </ResponsiveContainer>
            </div>
          ) : rows.length > 0 ? (
            <div className="px-6 py-3 text-sm text-muted-foreground border-b border-border/40">
              Graf se nezobrazuje — výsledná řada nemá platné číselné body.
            </div>
          ) : null}

          <div className="px-4 py-3">
            <div className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">
              Tabulka výsledku
            </div>
            <div className="data-table-shell">
            <div className="overflow-auto max-h-[400px]">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Období</th>
                    {!isMulti && mergeAB?.merged ? (
                      <>
                        <th className="num">Řada A</th>
                        <th className="num">Řada B</th>
                      </>
                    ) : null}
                    {!isMulti ? <th className="num">Výsledek</th> : null}
                    {isMulti
                      ? series.map((s, idx) => (
                          <th key={s.key || idx} className="num">
                            {s.name || s.indicator_id || `Řada ${idx + 1}`}
                          </th>
                        ))
                      : null}
                  </tr>
                </thead>
                <tbody>
                  {[...rows].reverse().map((r, i) => {
                    const mr = mergeAB?.merged?.find((x) => x.period === r.period);
                    return (
                      <tr key={i}>
                        <td className="mono">{fmtPeriod(r.period, { variant: "full" })}</td>
                        {!isMulti && mergeAB?.merged ? (
                          <>
                            <td className="num mono">
                              {mr ? (parseNumber(mr.a) !== null ? fmtNumber(parseNumber(mr.a)) : String(mr.a ?? "—")) : "—"}
                            </td>
                            <td className="num mono">
                              {mr ? (parseNumber(mr.b) !== null ? fmtNumber(parseNumber(mr.b)) : String(mr.b ?? "—")) : "—"}
                            </td>
                          </>
                        ) : null}
                        {!isMulti ? (
                          <td className="num mono font-medium">
                            {(() => {
                              const n = parseNumber(r.value);
                              return n !== null ? fmtNumber(n) : "—";
                            })()}
                          </td>
                        ) : (
                          series.map((s, idx) => (
                            <td key={s.key || idx} className="num mono">
                              {(() => {
                                const n = parseNumber(r[s.key]);
                                return n !== null ? fmtNumber(n) : "—";
                              })()}
                            </td>
                          ))
                        )}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function EditorModal({ mode, initial, sources, onClose, onSaved }) {
  const { allowed: canComposite, message: compositeMsg, ready: compositeReady } =
    useFeatureAccess("composite_charts");
  const isEdit = mode === "edit";
  const [wizardFlow, setWizardFlow] = useState(() => {
    if (isEdit) return "compute";
    return initial?.operation === "multi" ? "composite" : "compute";
  });
  const [form, setForm] = useState(() => ({
    name: initial?.name || "",
    operation: initial?.operation || "ratio",
    left: {
      source_id: initial?.left?.source_id || "",
      indicator_id: initial?.left?.indicator_id || "",
      x_field: initial?.left?.x_field || "",
      y_field: initial?.left?.y_field || "",
      name: initial?.left?.name || "",
    },
    right: {
      source_id: initial?.right?.source_id || "",
      indicator_id: initial?.right?.indicator_id || "",
      x_field: initial?.right?.x_field || "",
      y_field: initial?.right?.y_field || "",
      name: initial?.right?.name || "",
    },
    series: (initial?.series && initial.series.length > 0
      ? initial.series
      : [
          {
            source_id: initial?.left?.source_id || "",
            indicator_id: initial?.left?.indicator_id || "",
            x_field: initial?.left?.x_field || "",
            y_field: initial?.left?.y_field || "",
            chart_type: initial?.left?.chart_type || "line",
            name: initial?.left?.name || "",
          },
          {
            source_id: initial?.right?.source_id || "",
            indicator_id: initial?.right?.indicator_id || "",
            x_field: initial?.right?.x_field || "",
            y_field: initial?.right?.y_field || "",
            chart_type: initial?.right?.chart_type || "line",
            name: initial?.right?.name || "",
          },
        ]).filter((s) => s?.source_id || s?.indicator_id || s?.name || s?.x_field),
    description: initial?.description || "",
    unit: initial?.unit || "",
    options: initial?.options && typeof initial.options === "object" ? initial.options : {},
  }));
  const [nameTouched, setNameTouched] = useState(isEdit);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const wizardMode = !isEdit;
  const [wizStep, setWizStep] = useState(1);
  const [opSearch, setOpSearch] = useState("");
  const [metaLeft, setMetaLeft] = useState(null);
  const [metaRight, setMetaRight] = useState(null);
  const [prevLeft, setPrevLeft] = useState({ rows: [], loading: false, msg: null });
  const [prevRight, setPrevRight] = useState({ rows: [], loading: false, msg: null });
  const [wizPreviewData, setWizPreviewData] = useState(null);
  const [wizPreviewBusy, setWizPreviewBusy] = useState(false);
  const [wizPreviewErr, setWizPreviewErr] = useState("");
  useEffect(() => {
    setWizStep(1);
    setWizPreviewData(null);
    setWizPreviewErr("");
    setOpSearch("");
  }, [isEdit]);

  useEffect(() => {
    if (isEdit) return;
    if (wizardFlow !== "composite") return;
    if (!(compositeReady && canComposite)) {
      setWizardFlow("compute");
      return;
    }
    setForm((f) => ({
      ...f,
      operation: "multi",
      series:
        Array.isArray(f.series) && f.series.length >= 2
          ? f.series
          : [
              { source_id: "", indicator_id: "", x_field: "", y_field: "", chart_type: "line", name: "" },
              { source_id: "", indicator_id: "", x_field: "", y_field: "", chart_type: "line", name: "" },
            ],
    }));
  }, [wizardFlow, isEdit, compositeReady, canComposite]);

  useEffect(() => {
    if (isEdit || wizardFlow !== "compute") return;
    setForm((f) => (f.operation === "multi" ? { ...f, operation: "ratio" } : f));
  }, [wizardFlow, isEdit]);

  useEffect(() => {
    if (!refOkForComputed(form.left)) {
      setPrevLeft({ rows: [], loading: false, msg: null });
      return undefined;
    }
    let cancelled = false;
    setPrevLeft((p) => ({ ...p, loading: true }));
    (async () => {
      const r = await fetchSourcePreviewForRef(form.left);
      if (!cancelled) setPrevLeft({ rows: r.rows || [], loading: false, msg: r.message });
    })();
    return () => {
      cancelled = true;
    };
  }, [form.left]);

  useEffect(() => {
    if (!refOkForComputed(form.right)) {
      setPrevRight({ rows: [], loading: false, msg: null });
      return undefined;
    }
    let cancelled = false;
    setPrevRight((p) => ({ ...p, loading: true }));
    (async () => {
      const r = await fetchSourcePreviewForRef(form.right);
      if (!cancelled) setPrevRight({ rows: r.rows || [], loading: false, msg: r.message });
    })();
    return () => {
      cancelled = true;
    };
  }, [form.right]);
  // Nice-to-have UX: as soon as both indicators are picked, auto-suggest
  // a human-readable name like "DVYBAQ101 ÷ DBAQ101" unless the admin
  // already typed their own.
  useEffect(() => {
    if (nameTouched) return;
    if (form.operation === "multi") {
      const count = (form.series || []).filter((s) => refOkForComputed(s)).length;
      if (count < 2) return;
      setForm((f) => ({ ...f, name: `Složený graf (${count} řad)` }));
      return;
    }
    const la = summarizeSeriesRef(form.left);
    const lb = summarizeSeriesRef(form.right);
    if (unarySeriesA.has(form.operation)) {
      setForm((f) => ({ ...f, name: `${displayOperationLabel(f.operation)} · ${la}` }));
      return;
    }
    if (unarySeriesB.has(form.operation)) {
      setForm((f) => ({ ...f, name: `${displayOperationLabel(f.operation)} · ${lb}` }));
      return;
    }
    if (!refOkForComputed(form.left) || !refOkForComputed(form.right)) return;
    const symbol = {
      ratio: "÷",
      pct: "% ÷",
      sum: "+",
      diff: "−",
      mult: "×",
      pct_points: "pbΔ",
      index_vs_b_pct: "÷×100",
      real_div_infl: "÷defl.",
      corr_pearson: "~ρ~",
      regress_ols: "~OLS~",
    }[form.operation] || "?";
    setForm((f) => ({
      ...f,
      name: `${la} ${symbol} ${lb}`,
    }));
  }, [form.left, form.right, form.operation, form.series, nameTouched]);

  const problems = [];
  if (!form.name.trim()) problems.push("vyplň název");
  if (form.operation === "multi") {
    const validSeries = (form.series || []).filter((s) => refOkForComputed(s));
    if (validSeries.length < 2) problems.push("vyber alespoň 2 řady");
  } else if (form.operation && unarySeriesB.has(form.operation)) {
    if (!refOkForComputed(form.right)) problems.push("vyberte řadu B");
  } else if (form.operation && unarySeriesA.has(form.operation)) {
    if (!refOkForComputed(form.left)) problems.push("vyberte řadu A");
  } else {
    if (!refOkForComputed(form.left)) problems.push("vyberte řadu A");
    if (!refOkForComputed(form.right)) problems.push("vyberte řadu B");
  }
  const valid = problems.length === 0;

  const wizardStep1InputsOk =
    wizardFlow === "composite"
      ? Boolean(
          compositeReady &&
            canComposite &&
            (form.series || []).filter((s) => refOkForComputed(s)).length >= 2,
        )
      : unarySeriesB.has(form.operation)
        ? refOkForComputed(form.right)
        : unarySeriesA.has(form.operation)
          ? refOkForComputed(form.left)
          : refOkForComputed(form.left) && refOkForComputed(form.right);

  const buildPreviewPayload = () => {
    if (form.operation === "multi") {
      const validMulti = (form.series || []).filter((s) => refOkForComputed(s));
      const left = packComputedRefPayload(validMulti[0] || {});
      const right = packComputedRefPayload(validMulti[1] || {});
      return {
        name: form.name || "Náhled",
        operation: "multi",
        left,
        right,
        series: validMulti.map(packComputedRefPayload),
        unit: form.unit || "",
        options: form.options || {},
      };
    }
    return {
      name: form.name || "Náhled",
      operation: form.operation,
      left: packComputedRefPayload(form.left),
      right: packComputedRefPayload(form.right),
      series: [],
      unit: form.unit || "",
      options: form.options || {},
    };
  };

  const runWizardPreview = async () => {
    setWizPreviewBusy(true);
    setWizPreviewErr("");
    try {
      const { data } = await api.post("/computed/preview", buildPreviewPayload());
      setWizPreviewData(data);
      const hasRows = Array.isArray(data?.rows) && data.rows.length > 0;
      if (hasRows) setWizPreviewErr("");
      else setWizPreviewErr("Žádné výstupní řádky — zkontrolujte výběr řad nebo použijte jiný typ výpočtu.");
    } catch (e) {
      const msg =
        safeFormatApiDetail(e.response?.data?.detail) ||
        e.message ||
        "Náhled se nepodařil načíst. Zkontrolujte zvolené řady.";
      setWizPreviewErr(msg);
      setWizPreviewData(null);
    } finally {
      setWizPreviewBusy(false);
    }
  };

  const submit = async () => {
    if (!valid) {
      const msg = "Chybí: " + problems.join(", ");
      setErr(msg);
      toast.error(msg);
      return;
    }
    setBusy(true);
    setErr("");
    try {
      const validMulti = (form.series || []).filter((s) => refOkForComputed(s));
      const packSeries = (s) => ({
        source_id: s.source_id,
        indicator_id: s.indicator_id || "",
        name: s.name || "",
        x_field: s.x_field || "",
        y_field: s.y_field || "",
        chart_type: s.chart_type || "line",
      });
      const payload = {
        name: form.name.trim(),
        operation: form.operation,
        left:
          form.operation === "multi"
            ? packSeries(validMulti[0] || {})
            : { ...form.left },
        right:
          form.operation === "multi"
            ? packSeries(validMulti[1] || {})
            : { ...form.right },
        series: form.operation === "multi" ? validMulti.map(packSeries) : [],
        description: form.description || "",
        unit: form.unit || "",
        options: form.options || {},
      };
      let res;
      if (isEdit) {
        res = await api.put(`/computed/${initial.id}`, payload);
      } else {
        res = await api.post("/computed", payload);
      }
      onSaved(res?.data, mode);
    } catch (e) {
      console.error("[computed/save] request failed", e, e?.response);
      const status = e?.response?.status;
      const detail = e?.response?.data?.detail ?? e?.response?.data;
      const reason =
        formatApiError(detail) ||
        e?.message ||
        (isEdit ? "Nepodařilo se uložit výpočet" : "Nepodařilo se vytvořit výpočet");
      const full = status ? `HTTP ${status} · ${reason}` : reason;
      setErr(full);
      toast.error(full);
    }
    setBusy(false);
  };

  return (
    <div className="fixed inset-0 bg-slate-900/40 backdrop-blur-sm z-40 flex items-center justify-center p-4">
      <div className="computed-editor-modal-scope bg-white text-slate-800 border border-border/80 rounded-2xl shadow-2xl w-full max-w-5xl max-h-[92vh] flex flex-col overflow-hidden">
        <div className="px-5 py-4 border-b border-border/60 flex items-start justify-between gap-3 shrink-0 bg-gradient-to-b from-slate-50/95 to-white">
          <div className="min-w-0">
            <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-slate-500">
              {isEdit ? "Upravit vlastní výpočet" : "Nový vlastní výpočet · analytický průvodce"}
            </div>
            <h3 className="font-serif text-xl sm:text-2xl mt-1 text-slate-900">
              {isEdit ? `Úprava: ${initial?.name || ""}` : "Konfigurovat výstup"}
            </h3>
            <p className="text-[12px] text-slate-600 mt-1.5 max-w-2xl leading-relaxed">
              {wizardMode
                ? "Vyberte režim, datové řady ze všech konektorů (stejný katalog jako náhled zdroje), operaci nebo jen složený graf. Ověřte varování u jednotek a periody před uložením."
                : isEdit
                  ? "Po uložení se výpočet přepočítá nad aktuálními daty v databázi."
                  : "Rychlý formulář pro pokročilé použití."}
            </p>
          </div>
          <button
            type="button"
            aria-label="Zavřít a vrátit se na výpočty"
            onClick={onClose}
            className="shrink-0 h-9 w-9 grid place-items-center rounded-xl border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 hover:text-slate-900 shadow-sm"
          >
            <X className="h-5 w-5" strokeWidth={2} />
          </button>
        </div>

        <div className="px-5 py-4 space-y-4 overflow-auto flex-1">
          {sources.length === 0 && (
            <div className="border border-amber-300 bg-amber-50 text-amber-900 text-xs p-2.5 rounded-lg">
              <strong>V databázi zatím nejsou žádné datové zdroje.</strong> Přidejte a synchronizujte zdroje v sekci „Datové zdroje“.
            </div>
          )}
          {wizardMode ? (
            <>
              <div className="rounded-xl border border-slate-200 bg-gradient-to-br from-[hsl(202_90%_97%)] to-white shadow-sm px-4 py-3 mb-4">
                <div className="text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-500 mb-3">Co vytváříte</div>
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={() => setWizardFlow("compute")}
                    className={`relative flex-1 min-w-[240px] text-left px-4 py-3 rounded-lg border-2 text-sm font-semibold transition ${
                      wizardFlow === "compute"
                        ? "border-[hsl(202_80%_45%)] bg-white shadow-inner"
                        : "border-transparent bg-muted/30 hover:bg-muted/50 text-muted-foreground"
                    }`}
                  >
                    Výpočet z datových řad
                    <span className="block text-[11px] font-normal mt-1 text-muted-foreground leading-snug">
                      Řady A/B (nebo jen A u jednofaktorové analýzy), matematická nebo statistická operace.
                    </span>
                  </button>
                  <button
                    type="button"
                    disabled={compositeReady && !canComposite}
                    onClick={() => {
                      if (compositeReady && !canComposite) {
                        toast.error(compositeMsg || "Složené grafy nemá váš účet odemčené.");
                        return;
                      }
                      setWizardFlow("composite");
                    }}
                    className={`relative flex-1 min-w-[240px] text-left px-4 py-3 rounded-lg border-2 text-sm font-semibold transition ${
                      wizardFlow === "composite"
                        ? "border-[hsl(202_80%_45%)] bg-white shadow-inner"
                        : "border-transparent bg-muted/30 hover:bg-muted/50 text-muted-foreground"
                    } ${compositeReady && !canComposite ? "opacity-55 cursor-not-allowed" : ""}`}
                  >
                    Složený graf bez výpočtu
                    <span className="block text-[11px] font-normal mt-1 text-muted-foreground leading-snug">
                      Dvě a více řad ve společné časové ose bez spojování do jedné odvozené řady.
                    </span>
                  </button>
                </div>
                {!canComposite ? (
                  <div className="mt-3 text-[11px] text-muted-foreground">
                    Poznámka: aktivace složených grafů řeší administrátor (Zamykání funkcí).
                  </div>
                ) : null}
              </div>
              <nav className="flex flex-wrap gap-2" aria-label="Postup výpočtu">
                {[1, 2, 3, 4].map((s) => (
                  <div
                    key={s}
                    className={`text-[11px] px-3 py-1.5 rounded-full border ${
                      wizStep === s
                        ? "border-[hsl(var(--primary-deep))] bg-[hsl(var(--primary-soft))] font-semibold text-[hsl(var(--primary-deep))]"
                        : "border-border text-muted-foreground bg-background"
                    }`}
                  >
                    Krok {s}
                  </div>
                ))}
              </nav>

              {wizStep === 1 && (
                <div className="space-y-4">
                  <div className="data-table-shell bg-muted/10 shadow-sm">
                    <div className="p-4 sm:p-5 space-y-4">
                    <div>
                      <h4 className="text-sm font-semibold mb-2">Krok 1 · {wizardFlow === "composite" ? "Vyberte všechny řady grafu" : "Vyberte vstupní řady"}</h4>
                      <p className="text-xs text-muted-foreground leading-relaxed">
                      {wizardFlow === "composite"
                        ? "Minimálně dvě řady z libovolných datových zdrojů. Stejný výběr jako v náhledu konkrétního datasetu."
                        : "Dvě řady A a B napříč ARAD, Eurostat, ČSÚ, ECB, vlastní uploady apod."}
                      </p>
                    </div>
                    {wizardFlow === "composite" ? (
                      <>
                        {!compositeReady ? (
                          <p className="text-xs text-muted-foreground">Načítám oprávnění ke složeným grafům…</p>
                        ) : compositeReady && !canComposite ? (
                          <FeatureLock className="text-xs" message={compositeMsg || "Složené grafy jsou uzamčené."} />
                        ) : (
                          <MultiSeriesPicker sources={sources} series={form.series || []} onChange={(s) => setForm({ ...form, series: s })} />
                        )}
                      </>
                    ) : unarySeriesB.has(form.operation) ? (
                      <>
                        <IndicatorPicker
                          label="Řada B · jediný vstup této operace"
                          sources={sources}
                          value={form.right}
                          onChange={(v) => setForm({ ...form, right: v })}
                          onIndicatorMeta={setMetaRight}
                          testid="cmp-right-unary"
                          compact
                        />
                        <SeriesEvidencePanel
                          letter="B"
                          sourceName={sources.find((x) => x.id === form.right.source_id)?.name || ""}
                          indicatorRef={form.right}
                          meta={metaRight}
                          previewRows={prevRight.rows}
                          loading={prevRight.loading}
                          previewMessage={prevRight.msg}
                        />
                        <p className="text-[11px] text-muted-foreground border border-[hsl(var(--border)/0.55)] rounded-lg p-3 bg-muted/30">
                          Tato transformace pracuje jen s řadou B (bez párování s A).
                        </p>
                      </>
                    ) : (
                      <>
                        <IndicatorPicker
                          label="Řada A"
                          sources={sources}
                          value={form.left}
                          onChange={(v) => setForm({ ...form, left: v })}
                          onIndicatorMeta={setMetaLeft}
                          testid="cmp-left"
                          compact
                        />
                        <SeriesEvidencePanel
                          letter="A"
                          sourceName={sources.find((x) => x.id === form.left.source_id)?.name || ""}
                          indicatorRef={form.left}
                          meta={metaLeft}
                          previewRows={prevLeft.rows}
                          loading={prevLeft.loading}
                          previewMessage={prevLeft.msg}
                        />
                        {unarySeriesA.has(form.operation) ? (
                          <p className="text-[11px] text-muted-foreground border border-[hsl(var(--border)/0.55)] rounded-lg p-3 bg-muted/30">
                            Zvolená operace používá pouze řadu A — sloupec B ignorujte.
                          </p>
                        ) : (
                          <>
                            <IndicatorPicker
                              label="Řada B"
                              sources={sources}
                              value={form.right}
                              onChange={(v) => setForm({ ...form, right: v })}
                              onIndicatorMeta={setMetaRight}
                              testid="cmp-right"
                              compact
                            />
                            <SeriesEvidencePanel
                              letter="B"
                              sourceName={sources.find((x) => x.id === form.right.source_id)?.name || ""}
                              indicatorRef={form.right}
                              meta={metaRight}
                              previewRows={prevRight.rows}
                              loading={prevRight.loading}
                              previewMessage={prevRight.msg}
                            />
                          </>
                        )}
                      </>
                    )}
                  </div>
                  </div>
                </div>
              )}

              {wizStep === 2 ? (
                wizardFlow === "composite" ? (
                  <div className="rounded-xl border border-border/70 p-4 bg-muted/10 space-y-2">
                    <h4 className="text-sm font-semibold">Krok 2 · Kontrola sad</h4>
                    <p className="text-xs text-muted-foreground leading-relaxed">
                      Složený graf zobrazí každou řadu v jejím vlastním měřítku; sdílena je jen časová nálepka (období). Ověřte, že periody nedělí prázdné mezery
                      způsobené chybějícími synchronizacemi jednotlivých zdrojů.
                    </p>
                    <ul className="text-[11px] font-mono space-y-1">
                      {(form.series || []).filter(refOkForComputed).map((s, i) => (
                        <li key={`${s.source_id}-${i}`}>
                          {i + 1}. {summarizeSeriesRef(s)} · zdroj {sources.find((x) => x.id === s.source_id)?.name || s.source_id}
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : (
                  <CompatibilitySummary metaLeft={metaLeft} metaRight={metaRight} rowsLeft={prevLeft.rows} rowsRight={prevRight.rows} />
                )
              ) : null}

              {wizStep === 3 && (
                <div className="space-y-4">
                  {wizardFlow === "composite" ? (
                    <div className="rounded-xl border border-border/70 p-4 bg-muted/10 space-y-3">
                      <h4 className="text-sm font-semibold">Krok 3 · Pojmenujte graf</h4>
                      <p className="text-xs text-muted-foreground leading-relaxed">
                        Nejde o výpočet — pouze kombinuje vybrané časové řady do jedné vizualizace. Operace jako poměr nebo YoZ zůstávají v režimu „Výpočet z řad“.
                      </p>
                    </div>
                  ) : (
                    <div className="rounded-xl border border-border/70 p-4 bg-muted/10 space-y-4">
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <h4 className="text-sm font-semibold">Krok 3 · Analytická operace</h4>
                      </div>
                      <input
                        className="input max-w-md"
                        placeholder="Filtrovat typ výpočtu…"
                        value={opSearch}
                        onChange={(e) => setOpSearch(e.target.value)}
                      />
                      <div className="space-y-5">
                        {COMPUTED_OP_GROUPS.map(({ category, keys }) => {
                          const qs = opSearch.trim().toLowerCase();
                          const filteredKeys = keys.filter((k) => {
                            const lab = `${displayOperationLabel(k)} ${EXTENDED_OPERATION_LABELS[k] || ""}`.toLowerCase();
                            return !qs || lab.includes(qs);
                          });
                          if (!filteredKeys.length) return null;
                          return (
                            <div key={category}>
                              <div className="text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-500 mb-2">{category}</div>
                              <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-2">
                                {filteredKeys.map((opKey) => {
                                  const lbl = OP_LABELS[opKey]
                                    ? `${OP_LABELS[opKey]}`
                                    : displayOperationLabel(opKey);
                                  const hint = OP_HINT[opKey] || EXTENDED_OPERATION_LABELS[opKey] || "Sledujte diagnostiku a jednotková varování ve výstupním kroku.";
                                  const active = form.operation === opKey;
                                  return (
                                    <button
                                      key={opKey}
                                      type="button"
                                      onClick={() => setForm({ ...form, operation: opKey })}
                                      className={`text-left rounded-lg border p-3 text-xs transition hover:bg-muted/40 min-h-[4.75rem] ${
                                        active ? "border-[hsl(var(--primary-deep))] ring-2 ring-[hsl(var(--primary-soft))]" : "border-border bg-white"
                                      }`}
                                    >
                                      <div className="font-semibold text-sm">{lbl}</div>
                                      <div className="text-muted-foreground mt-1 leading-snug">{hint}</div>
                                      <div className="text-[10px] font-mono text-muted-foreground mt-1">{opKey}</div>
                                    </button>
                                  );
                                })}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                      {form.operation === "roll_mean" ? (
                        <Field label="Velikost okna klouzavého průměru (počet kroků)">
                          <input
                            type="number"
                            min={2}
                            className="input max-w-[180px]"
                            value={Number(form.options?.window || form.options?.rolling_window || 12)}
                            onChange={(e) => {
                              const w = Math.max(2, parseInt(e.target.value, 10) || 12);
                              setForm({ ...form, options: { ...(form.options || {}), window: w } });
                            }}
                          />
                        </Field>
                      ) : null}
                    </div>
                  )}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    <Field label="Název (viditelný v seznamu)">
                      <input
                        className="input"
                        value={form.name}
                        onChange={(e) => {
                          setNameTouched(true);
                          setForm({ ...form, name: e.target.value });
                        }}
                        placeholder="např. HDP parity k úvěrům"
                        data-testid="cmp-name"
                      />
                    </Field>
                    <Field label="Jednotka výsledku (volitelná)">
                      <input
                        className="input"
                        value={form.unit}
                        onChange={(e) => setForm({ ...form, unit: e.target.value })}
                        placeholder="např. %, index, měna"
                      />
                    </Field>
                  </div>
                </div>
              )}

              {wizStep === 4 && (
                <div className="space-y-4 rounded-xl border border-border/70 p-4 bg-background">
                  <h4 className="text-sm font-semibold">Krok 4 · Výsledek</h4>
                  <p className="text-xs text-muted-foreground">
                    Nejdříve spočítejte náhled. Pokud řady nedávají smysl, vraťte se zpět — vstupní data zůstanou viditelná v náhledu výše.
                  </p>
                  <button
                    type="button"
                    disabled={wizPreviewBusy || !valid}
                    onClick={runWizardPreview}
                    className="px-4 min-h-9 rounded-md text-sm font-medium text-white disabled:opacity-45"
                    style={{
                      background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                    }}
                  >
                    {wizPreviewBusy ? "Počítám…" : "Spočítat výsledek"}
                  </button>
                  {wizPreviewErr ? (
                    <div className="text-sm text-rose-800 bg-rose-50 border border-rose-100 rounded-lg px-3 py-2">{wizPreviewErr}</div>
                  ) : null}
                  {wizPreviewData?.warnings?.length ? (
                    <div className="text-sm text-amber-900 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2 space-y-1">
                      <div className="font-semibold">Varování k výpočtu</div>
                      <ul className="list-disc pl-5 text-xs space-y-0.5">
                        {wizPreviewData.warnings.map((w, i) => (
                          <li key={i}>{w}</li>
                        ))}
                      </ul>
                    </div>
                  ) : null}
                  {wizPreviewData && (
                    <>
                      <WizardPreviewMergedTable preview={wizPreviewData} form={form} />
                      {wizPreviewData.rows?.length ? (
                        <WizardPreviewChartMaybe data={wizPreviewData} />
                      ) : null}
                      {!wizPreviewData.rows?.length ? (
                        <p className="text-sm text-muted-foreground">
                          Tabulka je prázdná — výsledek nedává smysl, dokud řady nesdílejí společná období.
                        </p>
                      ) : null}
                    </>
                  )}
                  <details className="rounded-lg border border-dashed border-border/70 p-2 text-[11px]">
                    <summary className="cursor-pointer text-muted-foreground font-medium">
                      Technické detaily (odezva náhledu)
                    </summary>
                    <pre className="mt-2 whitespace-pre-wrap font-mono text-[10px] text-muted-foreground bg-muted/40 p-2 rounded max-h-48 overflow-auto">
                      {wizPreviewData ? JSON.stringify(wizPreviewData, null, 2) : "—"}
                    </pre>
                  </details>
                </div>
              )}
            </>
          ) : (
            <>
              {!isEdit ? (
                <div className="rounded-lg bg-muted/20 border border-border/60 px-3 py-2 text-xs text-muted-foreground mb-3">
                  Pro strukturovaný nový výpočet použijte tlačítko „Nový výpočet“. Níže je rozšířené najednou pro složený graf a úpravy.
                </div>
              ) : null}
              <div className="grid grid-cols-1 lg:grid-cols-[1.4fr_1fr] gap-3 rounded-xl border border-border/70 p-3 bg-muted/10">
                <Field label="Název výpočtu (jak se zobrazí v aplikaci)">
                  <input
                    className="input"
                    value={form.name}
                    onChange={(e) => {
                      setNameTouched(true);
                      setForm({ ...form, name: e.target.value });
                    }}
                    placeholder="např. Pokrytí rezerv k vkladům"
                    data-testid="cmp-name-flat"
                  />
                  {!nameTouched && form.name && (
                    <div className="text-[11px] text-muted-foreground mt-1">Název byl navržen automaticky · můžete přepsat</div>
                  )}
                </Field>

                <Field label="Typ výpočtu">
                  <select
                    className="input"
                    value={form.operation}
                    onChange={(e) => {
                      const op = e.target.value;
                      setForm((f) => ({
                        ...f,
                        operation: op,
                        series:
                          op === "multi" && (!f.series || f.series.length === 0)
                            ? [
                                {
                                  source_id: "",
                                  indicator_id: "",
                                  x_field: "",
                                  y_field: "",
                                  chart_type: "line",
                                  name: "",
                                },
                                {
                                  source_id: "",
                                  indicator_id: "",
                                  x_field: "",
                                  y_field: "",
                                  chart_type: "line",
                                  name: "",
                                },
                              ]
                            : f.series,
                      }));
                    }}
                    data-testid="cmp-op"
                  >
                    {Object.entries(OP_LABELS).map(([k, v]) => (
                      <option key={k} value={k} disabled={k === "multi" && !canComposite}>
                        {v}
                        {k === "multi" && compositeReady && !canComposite ? " — jen s přístupem ke složeným grafům" : ""}
                      </option>
                    ))}
                    {Object.entries(EXTENDED_OPERATION_LABELS).map(([k, v]) => (
                      <option key={`ext-${k}`} value={k}>
                        {v}
                      </option>
                    ))}
                  </select>
                  <p className="text-[11px] text-muted-foreground mt-1.5 leading-snug">
                    Pro rozšířený náhled indexu, meziroční změny a zarovnání období použijte na hlavní stránce sekci „Technické detaily“.
                  </p>
                </Field>
              </div>
              {compositeReady && !canComposite && (
                <FeatureLock
                  className="text-[11px] py-2"
                  message={
                    compositeMsg ||
                    "Složené grafy jsou pro váš účet uzamčeny. Změní správce v sekci Zamykání funkcí."
                  }
                />
              )}

              <div className="rounded-xl border border-border/70 p-3 bg-muted/10 space-y-3">
                <h4 className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">Datové řady</h4>
                {form.operation !== "multi" ? (
                  <>
                    <IndicatorPicker
                      label="Řada A"
                      sources={sources}
                      value={form.left}
                      onChange={(v) => setForm({ ...form, left: v })}
                      onIndicatorMeta={setMetaLeft}
                      testid="cmp-left-flat"
                      compact
                    />
                    <SeriesEvidencePanel
                      letter="A"
                      sourceName={sources.find((x) => x.id === form.left.source_id)?.name || ""}
                      indicatorRef={form.left}
                      meta={metaLeft || { indicator_id: form.left.indicator_id, name: form.left.name }}
                      previewRows={prevLeft.rows}
                      loading={prevLeft.loading}
                      previewMessage={prevLeft.msg}
                    />
                    <IndicatorPicker
                      label="Řada B"
                      sources={sources}
                      value={form.right}
                      onChange={(v) => setForm({ ...form, right: v })}
                      onIndicatorMeta={setMetaRight}
                      testid="cmp-right-flat"
                      compact
                    />
                    <SeriesEvidencePanel
                      letter="B"
                      sourceName={sources.find((x) => x.id === form.right.source_id)?.name || ""}
                      indicatorRef={form.right}
                      meta={metaRight || { indicator_id: form.right.indicator_id, name: form.right.name }}
                      previewRows={prevRight.rows}
                      loading={prevRight.loading}
                      previewMessage={prevRight.msg}
                    />
                  </>
                ) : (
                  <MultiSeriesPicker sources={sources} series={form.series || []} onChange={(series) => setForm({ ...form, series })} />
                )}
              </div>

              <div className="rounded-xl border border-border/70 p-3 bg-muted/10">
                <Field label="Jednotka výsledku (volitelné)">
                  <input
                    className="input"
                    value={form.unit}
                    onChange={(e) => setForm({ ...form, unit: e.target.value })}
                    placeholder="např. % nebo bezrozměrné"
                  />
                </Field>
              </div>

              {valid && <ComputedPreview form={form} />}
            </>
          )}

          {err && (
            <div className="border border-destructive/40 bg-destructive/5 text-destructive text-sm p-3 rounded-lg">
              {err}
            </div>
          )}
        </div>

        <div className="px-5 py-3 border-t border-border/60 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 shrink-0 bg-slate-50/90">
          {wizardMode ? (
            <>
              <div className="text-[11px] text-muted-foreground min-w-0">
                Krok {wizStep}/4 ·{" "}
                {wizStep < 4
                  ? "Po dokončení posledního kroku můžete výpočet uložit."
                  : wizPreviewData?.rows?.length
                    ? "Náhled má data — nyní můžete uložit."
                    : 'Nejdříve klikněte na „Spočítat výsledek“. '}
              </div>
              <div className="flex flex-wrap gap-2 justify-end">
                <button type="button" onClick={onClose} className="px-3 min-h-9 text-sm border border-border rounded-md bg-background">
                  Zrušit
                </button>
                {wizStep > 1 ? (
                  <button type="button" className="px-3 min-h-9 text-sm border border-border rounded-md inline-flex items-center gap-1" onClick={() => setWizStep((w) => w - 1)}>
                    <ChevronLeft className="h-4 w-4" aria-hidden /> Zpět
                  </button>
                ) : null}
                {wizStep < 4 ? (
                  <button
                    type="button"
                    disabled={
                      (wizStep === 1 && !wizardStep1InputsOk) || (wizStep === 3 && !form.name.trim())
                    }
                    className="px-4 min-h-9 text-sm rounded-md border border-transparent text-white disabled:opacity-45 inline-flex items-center gap-1"
                    style={{ background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))" }}
                    onClick={() => setWizStep((w) => Math.min(4, w + 1))}
                  >
                    Další <ChevronRight className="h-4 w-4" aria-hidden />
                  </button>
                ) : null}
                {wizStep === 4 ? (
                  <>
                    <button
                      type="button"
                      disabled={wizPreviewBusy || !valid}
                      onClick={runWizardPreview}
                      className="px-4 min-h-9 text-sm rounded-md border border-border bg-background hover:bg-muted/50 disabled:opacity-45"
                    >
                      {wizPreviewBusy ? "Počítám…" : "Spočítat znovu"}
                    </button>
                    <button
                      disabled={busy || !valid || !wizPreviewData?.rows?.length}
                      onClick={submit}
                      className="px-5 min-h-9 text-sm rounded-md text-white transition-opacity disabled:opacity-45"
                      style={{
                        background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                        boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
                      }}
                      data-testid="cmp-save"
                    >
                      {busy ? "Ukládám…" : "Uložit výpočet"}
                    </button>
                  </>
                ) : null}
              </div>
            </>
          ) : (
            <>
              <div className="text-[11px] text-muted-foreground min-w-0">
                {valid ? <span>Vše potřebné je vyplněno.</span> : <span>Vyberte údaje označené jako chybějící: {problems.join(" · ")}</span>}
              </div>
              <div className="flex gap-2">
                <button type="button" onClick={onClose} className="px-3 min-h-9 text-sm border border-border rounded-md bg-background">
                  Zrušit
                </button>
                <button
                  type="button"
                  disabled={busy}
                  onClick={submit}
                  title={valid ? "Uložit výpočet" : "Doplňte: " + problems.join(", ")}
                  className="px-5 min-h-9 text-sm rounded-md text-white transition-opacity disabled:opacity-50"
                  style={
                    valid
                      ? {
                          background:
                            "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                          boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
                        }
                      : {
                          background: "hsl(205 35% 80%)",
                          cursor: "not-allowed",
                        }
                  }
                  data-testid="cmp-save-flat"
                >
                  {busy ? "Ukládám…" : isEdit ? "Uložit změny" : "Vytvořit"}
                </button>
              </div>
            </>
          )}
        </div>
      </div>

      <style>{`.input{width:100%;height:34px;border:1px solid hsl(var(--border));border-radius:0.75rem;padding:0 9px;font-size:12px;background:white;color:hsl(218 28% 14%)}
        textarea.input{height:auto;padding:7px 9px}
        .input:focus{outline:none;box-shadow:0 0 0 1px hsl(var(--ring))}`}</style>
    </div>
  );
}

function IndicatorPicker({ label, sources, value, onChange, testid, compact = false, lockSource = false, onIndicatorMeta }) {
  const [catalog, setCatalog] = useState(null);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [filter, setFilter] = useState("");
  const selectedRowRef = React.useRef(null);
  const sourceId = value?.source_id || "";
  const selectedId = (value?.indicator_id || "").trim();

  const sourceMeta = useMemo(() => sources.find((s) => s.id === sourceId), [sources, sourceId]);

  useEffect(() => {
    if (!sourceId) {
      setCatalog(null);
      setCatalogLoading(false);
      return undefined;
    }
    let cancelled = false;
    setCatalogLoading(true);
    api
      .get(`/sources/${sourceId}/indicator-catalog`)
      .then(({ data }) => {
        if (!cancelled) setCatalog(data);
      })
      .catch((e) => {
        if (!cancelled) toast.error(safeFormatApiDetail(e.response?.data?.detail) || e.message);
      })
      .finally(() => {
        if (!cancelled) setCatalogLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [sourceId]);

  const indicators = Array.isArray(catalog?.indicators) ? catalog.indicators : [];
  const groupField = catalog?.group_field || null;
  const peekFields = Array.isArray(catalog?.peek_fields) ? catalog.peek_fields : [];
  const hasStructuredSeries = !!(groupField && indicators.length > 0);
  const canTabularFallback = !hasStructuredSeries && peekFields.length > 0;

  const filtered = useMemo(() => {
    if (!filter.trim()) return indicators;
    const q = filter.toLowerCase();
    return indicators.filter((i) => {
      const id = (i.indicator_id || i.id || "").toString().toLowerCase();
      const nm = (i.name || i.title || "").toLowerCase();
      const geo = (i.geography || "").toLowerCase();
      const cat = (i.category || "").toLowerCase();
      return id.includes(q) || nm.includes(q) || geo.includes(q) || cat.includes(q);
    });
  }, [indicators, filter]);

  useEffect(() => {
    if (selectedId) {
      const row = indicators.find((i) => (i.indicator_id || i.id || "").toString() === selectedId) || null;
      onIndicatorMeta?.(
        row
          ? {
              ...row,
              name: row.title || row.name,
              frequency_code: row.frequency_code || row.frequency || "",
            }
          : null,
      );
      return;
    }
    const xf = (value?.x_field || "").trim();
    const yf = (value?.y_field || "").trim();
    if (xf && yf) {
      onIndicatorMeta?.({
        name: `Sloupec ${yf}`,
        indicator_id: "",
        unit: "",
        frequency_code: "",
      });
    } else {
      onIndicatorMeta?.(null);
    }
  }, [selectedId, indicators, value?.x_field, value?.y_field, onIndicatorMeta]);

  useEffect(() => {
    if (!selectedId || indicators.length === 0) return;
    const t = setTimeout(() => {
      selectedRowRef.current?.scrollIntoView({ block: "nearest" });
    }, 50);
    return () => clearTimeout(t);
  }, [selectedId, indicators.length]);

  const selMeta = indicators.find((i) => (i.indicator_id || i.id || "").toString() === selectedId) || {};
  const selectedName = selMeta.title || selMeta.name || "";
  const tabularChosen = !!(value?.x_field && value?.y_field && !selectedId);
  const rowHighlight = !!(selectedId || tabularChosen);

  const reloadCatalog = async () => {
    if (!sourceId) return;
    setRefreshing(true);
    try {
      if (sourceMeta?.source_type === "arad") {
        await api.post(`/sources/${sourceId}/arad/refresh-indicators`);
      }
      const { data } = await api.get(`/sources/${sourceId}/indicator-catalog`);
      setCatalog(data);
      toast.success("Katalog datových řad byl aktualizován.");
    } catch (e) {
      toast.error(safeFormatApiDetail(e.response?.data?.detail) || e.message);
    }
    setRefreshing(false);
  };

  const selectIndicator = (indId) => {
    const row = indicators.find((i) => (i.indicator_id || i.id || "").toString() === String(indId)) || {};
    const nm = row.title || row.name || "";
    onChange({
      ...value,
      source_id: sourceId,
      indicator_id: String(indId),
      name: nm,
      x_field: "",
      y_field: "",
    });
  };

  const pickerShellCls = compact
    ? `rounded-lg overflow-hidden border border-[hsl(var(--border)/0.6)] bg-muted/25 shadow-sm p-3${
        rowHighlight ? " ring-1 ring-[hsl(218_55%_55%_/0.35)] border-[hsl(218_55%_60%_/0.82)]" : ""
      }`
    : `border rounded-sm bg-slate-50/40 p-4 ${rowHighlight ? "border-[hsl(218_55%_65%)]" : "border-border"}`;

  return (
    <div className={pickerShellCls}>
      <div className={`flex items-center justify-between ${compact ? "mb-2" : "mb-3"}`}>
        <div className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">
          {label}
        </div>
        {rowHighlight ? (
          <div className="text-[11px] font-mono text-[hsl(218_60%_32%)] truncate max-w-[60%]" title={selectedName}>
            ✓{" "}
            {selectedId
              ? `${selectedId} ${selectedName ? `· ${selectedName.slice(0, 48)}${selectedName.length > 48 ? "…" : ""}` : ""}`
              : `tabulární sloupce ${value.y_field} / ${value.x_field}`}
          </div>
        ) : (
          <div className="text-[11px] text-slate-400 font-mono">zatím nevybráno</div>
        )}
      </div>
      <div className={`grid grid-cols-1 md:grid-cols-12 ${compact ? "gap-2" : "gap-3"}`}>
        {!lockSource ? (
          <div className="md:col-span-7">
            <Field label="Datový zdroj">
              <select
                className="input"
                value={sourceId}
                onChange={(e) =>
                  onChange({
                    ...value,
                    source_id: e.target.value,
                    indicator_id: "",
                    name: "",
                    x_field: "",
                    y_field: "",
                  })
                }
                data-testid={`${testid}-src`}
              >
                <option value="">— vyberte zdroj —</option>
                {sources.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name} ({s.source_type})
                  </option>
                ))}
              </select>
            </Field>
          </div>
        ) : null}
        <div className={`${lockSource ? "md:col-span-12" : "md:col-span-5"} flex items-end gap-2`}>
          <button
            type="button"
            onClick={reloadCatalog}
            disabled={!sourceId || refreshing || catalogLoading}
            className={`${compact ? "h-8" : "h-9"} px-3 text-xs border border-border rounded-sm hover:bg-slate-100 disabled:opacity-40 flex-1 bg-white flex items-center justify-center gap-1`}
          >
            <RefreshCw className={`h-3.5 w-3.5 ${refreshing || catalogLoading ? "animate-spin" : ""}`} />
            Aktualizovat katalog ({indicators.length})
          </button>
        </div>

        {!sourceId ? (
          <div className="md:col-span-12 text-[11px] text-muted-foreground">Nejprve zvolte zdroj výše.</div>
        ) : catalogLoading ? (
          <div className="md:col-span-12 text-[11px] text-muted-foreground">Načítám jednotný katalog řad…</div>
        ) : hasStructuredSeries ? (
          <div className="md:col-span-12">
            <Field label={`Řady v datasetu · ${sourceMeta?.source_type || "?"}`}>
              <input
                className={`input ${compact ? "mb-1.5" : "mb-2"}`}
                placeholder="Hledat kód, název, zemi, kategorii…"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
              />
            </Field>
            <div
              className={`${compact ? "rounded-md" : "rounded-xl"} border border-[hsl(var(--border)/0.65)] bg-white overflow-hidden`}
              data-testid={`${testid}-ind`}
            >
              <div
                className="overflow-x-auto overflow-y-auto"
                style={{ maxHeight: compact ? 200 : 280, minHeight: compact ? 96 : 120 }}
              >
              {filtered.length === 0 ? (
                <div className={`px-3 ${compact ? "py-4 text-[11px]" : "py-5 text-[12px]"} text-slate-400 text-center`}>
                  Žádný záznam neodpovídá filtru
                </div>
              ) : (
                filtered.map((i) => {
                  const iid = (i.indicator_id || i.id || "").toString();
                  const fq = i.frequency_code || i.frequency || "?";
                  const isSelected = iid === selectedId;
                  const lastPv =
                    i.last_value !== null &&
                    i.last_value !== undefined &&
                    Number.isFinite(Number(i.last_value))
                      ? fmtNumber(Number(i.last_value))
                      : null;
                  return (
                    <button
                      key={iid}
                      type="button"
                      ref={isSelected ? selectedRowRef : null}
                      onClick={() => selectIndicator(iid)}
                      title={i.title || i.name || iid}
                      className={`w-full text-left px-3 py-2 border-b border-border/40 text-[11px] flex flex-wrap gap-x-2 gap-y-1 items-baseline hover:bg-muted/40 ${
                        isSelected ? "bg-[hsl(202_90%_92%)] font-medium" : ""
                      }`}
                    >
                      <span className="shrink-0 font-mono text-[10px] text-muted-foreground w-9">[{fq}]</span>
                      <span className="shrink-0 font-mono">{iid}</span>
                      <span className="flex-1 min-w-[120px] break-words">{i.title || i.name || "(bez názvu)"}</span>
                      <span className="w-full text-[10px] text-muted-foreground pl-11">
                        zdroj: {i.source_name || sourceMeta?.name || ""}
                        {i.geography ? <> · oblast {i.geography}</> : null}
                        {i.unit ? <> · jedn. {i.unit}</> : null}
                        {i.category ? <> · kat. {i.category}</> : null}
                        {i.last_period ? <> · posl. obd. {fmtPeriod(i.last_period, { variant: "axis" })}</> : null}
                        {lastPv ? <> · posl. hodnota {lastPv}</> : null}
                      </span>
                    </button>
                  );
                })
              )}
              </div>
            </div>
            <div className="text-[10px] text-slate-500 font-mono mt-1">{`${filtered.length} / ${indicators.length} řad · ${groupField}`}</div>
          </div>
        ) : canTabularFallback ? (
          <div className="md:col-span-12 space-y-2 overflow-hidden rounded-lg border border-[hsl(var(--border)/0.6)] p-2.5 bg-white">
            <div className="text-[11px] font-semibold text-slate-700">Tabulární řada</div>
            <p className="text-[10px] text-muted-foreground leading-snug">
              Dataset bez strukturovaných identifikátorů — zvolte sloupce času a hodnoty z nahraného souboru.
            </p>
            <TabularFieldPicker sourceId={sourceId} row={value} onChange={onChange} compact={compact} />
          </div>
        ) : (
          <div className="md:col-span-12 text-xs text-amber-900 bg-amber-50 border border-amber-200 rounded-md p-3">
            Pro výběr chybí načtená data. Synchronizujte zdroj v sekci dat.
            {catalog?.message ? <div className="mt-2 font-mono text-[11px]">{catalog.message}</div> : null}
          </div>
        )}
      </div>
    </div>
  );
}

function TabularFieldPicker({ sourceId, row, onChange, compact }) {
  const [fields, setFields] = useState([]);
  useEffect(() => {
    if (!sourceId) {
      setFields([]);
      return;
    }
    api
      .get(`/sources/${sourceId}/preview?limit=80`)
      .then(({ data }) => setFields(data?.fields || []))
      .catch((e) => toast.error(safeFormatApiDetail(e.response?.data?.detail) || e.message));
  }, [sourceId]);

  return (
    <div className={`grid grid-cols-1 md:grid-cols-2 ${compact ? "gap-2" : "gap-3"}`}>
      <Field label="Sloupec období / času (X)">
        <select
          className="input"
          value={row.x_field || ""}
          disabled={!sourceId}
          onChange={(e) =>
            onChange({
              ...row,
              x_field: e.target.value,
              indicator_id: "",
              name: "",
            })
          }
        >
          <option value="">—</option>
          {fields.map((f) => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Číselná hodnota (Y)">
        <select
          className="input"
          value={row.y_field || ""}
          disabled={!sourceId}
          onChange={(e) =>
            onChange({
              ...row,
              y_field: e.target.value,
              indicator_id: "",
              name: "",
            })
          }
        >
          <option value="">—</option>
          {fields.map((f) => (
            <option key={`y-${f}`} value={f}>
              {f}
            </option>
          ))}
        </select>
      </Field>
    </div>
  );
}

function MultiSeriesPicker({ sources, series, onChange }) {
  const rows = Array.isArray(series) ? series : [];
  const addRow = () => {
    onChange([
      ...(rows || []),
      { source_id: "", indicator_id: "", x_field: "", y_field: "", chart_type: "line", name: "" },
    ]);
  };
  const removeRow = (idx) => {
    onChange(rows.filter((_, i) => i !== idx));
  };
  const updateRow = (idx, value) => {
    onChange(rows.map((r, i) => (i === idx ? value : r)));
  };
  const validCount = rows.filter(
    (s) => s?.source_id && (s?.indicator_id || (s?.x_field && s?.y_field))
  ).length;

  return (
    <div className="data-table-shell bg-muted/25 p-3 sm:p-4 space-y-2.5 shadow-sm">
      <div className="flex items-center justify-between gap-2">
        <div className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">
          Řady pro složený graf
        </div>
        <div className="text-[11px] font-mono text-slate-500">
          vybráno: {validCount} řad
        </div>
      </div>

      <div className="space-y-2">
        {rows.map((row, idx) => {
          return (
            <div key={idx} className="overflow-hidden rounded-lg border border-[hsl(var(--border)/0.55)] bg-white p-2.5 space-y-2 shadow-sm">
              <div className="flex items-center justify-between mb-1">
                <div className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">
                  Řada {idx + 1}
                </div>
                <button
                  type="button"
                  onClick={() => removeRow(idx)}
                  className="text-[11px] text-rose-600 hover:underline"
                >
                  Odebrat
                </button>
              </div>
              <Field label="Datový zdroj (synchronizovaná data)">
                <select
                  className="input"
                  value={row.source_id || ""}
                  onChange={(e) =>
                    updateRow(idx, {
                      source_id: e.target.value,
                      indicator_id: "",
                      x_field: "",
                      y_field: "",
                      name: "",
                      chart_type: row.chart_type || "line",
                    })
                  }
                  data-testid={`cmp-series-${idx + 1}-src`}
                >
                  <option value="">— vyberte zdroj —</option>
                  {sources.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name} ({s.source_type})
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Typ vykreslení této řady">
                <select
                  className="input"
                  value={row.chart_type || "line"}
                  onChange={(e) => updateRow(idx, { ...row, chart_type: e.target.value })}
                >
                  <option value="line">Spojnice</option>
                  <option value="bar">Sloupce</option>
                  <option value="area">Plocha</option>
                </select>
              </Field>
              {!row.source_id ? (
                <div className="text-[11px] text-slate-400 font-mono py-2">Nejprve vyberte zdroj.</div>
              ) : (
                <IndicatorPicker
                  label={`Datová řada ${idx + 1}`}
                  sources={sources}
                  value={row}
                  onChange={(v) => updateRow(idx, { ...v, chart_type: row.chart_type || "line" })}
                  testid={`cmp-series-${idx + 1}`}
                  compact
                  lockSource
                />
              )}
            </div>
          );
        })}
      </div>

      <button
        type="button"
        onClick={addRow}
        className="h-8 px-3 text-xs border border-border rounded-sm hover:bg-slate-100 bg-white inline-flex items-center gap-1.5"
      >
        <Plus className="h-3.5 w-3.5" />
        Přidat řadu
      </button>
      <div className="text-[11px] text-slate-500 font-mono">
        Každá řada: strukturovaný ukazatel nebo vlastní upload (sloupce X/Y) přes jednotný výběr.
      </div>
    </div>
  );
}

function Field({ label, children }) {
  return (
    <div>
      <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">{label}</label>
      <div className="mt-1.5">{children}</div>
    </div>
  );
}

/** Čitelný souhrn odpovědi `/api/calculations/plan` — žádný syrový JSON v hlavní vrstvě. */
function summarizeAiCalculationPlan(plan) {
  const lines = [];
  if (!plan || typeof plan !== "object") return lines;

  const op = plan.suggested_operation;
  const intent = plan.intent;

  let opCz =
    op === "ratio"
      ? "poměr A / B"
      : op === "ratio_pct" || op === "ratio_percent"
        ? "poměr A / B vyjádřený v procentech"
        : op === "pearson_correlation"
          ? "korelační analýza (Pearson)"
          : null;

  if (!opCz && intent === "calculate_yoy") opCz = "meziroční změna (YoY)";
  if (!opCz && intent === "index_100_normalize") opCz = "index s bází 100 ve zvoleném období";
  if (!opCz && intent === "sum_series") opCz = "součet více řad";
  if (!opCz && intent === "calculate_roa") opCz = "ROA (návrh struktury)";
  if (!opCz && intent === "calculate_roe") opCz = "ROE (návrh struktury)";
  if (!opCz && intent === "calculate_npl_ratio") opCz = "poměr NPL (návrh struktury)";
  if (!opCz && intent === "calculate_ldr") opCz = "poměr úvěrů k vkladům (návrh struktury)";
  if (!opCz && intent === "correlation") opCz = "korelace řad";
  if (!opCz && intent === "forecast") opCz = "prognóza / odhad (zatím jen koncept)";

  if (opCz) lines.push(`AI navrhuje výpočet: ${opCz}.`);
  else if (intent && intent !== "generic_calculation") {
    lines.push(
      intent === "unknown"
        ? "AI nedokázala výpočet jednoznačně zařadit — upřesněte otázku."
        : `AI navrhuje zaměření: ${String(intent).replace(/_/g, " ")}.`,
    );
  }

  const req = Array.isArray(plan.required_series) ? plan.required_series : [];
  if (req.length >= 1) lines.push(`Potřebné řady: ${req.length}.`);
  else lines.push("AI zatím navrhla pouze typ výpočtu. Vyberte datové řady ručně.");

  lines.push("Před výpočtem je nutné zkontrolovat jednotky a společná období.");

  const norm = plan.normalization || {};
  if (
    norm.frequency_alignment ||
    norm.frequency_alignment === true ||
    typeof norm.frequency_alignment === "string"
  ) {
    lines.push("Doporučené zarovnání období: použít pouze společná období, kde obě řady mají hodnoty.");
  }
  if (norm.scale_alignment) {
    lines.push("Zkontrolujte jednotku / měřítko řad — přepočty se v tomto náhledu neaplikují automaticky.");
  }

  for (const w of plan.warnings || []) {
    if (w) lines.push(String(w));
  }
  for (const c of plan.user_confirmations_needed || []) {
    if (c) lines.push(String(c));
  }

  return lines;
}

const ADVANCED_CALC_OPS = [
  { value: "ratio", label: "A ÷ B (podíl)" },
  { value: "sum", label: "A + B" },
  { value: "diff", label: "A − B" },
  { value: "mult", label: "A × B" },
  { value: "pct", label: "(A ÷ B) × 100" },
  { value: "multi_sum", label: "Součet A + B + … (stejná období)" },
  { value: "index_100", label: "Index = 100 v základním období" },
  { value: "yoy_pct", label: "YoY % (kvartální párování)" },
];

function defaultCalcOperand() {
  return {
    source_id: "",
    indicator_id: "",
    name: "",
    x_field: "",
    y_field: "",
    chart_type: "line",
    manual_multiplier: 1,
    declared_scale: "",
    unit: "unknown",
    frequency: "unknown",
    currency: "",
  };
}

/** Náhled přes /api/calculations/compute (neukládá se do vlastních výpočtů). */
function TechnicalCalculationsSection({ sources }) {
  const [operation, setOperation] = useState("ratio");
  const [operands, setOperands] = useState([defaultCalcOperand(), defaultCalcOperand()]);
  const [basePeriod, setBasePeriod] = useState("2020Q1");
  const [busy, setBusy] = useState(false);
  const [aiQ, setAiQ] = useState("");
  const [aiPlan, setAiPlan] = useState(null);
  const [out, setOut] = useState(null);

  const setOpKind = (next) => {
    setOperation(next);
    if (next === "index_100" || next === "yoy_pct") {
      setOperands([defaultCalcOperand()]);
    } else if (next === "multi_sum") {
      setOperands((prev) => {
        if (prev.length >= 2) return [...prev];
        return [defaultCalcOperand(), defaultCalcOperand()];
      });
    } else {
      setOperands((prev) => [prev[0] || defaultCalcOperand(), prev[1] || defaultCalcOperand()]);
    }
  };

  const updateRow = (idx, value) => {
    setOperands((rows) => rows.map((r, i) => (i === idx ? value : r)));
  };

  const addOperand = () => setOperands((rows) => [...rows, defaultCalcOperand()]);
  const removeOperand = (idx) => setOperands((rows) => rows.filter((_, i) => i !== idx));

  const buildPayload = () => {
    const pack = (o) => {
      const ds = (o.declared_scale ?? "").toString().trim();
      return {
        source_id: o.source_id,
        indicator_id: o.indicator_id || "",
        name: o.name || "",
        x_field: o.x_field || "",
        y_field: o.y_field || "",
        chart_type: o.chart_type || "line",
        manual_multiplier: Number(o.manual_multiplier) === 0 ? 1 : Number(o.manual_multiplier) || 1,
        declared_scale: ds === "" ? null : Number(ds),
        unit: o.unit || "unknown",
        frequency: o.frequency || "unknown",
        currency: (o.currency || "").trim() || null,
      };
    };
    return {
      operation,
      operands: operands.map(pack),
      base_period: operation === "index_100" ? basePeriod.trim() : null,
    };
  };

  const runCompute = async () => {
    setBusy(true);
    setOut(null);
    try {
      const { data } = await api.post("/calculations/compute", buildPayload());
      setOut(data);
      toast.success("Výpočet dokončen (náhled, neukládá se do DB)");
    } catch (e) {
      const msg = safeFormatApiDetail(e.response?.data?.detail) || e.message;
      toast.error(msg);
    } finally {
      setBusy(false);
    }
  };

  const runSuggestScale = async () => {
    if (operands.length < 2) return;
    try {
      const a = (operands[0].declared_scale ?? "").toString().trim();
      const b = (operands[1].declared_scale ?? "").toString().trim();
      const { data } = await api.post("/calculations/suggest-scale", {
        scale_a: a === "" ? null : Number(a),
        scale_b: b === "" ? null : Number(b),
      });
      if (data?.note_cs) {
        toast.success(String(data.note_cs).slice(0, 600), { duration: 8000 });
      } else {
        toast.success("Zadejte jednotku / měřítko u obou řad pro návrh přepočtu.", { duration: 4000 });
      }
    } catch (e) {
      toast.error(safeFormatApiDetail(e.response?.data?.detail) || e.message);
    }
  };

  const runPlan = async () => {
    if (!aiQ.trim()) return;
    try {
      const { data } = await api.post("/calculations/plan", { question: aiQ.trim() });
      setAiPlan(data);
    } catch (e) {
      toast.error(safeFormatApiDetail(e.response?.data?.detail) || e.message);
    }
  };

  const unary = operation === "index_100" || operation === "yoy_pct";
  const multiSum = operation === "multi_sum";

  const seriesRows = Array.isArray(out?.result_series) ? out.result_series : EMPTY_ROWS;
  const chartNumeric = seriesRows
    .map((r) => {
      const y = parseNumber(r.value);
      return { x: r.period, y: y !== null ? y : NaN };
    })
    .filter((c) => typeof c.y === "number" && Number.isFinite(c.y));

  return (
    <section className="soft-card overflow-hidden mb-6">
      <div className="px-5 py-4 border-b border-border/50 flex items-center gap-3">
        <Calculator className="h-4 w-4 text-slate-600" strokeWidth={1.8} />
        <div>
          <div className="kpi-label" id="pokrocile-vypocty-anchor">
            Pokročilý náhled výpočtu (bez uložení)
          </div>
          <p className="text-[12px] text-slate-500 mt-1 max-w-3xl leading-relaxed">
            Ruční přepočet měřítka · zarovnání na společná období · YoY · index · součty více řad. Výsledek je jen náhled; do seznamu
            vlastních výpočtů se neukládá — použijte ho jako vodítko a zadejte totéž v průvodci výše.
          </p>
        </div>
      </div>

      <div className="p-5 space-y-6">
        <div className="flex flex-wrap gap-4 items-end">
          <Field label="Operace">
            <select
              className="input min-w-[260px]"
              value={operation}
              onChange={(e) => setOpKind(e.target.value)}
              data-testid="calc-advanced-op"
            >
              {ADVANCED_CALC_OPS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          {operation === "index_100" && (
            <Field label="Základní období (index = 100)">
              <input
                className="input w-40 font-mono"
                value={basePeriod}
                onChange={(e) => setBasePeriod(e.target.value)}
                placeholder="2020Q1"
              />
            </Field>
          )}
        </div>

        <div className="space-y-3">
          {operands.map((row, idx) => {
            const src = sources.find((s) => s.id === row.source_id);
            const isArad = src?.source_type === "arad";
            return (
              <div
                key={idx}
                className="border border-border/60 rounded-sm bg-slate-50/40 p-4 space-y-3"
                data-testid={`calc-advanced-operand-${idx}`}
              >
                <div className="flex justify-between items-center gap-2 flex-wrap">
                  <div className="text-[11px] uppercase tracking-[0.1em] text-slate-600 font-medium">
                    {unary ? "Řada" : `Řada ${String.fromCharCode(65 + idx)}`}
                  </div>
                  {multiSum && operands.length > 2 && (
                    <button type="button" className="text-[11px] text-rose-600 hover:underline" onClick={() => removeOperand(idx)}>
                      Odebrat
                    </button>
                  )}
                </div>
                <Field label="Zdroj dat">
                  <select
                    className="input"
                    value={row.source_id || ""}
                    onChange={(e) =>
                      updateRow(idx, {
                        ...row,
                        source_id: e.target.value,
                        indicator_id: "",
                        x_field: "",
                        y_field: "",
                        name: "",
                      })
                    }
                  >
                    <option value="">— vyberte zdroj —</option>
                    {sources.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.name} ({s.source_type})
                      </option>
                    ))}
                  </select>
                </Field>
                {!row.source_id ? (
                  <div className="text-[11px] text-slate-400 font-mono">Nejprve zvolte zdroj.</div>
                ) : isArad ? (
                  <IndicatorPicker
                    label="ARAD indikátor"
                    sources={sources}
                    value={row}
                    onChange={(v) => updateRow(idx, { ...v })}
                    testid={`calc-adv-arad-${idx}`}
                    compact
                    lockSource
                  />
                ) : (
                  <div className="border rounded-sm p-2 bg-white border-border/60">
                    <div className="text-[10px] uppercase tracking-[0.12em] text-slate-500 mb-2 font-medium">
                      Tabulární řada (sloupce)
                    </div>
                    <TabularFieldPicker sourceId={row.source_id} row={row} onChange={(v) => updateRow(idx, v)} compact />
                  </div>
                )}
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                  <Field label="Ruční násobič (přepočet měřítka)">
                    <input
                      type="number"
                      step="any"
                      className="input font-mono text-sm"
                      value={row.manual_multiplier}
                      onChange={(e) =>
                        updateRow(idx, { ...row, manual_multiplier: e.target.value === "" ? 1 : Number(e.target.value) })
                      }
                    />
                  </Field>
                  <Field label="Jednotka / měřítko (číselný přepočet, např. 1e9)">
                    <input
                      className="input font-mono text-sm"
                      placeholder="např. 1000000"
                      value={row.declared_scale}
                      onChange={(e) => updateRow(idx, { ...row, declared_scale: e.target.value })}
                    />
                  </Field>
                  <Field label="Periodicita (odhad / ruční · např. M, Q, Y)">
                    <input
                      className="input font-mono text-sm"
                      placeholder="nezjištěno"
                      value={row.frequency === "unknown" ? "" : row.frequency}
                      onChange={(e) =>
                        updateRow(idx, { ...row, frequency: e.target.value === "" ? "unknown" : e.target.value })
                      }
                    />
                  </Field>
                </div>
              </div>
            );
          })}
        </div>

        {multiSum && (
          <button
            type="button"
            onClick={addOperand}
            className="h-8 px-3 text-xs border border-border rounded-sm hover:bg-slate-100 bg-white inline-flex items-center gap-1.5"
          >
            <Plus className="h-3.5 w-3.5" />
            Přidat řadu do součtu
          </button>
        )}

        <div className="flex flex-wrap gap-3">
          <button
            type="button"
            disabled={busy}
            onClick={runCompute}
            className="px-4 h-9 text-sm rounded-md text-white font-medium"
            style={{ background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))" }}
            data-testid="calc-advanced-run"
          >
            {busy ? "Počítám…" : "Spočítat výsledek"}
          </button>
          {operands.length >= 2 && (
            <button type="button" className="h-9 px-3 text-xs border border-border rounded-md" onClick={runSuggestScale}>
              Návrh přepočtu měřítka (řady A a B)
            </button>
          )}
        </div>

        <div className="border border-border/60 rounded-sm p-4 bg-slate-50/50 space-y-3">
          <div className="text-[11px] uppercase tracking-[0.1em] text-slate-600 font-medium">AI návrh výpočtu</div>
          <div className="flex flex-wrap gap-2">
            <input
              className="input flex-1 min-w-[200px]"
              value={aiQ}
              onChange={(e) => setAiQ(e.target.value)}
              placeholder='Např.: "spočítej ROA ze zisku a aktiv"'
            />
            <button type="button" className="h-9 px-4 text-xs border border-border rounded-md" onClick={runPlan}>
              Navrhnout postup
            </button>
          </div>
          {aiPlan ? (
            <>
              <div className="rounded-md bg-white border border-border p-3 text-sm text-slate-800 space-y-1.5">
                {summarizeAiCalculationPlan(aiPlan).map((line, i) => (
                  <p key={i}>{line}</p>
                ))}
              </div>
              <details className="rounded-lg border border-dashed border-border/70 p-2 text-[11px]">
                <summary className="cursor-pointer text-muted-foreground font-medium">Technické detaily (strojová odpověď)</summary>
                <pre className="mt-2 whitespace-pre-wrap font-mono text-[10px] text-muted-foreground bg-muted/40 p-2 rounded max-h-48 overflow-auto">
                  {JSON.stringify(aiPlan, null, 2)}
                </pre>
              </details>
            </>
          ) : (
            <p className="text-xs text-muted-foreground">Otázku vepište výše — zobrazí se stručný návod v češtině.</p>
          )}
        </div>

        {out && (
          <div className="space-y-4 border-t border-border/50 pt-6">
            {seriesRows.length === 0 ? (
              <div className="text-sm text-amber-950 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
                {(out.warnings || []).length > 0
                  ? String((out.warnings || [])[0])
                  : humanUnknown(out.method_note) ||
                    "Výpočet nevrátil žádné platné řádky — zkontrolujte vstupy a zarovnání období."}
              </div>
            ) : null}

            <div>
              <div className="text-[11px] uppercase text-slate-500 font-medium mb-1">Metodická poznámka</div>
              <p className="text-sm text-slate-700 leading-relaxed">{out.method_note || "—"}</p>
            </div>

            <div>
              <div className="text-[11px] uppercase text-slate-500 font-medium mb-2">Tabulka výsledku</div>
              <div className="data-table-shell">
              <div className="overflow-auto max-h-72">
                <table className="data-table text-[11px]">
                  <thead>
                    <tr>
                      <th>Období</th>
                      <th className="num">Hodnota</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[...seriesRows].reverse().map((r, i) => {
                      const n = parseNumber(r.value);
                      return (
                        <tr key={i}>
                          <td className="mono">{fmtPeriod(r.period, { variant: "full" })}</td>
                          <td className="num mono">{n !== null ? fmtNumber(n) : humanUnknown(String(r.value))}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              </div>
            </div>

            {chartNumeric.length > 0 ? (
              <div className="h-64 pt-2 min-w-0 overflow-x-hidden">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartNumeric} margin={{ top: 10, right: 20, bottom: 10, left: 10 }}>
                    <CartesianGrid vertical={false} stroke="#E6EEE9" strokeDasharray="2 4" />
                    <XAxis dataKey="x" tick={{ fontSize: 10, fill: "#8FA69E", fontFamily: "JetBrains Mono" }} tickFormatter={(v) => fmtPeriod(v, { variant: "axis" })} />
                    <YAxis width={64} tick={{ fontSize: 10, fill: "#8FA69E", fontFamily: "JetBrains Mono" }} tickFormatter={(v) => fmtCompact(v)} />
                    <Tooltip
                      {...mergeRechartsTooltipProps({
                        contentStyle: { fontSize: 12 },
                        formatter: (v) => (typeof v === "number" ? fmtNumber(v) : ""),
                        labelFormatter: (l) => fmtPeriod(l, { variant: "full" }),
                      })}
                    />
                    <Line type="monotone" dataKey="y" stroke="hsl(208 75% 45%)" strokeWidth={2} dot={false} connectNulls />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            ) : seriesRows.length > 0 ? (
              <p className="text-sm text-muted-foreground">Graf nelze zobrazit — v řadě nejsou platná číselná pozorování.</p>
            ) : null}

            <details className="rounded-lg border border-dashed border-border/70 p-2 text-[11px]">
              <summary className="cursor-pointer text-muted-foreground font-medium">Technické detaily (varování, kompletní odpověď)</summary>
              <div className="mt-2 space-y-2">
                <div>
                  <div className="font-semibold text-slate-600 mb-1">Varování z výpočtu</div>
                  {(out.warnings || []).length === 0 ? (
                    <div className="text-xs text-slate-500">Žádná varování.</div>
                  ) : (
                    <ul className="list-disc pl-5 text-xs text-amber-900 space-y-1">
                      {(out.warnings || []).map((w, i) => (
                        <li key={i}>{w}</li>
                      ))}
                    </ul>
                  )}
                </div>
                <pre className="whitespace-pre-wrap font-mono text-[10px] text-muted-foreground bg-muted/40 p-2 rounded max-h-48 overflow-auto">
                  {JSON.stringify(out, null, 2)}
                </pre>
              </div>
            </details>
          </div>
        )}
      </div>
    </section>
  );
}

/**
 * Live preview of an unsaved computed indicator.
 * Posts the in-progress definition to /api/computed/preview and renders
 * a compact chart directly in the modal.
 *
 * Debounced 400ms so quickly switching dropdowns doesn't spam the API.
 */
function ComputedPreview({ form }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  const key = JSON.stringify({
    op: form.operation,
    l: form.left,
    r: form.right,
    s: form.series,
    o: form.options || {},
  });

  useEffect(() => {
    const handle = setTimeout(async () => {
      setLoading(true);
      setErr("");
      try {
        const validMulti = (form.series || []).filter((s) => refOkForComputed(s));
        const pack = (s) => ({
          ...packComputedRefPayload(s),
          chart_type: s?.chart_type || "line",
        });
        const { data: res } = await api.post("/computed/preview", {
          name: form.name || "Náhled",
          operation: form.operation,
          left:
            form.operation === "multi"
              ? pack(validMulti[0] || {})
              : packComputedRefPayload(form.left),
          right:
            form.operation === "multi"
              ? pack(validMulti[1] || {})
              : packComputedRefPayload(form.right),
          series: form.operation === "multi" ? validMulti.map(pack) : [],
          unit: form.unit || "",
          options: form.options || {},
        });
        setData(res);
      } catch (e) {
        setErr(safeFormatApiDetail(e.response?.data?.detail) || e.message || "Náhled se nepodařil");
        setData(null);
      } finally {
        setLoading(false);
      }
    }, 400);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return (
    <div className="border border-border/60 rounded-md bg-slate-50/50">
      <div className="w-full flex items-center justify-between gap-2 px-4 h-9 text-[11px] uppercase tracking-[0.12em] text-slate-600 font-medium">
        <span>Živý náhled výpočtu</span>
        <span className="text-slate-400 font-mono normal-case tracking-normal">automaticky</span>
      </div>
      <div className="border-t border-border/60 p-3">
        {loading && <div className="text-sm text-slate-500 font-mono py-3">Počítám…</div>}
        {err && <div className="text-sm text-rose-700 font-mono py-2">{err}</div>}
        {!loading && !err && data && <ComputedPreviewChart data={data} unit={form.unit} />}
      </div>
    </div>
  );
}

function ComputedPreviewChart({ data, unit }) {
  const rows = data?.rows || [];
  const isMulti = data?.operation === "multi";
  const series = Array.isArray(data?.series) ? data.series : [];
  const last = rows[rows.length - 1];
  if (rows.length === 0) {
    return (
      <div className="text-sm text-slate-500 font-mono">
        Žádná překrývající se období mezi A a B. Zkontroluj, že oba zdroje mají
        synchronizovaná data, nebo vyber jiné indikátory.
      </div>
    );
  }
  const chart = isMulti
    ? rows.slice(-60).map((r) => {
        const o = { x: r.period };
        for (const s of series) {
          const k = s.key;
          if (!k) continue;
          const n = parseNumber(r[k]);
          if (n !== null) o[k] = n;
        }
        return o;
      })
    : rows.slice(-60).map((r) => {
        const y = parseNumber(r.value);
        return { x: r.period, y: y !== null ? y : NaN };
      });
  return (
    <div>
      <div className="flex items-end justify-between gap-4 mb-3 flex-wrap">
        <div>
          <div className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-medium">
            {data.operation_label}
          </div>
          <div className="text-[11px] font-mono text-slate-500 mt-1">
            {rows.length} období · náhled posledních {Math.min(60, rows.length)}
          </div>
        </div>
        {last && (
          <div className="text-right">
            <div className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-medium">
              Poslední ({fmtPeriod(last.period, { variant: "full" })})
            </div>
            <div className="font-serif text-2xl mt-1">
              {isMulti
                ? "—"
                : (() => {
                    const n = parseNumber(last.value);
                    return n !== null ? fmtNumber(n) : "—";
                  })()}
            </div>
            {!isMulti && unit && <div className="text-[10px] text-slate-500 font-mono">{unit}</div>}
          </div>
        )}
      </div>
      <div className="h-44 min-w-0 overflow-x-hidden">
        <ResponsiveContainer width="100%" height="100%">
          {isMulti ? (
            <ComposedChart data={chart} margin={{ top: 4, right: 8, bottom: 4, left: 0 }}>
              <CartesianGrid vertical={false} stroke="#D4E6F7" strokeDasharray="2 4" />
              <XAxis
                dataKey="x"
                tick={{ fontSize: 9, fill: "#5878A0", fontFamily: "JetBrains Mono" }}
                tickLine={false}
                axisLine={{ stroke: "#D4E6F7" }}
                interval="preserveStartEnd"
                minTickGap={28}
                tickFormatter={(v) => fmtPeriod(v, { variant: "axis" })}
              />
              <YAxis
                width={52}
                tick={{ fontSize: 9, fill: "#5878A0", fontFamily: "JetBrains Mono" }}
                tickLine={false}
                axisLine={false}
                tickFormatter={(v) => fmtCompact(v)}
              />
              <Tooltip
                {...mergeRechartsTooltipProps({
                  contentStyle: { borderRadius: 6, fontSize: 11 },
                  formatter: (v) => {
                    if (typeof v === "number") return fmtNumber(v);
                    const n = parseNumber(v);
                    return n !== null ? fmtNumber(n) : String(v ?? "");
                  },
                  labelFormatter: (l) => `Období: ${fmtPeriod(l, { variant: "full" })}`,
                })}
              />
              <Legend wrapperStyle={{ fontSize: 10, fontFamily: "JetBrains Mono" }} />
              {series.map((s, idx) => {
                const kind = (s.chart_type || "line").toLowerCase();
                const color = MULTI_COLORS[idx % MULTI_COLORS.length];
                const name = s.name || s.indicator_id || `Řada ${idx + 1}`;
                const k = s.key;
                if (!k) return null;
                if (kind === "bar") {
                  return <Bar key={k} dataKey={k} name={name} fill={color} radius={[3, 3, 0, 0]} />;
                }
                if (kind === "area") {
                  return (
                    <Area
                      key={k}
                      type="monotone"
                      dataKey={k}
                      name={name}
                      stroke={color}
                      fill={color}
                      fillOpacity={0.12}
                      strokeWidth={1.8}
                      dot={false}
                      connectNulls
                    />
                  );
                }
                return (
                  <Line
                    key={k}
                    type="monotone"
                    dataKey={k}
                    name={name}
                    stroke={color}
                    strokeWidth={1.9}
                    dot={false}
                    connectNulls
                  />
                );
              })}
            </ComposedChart>
          ) : (
            <LineChart data={chart} margin={{ top: 4, right: 8, bottom: 4, left: 0 }}>
              <CartesianGrid vertical={false} stroke="#D4E6F7" strokeDasharray="2 4" />
              <XAxis
                dataKey="x"
                tick={{ fontSize: 9, fill: "#5878A0", fontFamily: "JetBrains Mono" }}
                tickLine={false}
                axisLine={{ stroke: "#D4E6F7" }}
                interval="preserveStartEnd"
                minTickGap={28}
                tickFormatter={(v) => fmtPeriod(v, { variant: "axis" })}
              />
              <YAxis
                width={52}
                tick={{ fontSize: 9, fill: "#5878A0", fontFamily: "JetBrains Mono" }}
                tickLine={false}
                axisLine={false}
                tickFormatter={(v) => fmtCompact(v)}
              />
              <Tooltip
                {...mergeRechartsTooltipProps({
                  contentStyle: { borderRadius: 6, fontSize: 11 },
                  formatter: (v) => {
                    if (typeof v === "number") return fmtNumber(v);
                    const n = parseNumber(v);
                    return n !== null ? fmtNumber(n) : String(v ?? "");
                  },
                  labelFormatter: (l) => `Období: ${fmtPeriod(l, { variant: "full" })}`,
                })}
              />
              <Line type="monotone" dataKey="y" stroke="hsl(202 90% 52%)" strokeWidth={2} dot={false} />
            </LineChart>
          )}
        </ResponsiveContainer>
      </div>
    </div>
  );
}
