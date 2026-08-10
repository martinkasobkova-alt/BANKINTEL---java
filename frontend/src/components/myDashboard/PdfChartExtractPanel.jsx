/**
 * PdfChartExtractPanel — import dat z PDF (graf pomocí AI nebo tabulka přes pdfplumber).
 *
 * Dva režimy:
 *   "chart" — GPT-4o vision rozpozná graf → data → widget
 *   "table" — pdfplumber extrahuje tabulku → náhled → CSV → widget
 */
import React, { useRef, useState } from "react";
import {
  ResponsiveContainer,
  CartesianGrid,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
  LineChart,
  Line,
  BarChart,
  Bar,
  AreaChart,
  Area,
} from "recharts";
import { FileText, Loader2, Sparkles, Table2, FileSpreadsheet } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios, postFormData } from "@/lib/api";

const COLORS = ["#3b82f6", "#f59e0b", "#10b981", "#ef4444", "#8b5cf6", "#ec4899"];

/* ─── helpers ──────────────────────────────────────────────────────────────── */

function buildRechartsData(series) {
  if (!series || series.length === 0) return { rows: [], keys: [] };
  const allKeys = new Set();
  const byX = {};
  series.forEach((s) => {
    s.data.forEach((pt) => {
      if (!byX[pt.x]) byX[pt.x] = { x: pt.x };
      byX[pt.x][s.name] = pt.y;
      allKeys.add(s.name);
    });
  });
  return { rows: Object.values(byX), keys: [...allKeys] };
}

function seriesToCsv(series) {
  if (!series || series.length === 0) return "x,value\n";
  const keys = series.map((s) => s.name);
  const byX = {};
  series.forEach((s) => {
    s.data.forEach((pt) => {
      if (!byX[pt.x]) byX[pt.x] = {};
      byX[pt.x][s.name] = pt.y;
    });
  });
  const header = ["x", ...keys].join(",");
  const rows = Object.entries(byX).map(([x, vals]) => {
    const cells = [x, ...keys.map((k) => (vals[k] != null ? String(vals[k]) : ""))];
    return cells.map((c) => (String(c).includes(",") ? `"${c}"` : c)).join(",");
  });
  return [header, ...rows].join("\n");
}

function tableToCsv(columns, rows) {
  if (!columns || columns.length === 0) return "";
  const header = columns.map((c) => (String(c).includes(",") ? `"${c}"` : c)).join(",");
  const dataRows = rows.map((row) =>
    columns.map((c) => {
      const v = row[c] != null ? String(row[c]) : "";
      return v.includes(",") ? `"${v}"` : v;
    }).join(",")
  );
  return [header, ...dataRows].join("\n");
}

/* ─── Chart chart type switcher (mini recharts preview) ──────────────────── */

function ChartPreview({ result }) {
  const chartData = buildRechartsData(result.series);
  if (!chartData.rows.length) return null;
  const ct = result.chart_type || "line";
  return (
    <div className="h-48 w-full">
      <ResponsiveContainer width="100%" height="100%">
        {ct === "bar" ? (
          <BarChart data={chartData.rows}>
            <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
            <XAxis dataKey="x" tick={{ fontSize: 10 }} />
            <YAxis tick={{ fontSize: 10 }} width={45} />
            <Tooltip />
            {chartData.keys.length > 1 && <Legend />}
            {chartData.keys.map((k, i) => <Bar key={k} dataKey={k} fill={COLORS[i % COLORS.length]} />)}
          </BarChart>
        ) : ct === "area" ? (
          <AreaChart data={chartData.rows}>
            <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
            <XAxis dataKey="x" tick={{ fontSize: 10 }} />
            <YAxis tick={{ fontSize: 10 }} width={45} />
            <Tooltip />
            {chartData.keys.length > 1 && <Legend />}
            {chartData.keys.map((k, i) => (
              <Area key={k} type="monotone" dataKey={k} stroke={COLORS[i % COLORS.length]} fill={COLORS[i % COLORS.length]} fillOpacity={0.18} />
            ))}
          </AreaChart>
        ) : (
          <LineChart data={chartData.rows}>
            <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
            <XAxis dataKey="x" tick={{ fontSize: 10 }} />
            <YAxis tick={{ fontSize: 10 }} width={45} />
            <Tooltip />
            {chartData.keys.length > 1 && <Legend />}
            {chartData.keys.map((k, i) => (
              <Line key={k} type="monotone" dataKey={k} stroke={COLORS[i % COLORS.length]} dot={false} strokeWidth={2} />
            ))}
          </LineChart>
        )}
      </ResponsiveContainer>
    </div>
  );
}

