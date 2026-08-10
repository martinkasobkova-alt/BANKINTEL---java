import {
  Check,
  ChevronRight,
  Download,
  ExternalLink,
  Eye,
  FileBarChart2,
  Folder,
  Layers,
  Plus,
  RefreshCw,
  Search,
} from "lucide-react";
import CatalogAnalyticsForecastButtons from "./CatalogAnalyticsForecastButtons";

export default function CatalogSetActionsPanel({
  variant = "full",
  def,
  row,
  semantic,
  fredCatId,
  wbLetter,
  bisDataflowBrowse,
  bisFlowHint,
  oecd3DataflowBrowse,
  oecd3Agency,
  oecd3Dataflow,
  catalogRowTitle,
  previewable,
  isOpen,
  isTree,
  canAddSources,
  added,
  busy,
  detailActionIsBrowse,
  detailActionIsQuickPreview,
  detailButtonLabel,
  detailDisabled = false,
  eurostatAiOpenCatalog,
  dlSupported,
  onTogglePreview,
  onActivate,
  onAddSource,
  onOpenSavedVisual,
  onSavedDashboardInfo,
  onSavedDownload,
  onOpenFredCategory,
  onOpenWorldBankLetter,
  onOpenBisDimensions,
  onOpenOecdDimensions,
  onOpenEurostatCatalog,
}) {
  const canForecast = previewable && Boolean(def?.sourceType) && Boolean(row?.set_id);
  const canAnalytics = canForecast;

  if (variant === "savedVisual") {
    return (
      <div
        className="relative flex shrink-0 flex-wrap items-center justify-start gap-1"
        onClick={(e) => e.stopPropagation()}
      >
        <button
          type="button"
          onClick={onOpenSavedVisual}
          className="inline-flex items-center gap-1 h-7 px-2 text-[11px] rounded-md bg-sky-600 hover:bg-sky-700 text-white"
        >
          <ExternalLink className="h-3 w-3" />
          Otevřít
        </button>
        <button
          type="button"
          className="inline-flex items-center gap-1 h-7 px-2 text-[11px] rounded-md border border-border/70 bg-card text-foreground/90 hover:bg-muted/50"
          onClick={onSavedDashboardInfo}
        >
          <Plus className="h-3 w-3" />
          Dashboard
        </button>
        <button
          type="button"
          disabled={!dlSupported}
          title={
            dlSupported
              ? "Export dostupný u katalogových grafů"
              : "Export typu Stáhnout je u tohoto widgetu zatím jen u vybraných katalogových grafů"
          }
          className="inline-flex items-center gap-1 h-7 px-2 text-[11px] rounded-md border border-border/70 bg-card text-foreground/90 hover:bg-muted/50 disabled:opacity-50 disabled:cursor-not-allowed"
          onClick={onSavedDownload}
        >
          <Download className="h-3 w-3" aria-hidden />
          Stáhnout
        </button>
      </div>
    );
  }

  if (variant === "compact") {
    return (
      <div
        className="relative flex shrink-0 flex-wrap items-center justify-start gap-1"
        onClick={(e) => e.stopPropagation()}
      >
        {previewable ? (
          <button
            type="button"
            onClick={onTogglePreview}
            className={`inline-flex items-center gap-1 h-7 px-2 text-[11px] rounded-md border transition-colors ${
              isOpen
                ? "border-[hsl(var(--primary)/0.5)] bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]"
                : "border-border/70 bg-card text-foreground/90 hover:bg-muted/50"
            }`}
          >
            <Eye className="h-3 w-3" />
            {isOpen ? "Skrýt" : "Data"}
          </button>
        ) : detailActionIsQuickPreview ? (
          <button
            type="button"
            onClick={onActivate}
            className="btn-mint inline-flex items-center gap-1 h-7 px-2 text-[11px]"
          >
            <Eye className="h-3 w-3" />
            {detailButtonLabel}
          </button>
        ) : null}
        {previewable && canAddSources && added ? (
          <span className="inline-flex items-center gap-1 px-2 h-7 text-[10px] uppercase tracking-wider rounded-md chip-mint border border-[hsl(215_45%_82%)]">
            <Check className="h-3 w-3" /> OK
          </span>
        ) : previewable && canAddSources ? (
          <button
            type="button"
            onClick={onAddSource}
            disabled={busy}
            className="btn-mint inline-flex items-center gap-1 px-2 h-7 text-[11px] disabled:opacity-50"
          >
            {busy ? <RefreshCw className="h-3 w-3 animate-spin" /> : <Plus className="h-3 w-3" />}
            Přidat
          </button>
        ) : null}
        {!detailDisabled ? (
          <button
            type="button"
            onClick={onActivate}
            className={`inline-flex items-center gap-1 h-7 px-2 text-[11px] rounded-md border border-border/70 bg-card text-foreground/90 hover:bg-muted/50 ${
              detailActionIsBrowse ? "btn-mint" : ""
            } ${detailActionIsQuickPreview ? "hidden" : ""}`}
          >
            {detailActionIsBrowse ? (
              <Layers className="h-3 w-3" />
            ) : (
              <FileBarChart2 className="h-3 w-3" />
            )}
            {detailButtonLabel}
          </button>
        ) : null}
        {canAnalytics ? (
          <CatalogAnalyticsForecastButtons
            sourceType={def?.sourceType}
            setId={row?.set_id}
            name={catalogRowTitle || row?.title || row?.name || row?.set_id}
            geo={row?.geo || row?.geo_label || row?.country || ""}
            queryParams={row?.query_params && typeof row.query_params === "object" ? row.query_params : null}
            dimensionFilters={row?.geo || row?.geo_label ? { geo: row.geo || row.geo_label } : null}
            selectedIndicator={String(row?.indicator_id || row?.selected_indicator || "").trim()}
            selectedIndicators={
              row?.indicator_id || row?.selected_indicator
                ? [String(row.indicator_id || row.selected_indicator).trim()]
                : []
            }
            query={catalogRowTitle || row?.title || row?.name}
          />
        ) : null}
      </div>
    );
  }

  return (
    <>
    <div className="relative flex flex-wrap items-center gap-1.5 shrink-0 justify-end max-w-[min(100%,24rem)]">
      {def.sourceType === "fred" && semantic === "category" && fredCatId ? (
        <>
          <button
            type="button"
            onClick={onOpenFredCategory}
            className="inline-flex items-center gap-1 h-7 px-2.5 text-[11px] rounded-lg border border-border/80 bg-card text-foreground hover:bg-muted/50 whitespace-nowrap"
          >
            <ChevronRight className="h-3 w-3" /> Otevřít podkategorie
          </button>
          <a
            href={`https://fred.stlouisfed.org/categories/${encodeURIComponent(fredCatId)}`}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 h-7 px-2.5 text-[11px] rounded-lg border border-border/80 bg-card text-foreground hover:bg-muted/50"
            onClick={(e) => e.stopPropagation()}
          >
            <ExternalLink className="h-3 w-3" /> Kategorie (web)
          </a>
          <a
            href={`https://fred.stlouisfed.org/searchresults?st=${encodeURIComponent(String(row.name || "").slice(0, 120))}`}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 h-7 px-2.5 text-[11px] rounded-lg border border-border/80 bg-card text-foreground hover:bg-muted/50"
            onClick={(e) => e.stopPropagation()}
          >
            <Search className="h-3 w-3" /> Vyhledat řady
          </a>
        </>
      ) : null}
      {def.sourceType === "worldbank" && semantic === "letter_bucket" && wbLetter ? (
        <button
          type="button"
          onClick={onOpenWorldBankLetter}
          className="inline-flex items-center gap-1 h-7 px-2.5 text-[11px] rounded-lg border border-border/80 bg-card text-foreground hover:bg-muted/50 whitespace-nowrap"
        >
          <Folder className="h-3 w-3" /> Načíst indikátory
        </button>
      ) : null}
      {bisDataflowBrowse && bisFlowHint ? (
        <button
          type="button"
          onClick={onOpenBisDimensions}
          className="btn-mint flex items-center gap-1 h-7 px-2.5 text-[11px] whitespace-nowrap"
        >
          <Layers className="h-3 w-3" /> Vybrat dimenze / řady
        </button>
      ) : null}
      {oecd3DataflowBrowse && oecd3Agency && oecd3Dataflow ? (
        <button
          type="button"
          onClick={onOpenOecdDimensions}
          className="btn-mint flex items-center gap-1 h-7 px-2.5 text-[11px] whitespace-nowrap"
          title={
            row.oecd_has_verified_series
              ? "Dataset ma ověřené řady přímo ve stromu OECD 3 — dimenze jen pro vlastní výběr."
              : "Otevře sestavení dotazu přes dimenze OECD (beta, nemusí vrátit data)."
          }
        >
          <Layers className="h-3 w-3" />{" "}
          {row.oecd_has_verified_series ? "Vlastní dimenze" : "Načíst dimenze z OECD"}
        </button>
      ) : null}
      {previewable ? (
        <button
          type="button"
          onClick={onTogglePreview}
          className={`flex items-center gap-1 h-7 px-2.5 text-[11px] rounded-lg border transition-colors ${
            isOpen
              ? "border-[hsl(var(--primary)/0.5)] bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]"
              : "border-border/70 bg-card text-foreground/90 hover:bg-muted/50"
          }`}
          title={isOpen ? "Zavřít náhled" : "Otevřít náhled dat"}
        >
          <Eye className="h-3 w-3" /> {isOpen ? (isTree ? "Zavřít" : "Skrýt data") : isTree ? "Náhled" : "Zobrazit data"}
        </button>
      ) : null}
      {!isTree ? (
        <details
          className="group/catalog-detail shrink-0"
          onClick={(e) => e.stopPropagation()}
        >
          <summary className="list-none [&::-webkit-details-marker]:hidden flex items-center gap-1 h-7 px-2.5 text-[11px] rounded-lg border border-border/70 bg-card text-foreground/90 hover:bg-muted/50 cursor-pointer">
            <FileBarChart2 className="h-3 w-3" /> Detail řady
          </summary>
          <div className="absolute right-4 mt-1 z-10 w-[min(100%,18rem)] rounded-lg border border-border bg-card p-2.5 text-[10px] text-muted-foreground shadow-lg space-y-1">
            <p className="font-medium text-foreground text-[11px]">{catalogRowTitle}</p>
            <p className="font-mono break-all">{String(row.set_id || "")}</p>
            {row.parentPath ? <p className="leading-snug">{row.parentPath}</p> : null}
          </div>
        </details>
      ) : null}
      {canAnalytics ? (
        <CatalogAnalyticsForecastButtons
          sourceType={def?.sourceType}
          setId={row?.set_id}
          name={catalogRowTitle || row?.title || row?.name || row?.set_id}
          geo={row?.geo || row?.geo_label || row?.country || ""}
          queryParams={row?.query_params && typeof row.query_params === "object" ? row.query_params : null}
          dimensionFilters={row?.geo || row?.geo_label ? { geo: row.geo || row.geo_label } : null}
          selectedIndicator={String(row?.indicator_id || row?.selected_indicator || "").trim()}
          selectedIndicators={
            row?.indicator_id || row?.selected_indicator
              ? [String(row.indicator_id || row.selected_indicator).trim()]
              : []
          }
          query={catalogRowTitle || row?.title || row?.name}
        />
      ) : null}
      {previewable && canAddSources && added ? (
        <span className="flex items-center gap-1 px-2.5 h-7 text-[11px] uppercase tracking-wider rounded-lg chip-mint border border-[hsl(215_45%_82%)]">
          <Check className="h-3 w-3" /> přidáno
        </span>
      ) : eurostatAiOpenCatalog && canAddSources ? (
        <button
          type="button"
          onClick={onOpenEurostatCatalog}
          className="inline-flex items-center gap-1 h-7 px-2.5 text-[11px] rounded-lg border border-border/80 bg-card text-foreground hover:bg-muted/50 whitespace-nowrap"
          data-testid="eurostat-ai-open-catalog"
        >
          <Folder className="h-3 w-3" /> Otevřít v katalogu
        </button>
      ) : previewable && canAddSources ? (
        <button
          type="button"
          onClick={onAddSource}
          disabled={busy}
          className="btn-mint flex items-center gap-1.5 px-3 h-7 text-xs disabled:opacity-50"
        >
          {busy ? <RefreshCw className="h-3 w-3 animate-spin" /> : <Plus className="h-3 w-3" />}
          {isTree ? "Přidat" : "Přidat do analýzy"}
        </button>
      ) : null}
    </div>
    </>
  );
}
