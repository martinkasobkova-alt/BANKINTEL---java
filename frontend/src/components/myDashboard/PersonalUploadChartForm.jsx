import React, { useEffect, useMemo, useRef, useState } from "react";
import { Upload } from "lucide-react";
import { toast } from "sonner";
import {
  ResponsiveContainer,
  CartesianGrid,
  XAxis,
  YAxis,
  Tooltip,
  LineChart,
  Line,
  BarChart,
  Bar,
  AreaChart,
  Area,
  Legend,
} from "recharts";
import api, { formatApiErrorFromAxios, postFormData } from "@/lib/api";
import { LoadingInline } from "@/components/ui/loading";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import { CHART_KINDS } from "@/lib/chartKindCatalog";

const AGG = [
  { id: "sum", label: "Součet" },
  { id: "avg", label: "Průměr" },
  { id: "last", label: "Poslední" },
  { id: "max", label: "Maximum" },
  { id: "min", label: "Minimum" },
  { id: "count", label: "Počet" },
];

const YOPS = [
  { id: "+", label: "+ (součet sloupců)" },
  { id: "-", label: "− (rozdíl)" },
  { id: "*", label: "× (součin)" },
  { id: "/", label: "÷ (podíl)" },
];

const CHART_TYPES = CHART_KINDS.map(({ id, label }) => ({ id, label }));

const FREQUENCY_OPTIONS = [
  { id: "__auto__", label: "Původní / automaticky" },
  { id: "M", label: "Měsíčně" },
  { id: "Q", label: "Čtvrtletně" },
  { id: "S", label: "Pololetně" },
  { id: "Y", label: "Ročně" },
];

const DATE_NAME_HINT = /(date|datum|period|obdobi|čas|cas|time|year|rok|month|měs|mes|quarter|qtr)/i;
const VALUE_NAME_HINT = /(value|hodnota|amount|qty|count|index|total|sum)/i;

function parsePreviewNumber(val) {
  if (val == null) return null;
  if (typeof val === "number" && Number.isFinite(val)) return val;
  const s = String(val).trim().replace(/\s/g, "").replace(",", ".");
  if (!s) return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

function toUtcDate(y, m, d) {
  const dt = new Date(Date.UTC(y, m - 1, d));
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== m - 1 || dt.getUTCDate() !== d) return null;
  return dt;
}

function parseDateLikePreview(value) {
  if (value == null) return null;
  if (value instanceof Date && !Number.isNaN(value.getTime())) return value;
  if (typeof value === "number" && Number.isFinite(value)) {
    if (Number.isInteger(value)) {
      const s = String(Math.abs(value));
      if (s.length === 8 && /^(19|20)\d{2}/.test(s)) {
        return toUtcDate(Number(s.slice(0, 4)), Number(s.slice(4, 6)), Number(s.slice(6, 8)));
      }
      if (s.length === 6 && /^(19|20)\d{2}/.test(s)) {
        return toUtcDate(Number(s.slice(0, 4)), Number(s.slice(4, 6)), 1);
      }
      if (s.length === 4 && /^(19|20)\d{2}/.test(s)) {
        return toUtcDate(Number(s), 1, 1);
      }
      if (value >= 20000 && value <= 90000) {
        return new Date(Date.UTC(1899, 11, 30 + value));
      }
    }
    return null;
  }
  const s = String(value).trim();
  if (!s) return null;
  if (/^\d{8}$/.test(s) && /^(19|20)\d{2}/.test(s)) {
    return toUtcDate(Number(s.slice(0, 4)), Number(s.slice(4, 6)), Number(s.slice(6, 8)));
  }
  if (/^\d{6}$/.test(s) && /^(19|20)\d{2}/.test(s)) {
    return toUtcDate(Number(s.slice(0, 4)), Number(s.slice(4, 6)), 1);
  }
  if (/^\d{4}$/.test(s) && /^(19|20)\d{2}/.test(s)) {
    return toUtcDate(Number(s), 1, 1);
  }
  const m = s.match(/^((?:19|20)\d{2})[-/.](\d{1,2})[-/.](\d{1,2})$/);
  if (m) return toUtcDate(Number(m[1]), Number(m[2]), Number(m[3]));
  const ym = s.match(/^((?:19|20)\d{2})[-/.](\d{1,2})$/);
  if (ym) return toUtcDate(Number(ym[1]), Number(ym[2]), 1);
  const parsed = Date.parse(s);
  if (Number.isNaN(parsed)) return null;
  return new Date(parsed);
}

