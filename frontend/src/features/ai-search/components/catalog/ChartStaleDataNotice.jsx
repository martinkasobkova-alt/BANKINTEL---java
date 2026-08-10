import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, Search } from "lucide-react";
import {
  enrichChartExplainMeta,
  extractLastChartObservation,
  fetchChartDataQuality,
  isChartPeriodStale,
} from "@/lib/chartDataQuality";

/**
 * Varování pod grafem u starých dat — napříč katalogem, dashboardem i explore.
 */
export default function ChartStaleDataNotice({
  meta,
  rows = [],
  timeField,
  valueField,
  onFindInCatalogSearch,
  className = "",
}) {
  const enrichedMeta = useMemo(
    () => enrichChartExplainMeta(meta, rows, { timeField, valueField }),
    [meta, rows, timeField, valueField],
  );
  const metaKey = JSON.stringify(enrichedMeta);
  const [quality, setQuality] = useState(null);

  useEffect(() => {
    let cancelled = false;
    const last = extractLastChartObservation(rows, { timeField, valueField });
    if (!last?.period || !isChartPeriodStale(last.period)) {
      setQuality(null);
      return undefined;
    }
    void fetchChartDataQuality(enrichedMeta).then((data) => {
      if (!cancelled && data?.stale) setQuality(data);
      else if (!cancelled) setQuality(null);
    });
    return () => {
      cancelled = true;
    };
  }, [metaKey, rows, timeField, valueField, enrichedMeta]);

  if (!quality?.stale) return null;

  const warnings = Array.isArray(quality.warnings) ? quality.warnings : [];
  const warningText = String(warnings.find((w) => w?.detail_cs || w?.message_cs)?.detail_cs || warnings.find((w) => w?.message_cs)?.message_cs || "").trim();
  const lastPeriod = String(quality.last_period || enrichedMeta.last_period || "").trim();
  const notice = String(
    quality.notice_cs ||
      warningText ||
      (lastPeriod
        ? `Poslední hodnota v grafu je z období ${lastPeriod}; data mohou být u zdroje ukončená.`
        : "Data v grafu vypadají zastarale; ověřte definici u vydavatele.")
  ).trim();
  const detail = String(quality.detail_cs || "").trim();
  const searchQuery = String(
    quality.suggested_search_query || (Array.isArray(quality.suggested_search_queries) ? quality.suggested_search_queries[0] : "")
  ).trim();
  const canSearch = Boolean(searchQuery && typeof onFindInCatalogSearch === "function");

  return (
    <div
      className={`rounded-xl border border-amber-200/90 bg-gradient-to-r from-amber-50/95 to-orange-50/70 px-3 py-2.5 text-[12px] leading-snug text-amber-950 ${className}`}
      data-testid="chart-stale-data-notice"
      role="status"
    >
      <div className="flex items-start gap-2">
        <AlertTriangle className="h-4 w-4 shrink-0 text-amber-600 mt-0.5" aria-hidden />
        <div className="min-w-0 flex-1 space-y-1.5">
          <p className="font-semibold text-amber-950">{notice}</p>
          {detail ? <p className="text-amber-900/90">{detail}</p> : null}
          {canSearch ? (
            <button
              type="button"
              onClick={() => onFindInCatalogSearch(searchQuery)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-amber-300 bg-white/80 px-2.5 py-1 text-[11px] font-medium text-amber-950 hover:bg-amber-100/80"
              data-testid="chart-stale-find-alternative"
            >
              <Search className="h-3.5 w-3.5" aria-hidden />
              Vyhledat aktuálnější řadu
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}

export { enrichChartExplainMeta };
