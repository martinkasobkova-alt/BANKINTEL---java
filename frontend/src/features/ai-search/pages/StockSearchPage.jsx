import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { TrendingUp, ExternalLink } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import CatalogChartPreview from "@/components/catalog/CatalogChartPreview";
import { LoadingBlock } from "@/components/ui/loading";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import {
  addCatalogPreviewToPersonalDashboard,
  buildCatalogChartActionsProps,
} from "@/lib/catalogPageDashboard";
import { STOCK_MARKET_CATALOGS } from "@/lib/catalogDefinitions";
import { fetchCatalogLivePreview } from "@/lib/catalogLivePreview";

const YAHOO_DEF = STOCK_MARKET_CATALOGS.find((c) => c.id === "yahoo_finance");

function previewDefForRow(row) {
  const src = String(row?.source_type || row?.source || "yahoo_finance").trim().toLowerCase();
  const def = STOCK_MARKET_CATALOGS.find((c) => c.id === src || c.sourceType === src) || YAHOO_DEF;
  return def;
}

export default function StockSearchPage() {
  const nav = useNavigate();
  const [params, setParams] = useSearchParams();
  const initialQ = (params.get("q") || "").trim();
  /** Co už se hledalo — brání smyčce, protože runSearch samo zapisuje `q` do URL. */
  const lastRunQueryRef = useRef(initialQ);
  const [loading, setLoading] = useState(false);
  const [payload, setPayload] = useState(null);
  const [selectedTicker, setSelectedTicker] = useState(null);
  const [previewData, setPreviewData] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [addingToDash, setAddingToDash] = useState(false);

  const { isSubscriber } = useAuth();
  const { allowed: canPersonalDashboard, message: personalDashMsg } = useFeatureAccess("personal_dashboard");
  const { allowed: canSaveWidget, message: saveWidgetMsg } = useFeatureAccess("save_widget");
  const dashboardFeature = useMemo(
    () => ({ isSubscriber, canPersonalDashboard, canSaveWidget, personalDashMsg, saveWidgetMsg }),
    [isSubscriber, canPersonalDashboard, canSaveWidget, personalDashMsg, saveWidgetMsg],
  );

  const results = useMemo(() => payload?.results || [], [payload]);
  const resolver = payload?.resolver;

  const runSearch = useCallback(
    async (qRaw) => {
      const q = String(qRaw || "").trim();
      if (q.length < 2) {
        toast.error("Zadejte alespoň 2 znaky.");
        return;
      }
      lastRunQueryRef.current = q;
      setLoading(true);
      setSelectedTicker(null);
      setPreviewData(null);
      setPreviewError("");
      try {
        const { data } = await api.post("/stocks/search", { query: q, limit: 8 });
        setPayload(data);
        if (data?.best_match?.ticker) {
          setSelectedTicker(data.best_match.ticker);
        } else if (data?.results?.[0]?.ticker) {
          setSelectedTicker(data.results[0].ticker);
        }
        const next = new URLSearchParams(params);
        next.set("q", q);
        setParams(next, { replace: true });
      } catch (err) {
        toast.error(formatApiErrorFromAxios(err, "Vyhledávání akcií selhalo."));
        setPayload(null);
      } finally {
        setLoading(false);
      }
    },
    [params, setParams],
  );

  /**
   * Dotaz drží URL (`?q=`), protože hledání akcií teď obstarává horní pole aplikace —
   * stránka už vlastní hledací pole nemá. Dřív se hledalo jen při prvním otevření, takže
   * druhé hledání ze stejné stránky by se neprojevilo.
   */
  useEffect(() => {
    const q = (params.get("q") || "").trim();
    if (q.length < 2) return;
    if (q === lastRunQueryRef.current) return;
    runSearch(q);
  }, [params, runSearch]);

  const selectedRow = results.find((r) => r.ticker === selectedTicker) || results[0];
  const previewDef = selectedRow ? previewDefForRow(selectedRow) : null;

  const fetchStockPreview = useCallback(async (row) => {
    if (!row) return;
    const def = previewDefForRow(row);
    setPreviewLoading(true);
    setPreviewData(null);
    setPreviewError("");
    try {
      const data = await fetchCatalogLivePreview({ def, row });
      setPreviewData(data);
    } catch (err) {
      setPreviewError(formatApiErrorFromAxios(err, "Náhled tržních dat selhal."));
    } finally {
      setPreviewLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!selectedRow) {
      setPreviewData(null);
      setPreviewError("");
      return;
    }
    void fetchStockPreview(selectedRow);
  }, [selectedRow, fetchStockPreview]);

  const handleAddPreviewToDashboard = useCallback(async () => {
    if (!selectedRow || !previewDef || !previewData || previewError) return;
    setAddingToDash(true);
    try {
      await addCatalogPreviewToPersonalDashboard({
        api,
        nav,
        def: previewDef,
        previewData,
        row: {
          ...selectedRow,
          set_id: String(selectedRow.set_id || selectedRow.ticker || "").trim(),
        },
        feature: dashboardFeature,
      });
    } finally {
      setAddingToDash(false);
    }
  }, [selectedRow, previewDef, previewData, previewError, dashboardFeature, nav]);

  const chartPreview = useMemo(() => {
    if (!selectedRow || !previewDef) return null;
    const title = selectedRow.name || selectedRow.ticker;
    const source = { name: title, source_type: previewDef.sourceType };
    const emptyMessage =
      String(previewError || "").trim() ||
      "Nepodařilo se načíst data z Yahoo Finance. Zkuste to znovu nebo jiný ticker.";
    const preview =
      previewError && !previewData?.rows?.length
        ? { error: previewError || emptyMessage, source }
        : previewData
          ? { ...previewData, source }
          : { source, message: emptyMessage };

    return (
      <CatalogChartPreview
        widgetId={`stock-preview-${String(selectedRow.ticker || "row").slice(0, 40)}`}
        title={title}
        sourceType={previewDef.sourceType}
        catalogDef={previewDef}
        catalogRow={{
          ...selectedRow,
          set_id: String(selectedRow.set_id || selectedRow.ticker || "").trim(),
        }}
        preferAradView
        preview={preview}
        previewError={previewError}
        previewLoading={previewLoading}
        controlsInOptionsPanel
        catalogChartSize="detail-expanded"
        compareMode="cross-source"
        sourcePreviewProps={{
          catalogChartActions: buildCatalogChartActionsProps({
            feature: dashboardFeature,
            previewData,
            previewError,
            previewLoading,
            onAddToDashboard: handleAddPreviewToDashboard,
            addingToDashboard: addingToDash,
          }),
        }}
      />
    );
  }, [
    selectedRow,
    previewDef,
    previewData,
    previewError,
    previewLoading,
    dashboardFeature,
    handleAddPreviewToDashboard,
    addingToDash,
  ]);

  return (
    <AppShell>
      <div className="mx-auto w-full min-w-0 max-w-6xl overflow-x-hidden px-4 py-6 space-y-6 max-md:px-0">
        <div className="space-y-2">
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <TrendingUp className="h-4 w-4" />
            <span>Akcie · ETF · indexy</span>
          </div>
          <h1 className="text-2xl font-semibold tracking-tight">Hledání akcií</h1>
          <p className="text-sm text-muted-foreground max-w-2xl">
            Hledejte v poli nahoře — na této stránce vyhledává tržní instrumenty (Yahoo Finance,
            Alpha Vantage). Statistické katalogy (Eurostat, ARAD, ČSÚ…) najdete v{" "}
            <Link to="/search/catalog" className="text-primary underline-offset-2 hover:underline">
              katalogovém vyhledávání
            </Link>
            .
          </p>
        </div>

        {loading ? <LoadingBlock label="Yahoo symbol search a tržní data…" /> : null}

        {resolver?.ticker_candidates?.length ? (
          <div className="rounded-lg border border-border bg-card p-4 max-md:p-3 space-y-3 max-md:space-y-2">
            <div className="text-sm font-medium max-md:text-[13px]">Nalezené tickery</div>
            {resolver.company_name ? (
              <p className="text-sm max-md:text-xs text-muted-foreground">{resolver.company_name}</p>
            ) : null}
            <div className="flex min-w-0 flex-wrap gap-1.5 max-md:gap-1">
              {resolver.ticker_candidates.map((c) => (
                <button
                  key={c.ticker}
                  type="button"
                  onClick={() => setSelectedTicker(c.ticker)}
                  title={c.exchange ? `${c.ticker} · ${c.exchange}` : c.ticker}
                  className={`max-w-full truncate rounded-md border px-2.5 py-1 max-md:px-2 max-md:py-0.5 text-sm max-md:text-xs ${
                    selectedTicker === c.ticker
                      ? "border-primary bg-primary/10"
                      : "border-border hover:bg-muted/60"
                  }`}
                >
                  <span className="font-mono font-medium">{c.ticker}</span>
                  {c.exchange ? (
                    <span className="text-muted-foreground max-md:hidden"> · {c.exchange}</span>
                  ) : null}
                </button>
              ))}
            </div>
          </div>
        ) : null}

        {selectedRow ? (
          <div className="min-w-0 max-w-full overflow-hidden space-y-2 rounded-lg border border-border bg-card p-4">
            <h2 className="text-lg font-medium">Náhled — {selectedRow.ticker}</h2>
            {chartPreview}
          </div>
        ) : null}

        <p className="text-xs text-muted-foreground flex items-center gap-1">
          <ExternalLink className="h-3 w-3" />
          Data z Yahoo Finance (yfinance). Pro uložení jako zdroj použijte sekci Zdroje.
        </p>
      </div>
    </AppShell>
  );
}