function fmtDateIso(d) {
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, "0");
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function bucketDateLabel(d, frequency) {
  const f = String(frequency || "").toUpperCase();
  if (f === "Y") return { label: String(d.getUTCFullYear()), sort: Date.UTC(d.getUTCFullYear(), 0, 1) };
  if (f === "S") {
    const h = d.getUTCMonth() < 6 ? 1 : 2;
    return { label: `${d.getUTCFullYear()}-H${h}`, sort: Date.UTC(d.getUTCFullYear(), h === 1 ? 0 : 6, 1) };
  }
  if (f === "Q") {
    const q = Math.floor(d.getUTCMonth() / 3) + 1;
    return { label: `${d.getUTCFullYear()}-Q${q}`, sort: Date.UTC(d.getUTCFullYear(), (q - 1) * 3, 1) };
  }
  if (f === "M") {
    const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
    return { label: `${d.getUTCFullYear()}-${mm}`, sort: Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1) };
  }
  return { label: fmtDateIso(d), sort: Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) };
}

function chooseDefaultXY(columns, rows) {
  if (!Array.isArray(columns) || columns.length === 0) {
    return { x: "date", y: "value", yb: "value" };
  }
  const firstRow = rows?.[0] && typeof rows[0] === "object" ? rows[0] : {};
  const xCandidate =
    columns.find((c) => DATE_NAME_HINT.test(c) && parseDateLikePreview(firstRow?.[c])) ||
    columns.find((c) => DATE_NAME_HINT.test(c)) ||
    columns.find((c) => parseDateLikePreview(firstRow?.[c])) ||
    columns[0];
  const yPool = columns.filter((c) => c !== xCandidate);
  const yCandidate =
    yPool.find((c) => VALUE_NAME_HINT.test(c) && parsePreviewNumber(firstRow?.[c]) != null) ||
    yPool.find((c) => parsePreviewNumber(firstRow?.[c]) != null) ||
    yPool[0] ||
    columns[0];
  const ybCandidate = yPool.find((c) => c !== yCandidate) || yCandidate || xCandidate;
  return { x: xCandidate, y: yCandidate, yb: ybCandidate };
}

