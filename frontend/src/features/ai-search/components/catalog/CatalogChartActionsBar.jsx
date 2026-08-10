import React, { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { BarChart3, Copy, Download, GitCompare, LayoutDashboard, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import api from "@/lib/api";
import { copyChartDataWideToClipboard, exportChartXlsxViaApi } from "@/charts/chartExport";
import { contractFromAradViewState } from "@/charts/contractFromAradViewState";
import { buildAradDataFromCatalogPreview } from "@/lib/mapCatalogPreviewToArad";
import { extractSelectedDimensionsForStorage } from "@/lib/catalogDimensions";
import CatalogAnalyticsForecastButtons from "./search/CatalogAnalyticsForecastButtons";

/**
 * Akční lišta katalogového preview — dashboard, compare, export.
 */
export default function CatalogChartActionsBar({
  preview,
  previewError = "",
  title = "",
  widgetId = "catalog-preview",
  canAddToDashboard = false,
  onAddToDashboard,
  addingToDashboard = false,
  canCompare = false,
  onCompare,
  canSync = false,
  onSync,
  syncing = false,
  canExport = true,
  disabled = false,
  sourceType = "",
  setId = "",
  catalogTitle = "",
  geo = "",
  queryParams = null,
  dimensionFilters = null,
  selectedIndicator = "",
  selectedIndicators = [],
  comparisonDimension = "",
  comparisonGroups = [],
  query = "",
  /**
   * Řady se stejnou periodicitou/agregací, jakou má právě vybraný graf (viz AradView.jsx
   * chartDisplayState -> CatalogChartPreview.jsx) - {period, value}[]. Export bez tohohle vždy
   * bral syrová `preview.rows`, takže Copy Excel/XLSX neodpovídalo tomu, co uživatel vidí a
   * vybral si v grafu (Denní/Týdenní/Měsíční…).
   */
  periodicityOverrideRows = null,
  periodicityOverrideFrequencyLabel = "",
  /** Kód frekvence (D/W/M/Q/H/Y) vybrané v grafu - přeposílá se do Analytiky/Forecastu. */
  periodicityFrequencyCode = "",
}) {
  const [exportBusy, setExportBusy] = useState(false);
  const navigate = useNavigate();

  /**
   * Export je funkce jen pro předplatitele — backend na to u nepředplatitelů vrací 403.
   * Místo surové axios hlášky "Request failed with status code 403" ukážeme srozumitelnou
   * zprávu s odkazem do sekce Předplatné. Ostatní chyby dostanou obecnou, ne technickou hlášku.
   */
  const notifyExportError = useCallback(
    (e, genericMsg) => {
      if (e?.response?.status === 403) {
        toast.error("Export je dostupný jen pro předplatitele.", {
          description: "Odemknete ho předplatným časopisu Bankovnictví.",
          action: { label: "Předplatné", onClick: () => navigate("/predplatne") },
        });
        return;
      }
      toast.error(genericMsg);
    },
    [navigate]
  );

  const buildContract = useCallback(() => {
    if (!preview || previewError) return null;
    const aradData = buildAradDataFromCatalogPreview(preview, title);
    if (!aradData?.rows?.length) return null;
    const isMulti = Boolean(aradData.multi_series);
    const seriesList = isMulti
      ? (aradData.series || []).map((s) => ({ key: s.key, label: s.label, indicator_id: s.key }))
      : [{ key: "main", label: title || "Řada", indicator_id: "main" }];
    const useOverride =
      !isMulti && Array.isArray(periodicityOverrideRows) && periodicityOverrideRows.length > 0;
    const chartRows = isMulti
      ? aradData.rows.map((r) => {
          const row = { period: r.period, x: r.period };
          for (const s of aradData.series || []) {
            row[s.key] = r[s.key];
          }
          return row;
        })
      : useOverride
        ? periodicityOverrideRows.map((r) => ({ period: r.period, x: r.period, value: r.value }))
        : aradData.rows.map((r) => ({ period: r.period, x: r.period, value: r.value }));
    const latestMode = String(preview?.chart_data_mode || preview?.metadata?.chart_data_mode || "")
      .toLowerCase() === "latest";
    return contractFromAradViewState({
      title: title || preview?.source?.name || "Graf",
      unit: aradData.unit || "",
      chartKind: "line",
      latestDataMode: latestMode,
      isMultiSeries: isMulti,
      seriesList,
      chartRows,
      data: aradData,
      widget: { id: widgetId, config: { selected_dimensions: extractSelectedDimensionsForStorage(preview) } },
      frequency: useOverride ? periodicityOverrideFrequencyLabel || aradData.frequency || "" : aradData.frequency || "",
    });
  }, [preview, previewError, title, widgetId, periodicityOverrideRows, periodicityOverrideFrequencyLabel]);

  const onCopyExcel = async () => {
    const contract = buildContract();
    if (!contract) {
      toast.error("Nejsou data pro export.");
      return;
    }
    setExportBusy(true);
    try {
      await copyChartDataWideToClipboard(contract, { locale: "cs-CZ" });
      toast.success("Data zkopírována do schránky (Excel).");
    } catch (e) {
      notifyExportError(e, "Zkopírování dat do schránky selhalo.");
    } finally {
      setExportBusy(false);
    }
  };

  const onXlsx = async () => {
    const contract = buildContract();
    if (!contract) {
      toast.error("Nejsou data pro export.");
      return;
    }
    setExportBusy(true);
    try {
      const dims = extractSelectedDimensionsForStorage(preview);
      await exportChartXlsxViaApi(
        {
          ...contract,
          dimensions_meta: dims,
        },
        { api, filename: (title || "katalog-graf").replace(/[^\w\d-]+/gi, "_").slice(0, 60) }
      );
      toast.success("Excel stažen.");
    } catch (e) {
      notifyExportError(e, "Export XLSX se nepodařilo dokončit. Zkuste to prosím znovu.");
    } finally {
      setExportBusy(false);
    }
  };

  const busy = disabled || exportBusy || addingToDashboard || syncing;

  return (
    <div className="flex flex-nowrap sm:flex-wrap items-center gap-1.5 mb-2 max-md:mb-1 max-md:shrink-0 overflow-x-auto" data-testid="catalog-chart-actions">
      {canSync && onSync ? (
        <button
          type="button"
          disabled={busy}
          onClick={onSync}
          className="inline-flex shrink-0 items-center gap-1 h-7 px-2 sm:px-2.5 text-xs rounded-lg border border-border/70 bg-card hover:bg-muted/50 disabled:opacity-50"
        >
          <RefreshCw className={`h-3 w-3 ${syncing ? "animate-spin" : ""}`} />
          <span className="hidden sm:inline">Synchronizovat</span>
        </button>
      ) : null}
      {canAddToDashboard && onAddToDashboard ? (
        <button
          type="button"
          disabled={busy}
          onClick={onAddToDashboard}
          className="inline-flex shrink-0 items-center gap-1.5 h-7 px-2.5 sm:px-3 text-xs font-semibold rounded-lg border border-transparent bg-[hsl(var(--primary))] text-[hsl(var(--primary-foreground))] shadow-sm hover:bg-[hsl(var(--primary-deep))] disabled:opacity-50"
        >
          {addingToDashboard ? (
            <RefreshCw className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <LayoutDashboard className="h-3.5 w-3.5" />
          )}
          <span>Přidat na dashboard</span>
        </button>
      ) : null}
      {canCompare && onCompare ? (
        <button
          type="button"
          disabled={busy}
          onClick={onCompare}
          className="inline-flex shrink-0 items-center gap-1 h-7 px-2 sm:px-2.5 text-xs rounded-lg border border-border/70 bg-card hover:bg-muted/50 disabled:opacity-50"
        >
          <GitCompare className="h-3 w-3" />
          <span className="hidden sm:inline">Srovnat s…</span>
        </button>
      ) : null}
      {canExport ? (
        <>
          <button
            type="button"
            disabled={busy || !preview?.rows?.length}
            onClick={onCopyExcel}
            className="inline-flex shrink-0 items-center gap-1 h-7 px-2 sm:px-2.5 text-xs rounded-lg border border-border/70 bg-card hover:bg-muted/50 disabled:opacity-50"
            title="Kopírovat do Excelu (wide, cs-CZ)"
          >
            <Copy className="h-3 w-3" />
            <span className="hidden sm:inline">Copy Excel</span>
          </button>
          <button
            type="button"
            disabled={busy || !preview?.rows?.length}
            onClick={onXlsx}
            className="inline-flex shrink-0 items-center gap-1 h-7 px-2 sm:px-2.5 text-xs rounded-lg border border-border/70 bg-card hover:bg-muted/50 disabled:opacity-50"
            title="Stáhnout XLSX (Data_Wide, Data_Long, Metadata)"
          >
            <Download className="h-3 w-3" />
            <span className="hidden sm:inline">XLSX</span>
          </button>
        </>
      ) : null}
      <CatalogAnalyticsForecastButtons
        sourceType={sourceType}
        setId={setId}
        name={catalogTitle || title}
        geo={geo}
        queryParams={queryParams}
        dimensionFilters={dimensionFilters}
        selectedIndicator={selectedIndicator}
        selectedIndicators={selectedIndicators}
        comparisonDimension={comparisonDimension}
        comparisonGroups={comparisonGroups}
        query={query || catalogTitle || title}
        disabled={disabled || !preview?.rows?.length}
        targetFrequency={periodicityFrequencyCode}
      />
      <span className="hidden sm:inline-flex items-center gap-1 text-[10px] text-slate-500 ml-auto">
        <BarChart3 className="h-3 w-3" aria-hidden />
        AradView preview
      </span>
    </div>
  );
}
