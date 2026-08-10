import React, { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  Plus,
  Play,
  Trash2,
  Activity,
  RefreshCw,
  CheckCircle2,
  Calculator,
  FolderTree,
  Globe2,
  Landmark,
  BarChart3,
  Earth,
  ChevronDown,
  Building2,
  BadgePercent,
  BookMarked,
  Search,
  Info,
} from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import { useAuth } from "@/contexts/AuthContext";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { StatusBadge } from "@/components/widgets/WidgetRenderer";
import { fmtDateTime } from "@/lib/format";
import { buildSyncDetailTooltip, effectiveSyncBadgeStatus, humanSyncReason } from "@/lib/syncStatus";
import SourcePreview from "@/components/sources/SourcePreview";
import { LoadingBlock } from "@/components/ui/loading";
import {
  normalizePreviewPayload,
  previewShapeDebug,
} from "@/lib/previewNormalizer";
import { buildSourcePreviewParams } from "@/lib/previewRequestParams";
import CatalogSourcesMenuRow from "@/components/catalog/CatalogSourcesMenuRow";

const TYPE_LABEL = {
  arad: "ČNB - ARAD",
  eurostat: "Eurostat",
  csu: "ČSÚ",
  ecb: "ECB",
  fred: "FRED",
  alphavantage: "Alpha Vantage",
  worldbank: "World Bank",
  world_bank_data360: "World Bank",
  bis: "BIS",
  imf: "IMF",
  oecd: "OECD",
  tradingeconomics: "Trading Economics",
  custom: "Vlastní API",
  file_upload: "Soubor",
};

/** Pořadí a id pro filtr — musí odpovídat `source_type` v API. */
const SOURCE_TYPE_FILTERS = [
  { id: "arad", label: "ČNB - ARAD" },
  { id: "eurostat", label: "Eurostat" },
  { id: "csu", label: "ČSÚ" },
  { id: "ecb", label: "ECB" },
  { id: "fred", label: "FRED" },
  { id: "alphavantage", label: "Alpha Vantage" },
  { id: "world_bank_data360", label: "World Bank" },
  { id: "bis", label: "BIS" },
  { id: "imf", label: "IMF" },
  { id: "oecd", label: "OECD" },
  { id: "tradingeconomics", label: "Trading Economics" },
  { id: "custom", label: "Vlastní API" },
  { id: "file_upload", label: "Soubor" },
];

/** Legacy WDI konektor — stejný štítek jako Data360 ve filtru Zdroje. */
const WORLD_BANK_SOURCE_TYPES = new Set(["worldbank", "world_bank_data360"]);

const AUTH_LABEL_KEYS = {
  none: "pages.sources.authNone",
  bearer: "pages.sources.authBearer",
  api_key_header: "pages.sources.authApiKey",
  basic: "pages.sources.authBasic",
  custom_header: "pages.sources.authCustom",
};

function filterTypeLabel(filter, t) {
  if (filter.id === "custom") return t("pages.sources.typeCustom");
  if (filter.id === "file_upload") return t("pages.sources.typeFile");
  return filter.label;
}

function sourceTypeLabel(sourceType, t) {
  if (sourceType === "custom") return t("pages.sources.typeCustom");
  if (sourceType === "file_upload") return t("pages.sources.typeFile");
  return TYPE_LABEL[sourceType] || sourceType;
}

function logSourcesPreviewDebug(event, details = {}) {
  if (typeof console === "undefined" || typeof console.debug !== "function") return;
  try {
    console.debug(`[sources-preview] ${event}`, details);
  } catch {
    // no-op
  }
}