export default function PersonalUploadChartForm({ uploads, onApply, disabled, onUploadsRefresh }) {
  const [uploadId, setUploadId] = useState("");
  const [cols, setCols] = useState([]);
  const [sampleRows, setSampleRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [xField, setXField] = useState("date");
  const [yField, setYField] = useState("value");
  const [agg, setAgg] = useState("sum");
  const [chartFrequency, setChartFrequency] = useState("__auto__");
  const [chartType, setChartType] = useState("line");
  const [chartUnit, setChartUnit] = useState("");
  const [wTitle, setWTitle] = useState("");
  const [wDesc, setWDesc] = useState("");
  /** single = jeden sloupec Y; binary = výpočet ze dvou sloupců; multi = více řad (složený graf) */
  const [seriesMode, setSeriesMode] = useState("single");
  const [yFieldB, setYFieldB] = useState("");
  const [yOp, setYOp] = useState("+");
  const [multiYFields, setMultiYFields] = useState([]);
  const [uploadingFile, setUploadingFile] = useState(false);
  const [totalRows, setTotalRows] = useState(0);
  const [allRows, setAllRows] = useState(null);
  const [showAll, setShowAll] = useState(false);
  const [loadingAll, setLoadingAll] = useState(false);
  const fileRef = useRef(null);
  const feComposite = useFeatureAccess("composite_charts");

  useEffect(() => {
    if (!uploadId) {
      setCols([]);
      setSampleRows([]);
      setTotalRows(0);
      setAllRows(null);
      setShowAll(false);
      return;
    }
    setAllRows(null);
    setShowAll(false);
    let cancel = false;
    (async () => {
      setLoading(true);
      try {
        const { data } = await api.get(`/me/uploads/${uploadId}/preview`);
        if (cancel) return;
        const c = Array.isArray(data?.columns) ? data.columns : [];
        const rows = Array.isArray(data?.sample_rows) ? data.sample_rows : [];
        setCols(c);
        setSampleRows(rows);
        setTotalRows(Number(data?.total_rows) || rows.length);
        if (c.length) {
          const picked = chooseDefaultXY(c, rows);
          setXField(picked.x);
          setYField(picked.y);
          setYFieldB(picked.yb);
        }
      } catch {
        if (!cancel) {
          setCols([]);
          setSampleRows([]);
        }
      } finally {
        if (!cancel) setLoading(false);
      }
    })();
    return () => {
      cancel = true;
    };
  }, [uploadId]);

  useEffect(() => {
    setMultiYFields((prev) => prev.filter((f) => f !== xField));
  }, [xField]);

  useEffect(() => {
    if (seriesMode !== "multi" || !cols.length) return;
    setMultiYFields((prev) => {
      const valid = prev.filter((f) => cols.includes(f) && f !== xField);
      if (valid.length >= 2) return valid;
      if (valid.length === 1) return valid;
      const rest = cols.filter((c) => c !== xField);
      if (rest.length < 2) return [];
      return rest.slice(0, Math.min(6, rest.length));
    });
  }, [seriesMode, cols, xField]);

  const toggleMultiY = (col) => {
    if (col === xField) return;
    setMultiYFields((prev) => {
      if (prev.includes(col)) return prev.filter((c) => c !== col);
      return [...prev, col];
    });
  };

  const loadAllRows = async () => {
    if (!uploadId) return;
    if (allRows) {
      setShowAll(true);
      return;
    }
    setLoadingAll(true);
    try {
      const { data } = await api.get(`/me/uploads/${uploadId}/preview`, { params: { rows: 20000 } });
      const rows = Array.isArray(data?.sample_rows) ? data.sample_rows : [];
      setAllRows(rows);
      setTotalRows(Number(data?.total_rows) || rows.length);
      setShowAll(true);
    } catch (err) {
      toast.error(formatApiErrorFromAxios(err) || "Načtení celého souboru se nepodařilo.");
    } finally {
      setLoadingAll(false);
    }
  };

  const onPickFile = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setUploadingFile(true);
    try {
      const body = new FormData();
      body.append("file", file);
      const { data } = await postFormData("/me/uploads", body);
      if (data?.id) {
        toast.success("Soubor nahrán.");
        await onUploadsRefresh?.();
        setUploadId(data.id);
      }
    } catch (err) {
      toast.error(formatApiErrorFromAxios(err) || err?.message || "Nahrání se nepodařilo.");
    } finally {
      setUploadingFile(false);
    }
  };

  const submit = async () => {
    if (!uploadId || !xField) return;
    if (seriesMode === "single" && !yField) return;
    if (seriesMode === "binary" && (!yField || !yFieldB)) return;
    if (seriesMode === "multi" && multiYFields.length < 2) return;

    const config = {
      user_upload_id: uploadId,
      x_field: xField,
      agg,
      chart_type: chartType,
      unit: chartUnit.trim() || null,
      view: "chart",
      chart_frequency: chartFrequency === "__auto__" ? null : chartFrequency,
    };

    if (seriesMode === "multi") {
      config.chart_mode = "multi";
      config.y_fields = multiYFields.filter((f) => f && f !== xField);
    } else {
      config.chart_mode = "single";
      config.y_field = yField;
      if (seriesMode === "binary" && yFieldB) {
        config.y_mode = "binary";
        config.y_field_b = yFieldB;
        config.y_op = yOp;
      } else {
        config.y_mode = "single";
      }
    }

    const p = onApply?.({
      title: wTitle.trim() || "Graf z mých dat",
      description: wDesc,
      config,
    });
    if (p && typeof p.then === "function") await p;
  };

  const submitDisabled =
    disabled ||
    !uploadId ||
    !xField ||
    (seriesMode === "single" && !yField) ||
    (seriesMode === "binary" && (!yField || !yFieldB)) ||
    (seriesMode === "multi" && multiYFields.length < 2) ||
    (seriesMode === "multi" && feComposite.ready && !feComposite.allowed);
  const previewColumns = cols.length
    ? cols
    : sampleRows.length && sampleRows[0] && typeof sampleRows[0] === "object"
      ? Object.keys(sampleRows[0])
      : [];
  const displayRows = showAll && Array.isArray(allRows) ? allRows : sampleRows;
  const detectedDateColumns = useMemo(() => {
    if (!Array.isArray(cols) || cols.length === 0) return [];
    const head = sampleRows.slice(0, 8);
    return cols.filter((c) => {
      if (DATE_NAME_HINT.test(c)) return true;
      const parsed = head.reduce((acc, row) => {
        if (!row || typeof row !== "object") return acc;
        return acc + (parseDateLikePreview(row[c]) ? 1 : 0);
      }, 0);
      return parsed >= Math.max(1, Math.ceil(head.length / 2));
    });
  }, [cols, sampleRows]);
  const selectedXLooksDate = useMemo(() => {
    if (!xField) return false;
    const head = sampleRows.slice(0, 8);
    const parsed = head.reduce((acc, row) => {
      if (!row || typeof row !== "object") return acc;
      return acc + (parseDateLikePreview(row[xField]) ? 1 : 0);
    }, 0);
    return parsed >= Math.max(1, Math.ceil(head.length / 2));
  }, [xField, sampleRows]);
  const effectiveFrequency = chartFrequency === "__auto__" ? "" : chartFrequency;

  const previewChart = useMemo(() => {
    if (!uploadId || !sampleRows.length || !xField) return { rows: [], mode: seriesMode };

    const bucket = new Map();
    const sortedKeys = new Map();
    const yKeys = seriesMode === "multi" ? multiYFields.filter((f) => f && f !== xField) : [];
    const addOne = (label, sort, key, val) => {
      const row = bucket.get(label) || {};
      const list = row[key] || [];
      list.push(val);
      row[key] = list;
      bucket.set(label, row);
      if (!sortedKeys.has(label)) sortedKeys.set(label, sort);
    };
    for (const rec of sampleRows) {
      if (!rec || typeof rec !== "object") continue;
      const rawX = rec[xField];
      if (rawX == null || rawX === "") continue;
      const dateX = parseDateLikePreview(rawX);
      let xLabel = String(rawX);
      let xSort = Number.MAX_SAFE_INTEGER;
      if (dateX) {
        const b = bucketDateLabel(dateX, effectiveFrequency);
        xLabel = b.label;
        xSort = b.sort;
      } else if (effectiveFrequency) {
        xLabel = String(rawX);
      }
      if (seriesMode === "multi") {
        for (let i = 0; i < yKeys.length; i += 1) {
          const n = parsePreviewNumber(rec[yKeys[i]]);
          if (n == null) continue;
          addOne(xLabel, xSort, `s${i}`, n);
        }
      } else {
        let yVal = null;
        if (seriesMode === "binary") {
          const a = parsePreviewNumber(rec[yField]);
          const b = parsePreviewNumber(rec[yFieldB]);
          if (a != null && b != null) {
            if (yOp === "+") yVal = a + b;
            else if (yOp === "-") yVal = a - b;
            else if (yOp === "*") yVal = a * b;
            else if (yOp === "/") yVal = b === 0 ? null : a / b;
            else yVal = a + b;
          }
        } else {
          yVal = parsePreviewNumber(rec[yField]);
        }
        if (yVal == null) continue;
        addOne(xLabel, xSort, "y", yVal);
      }
    }

    const aggregate = (vals) => {
      if (!Array.isArray(vals) || vals.length === 0) return null;
      if (agg === "avg") return vals.reduce((s, v) => s + v, 0) / vals.length;
      if (agg === "max") return Math.max(...vals);
      if (agg === "min") return Math.min(...vals);
      if (agg === "count") return vals.length;
      if (agg === "last") return vals[vals.length - 1];
      return vals.reduce((s, v) => s + v, 0);
    };

    const rows = [...bucket.keys()]
      .sort((a, b) => {
        const sa = sortedKeys.get(a);
        const sb = sortedKeys.get(b);
        if (Number.isFinite(sa) && Number.isFinite(sb) && sa !== sb) return sa - sb;
        return String(a).localeCompare(String(b));
      })
      .map((x) => {
        const row = bucket.get(x) || {};
        const out = { x };
        if (seriesMode === "multi") {
          for (let i = 0; i < yKeys.length; i += 1) {
            const v = aggregate(row[`s${i}`]);
            if (v != null) out[`s${i}`] = v;
          }
        } else {
          const v = aggregate(row.y);
          if (v != null) out.y = v;
        }
        return out;
      })
      .filter((r) => (seriesMode === "multi" ? Object.keys(r).length > 1 : Number.isFinite(r.y)));
    return { rows, mode: seriesMode, yKeys };
  }, [uploadId, sampleRows, xField, seriesMode, yField, yFieldB, yOp, agg, effectiveFrequency, multiYFields]);

  return (
    <div className="space-y-3 border border-border/70 rounded-xl p-3 bg-slate-50/50">
      <div className="text-xs font-medium text-slate-700">Graf z nahraného souboru</div>
      <p className="text-[11px] text-slate-600 leading-relaxed">
        U jednoho sloupce zvolte hodnotu Y přímo. U <strong>vlastního výpočtu</strong> se nejdřív z každého řádku
        spočte hodnota (např. tržby ÷ množství) a z výsledků se pak udělá agregace podle osy X. Režim{" "}
        <strong>více řad</strong> zobrazí několik číselných sloupců jako složený graf (stejná osa X, stejná
        agregace podle X u každé řady).
      </p>

      <div className="flex flex-wrap items-center gap-2">
        <input ref={fileRef} type="file" accept=".csv,.xlsx,.xlsm" className="hidden" onChange={onPickFile} />
        <button
          type="button"
          disabled={disabled || uploadingFile}
          onClick={() => fileRef.current?.click()}
          className="inline-flex items-center gap-1.5 px-3 h-9 text-xs rounded-lg border border-border/80 bg-white hover:bg-slate-50 disabled:opacity-50 shadow-sm"
        >
          {uploadingFile ? (
            <LoadingInline label="Nahrávám…" size="sm" className="py-0" />
          ) : (
            <>
              <Upload className="h-3.5 w-3.5" aria-hidden />
              Nahrát soubor (CSV / XLSX)
            </>
          )}
        </button>
        <span className="text-[10px] text-slate-500">nebo vyberte dříve nahraný:</span>
      </div>

      <label className="block text-[11px] text-slate-600">
        Soubor
        <select
          className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
          value={uploadId}
          onChange={(e) => setUploadId(e.target.value)}
        >
          <option value="">— vyberte —</option>
          {(uploads || []).map((u) => (
            <option key={u.id} value={u.id}>
              {u.original_name || u.id}
            </option>
          ))}
        </select>
      </label>
      {(!uploads || uploads.length === 0) && (
        <p className="text-[11px] text-slate-600">
          Seznam je prázdný — použijte tlačítko nahrání výše, nebo nahrajte soubor také v sekci „Moje data“.
        </p>
      )}
      {loading && <LoadingInline label="Načítám sloupce…" size="sm" className="py-0.5" />}
      {uploadId && !loading && sampleRows.length > 0 && (
        <div className="rounded-lg border border-border/60 bg-white p-2">
          <div className="flex items-center justify-between gap-2 mb-1.5">
            <div className="text-[11px] font-medium text-slate-700">
              {showAll
                ? `Náhled dat (všech ${displayRows.length} řádků)`
                : `Náhled dat (prvních ${sampleRows.length} z ${totalRows} řádků)`}
            </div>
            {totalRows > sampleRows.length ? (
              showAll ? (
                <button
                  type="button"
                  onClick={() => setShowAll(false)}
                  className="text-[10px] text-[hsl(var(--primary))] hover:underline shrink-0"
                >
                  Zobrazit jen náhled
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => void loadAllRows()}
                  disabled={loadingAll}
                  className="text-[10px] text-[hsl(var(--primary))] hover:underline shrink-0 disabled:opacity-50"
                >
                  {loadingAll ? "Načítám…" : `Zobrazit celý soubor (${totalRows} řádků)`}
                </button>
              )
            ) : null}
          </div>
          <div className={`overflow-x-auto ${showAll ? "max-h-[60vh] overflow-y-auto" : ""}`}>
            <table className="min-w-full text-[11px] border-collapse">
              <thead className={showAll ? "sticky top-0 bg-white shadow-[0_1px_0_0_hsl(var(--border))]" : ""}>
                <tr>
                  {previewColumns.map((c) => (
                    <th
                      key={c}
                      className="text-left font-semibold text-slate-600 border-b border-border/60 px-2 py-1 whitespace-nowrap"
                    >
                      {c}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {displayRows.map((row, idx) => (
                  <tr key={`sample-row-${idx}`} className="border-b border-border/40 last:border-0">
                    {previewColumns.map((c) => (
                      <td key={`${idx}-${c}`} className="px-2 py-1 text-slate-700 whitespace-nowrap">
                        {row && Object.prototype.hasOwnProperty.call(row, c)
                          ? String(row[c] ?? "")
                          : ""}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
      {uploadId && !loading && (
        <div className="rounded-lg border border-border/60 bg-white p-2">
          <div className="text-[11px] font-medium text-slate-700 mb-1.5">
            Náhled grafu (podle aktuální konfigurace)
          </div>
          {previewChart.rows.length === 0 ? (
            <div className="text-[11px] text-slate-500">
              Zatím není možné sestavit náhled. Vyberte kombinaci X (datum/období) + Y (číselná hodnota).
            </div>
          ) : (
            <div className="h-48 w-full">
              <ResponsiveContainer width="100%" height="100%">
                {previewChart.mode === "multi" ? (
                  <LineChart data={previewChart.rows} margin={{ top: 8, right: 8, left: 0, bottom: 18 }}>
                    <CartesianGrid strokeDasharray="2 4" />
                    <XAxis dataKey="x" tick={{ fontSize: 10 }} />
                    <YAxis tick={{ fontSize: 10 }} />
                    <Tooltip />
                    <Legend wrapperStyle={{ fontSize: 11 }} />
                    {previewChart.yKeys.slice(0, 4).map((name, idx) => (
                      <Line
                        key={`py-${name}`}
                        type="monotone"
                        dataKey={`s${idx}`}
                        name={name}
                        stroke={["#1f8cdb", "#2f6ab8", "#5fb8a4", "#e08060"][idx % 4]}
                        strokeWidth={2}
                        dot={false}
                        isAnimationActive={false}
                      />
                    ))}
                  </LineChart>
                ) : chartType === "bar" ? (
                  <BarChart data={previewChart.rows} margin={{ top: 8, right: 8, left: 0, bottom: 18 }}>
                    <CartesianGrid strokeDasharray="2 4" />
                    <XAxis dataKey="x" tick={{ fontSize: 10 }} />
                    <YAxis tick={{ fontSize: 10 }} />
                    <Tooltip />
                    <Bar dataKey="y" fill="#1f8cdb" />
                  </BarChart>
                ) : chartType === "area" ? (
                  <AreaChart data={previewChart.rows} margin={{ top: 8, right: 8, left: 0, bottom: 18 }}>
                    <CartesianGrid strokeDasharray="2 4" />
                    <XAxis dataKey="x" tick={{ fontSize: 10 }} />
                    <YAxis tick={{ fontSize: 10 }} />
                    <Tooltip />
                    <Area type="monotone" dataKey="y" stroke="#1f8cdb" fill="#1f8cdb33" strokeWidth={2} />
                  </AreaChart>
                ) : (
                  <LineChart data={previewChart.rows} margin={{ top: 8, right: 8, left: 0, bottom: 18 }}>
                    <CartesianGrid strokeDasharray="2 4" />
                    <XAxis dataKey="x" tick={{ fontSize: 10 }} />
                    <YAxis tick={{ fontSize: 10 }} />
                    <Tooltip />
                    <Line
                      type="monotone"
                      dataKey="y"
                      stroke="#1f8cdb"
                      strokeWidth={2}
                      dot={false}
                      isAnimationActive={false}
                    />
                  </LineChart>
                )}
              </ResponsiveContainer>
            </div>
          )}
          <div className="mt-1 text-[10px] text-slate-500">
            Náhled se počítá z prvních {sampleRows.length} řádků. Finální widget použije celý soubor.
          </div>
        </div>
      )}
      {uploadId && cols.length > 0 && (
        <>
          <label className="block text-[11px] text-slate-600">
            Režim řad
            <select
              className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
              value={seriesMode}
              onChange={(e) => setSeriesMode(e.target.value)}
            >
              <option value="single">Jeden sloupec Y</option>
              <option value="binary">Výpočet ze dvou sloupců (jedna odvozená řada)</option>
              <option value="multi">Více sloupců — složený graf</option>
            </select>
          </label>

          <label className="block text-[11px] text-slate-600">
            Sloupec X (např. datum nebo období)
            <select
              className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
              value={xField}
              onChange={(e) => setXField(e.target.value)}
            >
              {cols.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </label>
          {!selectedXLooksDate && detectedDateColumns.length > 0 && detectedDateColumns[0] !== xField && (
            <div className="rounded-md border border-amber-200 bg-amber-50 px-2 py-1.5 text-[11px] text-amber-900">
              Vybraný X nevypadá jako datum. Doporučeno:{" "}
              <button
                type="button"
                onClick={() => setXField(detectedDateColumns[0])}
                className="underline font-semibold hover:no-underline"
              >
                použít `{detectedDateColumns[0]}`
              </button>
              .
            </div>
          )}

          {seriesMode === "multi" ? (
            <>
              {feComposite.ready && !feComposite.allowed ? (
                <p className="text-[11px] text-amber-900 bg-amber-50 border border-amber-200 rounded-lg px-2 py-1.5">
                  {feComposite.message || "Složené grafy vyžadují oprávnění předplatitele."}
                </p>
              ) : null}
              <div className="text-[11px] text-slate-600 font-medium">Sloupce Y v grafu (zaškrtněte alespoň 2)</div>
              <div className="max-h-40 overflow-y-auto rounded-lg border border-border/60 bg-white p-2 space-y-1.5">
                {cols
                  .filter((c) => c !== xField)
                  .map((c) => (
                    <label key={c} className="flex items-center gap-2 text-sm cursor-pointer">
                      <input
                        type="checkbox"
                        checked={multiYFields.includes(c)}
                        onChange={() => toggleMultiY(c)}
                        disabled={disabled}
                      />
                      <span className="font-mono text-xs">{c}</span>
                    </label>
                  ))}
              </div>
            </>
          ) : (
            <>
              <label className="block text-[11px] text-slate-600">
                {seriesMode === "binary" ? "Sloupec A (první operand)" : "Sloupec Y (hodnota)"}
                <select
                  className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                  value={yField}
                  onChange={(e) => setYField(e.target.value)}
                >
                  {cols.map((c) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                </select>
              </label>
              {seriesMode === "binary" && (
                <>
                  <label className="block text-[11px] text-slate-600">
                    Operátor
                    <select
                      className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                      value={yOp}
                      onChange={(e) => setYOp(e.target.value)}
                    >
                      {YOPS.map((o) => (
                        <option key={o.id} value={o.id}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="block text-[11px] text-slate-600">
                    Sloupec B (druhý operand)
                    <select
                      className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                      value={yFieldB}
                      onChange={(e) => setYFieldB(e.target.value)}
                    >
                      {cols.map((c) => (
                        <option key={c} value={c}>
                          {c}
                        </option>
                      ))}
                    </select>
                  </label>
                </>
              )}
            </>
          )}

          <label className="block text-[11px] text-slate-600">
            Agregace podle X
            <select
              className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
              value={agg}
              onChange={(e) => setAgg(e.target.value)}
            >
              {AGG.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.label}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-[11px] text-slate-600">
            Periodicita osy X (u datumu)
            <select
              className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
              value={chartFrequency}
              onChange={(e) => setChartFrequency(e.target.value)}
            >
              {FREQUENCY_OPTIONS.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.label}
                </option>
              ))}
            </select>
          </label>
          <div className="text-[10px] text-slate-500 -mt-1">
            Pokud je X sloupec datum (např. `20251231`), periodicita určí seskupení do měsíců/kvartálů/roku.
          </div>
          <label className="block text-[11px] text-slate-600">
            Typ grafu (první řada u složeného grafu)
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
          <label className="block text-[11px] text-slate-600">
            Jednotka hodnot
            <input
              className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
              value={chartUnit}
              onChange={(e) => setChartUnit(e.target.value)}
              placeholder="např. mld. Kč, mil. Kč, %, ks"
            />
          </label>
        </>
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
        disabled={submitDisabled}
        className="btn-primary text-sm py-1.5 px-3 disabled:opacity-50"
        onClick={submit}
      >
        Přidat graf z mých dat
      </button>
    </div>
  );
}