/* ─── Table preview ─────────────────────────────────────────────────────────  */

function TablePreview({ columns, rows, truncated, totalRows, modeUsed }) {
  if (!columns || columns.length === 0) return null;
  return (
    <div className="space-y-1">
      <div className="flex items-center justify-between text-[11px] text-muted-foreground">
        <span>{totalRows} řádků · {columns.length} sloupců{truncated ? ` (zobrazeno ${rows.length})` : ""} · metoda: {modeUsed}</span>
      </div>
      <div className="overflow-x-auto max-h-56 overflow-y-auto border border-border/50 rounded-lg">
        <table className="w-full text-[11px] font-mono border-collapse min-w-max">
          <thead className="sticky top-0 bg-muted/80 z-10">
            <tr>
              {columns.map((c) => (
                <th key={c} className="px-2 py-1.5 text-left border-b border-border/50 font-semibold whitespace-nowrap">
                  {c}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, ri) => (
              <tr key={ri} className={ri % 2 === 0 ? "bg-card" : "bg-muted/20"}>
                {columns.map((c) => (
                  <td key={c} className="px-2 py-1 border-b border-border/30 whitespace-nowrap max-w-[180px] truncate" title={String(row[c] ?? "")}>
                    {row[c] != null ? String(row[c]) : ""}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ─── Page navigation ───────────────────────────────────────────────────────  */

/* ─── Main component ────────────────────────────────────────────────────────  */

/**
 * @param {{ onWidgetCreated?: (payload: object) => Promise<void>, widgetApplyDisabled?: boolean }} props
 */
export default function PdfChartExtractPanel({ onWidgetCreated, widgetApplyDisabled }) {
  const fileRef = useRef(null);
  const [mode, setMode] = useState("chart"); // "chart" | "table"

  // Shared
  const [pdfFile, setPdfFile] = useState(null);
  const [page, setPage] = useState(1);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [widgetTitle, setWidgetTitle] = useState("");
  const [saving, setSaving] = useState(false);

  // Table-specific
  const [headerRow, setHeaderRow] = useState(1);
  const [tableIndex, setTableIndex] = useState(-1);

  const reset = () => {
    setResult(null);
    setWidgetTitle("");
    setPage(1);
  };

  const handleFile = (e) => {
    const f = e.target.files?.[0];
    if (!f) return;
    if (!f.name.toLowerCase().endsWith(".pdf")) {
      toast.error("Nahrávejte prosím soubor PDF.");
      return;
    }
    setPdfFile(f);
    reset();
  };

  const handleSwitchMode = (m) => {
    setMode(m);
    reset();
  };

  /* ── Extract chart (AI vision) ──────────────────────────────────────────── */
  const handleExtractChart = async () => {
    if (!pdfFile) return;
    setBusy(true);
    setResult(null);
    try {
      const fd = new FormData();
      fd.append("file", pdfFile);
      const { data } = await postFormData(`/me/extract-chart-from-pdf?page=${page}`, fd);
      if (!data.series || data.series.length === 0) {
        toast.warning("AI nenašla v grafu žádná data. Zkuste jinou stránku nebo kvalitnější PDF.");
      } else {
        setResult({ kind: "chart", ...data });
        setWidgetTitle(data.title || pdfFile.name.replace(/\.pdf$/i, ""));
        toast.success(`Extrahováno ${data.series.reduce((s, r) => s + r.data.length, 0)} datových bodů.`);
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Extrakce grafu selhala.");
    } finally {
      setBusy(false);
    }
  };

  /* ── Extract table (pdfplumber) ─────────────────────────────────────────── */
  const handleExtractTable = async () => {
    if (!pdfFile) return;
    setBusy(true);
    setResult(null);
    try {
      const fd = new FormData();
      fd.append("file", pdfFile);
      const params = new URLSearchParams({
        page: String(page),
        header_row: String(headerRow),
        table_index: String(tableIndex),
      });
      const { data } = await postFormData(`/me/extract-table-from-pdf?${params}`, fd);
      if (data.error) {
        toast.warning(data.error);
        setResult(null);
      } else if (!data.columns || data.columns.length === 0) {
        toast.warning("Na dané stránce nebyla nalezena žádná tabulka.");
      } else {
        setResult({ kind: "table", ...data });
        setWidgetTitle(pdfFile.name.replace(/\.pdf$/i, ""));
        toast.success(`Extrahováno ${data.total_rows} řádků · ${data.columns.length} sloupců.`);
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Extrakce tabulky selhala.");
    } finally {
      setBusy(false);
    }
  };

  /* ── Save as widget ─────────────────────────────────────────────────────── */
  const handleAddWidget = async () => {
    if (!result || !onWidgetCreated) return;
    setSaving(true);
    try {
      let csvContent;
      let keys;

      if (result.kind === "chart") {
        csvContent = seriesToCsv(result.series);
        keys = result.series.map((s) => s.name);
      } else {
        csvContent = tableToCsv(result.columns, result.rows);
        keys = result.columns;
      }

      const csvFileName = `${(widgetTitle || "pdf-data").replace(/[^a-z0-9_-]/gi, "_")}.csv`;
      const csvBlob = new Blob([csvContent], { type: "text/csv" });
      const csvFile = new File([csvBlob], csvFileName, { type: "text/csv" });

      const fd = new FormData();
      fd.append("file", csvFile);
      const { data: uploadData } = await postFormData("/me/uploads", fd);

      const xCol = result.kind === "chart" ? "x" : (keys[0] || "x");
      const yCol = result.kind === "chart" ? (keys[0] || "value") : (keys[1] || keys[0] || "value");

      const payload = {
        type: "user_upload_chart",
        title: widgetTitle || "Graf z PDF",
        config: {
          user_upload_id: uploadData.id,
          x_column: xCol,
          y_column: yCol,
          chart_type: result.kind === "chart" ? (result.chart_type || "line") : "line",
          y_axis_label: result.y_axis_label || "",
          x_axis_label: result.x_axis_label || "",
          ...(keys.length > 1
            ? {
                extra_series: keys.slice(result.kind === "chart" ? 1 : 2).map((k, i) => ({
                  column: k,
                  label: k,
                  color: COLORS[(i + 1) % COLORS.length],
                })),
              }
            : {}),
        },
      };

      await onWidgetCreated(payload);
      toast.success("Widget přidán!");
      setPdfFile(null);
      setResult(null);
      setWidgetTitle("");
      if (fileRef.current) fileRef.current.value = "";
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se přidat widget.");
    } finally {
      setSaving(false);
    }
  };

  /* ── Download table as Excel ────────────────────────────────────────────── */
  const handleDownloadExcel = async () => {
    if (!result || result.kind !== "table") return;
    try {
      const res = await api.post(
        "/export/widget.xlsx",
        {
          title: widgetTitle || pdfFile?.name?.replace(/\.pdf$/i, "") || "tabulka",
          subtitle: `Extrahováno z PDF, str. ${result.extracted_from_page}`,
          columns: result.columns,
          rows: result.rows,
          filename: (widgetTitle || "tabulka").replace(/[^a-z0-9_-]+/gi, "_"),
        },
        { responseType: "blob" }
      );
      const safeName = (widgetTitle || "tabulka").replace(/[^a-z0-9_-]+/gi, "_").slice(0, 60) || "tabulka";
      const blob = new Blob([res.data], {
        type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${safeName}.xlsx`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 200);
      toast.success("Excel stažen.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Stahování Excelu selhalo.");
    }
  };

  const totalPages = result?.total_pages ?? null;

  return (
    <div className="space-y-3">
      {/* Mode tabs */}
      <div className="flex gap-1 border-b border-border/50 pb-2">
        <button
          type="button"
          onClick={() => handleSwitchMode("chart")}
          className={`flex items-center gap-1.5 px-3 h-8 rounded-lg text-xs font-medium transition-colors ${
            mode === "chart" ? "bg-amber-100 text-amber-800 border border-amber-200" : "text-muted-foreground hover:bg-muted/40"
          }`}
        >
          <Sparkles className="h-3.5 w-3.5" /> Graf z PDF (AI)
        </button>
        <button
          type="button"
          onClick={() => handleSwitchMode("table")}
          className={`flex items-center gap-1.5 px-3 h-8 rounded-lg text-xs font-medium transition-colors ${
            mode === "table" ? "bg-sky-100 text-sky-800 border border-sky-200" : "text-muted-foreground hover:bg-muted/40"
          }`}
        >
          <Table2 className="h-3.5 w-3.5" /> Tabulka z PDF
        </button>
      </div>

      {/* Upload + controls */}
      <div className={`rounded-xl border border-dashed p-4 space-y-3 ${mode === "chart" ? "border-amber-200 bg-amber-50/40" : "border-sky-200 bg-sky-50/40"}`}>
        <p className="text-xs text-muted-foreground leading-snug">
          {mode === "chart"
            ? "Nahrajte PDF s grafem. GPT-4o rozpozná typ grafu, osy a datové body."
            : "Nahrajte PDF s tabulkou. pdfplumber ji přečte a převede na CSV/Excel pro graf."}
        </p>

        <div className="flex flex-wrap items-center gap-2">
          <label className="inline-flex items-center gap-1.5 cursor-pointer px-3 h-9 rounded-lg border border-border bg-card text-sm hover:bg-muted/50 transition-colors">
            <FileText className="h-4 w-4 text-muted-foreground" />
            {pdfFile ? pdfFile.name : "Vybrat PDF…"}
            <input ref={fileRef} type="file" accept=".pdf" className="sr-only" onChange={handleFile} />
          </label>

          {pdfFile && (
            <div className="flex items-center gap-1 text-xs text-muted-foreground">
              <span>Stránka:</span>
              <input
                type="number"
                min={1}
                value={page}
                onChange={(e) => { setPage(Math.max(1, parseInt(e.target.value) || 1)); reset(); }}
                className="w-14 h-8 border border-border rounded-lg px-2 text-sm bg-card text-foreground"
              />
              {totalPages && <span className="text-muted-foreground">/ {totalPages}</span>}
            </div>
          )}

          {mode === "table" && pdfFile && (
            <>
              <div className="flex items-center gap-1 text-xs text-muted-foreground">
                <span>Záhlaví:</span>
                <input
                  type="number"
                  min={1}
                  max={10}
                  value={headerRow}
                  onChange={(e) => setHeaderRow(Math.max(1, parseInt(e.target.value) || 1))}
                  className="w-12 h-8 border border-border rounded-lg px-2 text-sm bg-card text-foreground"
                />
              </div>
              <div className="flex items-center gap-1 text-xs text-muted-foreground">
                <span title="Index tabulky na stránce; -1 = první">Tabulka č.:</span>
                <input
                  type="number"
                  min={-1}
                  max={20}
                  value={tableIndex}
                  onChange={(e) => setTableIndex(parseInt(e.target.value) ?? -1)}
                  className="w-12 h-8 border border-border rounded-lg px-2 text-sm bg-card text-foreground"
                />
              </div>
            </>
          )}

          <button
            type="button"
            onClick={mode === "chart" ? handleExtractChart : handleExtractTable}
            disabled={!pdfFile || busy}
            className={`inline-flex items-center gap-1.5 px-3 h-9 rounded-lg border text-sm font-medium disabled:opacity-50 transition-colors ${
              mode === "chart"
                ? "border-amber-300 bg-amber-50 text-amber-800 hover:bg-amber-100"
                : "border-sky-300 bg-sky-50 text-sky-800 hover:bg-sky-100"
            }`}
          >
            {busy ? (
              <><Loader2 className="h-4 w-4 animate-spin" /> Analyzuji…</>
            ) : mode === "chart" ? (
              <><Sparkles className="h-4 w-4" /> Extrahovat graf (AI)</>
            ) : (
              <><Table2 className="h-4 w-4" /> Extrahovat tabulku</>
            )}
          </button>
        </div>
      </div>

      {/* Preview výsledku */}
      {result && (
        <div className="rounded-xl border border-border/80 bg-card p-4 space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="text-xs uppercase tracking-widest text-muted-foreground font-semibold">
              {result.kind === "chart" ? "Náhled extrahovaného grafu" : "Náhled extrahované tabulky"}
            </div>
            <div className="text-xs text-muted-foreground">
              {result.kind === "chart"
                ? `${result.series?.length} série · ${result.series?.reduce((s, r) => s + r.data.length, 0)} bodů · str. ${result.extracted_from_page}`
                : `${result.total_rows} řádků · ${result.columns?.length} sl. · str. ${result.extracted_from_page}${result.truncated ? " (zkráceno)" : ""}`
              }
            </div>
          </div>

          {result.kind === "chart" && <ChartPreview result={result} />}
          {result.kind === "table" && (
            <TablePreview
              columns={result.columns}
              rows={result.rows}
              truncated={result.truncated}
              totalRows={result.total_rows}
              modeUsed={result.mode_used}
            />
          )}

          {onWidgetCreated && (
            <div className="flex flex-wrap items-end gap-2 pt-1 border-t border-border/50">
              <div className="flex-1 min-w-[160px]">
                <label className="text-[11px] text-muted-foreground mb-1 block">Název widgetu</label>
                <input
                  className="w-full h-8 border border-border rounded-lg px-2 text-sm bg-card text-foreground"
                  value={widgetTitle}
                  onChange={(e) => setWidgetTitle(e.target.value)}
                  placeholder="Název grafu…"
                />
              </div>
              <button
                type="button"
                onClick={handleAddWidget}
                disabled={saving || !widgetTitle.trim() || widgetApplyDisabled}
                className="inline-flex items-center gap-1.5 px-3 h-8 rounded-lg bg-primary text-primary-foreground text-sm font-medium disabled:opacity-50 hover:opacity-90 transition-opacity"
              >
                {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                {saving ? "Ukládám…" : "Přidat jako widget"}
              </button>
              {result.kind === "table" && (
                <button
                  type="button"
                  onClick={handleDownloadExcel}
                  className="inline-flex items-center gap-1.5 px-3 h-8 rounded-lg border border-emerald-300 bg-emerald-50 text-emerald-800 text-sm font-medium hover:bg-emerald-100 transition-colors"
                >
                  <FileSpreadsheet className="h-3.5 w-3.5" />
                  Stáhnout jako Excel
                </button>
              )}
              <button
                type="button"
                onClick={mode === "chart" ? handleExtractChart : handleExtractTable}
                disabled={busy || !pdfFile}
                className="inline-flex items-center gap-1.5 px-3 h-8 rounded-lg border border-border text-sm text-foreground/80 hover:bg-muted/40 disabled:opacity-50"
              >
                Znovu analyzovat
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