export default function SourcesPage() {
  const { t } = useTranslation();
  const { isAdmin } = useAuth();
  const [sources, setSources] = useState([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState({});
  const [fredApiConfigured, setFredApiConfigured] = useState(null);
  const [alphavantageApiConfigured, setAlphavantageApiConfigured] = useState(null);
  const [testResult, setTestResult] = useState(null);
  const [syncDetailSource, setSyncDetailSource] = useState(null);
  const [preview, setPreview] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(null);
  const previewJobRef = useRef(0);
  const sourcesTableMainScrollRef = useRef(null);
  const sourcesTableTopXRef = useRef(null);
  const [sourcesHScroll, setSourcesHScroll] = useState({ inner: 0, client: 0 });
  const navigate = useNavigate();
  const [allowedTypes, setAllowedTypes] = useState(
    () => new Set(SOURCE_TYPE_FILTERS.map((f) => f.id))
  );

  const filteredSources = useMemo(
    () =>
      sources.filter((s) => {
        const st = s.source_type;
        if (WORLD_BANK_SOURCE_TYPES.has(st)) {
          return allowedTypes.has("world_bank_data360");
        }
        return allowedTypes.has(st);
      }),
    [sources, allowedTypes]
  );

  const showSourcesTopHScroll = sourcesHScroll.inner > sourcesHScroll.client + 1;

  useLayoutEffect(() => {
    const outer = sourcesTableMainScrollRef.current;
    if (!outer) return undefined;
    const table = outer.querySelector('[data-testid="sources-table"]');
    const measure = () => {
      setSourcesHScroll({
        inner: table?.scrollWidth ?? outer.scrollWidth,
        client: outer.clientWidth,
      });
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(outer);
    if (table) ro.observe(table);
    return () => ro.disconnect();
  }, [filteredSources.length, loading, sources.length, preview?.source?.id]);

  const onSourcesTableMainScroll = (e) => {
    const m = e.currentTarget;
    const t = sourcesTableTopXRef.current;
    if (t && t.scrollLeft !== m.scrollLeft) t.scrollLeft = m.scrollLeft;
  };

  const onSourcesTableTopXScroll = (e) => {
    const topEl = e.currentTarget;
    const m = sourcesTableMainScrollRef.current;
    if (m && m.scrollLeft !== topEl.scrollLeft) m.scrollLeft = topEl.scrollLeft;
  };

  const toggleType = (id) => {
    setAllowedTypes((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const toggleAllTypes = () =>
    setAllowedTypes((prev) =>
      prev.size === SOURCE_TYPE_FILTERS.length
        ? new Set()
        : new Set(SOURCE_TYPE_FILTERS.map((f) => f.id))
    );
  const allTypesActive = allowedTypes.size === SOURCE_TYPE_FILTERS.length;

  const load = async () => {
    try {
      const prev = sources;
      const prevStatus = Object.fromEntries(
        (prev || []).map((s) => [s.id, s.last_sync_status])
      );
      const res = await api.get("/sources");
      const data = res.data;
      const h =
        res.headers?.["x-fred-api-configured"] ??
        res.headers?.["X-FRED-API-CONFIGURED"];
      if (h != null) setFredApiConfigured(String(h).toLowerCase() === "true");
      const avh =
        res.headers?.["x-alphavantage-api-configured"] ??
        res.headers?.["X-ALPHAVANTAGE-API-CONFIGURED"];
      if (avh != null) setAlphavantageApiConfigured(String(avh).toLowerCase() === "true");
      for (const s of data || []) {
        const prevSt = prevStatus[s.id];
        if (
          prevSt === "running" &&
          s.last_sync_status &&
          s.last_sync_status !== "running"
        ) {
          window.dispatchEvent(new CustomEvent("banko:datasets-changed"));
          break;
        }
      }
      setSources(data);
      return data;
    } catch  {
      return null;
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    load();
  }, []);

  // Pokud kterýkoli zdroj má `last_sync_status === "running"`, periodicky
  // obnovujeme seznam, abychom zachytili dokončení synchronizace na pozadí.
  // Polling se sám zastaví, jakmile už nikde nic neběží.
  useEffect(() => {
    const anyRunning = sources.some((s) => effectiveSyncBadgeStatus(s) === "running");
    if (!anyRunning) return undefined;
    const id = setInterval(() => {
      load();
    }, 4000);
    return () => clearInterval(id);
  }, [sources]);

  const runSync = async (id, { silent = false } = {}) => {
    const source = sources.find((s) => s.id === id);
    const cfg = source?.query_params;
    console.log("Running sync for source", source);
    console.log("source.type", source?.source_type);
    console.log("source.file_path", source?.endpoint);
    console.log("source.config", cfg);
    if (!silent) setBusy((b) => ({ ...b, [id]: "sync" }));
    try {
      await api.post(`/sources/${id}/sync`);
    } catch (e) {
      console.warn("sync POST failed", e?.response?.data ?? e?.message);
      toast.error(formatApiErrorFromAxios(e) || "Synchronizaci se nepodařilo spustit.");
    }
    // Hned obnovíme seznam, abychom zachytili "running" stav, který backend
    // nastavil ihned po přijetí požadavku. Polling effect výše pak dotahuje
    // zbytek (success / error).
    const list = await load();
    if (!silent) setBusy((b) => ({ ...b, [id]: null }));
    return list;
  };

  const testConn = async (id) => {
    setBusy((b) => ({ ...b, [id]: "test" }));
    setTestResult(null);
    try {
      const { data } = await api.post(`/sources/${id}/test`);
      setTestResult({ id, ...data });
    } catch (e) {
      setTestResult({ id, ok: false, error: e.message });
    }
    setBusy((b) => ({ ...b, [id]: null }));
  };

  const previewLimitForSource = (sourceType, indicatorId) => {
    if (indicatorId) return 500;
    const st = String(sourceType || "").trim().toLowerCase();
    // Eurostat cross-sections (many countries in one period) need a larger
    // sample window, otherwise preview often contains only one time point.
    if (st === "eurostat") return 500;
    return 80;
  };

  const fetchPreview = async (
    sourceId,
    indicatorId,
    sourceTypeHint,
    indicatorIds = null,
    groupFieldHint = "",
    geoValues = null,
    dimensionFilters = null,
  ) => {
    setPreviewLoading(sourceId);
    logSourcesPreviewDebug("loading_start", {
      sourceId,
      sourceTypeHint,
      indicatorId: indicatorId ?? null,
      indicatorIds,
      groupFieldHint,
    });
    try {
      const sourceType =
        sourceTypeHint ||
        preview?.source?.source_type ||
        sources.find((s) => s.id === sourceId)?.source_type ||
        "";
      const params = buildSourcePreviewParams({
        sourceType,
        limit: previewLimitForSource(sourceType, indicatorId),
        indicatorId,
        indicatorIds,
        groupField: groupFieldHint || preview?.group_field || "",
        geoValues,
        dimensionFilters,
      });
      logSourcesPreviewDebug("endpoint_call", {
        endpoint: `/api/sources/${sourceId}/preview`,
        method: "GET",
        params,
      });
      const { data } = await api.get(`/sources/${sourceId}/preview`, { params });
      logSourcesPreviewDebug("endpoint_response", {
        endpoint: `/api/sources/${sourceId}/preview`,
        status: 200,
        shape: previewShapeDebug(data),
      });
      const normalized = normalizePreviewPayload(data, sourceType);
      const shape = previewShapeDebug(data);
      const hasKnownShape = shape.hasRows || shape.hasData || shape.hasObservations || shape.hasFields || shape.hasColumns;
      if (!hasKnownShape && !String(normalized?.message || "").trim()) {
        normalized.error = `Neocekavany tvar odpovedi nahledu. Klice: ${shape.keys.join(", ") || "(zadne)"}`;
      }
      setPreview(normalized);
      logSourcesPreviewDebug("preview_state_assign", {
        sourceId,
        rows: normalized?.rows?.length || 0,
        columns: normalized?.columns?.length || 0,
      });
      return normalized;
    } catch (e) {
      const fallback = { source: { id: sourceId }, rows: [], fields: [], error: e?.response?.data?.detail || e.message };
      setPreview(fallback);
      logSourcesPreviewDebug("error_state_set", {
        sourceId,
        error: fallback.error,
        endpoint: `/api/sources/${sourceId}/preview`,
      });
      return fallback;
    } finally {
      logSourcesPreviewDebug("loading_end", { sourceId });
      setPreviewLoading(null);
    }
  };

  const openPreview = async (source) => {
    previewJobRef.current += 1;
    const jobId = previewJobRef.current;
    if (preview?.source?.id === source.id) {
      setPreview(null);
      return;
    }
    setPreview({ source, rows: [], fields: [], loading: true });
    const first = await fetchPreview(source.id, undefined, source.source_type);
    if (jobId !== previewJobRef.current) return;
    const hasRows = Array.isArray(first?.rows) && first.rows.length > 0;
    if (hasRows) return;
    if (String(source?.source_type || "").toLowerCase() === "eurostat") {
      // Eurostat empty preview is often a filter-shape mismatch, not missing data.
      // Avoid long auto-sync loop that makes the UI look stuck.
      return;
    }

    // Auto-sync při otevření náhledu: pokud zatím nejsou data (nebo endpoint
    // vrátí Not Found), spustíme synchronizaci a náhled periodicky obnovujeme
    // až do dokončení běhu.
    let latestList = sources;
    let current = latestList.find((s) => s.id === source.id);
    if (current?.last_sync_status !== "running") {
      latestList = (await runSync(source.id, { silent: true })) || latestList;
      if (jobId !== previewJobRef.current) return;
      current = latestList.find((s) => s.id === source.id) || current;
    }

    for (let i = 0; i < 30; i += 1) {
      if (jobId !== previewJobRef.current) return;
      await new Promise((resolve) => setTimeout(resolve, 3500));
      if (jobId !== previewJobRef.current) return;

      latestList = (await load()) || latestList;
      if (jobId !== previewJobRef.current) return;
      current = latestList.find((s) => s.id === source.id) || current;

      const data = await fetchPreview(source.id, undefined, source.source_type);
      if (jobId !== previewJobRef.current) return;
      const nowHasRows = Array.isArray(data?.rows) && data.rows.length > 0;
      if (nowHasRows) break;

      // Sync skončila a data stále nejsou — končíme polling.
      if (current?.last_sync_status && current.last_sync_status !== "running") break;
    }
  };

  const changePreviewIndicator = (indicatorId) => {
    if (!preview?.source?.id) return;
    // Stop any ongoing auto-sync polling loop started by openPreview().
    // Otherwise background retries (without indicator filter) can overwrite
    // freshly loaded country-specific preview and look like a stuck spinner.
    previewJobRef.current += 1;
    fetchPreview(
      preview.source.id,
      indicatorId,
      preview?.source?.source_type,
      null,
      preview?.group_field,
      preview?.metadata?.filters_applied?.geo || preview?.requested_filters?.geo || [],
    );
  };
  const changePreviewIndicators = (indicatorIds) => {
    if (!preview?.source?.id) return;
    // Cancel old openPreview polling run before manual multi-select refresh.
    previewJobRef.current += 1;
    const many = Array.isArray(indicatorIds)
      ? [...new Set(indicatorIds.map((v) => String(v || "").trim()).filter(Boolean))]
      : [];
    if (many.length <= 1) {
      fetchPreview(
        preview.source.id,
        many[0] || "",
        preview?.source?.source_type,
        null,
        preview?.group_field,
      );
      return;
    }
    fetchPreview(
      preview.source.id,
      "",
      preview?.source?.source_type,
      many,
      preview?.group_field,
      preview?.metadata?.filters_applied?.geo || preview?.requested_filters?.geo || [],
    );
  };
  const changePreviewGeos = (geoValues) => {
    if (!preview?.source?.id) return;
    previewJobRef.current += 1;
    fetchPreview(
      preview.source.id,
      preview?.selected_indicator || "",
      preview?.source?.source_type,
      preview?.selected_indicators || null,
      preview?.group_field,
      geoValues,
    );
  };
  const changePreviewDimensionFilters = (dimensionFilters) => {
    if (!preview?.source?.id) return;
    previewJobRef.current += 1;
    fetchPreview(
      preview.source.id,
      preview?.selected_indicator || "",
      preview?.source?.source_type,
      preview?.selected_indicators || null,
      preview?.group_field,
      null,
      dimensionFilters,
    );
  };

  const del = async (id) => {
    if (!window.confirm("Opravdu smazat tento zdroj?")) return;
    try {
      await api.delete(`/sources/${id}`);
      load();
    } catch (e) {
      if (e?.response?.status === 401) return;
      toast.error(formatApiErrorFromAxios(e));
    }
  };

  return (
    <AppShell
      title={t("pages.sources.title")}
      subtitle={t("pages.sources.subtitle")}
      actions={
        <div className="flex flex-col items-stretch sm:items-end gap-2 max-w-full">
          <div className="flex flex-wrap items-center gap-2 justify-end">
            <DropdownMenu>
              <DropdownMenuTrigger
                data-testid="sources-catalogs-menu"
                className="flex items-center gap-2 px-3 h-9 text-sm rounded-xl border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm transition hover:bg-card hover:border-[hsl(var(--primary)/0.35)]"
              >
                <FolderTree className="h-4 w-4 shrink-0" strokeWidth={1.8} />
                <span className="whitespace-nowrap">{t("pages.sources.catalogsMenu")}</span>
                <ChevronDown className="h-4 w-4 shrink-0 opacity-60" />
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-[min(100vw-2rem,18rem)]">
                <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
                  {t("pages.sources.catalogsHint")}
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem asChild data-testid="sources-global-search-btn">
                  <Link to="/search/catalog" className="cursor-pointer font-medium">
                    <Search className="h-4 w-4" strokeWidth={1.8} />
                    {t("pages.sources.searchCatalogs")}
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem className="p-0 focus:bg-transparent" onSelect={(e) => e.preventDefault()}>
                  <CatalogSourcesMenuRow
                    to="/sources/catalog"
                    catalogId="arad"
                    label="Katalog ČNB - ARAD"
                    icon={FolderTree}
                    testId="sources-catalog-btn"
                  />
                </DropdownMenuItem>
                <DropdownMenuItem className="p-0 focus:bg-transparent" onSelect={(e) => e.preventDefault()}>
                  <CatalogSourcesMenuRow
                    to="/sources/eurostat"
                    catalogId="eurostat"
                    label="Katalog Eurostat"
                    icon={Globe2}
                    testId="sources-eurostat-btn"
                  />
                </DropdownMenuItem>
                <DropdownMenuItem className="p-0 focus:bg-transparent" onSelect={(e) => e.preventDefault()}>
                  <CatalogSourcesMenuRow
                    to="/sources/csu"
                    catalogId="csu"
                    label="Katalog ČSÚ (DataStat)"
                    icon={Landmark}
                    testId="sources-csu-btn"
                  />
                </DropdownMenuItem>
                <DropdownMenuItem className="p-0 focus:bg-transparent" onSelect={(e) => e.preventDefault()}>
                  <CatalogSourcesMenuRow
                    to="/sources/fred"
                    catalogId="fred"
                    label="Katalog FRED (St. Louis Fed)"
                    icon={BarChart3}
                    testId="sources-fred-btn"
                  />
                </DropdownMenuItem>
                <DropdownMenuItem className="p-0 focus:bg-transparent" onSelect={(e) => e.preventDefault()}>
                  <CatalogSourcesMenuRow
                    to="/ecb2/browse-tree"
                    catalogId="ecb2"
                    label="Katalog ECB"
                    icon={Landmark}
                    testId="sources-ecb2-btn"
                  />
                </DropdownMenuItem>
                <DropdownMenuItem className="p-0 focus:bg-transparent" onSelect={(e) => e.preventDefault()}>
                  <CatalogSourcesMenuRow
                    to="/sources/data360"
                    catalogId="data360"
                    label="Katalog World Bank"
                    icon={Earth}
                    testId="sources-data360-btn"
                  />
                </DropdownMenuItem>
                <DropdownMenuItem className="p-0 focus:bg-transparent" onSelect={(e) => e.preventDefault()}>
                  <CatalogSourcesMenuRow
                    to="/sources/bis"
                    catalogId="bis"
                    label="Katalog BIS"
                    icon={Building2}
                    testId="sources-bis-btn"
                  />
                </DropdownMenuItem>
                <DropdownMenuItem className="p-0 focus:bg-transparent" onSelect={(e) => e.preventDefault()}>
                  <CatalogSourcesMenuRow
                    to="/search/catalog"
                    catalogId="oecd4"
                    label="Katalog OECD"
                    icon={BookMarked}
                    testId="sources-oecd4-btn"
                  />
                </DropdownMenuItem>
                <DropdownMenuItem className="p-0 focus:bg-transparent" onSelect={(e) => e.preventDefault()}>
                  <CatalogSourcesMenuRow
                    to="/sources/imf"
                    catalogId="imf"
                    label="Katalog IMF"
                    icon={BadgePercent}
                    testId="sources-imf-btn"
                  />
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            <Link
              data-testid="sources-computed-btn"
              to="/computed"
              className="flex items-center gap-2 px-3 h-9 text-sm rounded-xl border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm transition hover:bg-card hover:border-[hsl(var(--primary)/0.35)]"
            >
              <Calculator className="h-4 w-4" strokeWidth={1.8} />
              {t("pages.sources.customCalculations")}
            </Link>
            <Link
              data-testid="sources-add-btn"
              to="/sources/new"
              className="btn-mint flex items-center gap-2 px-4 h-9 text-sm"
            >
              <Plus className="h-4 w-4" strokeWidth={1.8} />
              {t("pages.sources.customSource")}
            </Link>
          </div>
        </div>
      }
    >
      {loading ? (
        <LoadingBlock label={t("pages.sources.loading")} minHeightClass="min-h-[200px]" showSkeletonLines skeletonRows={6} />
      ) : sources.length === 0 ? (
        <div className="soft-card rounded-2xl border-dashed border-border/80 bg-muted/30 p-12 text-center text-sm text-muted-foreground font-mono">
          {t("pages.sources.empty")}
        </div>
      ) : (
        <div className="space-y-4 copper-text-fix-scope">
          {isAdmin && (
            <div className="text-sm text-foreground/90">
              <Link
                to="/sync-logs"
                className="text-[hsl(var(--primary-deep))] font-medium underline"
                data-testid="sources-link-sync-logs"
              >
                {t("pages.sources.syncLogLink")}
              </Link>
              <span className="text-muted-foreground">{t("pages.sources.syncLogHint")}</span>
            </div>
          )}
          {fredApiConfigured === false ? (
            <div
              className="text-xs sm:text-sm rounded-xl border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 text-amber-950 canvas-dark:text-amber-50 px-3 py-2.5"
              data-testid="sources-fred-api-key-hint"
            >
              Backend hlásí, že nemá nastavený <span className="font-mono">FRED_API_KEY</span> —
              katalog i synchronizace FRED řad přes oficiální API na serveru nebudou fungovat, dokud nepřidáte
              klíč v prostředí (např. Render → Environment, proměnná{" "}
              <span className="font-mono">FRED_API_KEY</span>).
            </div>
          ) : null}
          {alphavantageApiConfigured === false ? (
            <div
              className="text-xs sm:text-sm rounded-xl border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 text-amber-950 canvas-dark:text-amber-50 px-3 py-2.5"
              data-testid="sources-alphavantage-api-key-hint"
            >
              Backend hlásí, že nemá nastavený <span className="font-mono">ALPHAVANTAGE_API_KEY</span> —
              synchronizace zdrojů typu Alpha Vantage nebude fungovat, dokud klíč nepřidáte v prostředí serveru (
              viz{" "}
              <a
                href="https://www.alphavantage.co/support/#api-key"
                className="underline font-medium"
                target="_blank"
                rel="noreferrer"
              >
                alphavantage.co/support
              </a>
              ).
            </div>
          ) : null}
          <div
            className="soft-card rounded-2xl border-border/80 px-4 py-3.5 bg-gradient-to-b from-muted/40 to-card/[0.97]"
            data-testid="sources-type-filter"
          >
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-[11px] text-muted-foreground">
              <span className="font-semibold uppercase tracking-wider text-muted-foreground shrink-0">{t("pages.sources.sourceType")}</span>
              <button
                type="button"
                onClick={toggleAllTypes}
                title={allTypesActive ? t("pages.sources.deselectAllTypes") : t("pages.sources.selectAllTypes")}
                className={`shrink-0 px-3 py-1 rounded-full border text-[11px] font-medium shadow-sm transition-colors ${
                  allTypesActive
                    ? "border-[hsl(var(--primary))] bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]"
                    : "border-border/80 bg-card hover:bg-muted/50"
                }`}
              >
                {t("common.all")}
              </button>
              <span className="text-border">|</span>
              {SOURCE_TYPE_FILTERS.map((f) => {
                return (
                  <label
                    key={f.id}
                    className="inline-flex items-center gap-1.5 select-none shrink-0 cursor-pointer"
                  >
                    <input
                      type="checkbox"
                      className="rounded border-border h-3.5 w-3.5"
                      checked={allowedTypes.has(f.id)}
                      onChange={() => toggleType(f.id)}
                    />
                    <span>{filterTypeLabel(f, t)}</span>
                  </label>
                );
              })}
              <span className="text-border hidden sm:inline">|</span>
              <Link
                to="/computed"
                className="text-[11px] font-medium text-[hsl(var(--primary-deep))] hover:underline shrink-0"
              >
                {t("pages.sources.customCalculations")} →
              </Link>
              {allowedTypes.size === 0 && (
                <button
                  type="button"
                  onClick={toggleAllTypes}
                  className="text-[11px] text-muted-foreground hover:text-foreground underline ml-auto"
                >
                  Zobrazit všechny typy
                </button>
              )}
            </div>
            <p className="mt-2 text-[10px] text-muted-foreground">
              Zobrazeno{" "}
              <span className="font-mono text-foreground/90">{filteredSources.length}</span> z{" "}
              <span className="font-mono text-foreground/90">{sources.length}</span> zdrojů. Úplné názvy a URL po najetí
              myší.
            </p>
          </div>

          {filteredSources.length === 0 ? (
            <div className="soft-card rounded-2xl border-dashed border-border/80 bg-muted/25 p-8 text-center text-sm text-muted-foreground">
              Žádný zdroj neodpovídá filtru. Upravte typy výše nebo klikněte na <strong>Vše</strong>.
            </div>
          ) : (
            <div className="soft-card rounded-2xl overflow-hidden border-border/80 max-h-[calc(100vh-280px)] min-h-[260px] flex flex-col">
              {showSourcesTopHScroll && (
                <div
                  ref={sourcesTableTopXRef}
                  onScroll={onSourcesTableTopXScroll}
                  className="shrink-0 min-h-[15px] max-h-[18px] overflow-x-auto overflow-y-hidden border-b border-border/60 bg-[hsl(205_76%_96%)] [scrollbar-gutter:stable]"
                  aria-label="Vodorovný posun tabulky zdrojů (shodný s posuvníkem dole u tabulky)"
                  title="Posun vpravo/vlevo — totéž jako vodorovný posuv dole u seznamu"
                >
                  <div
                    className="pointer-events-none h-px"
                    style={{ width: sourcesHScroll.inner }}
                    aria-hidden
                  />
                </div>
              )}
              <div
                ref={sourcesTableMainScrollRef}
                onScroll={onSourcesTableMainScroll}
                className="overflow-x-auto overflow-y-auto min-h-0 flex-1 [scrollbar-gutter:stable] overscroll-x-contain"
              >
                <table
                  className="data-table min-w-[960px] [&_thead_th]:py-2.5 [&_thead_th]:px-3 [&_thead_th]:text-[9px] [&_tbody_td]:py-2 [&_tbody_td]:px-3 [&_tbody_td]:align-middle [&_thead]:sticky [&_thead]:top-0 [&_thead]:z-20 [&_thead_th]:!bg-[hsl(205_76%_96%)] [&_thead_th]:shadow-[inset_0_-1px_0_hsl(var(--border)/0.5)] [&_tbody_tr]:transition-colors [&_tbody_tr]:duration-150"
                  data-testid="sources-table"
                >
                <thead>
                  <tr>
                    <th className="max-w-[min(28vw,320px)] rounded-tl-2xl">Název</th>
                    <th className="whitespace-nowrap w-[1%]">Typ</th>
                    <th className="whitespace-nowrap hidden lg:table-cell">Autentizace</th>
                    <th className="max-w-[220px] hidden md:table-cell">Endpoint</th>
                    <th className="whitespace-nowrap w-[1%]">Interval</th>
                    <th className="whitespace-nowrap w-[1%]">Stav</th>
                    <th className="whitespace-nowrap w-[1%] hidden sm:table-cell">Poslední synch.</th>
                    <th className="text-right whitespace-nowrap w-[1%] min-w-[10.5rem] pl-2 rounded-tr-2xl">
                      Akce
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {filteredSources.map((s) => {
                    const fullUrl = `${s.base_url || ""}${s.endpoint || ""}`;
                    const effBadge = effectiveSyncBadgeStatus(s);
                    const syncReallyRunning =
                      effBadge === "running" || busy[s.id] === "sync";
                    const showSyncDiag =
                      s.last_sync_status &&
                      ["partial", "error", "timeout"].includes(s.last_sync_status);
                    return (
                      <Fragment key={s.id}>
                      <tr
                        data-testid={`source-row-${s.id}`}
                        className={`h-9 cursor-pointer ${
                          preview?.source?.id === s.id
                            ? "bg-[hsl(var(--primary-soft)/0.72)] shadow-[inset_3px_0_0_hsl(var(--primary))]"
                            : ""
                        }`}
                        onClick={() => openPreview(s)}
                        title="Kliknutím zobrazíte náhled dat"
                      >
                        <td className="font-medium max-w-[min(28vw,320px)]">
                          <Link
                            to={`/sources/${s.id}`}
                            className="block truncate hover:underline text-[13px] leading-tight"
                            title={s.name}
                            onClick={(e) => e.stopPropagation()}
                          >
                            {s.name}
                          </Link>
                        </td>
                        <td className="whitespace-nowrap">
                          <span className="inline-flex items-center text-[10px] px-2.5 py-1 rounded-full border border-border/90 bg-gradient-to-b from-white to-slate-100/90 text-foreground/90 uppercase tracking-wide font-semibold leading-none shadow-[0_1px_2px_hsl(218_55%_25%/0.06)]">
                            {sourceTypeLabel(s.source_type, t)}
                          </span>
                        </td>
                        <td className="mono text-[11px] text-muted-foreground truncate max-w-[140px] hidden lg:table-cell" title={AUTH_LABEL_KEYS[s.auth_type] ? t(AUTH_LABEL_KEYS[s.auth_type]) : s.auth_type}>
                          {AUTH_LABEL_KEYS[s.auth_type] ? t(AUTH_LABEL_KEYS[s.auth_type]) : s.auth_type}
                        </td>
                        <td
                          className="mono text-[11px] text-muted-foreground truncate max-w-[220px] hidden md:table-cell"
                          title={fullUrl}
                        >
                          {fullUrl || "—"}
                        </td>
                        <td className="mono text-[11px] whitespace-nowrap">{s.refresh_interval_minutes} min</td>
                        <td className="whitespace-nowrap">
                          {s.last_sync_status ? (
                            <span
                              className="scale-90 origin-left inline-block max-w-[200px]"
                              title={
                                buildSyncDetailTooltip(s) ||
                                (s.last_sync_message &&
                                (s.last_sync_status === "error" ||
                                  s.last_sync_status === "timeout" ||
                                  s.last_sync_status === "partial")
                                  ? s.last_sync_message
                                  : undefined)
                              }
                            >
                              <StatusBadge status={effBadge} />
                            </span>
                          ) : (
                            <span className="text-[10px] text-muted-foreground uppercase tracking-wider">Nikdy</span>
                          )}
                        </td>
                        <td className="mono text-[11px] text-muted-foreground whitespace-nowrap hidden sm:table-cell">
                          {fmtDateTime(s.last_sync_at)}
                        </td>
                        <td className="text-right whitespace-nowrap align-middle">
                          <div className="inline-flex flex-nowrap items-center justify-end gap-1">
                            {showSyncDiag ? (
                              <button
                                type="button"
                                data-testid={`source-sync-detail-${s.id}`}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setSyncDetailSource(s);
                                }}
                                title="Detail synchronizace (HTTP, důvod, úryvek)"
                                className="px-2 h-7 text-[11px] border border-border/80 rounded-lg bg-card/90 hover:bg-muted/60 shadow-sm whitespace-nowrap"
                              >
                                <span className="inline-flex items-center gap-1">
                                  <Info className="h-3.5 w-3.5" strokeWidth={1.8} />
                                  Detail
                                </span>
                              </button>
                            ) : null}
                            <button
                              data-testid={`source-test-${s.id}`}
                              onClick={(e) => {
                                e.stopPropagation();
                                testConn(s.id);
                              }}
                              disabled={busy[s.id]}
                              title="Otestovat připojení"
                              className="p-1.5 border border-border/80 rounded-lg bg-card/90 hover:bg-muted/60 disabled:opacity-50 shadow-sm"
                            >
                              <Activity className="h-3.5 w-3.5" strokeWidth={1.8} />
                            </button>
                            <button
                              data-testid={`source-sync-${s.id}`}
                              onClick={(e) => {
                                e.stopPropagation();
                                runSync(s.id);
                              }}
                              disabled={busy[s.id]}
                              title="Spustit synchronizaci"
                              className="btn-mint p-1.5 flex items-center justify-center disabled:opacity-50 rounded-lg shadow-sm"
                            >
                              {busy[s.id] === "sync" ? (
                                <RefreshCw className="h-3.5 w-3.5 animate-spin" strokeWidth={1.8} />
                              ) : (
                                <Play className="h-3.5 w-3.5" strokeWidth={1.8} />
                              )}
                            </button>
                            <button
                              data-testid={`source-edit-${s.id}`}
                              onClick={(e) => {
                                e.stopPropagation();
                                navigate(`/sources/${s.id}`);
                              }}
                              title="Upravit zdroj"
                              className="px-2.5 h-7 text-[11px] border border-border/80 rounded-lg bg-card/90 hover:bg-muted/60 shadow-sm"
                            >
                              Upravit
                            </button>
                            <button
                              data-testid={`source-delete-${s.id}`}
                              onClick={(e) => {
                                e.stopPropagation();
                                del(s.id);
                              }}
                              title="Smazat zdroj"
                              className="p-1.5 text-muted-foreground hover:text-red-600"
                            >
                              <Trash2 className="h-3.5 w-3.5" strokeWidth={1.6} />
                            </button>
                          </div>
                        </td>
                      </tr>
                      {syncReallyRunning && (
                        <tr
                          className="pointer-events-none hover:!bg-transparent [&_td]:!py-0"
                          aria-hidden
                        >
                          <td colSpan={8} className="!p-0 !align-top border-b min-h-[3px]">
                            <div
                              className="source-sync-indeterminate w-full"
                              title="Synchronizace probíhá…"
                            >
                              <div className="source-sync-indeterminate__bar" />
                            </div>
                          </td>
                        </tr>
                      )}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
              </div>
            </div>
          )}
          {preview && (
            <SourcePreview
              preview={preview}
              loading={previewLoading === preview?.source?.id || preview.loading}
              onClose={() => {
                previewJobRef.current += 1;
                setPreview(null);
              }}
              onIndicatorChange={changePreviewIndicator}
              onIndicatorSelectionChange={changePreviewIndicators}
              onGeoSelectionChange={changePreviewGeos}
              onDimensionFiltersApply={changePreviewDimensionFilters}
            />
          )}
        </div>
      )}

      {syncDetailSource && (
        <div
          className="fixed inset-0 z-[90] flex items-center justify-center p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="sync-detail-title"
          data-testid="source-sync-detail-modal"
        >
          <button
            type="button"
            className="absolute inset-0 w-full h-full cursor-default border-0 bg-black/45 p-0"
            aria-label="Zavřít"
            onClick={() => setSyncDetailSource(null)}
          />
          <div
            className="relative bg-card rounded-2xl shadow-xl max-w-lg w-full max-h-[85vh] overflow-y-auto p-5 space-y-3 text-sm border border-border/80"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="sync-detail-title" className="font-semibold text-base pr-8">
              {syncDetailSource.name}
            </h2>
            <dl className="space-y-1 text-xs font-mono text-foreground/90">
              <div>
                <dt className="inline text-muted-foreground mr-2">Stav:</dt>
                <dd className="inline">{syncDetailSource.last_sync_status}</dd>
              </div>
              <div>
                <dt className="inline text-muted-foreground mr-2">HTTP:</dt>
                <dd className="inline">{syncDetailSource.last_sync_http_status ?? "—"}</dd>
              </div>
              <div>
                <dt className="inline text-muted-foreground mr-2">Typ:</dt>
                <dd className="inline">
                  {(syncDetailSource.last_sync_reason_code || "—").toString()}
                  {syncDetailSource.last_sync_reason_code
                    ? ` — ${humanSyncReason(
                        syncDetailSource.last_sync_reason_code,
                        syncDetailSource.last_sync_message
                      )}`
                    : ""}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Zpráva</dt>
                <dd className="whitespace-pre-wrap text-foreground mt-0.5 font-sans">
                  {syncDetailSource.last_sync_message || syncDetailSource.last_sync_error || "—"}
                </dd>
              </div>
              {syncDetailSource.last_sync_response_preview ? (
                <div>
                  <dt className="text-muted-foreground">Úryvek odpovědi (bez secretů)</dt>
                  <dd className="mt-0.5 p-2 bg-muted/25 rounded-lg text-[11px] break-all">{syncDetailSource.last_sync_response_preview}</dd>
                </div>
              ) : null}
              <div>
                <dt className="text-muted-foreground">Endpoint</dt>
                <dd className="break-all text-[11px] mt-0.5">{`${syncDetailSource.base_url || ""}${syncDetailSource.endpoint || ""}`}</dd>
              </div>
            </dl>
            <div className="flex flex-wrap gap-2 pt-2">
              <button
                type="button"
                className="px-3 h-9 text-xs rounded-xl border border-border bg-card hover:bg-muted/50"
                onClick={() => {
                  const id = syncDetailSource.id;
                  setSyncDetailSource(null);
                  testConn(id);
                }}
              >
                Otestovat endpoint
              </button>
              <button
                type="button"
                className="px-3 h-9 text-xs rounded-xl btn-mint shadow-sm disabled:opacity-50"
                disabled={busy[syncDetailSource.id]}
                onClick={() => {
                  const id = syncDetailSource.id;
                  runSync(id);
                }}
              >
                Zkusit znovu
              </button>
              <button
                type="button"
                className="px-3 h-9 text-xs rounded-xl border border-border hover:bg-muted/50 ml-auto"
                onClick={() => setSyncDetailSource(null)}
              >
                Zavřít
              </button>
            </div>
          </div>
        </div>
      )}

      {testResult && (
        <div
          data-testid="source-test-result"
          className={`mt-4 border rounded-2xl p-4 text-sm shadow-sm ${
            testResult.ok ? "border-[hsl(215_45%_82%)] bg-[hsl(215_55%_95%)]" : "border-red-200 bg-red-50"
          }`}
        >
          <div className="flex items-center gap-2 font-medium">
            <CheckCircle2 className="h-4 w-4" />
            Výsledek testu — HTTP {testResult.http_status ?? "—"} · náhled {testResult.record_count_preview ?? 0} záznamů
          </div>
          {testResult.records_preview?.length > 0 && (
            <pre className="mt-2 text-xs font-mono text-foreground/90 overflow-auto">
              {JSON.stringify(testResult.records_preview, null, 2)}
            </pre>
          )}
          {testResult.error && <div className="mt-2 text-xs font-mono text-red-700">{testResult.error}</div>}
        </div>
      )}
    </AppShell>
  );
}

