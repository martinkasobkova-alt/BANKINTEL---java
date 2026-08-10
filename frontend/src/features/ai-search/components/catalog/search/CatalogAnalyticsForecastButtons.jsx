import { useState } from "react";
import { BarChart3, TrendingUp } from "lucide-react";
import ForecastPanel from "./ForecastPanel";
import AnalyticsPanel from "./AnalyticsPanel";

/**
 * Sdílená tlačítka Analytika / Forecast + panely pro katalog (karty, tabulka, detail).
 */
export default function CatalogAnalyticsForecastButtons({
  sourceType,
  setId,
  name,
  geo = "",
  queryParams = null,
  dimensionFilters = null,
  selectedIndicator = "",
  selectedIndicators = [],
  comparisonDimension = "",
  comparisonGroups = [],
  query = "",
  disabled = false,
  className = "",
  /** Kód frekvence (D/W/M/Q/H/Y) vybrané v grafu - Analytika/Forecast pak počítají ze stejné
   * periodicity, ne vždy z nativní. */
  targetFrequency = "",
}) {
  const [forecastOpen, setForecastOpen] = useState(false);
  const [analyticsOpen, setAnalyticsOpen] = useState(false);
  const canRun = Boolean(sourceType) && Boolean(setId) && !disabled;

  if (!canRun) return null;

  return (
    <>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          setAnalyticsOpen(true);
        }}
        className={`inline-flex items-center gap-1 h-7 px-2.5 text-[11px] rounded-lg border border-border/70 bg-card text-foreground/90 hover:bg-muted/50 whitespace-nowrap ${className}`}
        title="Metriky, trendy, vztahy, anomálie"
      >
        <BarChart3 className="h-3 w-3" /> Analytika
      </button>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          setForecastOpen(true);
        }}
        className={`inline-flex items-center gap-1 h-7 px-2.5 text-[11px] rounded-lg border border-border/70 bg-card text-foreground/90 hover:bg-muted/50 whitespace-nowrap ${className}`}
        title="Technický forecast (baseline, interval nejistoty, scénáře)"
      >
        <TrendingUp className="h-3 w-3" /> Forecast
      </button>
      <ForecastPanel
        open={forecastOpen}
        onClose={() => setForecastOpen(false)}
        sourceType={sourceType}
        setId={setId}
        name={name}
        geo={geo}
        queryParams={queryParams}
        dimensionFilters={dimensionFilters}
        selectedIndicator={selectedIndicator}
        selectedIndicators={selectedIndicators}
        comparisonDimension={comparisonDimension}
        comparisonGroups={comparisonGroups}
        targetFrequency={targetFrequency}
      />
      <AnalyticsPanel
        open={analyticsOpen}
        onClose={() => setAnalyticsOpen(false)}
        sourceType={sourceType}
        setId={setId}
        name={name}
        geo={geo}
        queryParams={queryParams}
        dimensionFilters={dimensionFilters}
        selectedIndicator={selectedIndicator}
        selectedIndicators={selectedIndicators}
        comparisonDimension={comparisonDimension}
        comparisonGroups={comparisonGroups}
        query={query || name}
        targetFrequency={targetFrequency}
      />
    </>
  );
}
