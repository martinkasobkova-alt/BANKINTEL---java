import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ClipboardCopy, Database, Download, FileImage, FileSpreadsheet, FileText, FileType2, Lock } from "lucide-react";
import api from "@/lib/api";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import { captureChartCardAsDataUrl, exportChartNodeAsImage } from "@/lib/chartImageExport";
import {
  exportChartDataLongCsv,
  exportChartDataWideCsv,
  exportChartOlapFactCsv,
  exportChartOlapJson,
  exportChartXlsxViaApi,
  copyChartDataWideToClipboard,
} from "@/charts/chartExport";

/**
 * Per-widget export menu. Renders a small "Export" button that opens
 * a popover with CSV / XLSX / PDF options.
 *
 * Props:
 *   title     – used as filename + document heading
 *   subtitle? – optional sub-heading written into XLSX/PDF
 *   columns   – array of column keys (string)
 *   rows      – array of objects keyed by `columns`
 *   meCatalogWidgetId? – u osobního katalogového widgetu: kompletní sada přes
 *   POST /me/dashboard/widgets/{id}/export-catalog (vlastník + export_data)
 */
export default function ExportMenu({
  title,
  subtitle = "",
  columns = [],
  rows = [],
  compact = false,
  meCatalogWidgetId = null,
  chartTargetRef = null,
  enableChartImageExport = false,
  /** Zamčená zdrojová data — povolit jen PDF/PNG, ne Excel/CSV/kopírování */
  lockSourceData = false,
  /** Chart System contract — CSV long / chart.xlsx / clipboard */
  chartContract = null,
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(null);
  const [menuPos, setMenuPos] = useState(null);
  const ref = useRef(null);
  const triggerRef = useRef(null);
  const menuRef = useRef(null);
  const { allowed: canExport, message: exportLockMsg, loading: feLoading, ready: exportReady } =
    useFeatureAccess("export_data");
  const {
    allowed: canImageExport,
    message: imageLockMsg,
    loading: imageLoading,
    ready: imageReady,
  } = useFeatureAccess("chart_image_export");

  useEffect(() => {
    if (!open) return;
    const onDoc = (e) => {
      if (ref.current?.contains(e.target)) return;
      if (menuRef.current?.contains(e.target)) return;
      setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  const safeName = (title || "export").replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 60) || "export";

  const downloadBlob = (blob, ext) => {
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${safeName}.${ext}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 200);
  };

  const exportCsv = () => {
    if (feLoading || !canExportData) {
      return;
    }
    const headers = columns.length ? columns : Array.from(new Set(rows.flatMap((r) => Object.keys(r || {}))));
    const escape = (v) => {
      if (v === null || v === undefined) return "";
      const s = typeof v === "object" ? JSON.stringify(v) : String(v);
      return /[",;\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const lines = [headers.join(";")];
    for (const r of rows) lines.push(headers.map((h) => escape((r || {})[h])).join(";"));
    const blob = new Blob(["\uFEFF" + lines.join("\n")], { type: "text/csv;charset=utf-8" });
    downloadBlob(blob, "csv");
    setOpen(false);
  };

  const exportRemote = async (kind) => {
    if (feLoading || !canExportData) return;
    try {
      setBusy(kind);
      const path = kind === "xlsx" ? "/export/widget.xlsx" : "/export/widget.pdf";
      const body = { title, subtitle, columns, rows, filename: safeName };
      if (kind === "pdf" && enableChartImageExport && chartTargetRef?.current) {
        try {
          // Close popover first so export snapshot never includes menu UI.
          setOpen(false);
          await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          const dataUrl = await captureChartCardAsDataUrl(chartTargetRef.current, "png");
          const b64 = dataUrl.includes(",") ? dataUrl.split(",")[1] : dataUrl;
          if (b64) body.chart_image_png = b64;
        } catch (imgErr) {
          console.warn("Snímek grafu pro PDF se nepodařil, PDF bude jen tabulka.", imgErr);
        }
      }
      const res = await api.post(path, body, { responseType: "blob" });
      const mime = kind === "xlsx"
        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        : "application/pdf";
      const blob = new Blob([res.data], { type: mime });
      downloadBlob(blob, kind);
      setOpen(false);
    } catch (e) {
      console.error("Export failed", e);
      alert("Export selhal. Zkontroluj, že běží backend.");
    } finally {
      setBusy(null);
    }
  };

  const downloadMeCatalogFull = async (fmt) => {
    if (feLoading || !canExportData || !meCatalogWidgetId) return;
    try {
      setBusy(`me-cat-${fmt}`);
      const res = await api.post(
        `/me/dashboard/widgets/${meCatalogWidgetId}/export-catalog`,
        { format: fmt },
        { responseType: "blob" }
      );
      const cd = res.headers?.["content-disposition"] || "";
      const m = /filename\s*=\s*"?([^";]+)"?/i.exec(cd);
      const ext = fmt === "xlsx" ? "xlsx" : fmt === "json" ? "json" : "csv";
      const fname = m?.[1] || `katalog.${ext}`;
      const url = URL.createObjectURL(res.data);
      const a = document.createElement("a");
      a.href = url;
      a.download = fname;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 0);
      setOpen(false);
    } catch (e) {
      console.error("Katalogový export selhal", e);
      alert("Stahování plné sady se nepodařilo (oprávnění nebo síť).");
    } finally {
      setBusy(null);
    }
  };

  const hasChartContract = Boolean(chartContract?.data?.length);

  const noData = (!rows || rows.length === 0) && !hasChartContract;
  const canExportData = canExport && !lockSourceData;
  const canChartImage = Boolean(enableChartImageExport && canImageExport);
  // PDF je dostupné i u zamčeného grafu, takže samotné `canExport` stačí na otevření nabídky.
  const canAnyExport = canExportData || canChartImage || canExport;
  const loadingAny = feLoading || imageLoading;
  const disabled = noData || loadingAny || !canAnyExport;

  const exportChartImage = async (fmt) => {
    if (!canChartImage || !chartTargetRef?.current) return;
    try {
      setBusy(`img-${fmt}`);
      await exportChartNodeAsImage(chartTargetRef.current, fmt, safeName);
      setOpen(false);
    } catch (e) {
      console.error("Chart image export failed", e);
      alert("Stažení obrázku grafu se nepodařilo.");
    } finally {
      setBusy(null);
    }
  };

  const exportChartCsvContract = () => {
    if (!chartContract?.data?.length || feLoading || !canExportData) return;
    exportChartDataLongCsv(chartContract, { filename: safeName, delimiter: "," });
    setOpen(false);
  };

  const exportOlapJsonContract = () => {
    if (!chartContract?.data?.length || feLoading || !canExportData) return;
    exportChartOlapJson(chartContract, { filename: safeName, query: subtitle || "" });
    setOpen(false);
  };

  const exportOlapFactCsvContract = () => {
    if (!chartContract?.data?.length || feLoading || !canExportData) return;
    exportChartOlapFactCsv(chartContract, { filename: safeName, query: subtitle || "" });
    setOpen(false);
  };

  const exportChartXlsxContract = async () => {
    if (!chartContract?.data?.length || feLoading || !canExportData) return;
    try {
      setBusy("chart-xlsx");
      await exportChartXlsxViaApi(chartContract, { api, filename: safeName, query: subtitle || "" });
      setOpen(false);
    } catch (e) {
      console.error("Chart XLSX export failed", e);
      alert("Export Excel (chart) selhal. Zkontroluj backend /api/export/chart.xlsx.");
    } finally {
      setBusy(null);
    }
  };

  const copyChartContract = async () => {
    if (!chartContract?.data?.length || feLoading || !canExportData) return;
    try {
      setBusy("clipboard");
      const ok = await copyChartDataWideToClipboard(chartContract, { locale: "cs-CZ" });
      if (!ok) alert("Kopírování do schránky není v prohlížeči podporováno.");
      setOpen(false);
    } catch (e) {
      console.error("Clipboard export failed", e);
      alert("Kopírování dat selhalo.");
    } finally {
      setBusy(null);
    }
  };

  const updateMenuPos = () => {
    const el = triggerRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const menuW = 280;
    const gap = 6;
    const viewportPad = 8;
    let left = r.left;
    if (left + menuW > window.innerWidth - viewportPad) left = Math.max(viewportPad, window.innerWidth - viewportPad - menuW);
    const naturalHeight = menuRef.current?.scrollHeight || 420;
    const desiredH = Math.min(naturalHeight, Math.max(180, window.innerHeight - viewportPad * 2));
    const belowSpace = window.innerHeight - r.bottom - gap - viewportPad;
    const aboveSpace = r.top - gap - viewportPad;
    let top = r.bottom + gap;
    let maxHeight = Math.max(180, belowSpace);
    if (belowSpace < Math.min(desiredH, 260) && aboveSpace > belowSpace) {
      maxHeight = Math.max(180, aboveSpace);
      top = Math.max(viewportPad, r.top - gap - Math.min(desiredH, maxHeight));
    }
    setMenuPos({ top, left, width: menuW, maxHeight: Math.min(maxHeight, 520) });
  };

  useLayoutEffect(() => {
    if (!open) {
      setMenuPos(null);
      return undefined;
    }
    updateMenuPos();
    window.addEventListener("resize", updateMenuPos);
    window.addEventListener("scroll", updateMenuPos, true);
    return () => {
      window.removeEventListener("resize", updateMenuPos);
      window.removeEventListener("scroll", updateMenuPos, true);
    };
  }, [open]);

  const menuOpen = open && (exportReady || imageReady) && canAnyExport;

  return (
    <div className="relative" ref={ref} data-export-ignore="1">
      <button
        ref={triggerRef}
        type="button"
        onClick={() => {
          if (noData || !canAnyExport || loadingAny) return;
          if (!open) updateMenuPos();
          setOpen((v) => !v);
        }}
        disabled={disabled}
        title={
          noData
            ? "Žádná data k exportu"
            : loadingAny
              ? exportLockMsg || imageLockMsg || "Kontrola oprávnění…"
              : !canAnyExport
                ? exportLockMsg || imageLockMsg || "Export není k dispozici"
                : "Stáhnout data"
        }
        className={`flex items-center gap-1 ${compact ? "h-6 px-2 text-[9px] tracking-[0.1em]" : "h-7 px-3 text-[10px] tracking-[0.12em]"} uppercase rounded-full border transition-colors ${
          disabled
            ? "border-border/40 text-slate-300 cursor-not-allowed"
            : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
        }`}
      >
        {(exportReady || imageReady) && !canAnyExport ? (
          <Lock className={compact ? "h-3 w-3" : "h-3.5 w-3.5"} />
        ) : (
          <Download className={compact ? "h-3 w-3" : "h-3.5 w-3.5"} />
        )}
        {!compact && <span>Export</span>}
      </button>
      {!compact && (exportReady || imageReady) && !canAnyExport && !noData && (
        <p className="text-[8px] text-slate-500 mt-0.5 max-w-[11rem] leading-tight">
          Export pro předplatitele. {exportLockMsg || imageLockMsg}
        </p>
      )}
      {menuOpen && typeof document !== "undefined"
        ? createPortal(
            <div
              ref={menuRef}
              className="fixed z-[10050] bg-white border border-border/60 rounded-md shadow-2xl overflow-y-auto overscroll-contain"
              style={{
                top: menuPos?.top ?? 0,
                left: menuPos?.left ?? 0,
                width: menuPos?.width ?? 280,
                maxHeight: menuPos?.maxHeight ?? "min(70vh,420px)",
                visibility: menuPos ? "visible" : "hidden",
              }}
              role="menu"
            >
          {enableChartImageExport ? (
            <>
              <div className="px-2 py-1.5 text-[9px] uppercase tracking-wider text-slate-500 border-b border-border/40">
                Obrázek grafu
              </div>
              <button
                type="button"
                onClick={() => exportChartImage("png")}
                disabled={busy !== null || !canChartImage || !chartTargetRef?.current}
                className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left"
              >
                <FileImage className="h-3.5 w-3.5 text-indigo-600" />
                <span>PNG</span>
                {busy === "img-png" && <span className="ml-auto text-[10px] text-slate-400">…</span>}
              </button>
              <button
                type="button"
                onClick={() => exportChartImage("jpg")}
                disabled={busy !== null || !canChartImage || !chartTargetRef?.current}
                className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left border-b border-border/40"
              >
                <FileImage className="h-3.5 w-3.5 text-amber-700" />
                <span>JPG</span>
                {busy === "img-jpg" && <span className="ml-auto text-[10px] text-slate-400">…</span>}
              </button>
            </>
          ) : null}
          {/* PDF je u zamčeného grafu záměrně dostupné — popisek u zámku slibuje „jen obrázek
              nebo PDF". Dokud bylo uvnitř bloku canExportData, mizelo spolu s Excelem a slib
              se nedodržel. */}
          {canExport ? (
            <button
              type="button"
              onClick={() => exportRemote("pdf")}
              disabled={busy !== null}
              className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left"
            >
              <FileText className="h-3.5 w-3.5 text-red-600" />
              <span>PDF</span>
              {busy === "pdf" && <span className="ml-auto text-[10px] text-slate-400">…</span>}
            </button>
          ) : null}
          {canExportData ? (
            <>
          {hasChartContract ? (
            <>
              <div className="px-2 py-1.5 text-[9px] uppercase tracking-wider text-slate-500 border-b border-border/40">
                Data grafu
              </div>
              <button
                type="button"
                onClick={copyChartContract}
                disabled={busy !== null}
                className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left"
              >
                <ClipboardCopy className="h-3.5 w-3.5 text-sky-600" />
                <span>Kopírovat do Excelu</span>
                {busy === "clipboard" && <span className="ml-auto text-[10px] text-slate-400">…</span>}
              </button>
              <button
                type="button"
                onClick={() => {
                  if (!chartContract?.data?.length || feLoading || !canExportData) return;
                  exportChartDataWideCsv(chartContract, {
                    filename: safeName,
                    locale: "cs-CZ",
                    delimiter: ";",
                  });
                  setOpen(false);
                }}
                disabled={busy !== null}
                className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left"
              >
                <FileType2 className="h-3.5 w-3.5 text-slate-500" />
                <span>CSV pro Excel</span>
              </button>
              <button
                type="button"
                onClick={exportChartCsvContract}
                disabled={busy !== null}
                className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left"
              >
                <FileType2 className="h-3.5 w-3.5 text-slate-400" />
                <span>Technický CSV export</span>
              </button>
              <button
                type="button"
                onClick={exportChartXlsxContract}
                disabled={busy !== null}
                className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left border-b border-border/40"
              >
                <FileSpreadsheet className="h-3.5 w-3.5 text-emerald-600" />
                <span>Excel (XLSX)</span>
                {busy === "chart-xlsx" && <span className="ml-auto text-[10px] text-slate-400">…</span>}
              </button>
              <div className="px-2 py-1.5 text-[9px] uppercase tracking-wider text-slate-500 border-y border-border/40 bg-slate-50/70">
                OLAP / BI
              </div>
              <button
                type="button"
                onClick={exportOlapJsonContract}
                disabled={busy !== null}
                className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left"
                title="Self-contained OLAP balicek: fact_values + dim_period + dim_series + dim_geo + dim_source"
              >
                <Database className="h-3.5 w-3.5 text-violet-700" />
                <span>OLAP kostka (JSON)</span>
              </button>
              <button
                type="button"
                onClick={exportOlapFactCsvContract}
                disabled={busy !== null}
                className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left border-b border-border/40"
                title="Faktova tabulka pro warehouse / Power BI / SQL import"
              >
                <Database className="h-3.5 w-3.5 text-sky-700" />
                <span>Fact table CSV</span>
              </button>
            </>
          ) : null}
          {meCatalogWidgetId ? (
            <div className="px-2 py-1.5 text-[9px] uppercase tracking-wider text-slate-500 border-b border-border/40">
              Zobrazená data
            </div>
          ) : null}
          <button
            type="button"
            onClick={() => exportRemote("xlsx")}
            disabled={busy !== null}
            className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left"
          >
            <FileSpreadsheet className="h-3.5 w-3.5 text-emerald-600" />
            <span>Excel (tabulka widgetu)</span>
            {busy === "xlsx" && <span className="ml-auto text-[10px] text-slate-400">…</span>}
          </button>
          <button
            type="button"
            onClick={exportCsv}
            className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 text-left border-t border-border/40"
          >
            <FileType2 className="h-3.5 w-3.5 text-slate-500" />
            <span>CSV (jen data)</span>
          </button>
          {meCatalogWidgetId ? (
            <>
              <div className="px-2 py-1.5 text-[9px] uppercase tracking-wider text-slate-500 border-t border-border/40">
                Kompletní sada (katalog)
              </div>
              {[
                { fmt: "csv", label: "CSV" },
                { fmt: "xlsx", label: "Excel" },
                { fmt: "json", label: "JSON" },
              ].map((opt) => (
                <button
                  key={opt.fmt}
                  type="button"
                  onClick={() => downloadMeCatalogFull(opt.fmt)}
                  disabled={busy !== null}
                  className="w-full flex items-center gap-2 px-3 h-8 text-[12px] hover:bg-slate-50 disabled:opacity-60 text-left border-t border-border/40"
                >
                  <FileType2 className="h-3.5 w-3.5 text-slate-500" />
                  <span>Plná sada — {opt.label}</span>
                  {busy === `me-cat-${opt.fmt}` && (
                    <span className="ml-auto text-[10px] text-slate-400">…</span>
                  )}
                </button>
              ))}
            </>
          ) : null}
            </>
          ) : null}
            </div>,
            document.body
          )
        : null}
    </div>
  );
}
