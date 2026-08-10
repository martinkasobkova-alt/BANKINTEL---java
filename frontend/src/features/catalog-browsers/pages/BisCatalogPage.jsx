import React, { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  ChevronRight,
  ChevronDown,
  Search,
  RefreshCw,
  Plus,
  Check,
  ArrowLeft,
  Folder,
  FileBarChart2,
  ExternalLink,
  Database,
} from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import CatalogBackToHubButton from "@/components/catalog/CatalogBackToHubButton";
import CatalogAiSearchPanel from "@/components/catalog/CatalogAiSearchPanel";
import BisDimensionWizardModal from "@/components/bis/BisDimensionWizardModal";
import CatalogChartPreview from "@/components/catalog/CatalogChartPreview";
import { LoadingBlock, LoadingInline, LoadingSpinner } from "@/components/ui/loading";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import {
  addCatalogPreviewToPersonalDashboard,
  buildCatalogChartActionsProps,
} from "@/lib/catalogPageDashboard";
import { normalizeSelectedIndicators } from "@/lib/catalogLivePreview";
import { buildCatalogPreviewBody } from "@/lib/catalogPreviewBody";
import { CATALOGS } from "@/lib/catalogDefinitions";
import { isCatalogRowPreviewEligible } from "@/lib/catalogRowPreviewEligible";
import { normalizePreviewPayload } from "@/lib/previewNormalizer";
import {
  bisDefaultPreviewFreq,
  bisPreviewGroupKey,
  bisRowAnyVariantAdded,
  bisSetIdForCountryAndFreq,
  getBisFreqVariants,
  resolveBisVariantRow,
  sortBisFreqVariants,
  BIS_FREQ_LABEL_CS,
} from "@/lib/bisCatalogFreq";
import {
  applyBisDimensionFiltersToQueryParams,
  extractGeoValuesFromDimensionFilters,
} from "@/lib/bisPreviewFilters";
import {
  flattenCatalogCategories,
  allCategoryPathsFromTree,
  defaultOpenPathsFromTree,
  browseAncestorsOpen,
  buildPathIndex,
  buildFilteredPaths,
  parseSearchKeywords,
  patchBrowseRowsForLazyCountry,
  MAX_CATALOG_FILTER_ROWS,
  browseCategoryCountNode,
} from "@/lib/catalogTree";

const BIS_BROWSER_TIMEOUT_MS = 20_000;
const BIS_CATALOG_DEF = CATALOGS.find((c) => c.id === "bis");

export default function BisCatalogPage() {
  const nav = useNavigate();
  const [params, setParams] = useSearchParams();
  const [tree, setTree] = useState(null);
  const [seriesBundle, setSeriesBundle] = useState(null);
  const [seriesLoading, setSeriesLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [catalogError, setCatalogError] = useState("");
  const [search, setSearch] = useState("");
  const [existing, setExisting] = useState(new Set());
  const [adding, setAdding] = useState({});
  const [openPaths, setOpenPaths] = useState(new Set());
  const [bisWizard, setBisWizard] = useState(null);
  const [availabilityOnly, setAvailabilityOnly] = useState(false);
  const [availSummary, setAvailSummary] = useState(null);
  const [previewGroupKey, setPreviewGroupKey] = useState(null);
  const [previewRow, setPreviewRow] = useState(null);
  const [previewFreq, setPreviewFreq] = useState("M");
  const [previewData, setPreviewData] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [previewDimensionFilters, setPreviewDimensionFilters] = useState(null);
  const [previewCompareList, setPreviewCompareList] = useState([]);
  const [addingToDash, setAddingToDash] = useState(false);
  const { isSubscriber } = useAuth();
  const {
    allowed: canPersonalDashboard,
    message: personalDashMsg,
  } = useFeatureAccess("personal_dashboard");
  const {
    allowed: canSaveWidget,
    message: saveWidgetMsg,
  } = useFeatureAccess("save_widget");
  const dashboardFeature = useMemo(
    () => ({
      isSubscriber,
      canPersonalDashboard,
      canSaveWidget,
      personalDashMsg,
      saveWidgetMsg,
    }),
    [isSubscriber, canPersonalDashboard, canSaveWidget, personalDashMsg, saveWidgetMsg]
  );
  const [addFreqByPath, setAddFreqByPath] = useState({});
  const loadGen = useRef(0);
  const previewFetchGen = useRef(0);
  /** Po „Zpět na indikátory“ zabrání efektu expandSeries znovu načíst stejný tok. */
  const skipExpandSeriesEffectRef = useRef(false);
  const [patchedRows, setPatchedRows] = useState(null);
  const [lazyCountryLoading, setLazyCountryLoading] = useState(null);
  const lazyCountriesLoadedRef = useRef(new Set());

  const load = async (force = false) => {
    loadGen.current += 1;
    const gen = loadGen.current;
    if (force) setRefreshing(true);
    else setLoading(true);
    setCatalogError("");
    try {
      const url = force ? "/bis/catalog/refresh" : "/bis/catalog";
      const resp = force
        ? await api.post(url, {}, { timeout: BIS_BROWSER_TIMEOUT_MS })
        : await api.get(url, { timeout: BIS_BROWSER_TIMEOUT_MS });
      if (gen !== loadGen.current) return;
      setTree(resp.data);
      setSeriesBundle(null);
      setOpenPaths(new Set(allCategoryPathsFromTree(resp.data.categories || [])));
      const { data: srcs } = await api.get("/sources/catalog-stubs", { timeout: BIS_BROWSER_TIMEOUT_MS });
      if (gen !== loadGen.current) return;
      setExisting(
        new Set(
          (srcs || [])
            .filter((s) => s.source_type === "bis")
            .map((s) => {
              const canon = (s.bis_catalog_set_id || "").trim();
              if (canon) return canon;
              const flow = s.bis_dataflow || "";
              const key = s.bis_series_key || "";
              return flow && key ? `${flow}/${key}` : "";
            })
            .filter(Boolean)
        )
      );
    } catch (e) {
      if (gen !== loadGen.current) return;
      const msg = formatApiErrorFromAxios(e);
      setCatalogError(msg);
      toast.error(msg || "BIS katalog se nepodařilo načíst.");
    }
    if (gen === loadGen.current) {
      setLoading(false);
      setRefreshing(false);
    }
  };

  const loadSeriesGen = useRef(0);

  const loadSeries = async (dataflowId, opts = {}) => {
    const id = String(dataflowId || "").trim();
    if (!id) return;
    const onlyAvail = opts.availabilityOnly ?? availabilityOnly;
    loadSeriesGen.current += 1;
    const gen = loadSeriesGen.current;
    setSeriesLoading(true);
    try {
      const { data } = await api.get("/bis/catalog/series", {
        params: {
          dataflow: id,
          availability_only: onlyAvail ? "true" : "false",
        },
        timeout: BIS_BROWSER_TIMEOUT_MS,
      });
      if (gen !== loadSeriesGen.current) return;
      setSeriesBundle({ dataflowId: id, tree: data });
      setPreviewGroupKey(null);
      setPreviewRow(null);
      setPreviewData(null);
      setPreviewError("");
      setOpenPaths(
        new Set(
          defaultOpenPathsFromTree(data.categories || [], {
            catalogMode: data.catalog_mode,
          })
        )
      );
      setPatchedRows(null);
      lazyCountriesLoadedRef.current = new Set();
      if (gen === loadSeriesGen.current) setSeriesLoading(false);
      const { data: srcs } = await api.get("/sources/catalog-stubs", { timeout: BIS_BROWSER_TIMEOUT_MS });
      if (gen !== loadSeriesGen.current) return;
      setExisting(
        new Set(
          (srcs || [])
            .filter((s) => s.source_type === "bis")
            .map((s) => {
              const canon = (s.bis_catalog_set_id || "").trim();
              if (canon) return canon;
              const flow = s.bis_dataflow || "";
              const key = s.bis_series_key || "";
              return flow && key ? `${flow}/${key}` : "";
            })
            .filter(Boolean)
        )
      );
    } catch (e) {
      if (gen !== loadSeriesGen.current) return;
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      if (gen === loadSeriesGen.current) setSeriesLoading(false);
    }
  };

  useEffect(() => {
    load(false);
    api
      .get("/bis/catalog/availability-summary", { timeout: BIS_BROWSER_TIMEOUT_MS })
      .then(({ data }) => setAvailSummary(data))
      .catch(() => setAvailSummary(null));
  }, []);

  useEffect(() => {
    const df = (params.get("expandSeries") || "").trim();
    if (skipExpandSeriesEffectRef.current) {
      if (!df) skipExpandSeriesEffectRef.current = false;
      return undefined;
    }
    if (!df || !tree) return undefined;
    const next = new URLSearchParams(params);
    next.delete("expandSeries");
    if (seriesBundle?.dataflowId === df) {
      setParams(next, { replace: true });
      return undefined;
    }
    loadSeries(df);
    setParams(next, { replace: true });
    return undefined;
  }, [tree, params, seriesBundle?.dataflowId, setParams]);

  const activeTree = seriesBundle?.tree || tree;
  const flowTitleForSubtitle = seriesBundle?.tree?.categories?.[0]?.name ?? "";

  const baseRows = useMemo(
    () => (activeTree ? flattenCatalogCategories(activeTree.categories || []) : []),
    [activeTree]
  );
  const allRows = patchedRows ?? baseRows;

  const loadBisCountrySeries = async (countryRow) => {
    const code = String(countryRow.ref_area || "").trim().toUpperCase();
    const countryPath = String(countryRow.path || "").trim();
    const flowId = seriesBundle?.dataflowId;
    if (!code || !flowId || !countryPath || lazyCountriesLoadedRef.current.has(countryPath)) return;
    lazyCountriesLoadedRef.current.add(countryPath);
    setLazyCountryLoading(countryPath);
    try {
      const { data } = await api.get("/bis/catalog/series", {
        params: {
          dataflow: flowId,
          ref_areas: code,
          availability_only: availabilityOnly ? "true" : "false",
        },
        timeout: BIS_BROWSER_TIMEOUT_MS,
      });
      const countryNode =
        data?.country_node ||
        (Array.isArray(data?.categories?.[0]?.children) ? data.categories[0].children[0] : null);
      if (countryNode) {
        setOpenPaths((s) => {
          const n = new Set(s);
          n.add(countryPath);
          return n;
        });
        setPatchedRows((prior) =>
          patchBrowseRowsForLazyCountry(prior ?? baseRows, countryNode, countryRow)
        );
      }
    } catch (e) {
      lazyCountriesLoadedRef.current.delete(countryPath);
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setLazyCountryLoading(null);
    }
  };
  const rowIndex = useMemo(() => buildPathIndex(allRows), [allRows]);
  const keywords = useMemo(() => parseSearchKeywords(search), [search]);
  const filteredPaths = useMemo(
    () => buildFilteredPaths(allRows, rowIndex, keywords, { nameFieldsOnly: true }),
    [allRows, rowIndex, keywords]
  );

  const visibleRows = useMemo(() => {
    if (!allRows.length) return [];
    if (filteredPaths) {
      return allRows.filter((r) => filteredPaths.has(r.path)).slice(0, MAX_CATALOG_FILTER_ROWS);
    }
    const result = [];
    for (const r of allRows) {
      if (r.depth === 0) {
        result.push(r);
        continue;
      }
      if (browseAncestorsOpen(openPaths, r.parentPath)) result.push(r);
    }
    return result;
  }, [allRows, openPaths, filteredPaths]);

  const toggle = (path) => {
    setOpenPaths((s) => {
      const n = new Set(s);
      if (n.has(path)) n.delete(path);
      else n.add(path);
      return n;
    });
  };

  const buildBisPreviewBody = (row, geoIds = null, dimensionFilters = null, freqOverride = null, indicatorIds = []) => {
    const freq = String(freqOverride ?? previewFreq ?? "")
      .trim()
      .toUpperCase();
    const resolved = {
      ...resolveBisVariantRow(row, freq),
      item_kind: row.item_kind || "selection",
      kind: "set",
    };
    const body = buildCatalogPreviewBody(BIS_CATALOG_DEF, resolved);
    const fromMeta = Array.isArray(geoIds) ? geoIds : null;
    const fromDims = extractGeoValuesFromDimensionFilters(dimensionFilters);
    const ref = String(resolved.ref_area || "").trim().toUpperCase();
    const geos = (fromMeta && fromMeta.length ? fromMeta : fromDims.length ? fromDims : ref ? [ref] : [])
      .map((c) => String(c || "").trim().toUpperCase())
      .filter(Boolean);
    const baseQp =
      body.query_params && typeof body.query_params === "object" ? body.query_params : {};
    body.query_params = applyBisDimensionFiltersToQueryParams(baseQp, dimensionFilters);
    if (geos.length) {
      body.geo = geos;
      body.query_params.REF_AREA = geos.length === 1 ? geos[0] : geos;
    }
    const many = normalizeSelectedIndicators(indicatorIds);
    if (many.length) {
      body.selected_indicator = many[0];
      body.selected_indicators = many;
    }
    return body;
  };

  const readPreviewGeoCodes = () => {
    const applied = previewData?.metadata?.filters_applied;
    const raw = applied?.geo ?? applied?.REF_AREA ?? previewData?.requested_filters?.geo;
    if (Array.isArray(raw)) return raw.map((c) => String(c).trim().toUpperCase()).filter(Boolean);
    if (typeof raw === "string" && raw.trim()) return [raw.trim().toUpperCase()];
    return [];
  };

  const fetchSeriesPreview = async (
    row,
    geoIds = null,
    dimensionFilters = null,
    freqOverride = null,
    indicatorIds = []
  ) => {
    previewFetchGen.current += 1;
    const gen = previewFetchGen.current;
    setPreviewLoading(true);
    setPreviewError("");
    try {
      const body = buildBisPreviewBody(row, geoIds, dimensionFilters, freqOverride, indicatorIds);
      const { data } = await api.post("/catalog/preview", body, { timeout: BIS_BROWSER_TIMEOUT_MS });
      if (gen !== previewFetchGen.current) return;
      setPreviewData(normalizePreviewPayload(data, "bis"));
    } catch (e) {
      if (gen !== previewFetchGen.current) return;
      setPreviewError(formatApiErrorFromAxios(e));
    } finally {
      if (gen === previewFetchGen.current) setPreviewLoading(false);
    }
  };

  const closeSeriesPreview = () => {
    previewFetchGen.current += 1;
    setPreviewGroupKey(null);
    setPreviewRow(null);
    setPreviewData(null);
    setPreviewError("");
    setPreviewLoading(false);
    setPreviewDimensionFilters(null);
    setPreviewCompareList([]);
  };

  const backToDataflowList = () => {
    skipExpandSeriesEffectRef.current = true;
    loadSeriesGen.current += 1;
    setSeriesLoading(false);
    closeSeriesPreview();
    const next = new URLSearchParams(params);
    next.delete("expandSeries");
    setParams(next, { replace: true });
    setSeriesBundle(null);
    setPatchedRows(null);
    lazyCountriesLoadedRef.current = new Set();
    if (tree) setOpenPaths(new Set(allCategoryPathsFromTree(tree.categories || [])));
  };

  const toggleSeriesPreview = async (row) => {
    const sid = String(row.set_id || "").trim();
    if (!sid || !isCatalogRowPreviewEligible(BIS_CATALOG_DEF, { ...row, set_id: sid, item_kind: "selection" })) {
      return;
    }
    const gk = bisPreviewGroupKey(row);
    if (previewGroupKey === gk) {
      closeSeriesPreview();
      return;
    }
    const freq = bisDefaultPreviewFreq(row);
    setPreviewGroupKey(gk);
    setPreviewRow(row);
    setPreviewFreq(freq);
    setPreviewDimensionFilters(null);
    await fetchSeriesPreview(resolveBisVariantRow(row, freq));
  };

  const switchPreviewFreq = async (row, freq) => {
    const next = String(freq || "").trim().toUpperCase();
    if (!next || next === previewFreq) return;
    setPreviewFreq(next);
    const resolved = resolveBisVariantRow(row, next);
    setPreviewRow(resolved);
    const geos = readPreviewGeoCodes();
    await fetchSeriesPreview(
      resolved,
      geos.length ? geos : null,
      previewDimensionFilters,
      next
    );
  };

  const handlePreviewGeoChange = async (geoIds) => {
    if (!previewRow) return;
    await fetchSeriesPreview(previewRow, geoIds, previewDimensionFilters);
  };

  const handlePreviewDimensionFiltersApply = async (dimensionFilters) => {
    if (!previewRow) return;
    const hasDims =
      dimensionFilters &&
      typeof dimensionFilters === "object" &&
      !Array.isArray(dimensionFilters) &&
      Object.keys(dimensionFilters).length > 0;
    setPreviewDimensionFilters(hasDims ? dimensionFilters : null);
    const geoIds = extractGeoValuesFromDimensionFilters(dimensionFilters);
    const fallback = readPreviewGeoCodes();
    await fetchSeriesPreview(
      previewRow,
      geoIds.length ? geoIds : fallback.length ? fallback : null,
      hasDims ? dimensionFilters : null
    );
  };

  const handleAddPreviewToDashboard = async (row, { setPagePick } = {}) => {
    if (!previewData || previewError) return;
    setAddingToDash(true);
    try {
      const resolved = resolveBisVariantRow(row, previewFreq);
      await addCatalogPreviewToPersonalDashboard({
        api,
        nav,
        def: BIS_CATALOG_DEF,
        previewData,
        row: {
          ...resolved,
          set_id: String(resolved.set_id || row.set_id || "").trim(),
          name: row.name || resolved.name,
        },
        feature: dashboardFeature,
        setPagePick,
      });
    } finally {
      setAddingToDash(false);
    }
  };

  const handlePreviewCompareSave = async (payload) => {
    if (!previewRow) return;
    const compareIds = (payload?.chart_compare_with || [])
      .map((c) => String(c?.selected_indicator || "").trim())
      .filter(Boolean);
    setPreviewCompareList(Array.isArray(payload?.chart_compare_with) ? payload.chart_compare_with : []);
    const main = String(previewData?.selected_indicator || "").trim();
    const all = [...new Set([main, ...compareIds].filter(Boolean))];
    const geo = readPreviewGeoCodes();
    await fetchSeriesPreview(
      previewRow,
      geo.length ? geo : null,
      previewDimensionFilters,
      null,
      all
    );
  };

  const subtitle = seriesBundle
    ? `${seriesBundle.tree?.categories?.[0]?.name ?? "Dataflow"} · ${seriesBundle.dataflowId} — ${formatCount(seriesBundle.tree?.total_sets)} řad`
    : `${tree?.total_sets?.toLocaleString("cs-CZ") || "—"} BIS datových toků (abecedně) · stats.bis.org`;

  const getAddFreq = (row) => addFreqByPath[row.path] || bisDefaultPreviewFreq(row);

  const addSource = async (set_id, extra = {}) => {
    setAdding((a) => ({ ...a, [set_id]: true }));
    try {
      const body = extra.query_params ? { set_id, query_params: extra.query_params } : { set_id };
      const { data } = await api.post("/bis/catalog/add-source", body, { timeout: BIS_BROWSER_TIMEOUT_MS });
      toast.success(`Přidáno: ${data.name}`);
      setExisting((s) => new Set([...s, String(data.set_id || set_id)]));
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setAdding((a) => ({ ...a, [set_id]: false }));
  };

  const addSourceForRow = async (row) => {
    const freq = getAddFreq(row);
    const country = String(row.ref_area || "").trim().toUpperCase();
    const setId = bisSetIdForCountryAndFreq(row, country, freq);
    if (!setId) {
      toast.error("Nelze sestavit identifikátor řady — zkontrolujte zemi a frekvenci.");
      return;
    }
    await addSource(setId, row.query_params ? { query_params: row.query_params } : {});
  };

  function formatCount(v) {
    if (typeof v !== "number" || Number.isNaN(v)) return "—";
    return String(v.toLocaleString("cs-CZ"));
  }

  return (
    <AppShell
      title="Katalog BIS"
      subtitle={subtitle}
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <a
            href="https://www.bis.org/statistics/index.htm"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
            title="BIS Statistics"
          >
            <ExternalLink className="h-4 w-4" /> bis.org/statistics
          </a>
          <button
            type="button"
            onClick={() => nav("/sources")}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
          >
            <ArrowLeft className="h-4 w-4" /> Zpět na zdroje
          </button>
          <CatalogBackToHubButton catalogId="bis" />
          {seriesBundle ? (
            <button
              type="button"
              onClick={backToDataflowList}
              className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
            >
              <ArrowLeft className="h-4 w-4" /> Zpět na výběr indikátorů
            </button>
          ) : null}
          <button
            type="button"
            onClick={() => load(true)}
            disabled={refreshing}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))] disabled:opacity-50"
          >
            <RefreshCw className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} />
            {refreshing ? "Stahuji…" : "Obnovit"}
          </button>
        </div>
      }
    >
      <CatalogAiSearchPanel catalogId="bis" className="mb-6 max-w-4xl" />

      <div className="rounded-xl border border-border bg-card/92 shadow-sm mb-6 overflow-hidden">
        <div className="px-4 py-3 text-sm border-b border-border/60 bg-muted/35">
          <div className="font-medium text-foreground">Procházet datové toky (SDMX)</div>
          <p className="text-[11px] text-muted-foreground mt-1 leading-snug">
            Dataflow není jen „složka“ — řada vzniká až výběrem dimenzí. Pro hlavní práci použijte tlačítko vybraného
            toku.
          </p>
        </div>
        <div className="space-y-4 px-2 sm:px-4 py-4">
          <div className="max-w-xl relative mb-4">
            <Search className="h-4 w-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              className="w-full h-10 pl-9 pr-3 border border-[hsl(var(--border)/0.75)] rounded-xl text-sm bg-card shadow-sm"
              placeholder="Název datového toku (anglicky) — např. exchange rate, credit…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              data-testid="bis-catalog-search"
            />
          </div>

          {seriesBundle ? (
            <div
              className="sticky top-0 z-20 -mx-2 sm:-mx-4 px-2 sm:px-4 py-2 mb-3 flex flex-wrap items-center gap-2 border-b border-border/60 bg-card/95 backdrop-blur-sm"
              data-testid="bis-catalog-breadcrumb"
            >
              <button
                type="button"
                onClick={backToDataflowList}
                className="inline-flex items-center gap-1.5 rounded-lg border border-[hsl(var(--border)/0.75)] bg-white px-3 h-9 text-xs font-semibold text-slate-800 shadow-sm hover:bg-[hsl(var(--primary-soft))]"
              >
                <ArrowLeft className="h-4 w-4 shrink-0" />
                Zpět na výběr indikátorů
              </button>
              <span className="text-xs text-muted-foreground min-w-0">
                <span className="font-medium text-foreground">{flowTitleForSubtitle || seriesBundle.dataflowId}</span>
                <span className="font-mono text-[10px] ml-1.5 text-muted-foreground">{seriesBundle.dataflowId}</span>
                {search.trim() ? (
                  <>
                    {" "}
                    · filtr: <span className="font-mono text-foreground">„{search.trim()}“</span>
                  </>
                ) : null}
              </span>
            </div>
          ) : null}

          {seriesBundle ? (
            <label className="flex items-start gap-2 text-sm text-foreground mb-2 cursor-pointer max-w-2xl">
              <input
                type="checkbox"
                className="mt-1"
                checked={availabilityOnly}
                onChange={(e) => {
                  const on = e.target.checked;
                  setAvailabilityOnly(on);
                  if (seriesBundle?.dataflowId) {
                    loadSeries(seriesBundle.dataflowId, { availabilityOnly: on });
                  }
                }}
                data-testid="bis-availability-only-filter"
              />
              <span>
                Jen země s ověřenými daty (fáze 1)
                {availSummary?.country_count != null ? (
                  <span className="block text-[11px] text-muted-foreground font-normal mt-0.5">
                    {availSummary.country_count} zemí · {availSummary.series_rows_phase1 ?? "—"} ověřených řad · index
                    2A:{" "}
                    {seriesBundle.tree?.source === "index"
                      ? formatCount(seriesBundle.tree?.total_sets)
                      : "živě z API"}
                  </span>
                ) : null}
              </span>
            </label>
          ) : availSummary?.series_rows_phase1 ? (
            <p className="text-[11px] text-muted-foreground mb-3">
              Fáze 1: {availSummary.series_rows_phase1} ověřených řad v {availSummary.country_count} zemích. Po
              rozbalení toku načteme kompletní index řad (2A).
            </p>
          ) : null}

          {catalogError ? (
            <div className="rounded-2xl border border-red-200 bg-red-50/95 px-4 py-4 text-sm text-red-950 space-y-2">
              <div className="font-medium">BIS katalog se nepodařilo načíst.</div>
              <pre className="text-[11px] whitespace-pre-wrap break-all font-mono text-red-900/95">{catalogError}</pre>
            </div>
          ) : null}

          {seriesBundle &&
          !seriesLoading &&
          Array.isArray(seriesBundle.tree?.errors) &&
          seriesBundle.tree.errors.length > 0 ? (
            <div className="rounded-2xl border border-amber-200 bg-amber-50/95 px-4 py-3 text-sm text-amber-950 mb-3 space-y-1">
              <div className="font-medium">BIS vrátilo varování při načítání řad</div>
              {seriesBundle.tree.errors.map((msg, i) => (
                <div key={i} className="text-[11px] font-mono whitespace-pre-wrap break-all">
                  {msg}
                </div>
              ))}
            </div>
          ) : null}

          {seriesBundle &&
          !seriesLoading &&
          seriesBundle.tree?.catalog_mode === "countries_lazy" ? (
            <p className="text-[11px] text-muted-foreground mb-3 leading-relaxed">
              Velký tok ({formatCount(seriesBundle.tree?.total_sets)} řad v BIS) — nejdřív seznam zemí.{" "}
              <strong>Rozklikněte zemi</strong>, načtou se její řady (jednou na zemi).
            </p>
          ) : null}

          {seriesBundle && !seriesLoading && Number(seriesBundle.tree?.total_sets) === 0 ? (
            <div className="rounded-2xl border border-teal-200/90 bg-teal-50/90 px-4 py-4 text-sm text-teal-950 space-y-3">
              <div>
                <strong>Tento dataflow nemá podstrom řad ve výpisu CSV</strong>
                (&quot;serieskeysonly&quot; od BIS může být prázdný). Pokračujte výběrem dimenzí / sestavením klíče.
              </div>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  data-testid="bis-empty-wizard-open"
                  className="btn-mint inline-flex items-center gap-2 px-4 h-9 text-xs"
                  onClick={() =>
                    setBisWizard({ dataflowId: seriesBundle.dataflowId, title: flowTitleForSubtitle || seriesBundle.dataflowId })
                  }
                >
                  <Database className="h-4 w-4 shrink-0" />
                  Vybrat dimenze / řady
                </button>
              </div>
            </div>
          ) : null}

          {seriesLoading ? (
            <LoadingBlock label="Načítám řady z BIS (CSV výpis)…" minHeightClass="min-h-[100px]" showSkeletonLines />
          ) : loading ? (
            <LoadingBlock label="Načítám BIS dataflow…" minHeightClass="min-h-[140px]" showSkeletonLines />
          ) : catalogError ? null : visibleRows.length === 0 ? (
            <div className="border border-dashed border-border bg-muted/25 rounded-2xl p-12 text-center text-sm text-muted-foreground">
              {search ? "Žádný výsledek pro zadaný filtr." : "Katalog je prázdný."}
            </div>
          ) : (
            <div className="bg-card border border-border rounded-2xl overflow-hidden shadow-sm">
              {visibleRows.map((row) => {
                if (row.kind === "cat") {
                  const rawCount =
                    typeof row.series_count === "number"
                      ? row.series_count
                      : typeof row.count === "number"
                        ? row.count
                        : (row.children?.length ?? 0) || row.count || 0;
                  const countryPath = String(row.path || "").trim();
                  const lazyLoaded = lazyCountriesLoadedRef.current.has(countryPath);
                  const isLazyCountry = Boolean(row.bis_lazy_country && row.ref_area);
                  const isOpen =
                    (openPaths.has(row.path) || Boolean(filteredPaths)) &&
                    (!isLazyCountry || lazyLoaded);
                  const isLeafBucket = rawCount === 0 && !row.bis_lazy_country;
                  const hasExpandTarget = rawCount > 0 || isLazyCountry;
                  const isLazyLoading = lazyCountryLoading === row.path;
                  const browseCountLabel = browseCategoryCountNode({ count: rawCount }, isOpen);
                  return (
                    <button
                      key={row.path}
                      type="button"
                      onClick={() => {
                        if (!hasExpandTarget) return;
                        if (isLazyCountry && !lazyLoaded) {
                          setOpenPaths((s) => {
                            const n = new Set(s);
                            n.add(countryPath);
                            return n;
                          });
                          if (lazyCountryLoading !== countryPath) {
                            void loadBisCountrySeries(row);
                          }
                          return;
                        }
                        const willOpen = !isOpen;
                        toggle(row.path);
                        if (willOpen && isLazyCountry) {
                          void loadBisCountrySeries(row);
                        }
                      }}
                      className={`w-full flex items-start gap-2 text-left px-4 py-2.5 border-t border-border/60 hover:bg-muted/50 ${
                        row.depth === 0 ? "bg-muted/30 font-medium" : ""
                      } ${!hasExpandTarget ? "cursor-default" : ""}`}
                      style={{ paddingLeft: `${16 + row.depth * 20}px` }}
                    >
                      {hasExpandTarget ? (
                        isOpen ? (
                          <ChevronDown className="h-4 w-4 text-muted-foreground shrink-0 mt-0.5" />
                        ) : (
                          <ChevronRight className="h-4 w-4 text-muted-foreground shrink-0 mt-0.5" />
                        )
                      ) : (
                        <span className="w-4 h-4 shrink-0 mt-0.5 inline-block" aria-hidden />
                      )}
                      <Folder className="h-4 w-4 text-muted-foreground shrink-0 mt-0.5" />
                      <span className="min-w-0 flex-1 text-sm text-foreground">
                        <span className="block truncate">{row.name}</span>
                        {isLazyLoading ? (
                          <LoadingInline
                            label="Načítám řady…"
                            size="xs"
                            muted
                            className="mt-0.5 block w-full"
                          />
                        ) : null}
                        {isLeafBucket ? (
                          <span className="block text-[11px] font-normal text-muted-foreground mt-0.5">
                            Prázdná skupina po filtru — případně se vraťte o úroveň výš.
                          </span>
                        ) : null}
                      </span>
                      <span className="text-[10px] uppercase tracking-wider text-muted-foreground shrink-0 pr-2 text-right leading-tight max-w-[8rem]">
                        {seriesBundle &&
                        row.depth === 0 &&
                        Number(seriesBundle.tree?.total_sets) === 0 &&
                        rawCount === 0 ? (
                          <span className="text-teal-800 font-medium normal-case tracking-normal">
                            Tento dataflow nemá podstrom. Pokračujte výběrem dimenzí.
                          </span>
                        ) : browseCountLabel != null ? (
                          browseCountLabel
                        ) : null}
                      </span>
                    </button>
                  );
                }
                const isDataflow = row.item_kind === "dataflow";
                const sid = String(row.set_id || "").trim();
                const freqVariants = sortBisFreqVariants(getBisFreqVariants(row));
                const hasFreqSwitch = freqVariants.length > 1;
                const addFreq = getAddFreq(row);
                const addSetId = bisSetIdForCountryAndFreq(row, row.ref_area, addFreq);
                const isAdded = addSetId ? existing.has(addSetId) : bisRowAnyVariantAdded(row, existing);
                const isAdding = Boolean(adding[addSetId]);
                const catalogPreviewRow = { ...row, set_id: sid, item_kind: row.item_kind || "selection" };
                const previewable =
                  !isDataflow && isCatalogRowPreviewEligible(BIS_CATALOG_DEF, catalogPreviewRow);
                const previewOpen = previewable && previewGroupKey === bisPreviewGroupKey(row);
                const activeFreqVariant = previewOpen
                  ? freqVariants.find((v) => v.freq === previewFreq) || freqVariants[0]
                  : null;
                const previewFreqLabel =
                  activeFreqVariant?.label_cs ||
                  BIS_FREQ_LABEL_CS[previewFreq] ||
                  previewFreq ||
                  "";
                const previewValueDescriptor = String(
                  (previewOpen ? resolveBisVariantRow(row, previewFreq) : row)?.bis_value_descriptor ||
                    previewData?.metadata?.bis_value_descriptor ||
                    previewData?.bis_value_descriptor ||
                    ""
                ).trim();
                const rowCountryLabel = String(row.territory || row.name || "")
                  .split(" — ")[0]
                  .trim();
                const meta = [];
                if (row.period && row.period !== "frekvence v náhledu") {
                  meta.push(`období: ${row.period}`);
                }
                if (!isDataflow && !String(row.set_id || "").includes("||DATAFLOW")) {
                  if (row.bis_dataflow) meta.push(`flow: ${row.bis_dataflow}`);
                  if (hasFreqSwitch) {
                    meta.push(`frekvence: ${freqVariants.map((v) => v.label_cs || v.freq).join(", ")}`);
                  } else if (row.bis_series_key && !row.bis_readable_title) {
                    meta.push(`key: ${row.bis_series_key}`);
                  }
                  const qp = row.query_params;
                  if (qp && typeof qp === "object") {
                    if (qp.lastNObservations || qp.firstNObservations) {
                      meta.push(
                        `limit: lastN=${qp.lastNObservations ?? "—"} firstN=${qp.firstNObservations ?? "—"}`
                      );
                    }
                    if (qp.startPeriod || qp.endPeriod) {
                      meta.push(`období: ${qp.startPeriod ?? "…"}→${qp.endPeriod ?? "…"}`);
                    }
                  }
                }

                if (isDataflow) {
                  const openDataflow = () => {
                    if (!row.bis_dataflow || seriesLoading) return;
                    void loadSeries(row.bis_dataflow);
                  };
                  return (
                    <div
                      key={row.path}
                      role="button"
                      tabIndex={seriesLoading ? -1 : 0}
                      onClick={openDataflow}
                      onKeyDown={(e) => {
                        if (seriesLoading || (e.key !== "Enter" && e.key !== " ")) return;
                        e.preventDefault();
                        openDataflow();
                      }}
                      className={`flex flex-col sm:flex-row sm:items-stretch gap-3 py-4 pr-4 border-t border-border/60 hover:bg-muted/50 ${
                        seriesLoading ? "opacity-60 cursor-wait" : "cursor-pointer"
                      }`}
                      style={{ paddingLeft: `${24 + row.depth * 20}px` }}
                      data-testid={`bis-dataflow-row-${row.bis_dataflow}`}
                    >
                      <div className="flex gap-3 min-w-0 flex-1 items-start">
                        <FileBarChart2 className="h-5 w-5 text-teal-600 shrink-0 mt-0.5" />
                        <div className="min-w-0 flex-1 space-y-2">
                          <div className="flex flex-wrap items-center gap-2 gap-y-1">
                            <span className="inline-flex items-center rounded-md border border-teal-200 bg-teal-50 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-teal-900">
                              Dataflow
                            </span>
                            <span className="text-sm font-medium text-foreground truncate" title={row.name}>
                              {row.name}
                            </span>
                          </div>
                          <p className="text-[12px] text-muted-foreground leading-snug">
                            Toto je BIS datový tok — řada vzniká až výběrem dimenzí z DSD / číselníků (SDMX klíč). Prázdný
                            výpis řad přes CSV u některých toků je očekávaný — použijte průvodce dimenzemi.
                          </p>
                          <div className="text-[11px] text-muted-foreground font-mono">
                            Kód:&nbsp;<span className="text-foreground">{row.bis_dataflow}</span>
                            &nbsp;·&nbsp; položka:&nbsp;{String(row.set_id || "").slice(0, 80)}
                            {meta.length ? `  ·  ${meta.join("  ·  ")}` : ""}
                          </div>
                          {row.bis_index_error ? (
                            <div className="text-[11px] text-amber-900 bg-amber-50 border border-amber-200 rounded-lg px-2 py-1.5 leading-snug">
                              Offline index nedostupný ({row.bis_index_error}). Řady se načtou živě z BIS API — může
                              trvat déle.
                            </div>
                          ) : null}
                          <div className="text-[10px] text-muted-foreground">Klikněte pro načtení řad tohoto toku</div>
                        </div>
                      </div>
                      <div
                        className="flex flex-col gap-2 shrink-0 sm:items-end sm:justify-center sm:min-w-[12rem] pl-14 sm:pl-0"
                        onClick={(e) => e.stopPropagation()}
                        onKeyDown={(e) => e.stopPropagation()}
                      >
                        <button
                          type="button"
                          data-testid={`bis-dim-wizard-${row.bis_dataflow}`}
                          className="btn-mint w-full sm:w-auto justify-center inline-flex items-center gap-2 px-4 h-9 text-xs"
                          onClick={() => setBisWizard({ dataflowId: row.bis_dataflow, title: row.name })}
                          disabled={seriesLoading}
                        >
                          <Database className="h-4 w-4 shrink-0" />
                          Vybrat dimenze / řady
                        </button>
                        <button
                          type="button"
                          className={`w-full sm:w-auto px-4 h-8 text-[11px] border border-border rounded-xl bg-card hover:bg-muted/50 disabled:opacity-50 ${
                            seriesLoading ? "inline-flex items-center justify-center gap-2" : ""
                          }`}
                          onClick={openDataflow}
                          disabled={seriesLoading}
                          aria-busy={seriesLoading}
                        >
                          {seriesLoading ? (
                            <>
                              <LoadingSpinner suppressAria size="xs" aria-label="" />
                              <span>Načítám řady…</span>
                            </>
                          ) : (
                            "Načíst řady (CSV)"
                          )}
                        </button>
                      </div>
                    </div>
                  );
                }

                return (
                  <div key={row.path} data-testid={previewable ? `bis-series-row-${sid}` : undefined}>
                    <div
                      role={previewable ? "button" : undefined}
                      tabIndex={previewable ? 0 : undefined}
                      onClick={() => {
                        if (previewable) void toggleSeriesPreview(catalogPreviewRow);
                      }}
                      onKeyDown={(e) => {
                        if (!previewable || (e.key !== "Enter" && e.key !== " ")) return;
                        e.preventDefault();
                        void toggleSeriesPreview(catalogPreviewRow);
                      }}
                      className={`flex items-center gap-3 py-2 pr-3 border-t border-border/60 hover:bg-muted/50 ${
                        previewable ? "cursor-pointer" : ""
                      } ${previewOpen ? "bg-muted/30" : ""}`}
                      style={{ paddingLeft: `${36 + row.depth * 20}px` }}
                    >
                      <FileBarChart2 className="h-4 w-4 text-muted-foreground shrink-0" />
                      <div className="min-w-0 flex-1">
                        <div className="text-sm font-medium text-foreground" title={row.name}>
                          {row.name}
                        </div>
                        {Array.isArray(row.bis_series_diff) && row.bis_series_diff.length > 0 ? (
                          <div
                            className="flex flex-wrap gap-1 mt-1"
                            data-testid="bis-series-diff"
                            title={row.bis_series_explanation || row.bis_series_diff_text}
                          >
                            {row.bis_series_diff.map((tag) => (
                              <span
                                key={tag}
                                className="inline-flex max-w-full truncate rounded-md border border-indigo-200/80 bg-indigo-50 px-1.5 py-0.5 text-[10px] font-medium text-indigo-950"
                              >
                                {tag}
                              </span>
                            ))}
                          </div>
                        ) : null}
                        {row.bis_value_descriptor ? (
                          <p
                            className="text-[11px] font-medium text-teal-900 bg-teal-50/90 border border-teal-200/70 rounded-md px-2 py-1 mt-1 leading-snug"
                            title={row.bis_value_descriptor}
                            data-testid="bis-value-descriptor"
                          >
                            <span className="text-teal-700/90 uppercase tracking-wide text-[9px] mr-1.5">
                              Co ukazuje:
                            </span>
                            {row.bis_value_descriptor}
                          </p>
                        ) : null}
                        {row.bis_series_explanation &&
                        !(Array.isArray(row.bis_series_diff) && row.bis_series_diff.length) ? (
                          <p
                            className="text-[11px] text-muted-foreground leading-snug mt-0.5 line-clamp-3"
                            title={row.bis_series_explanation}
                          >
                            {row.bis_series_explanation}
                          </p>
                        ) : null}
                        <div className="text-[11px] text-muted-foreground font-mono">
                          kód: {sid}
                          {meta.length ? `  ·  ${meta.join("  ·  ")}` : ""}
                        </div>
                        {previewable ? (
                          <div className="text-[10px] text-muted-foreground mt-0.5">Klikněte pro náhled dat</div>
                        ) : null}
                      </div>
                      <div
                        className="flex flex-col sm:flex-row sm:items-center gap-2 shrink-0 min-w-[12rem]"
                        onClick={(e) => e.stopPropagation()}
                      >
                        {hasFreqSwitch ? (
                          <label className="flex flex-col gap-0.5 text-[10px] text-muted-foreground">
                            <span>Frekvence</span>
                            <select
                              className="h-8 min-w-[7.5rem] px-2 text-xs border border-border rounded-lg bg-card text-foreground"
                              value={addFreq}
                              onChange={(e) =>
                                setAddFreqByPath((prev) => ({
                                  ...prev,
                                  [row.path]: e.target.value.trim().toUpperCase(),
                                }))
                              }
                              data-testid={`bis-add-freq-${row.path}`}
                            >
                              {freqVariants.map((v) => (
                                <option key={v.freq} value={v.freq}>
                                  {v.label_cs || v.freq}
                                </option>
                              ))}
                            </select>
                          </label>
                        ) : null}
                        {isAdded ? (
                          <span
                            data-testid={`bis-added-${addSetId}`}
                            className="flex items-center gap-1.5 px-2.5 h-7 text-[11px] uppercase tracking-wider rounded-lg chip-mint border border-[hsl(215_45%_82%)] self-end"
                          >
                            <Check className="h-3 w-3" /> přidáno
                          </span>
                        ) : (
                          <button
                            type="button"
                            onClick={() => void addSourceForRow(row)}
                            disabled={isAdding || !addSetId}
                            data-testid={`bis-add-${addSetId || sid}`}
                            className="btn-mint flex items-center gap-1.5 px-3 h-7 text-xs disabled:opacity-50 self-end"
                          >
                            {isAdding ? (
                              <LoadingSpinner suppressAria size="xs" />
                            ) : (
                              <Plus className="h-3 w-3" />
                            )}
                            Přidat řadu
                          </button>
                        )}
                      </div>
                    </div>
                    {previewOpen ? (
                      <div
                        className="border-t border-border/60 bg-muted/15 px-4 py-3 space-y-3"
                        style={{ paddingLeft: `${36 + row.depth * 20}px` }}
                        data-testid={`bis-preview-${bisPreviewGroupKey(row)}`}
                        onClick={(e) => e.stopPropagation()}
                      >
                        {hasFreqSwitch ? (
                          <div className="flex flex-wrap items-center gap-2" data-testid="bis-freq-switch">
                            <span className="text-[11px] text-muted-foreground shrink-0">Frekvence:</span>
                            {freqVariants.map((v) => (
                              <button
                                key={v.freq}
                                type="button"
                                disabled={previewLoading}
                                onClick={() => void switchPreviewFreq(row, v.freq)}
                                className={`px-2.5 h-7 text-xs rounded-lg border transition-colors ${
                                  previewFreq === v.freq
                                    ? "border-teal-500 bg-teal-50 text-teal-950 font-medium"
                                    : "border-border bg-card hover:bg-muted/60 text-foreground"
                                }`}
                                data-testid={`bis-freq-${v.freq}`}
                              >
                                {v.label_cs || v.freq}
                              </button>
                            ))}
                          </div>
                        ) : null}
                        <CatalogChartPreview
                          widgetId={`bis-preview-${bisPreviewGroupKey(row)}`}
                          title={row.name || row.label || "BIS"}
                          sourceType="bis"
                          catalogDef={BIS_CATALOG_DEF}
                          catalogRow={{
                            ...catalogPreviewRow,
                            set_id: String(catalogPreviewRow.set_id || "").trim(),
                          }}
                          preview={
                            previewError
                              ? {
                                  error: previewError,
                                  source: { name: row.name, source_type: "bis" },
                                }
                              : previewData
                                ? {
                                    ...previewData,
                                    source: { name: row.name, source_type: "bis" },
                                  }
                                : { source: { name: row.name, source_type: "bis" } }
                          }
                          previewError={previewError}
                          previewLoading={previewLoading}
                          sourcePreviewProps={{
                            catalogCountryLabel: rowCountryLabel,
                            catalogCountryCode: String(row.ref_area || "").trim(),
                            catalogFreqLabel: previewFreqLabel,
                            catalogFreqCode: previewOpen ? previewFreq : null,
                            catalogValueDescriptor: previewValueDescriptor,
                            onGeoSelectionChange: (geoIds) => void handlePreviewGeoChange(geoIds),
                            onDimensionFiltersApply: (dimensionFilters) =>
                              void handlePreviewDimensionFiltersApply(dimensionFilters),
                            catalogChartActions: buildCatalogChartActionsProps({
                              feature: dashboardFeature,
                              previewData,
                              previewError,
                              previewLoading,
                              onAddToDashboard: (ctx) => void handleAddPreviewToDashboard(catalogPreviewRow, ctx),
                              addingToDashboard: addingToDash,
                            }),
                          }}
                          actions={{
                            compareList: previewCompareList,
                            onCompareSave: handlePreviewCompareSave,
                          }}
                        />
                      </div>
                    ) : null}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <BisDimensionWizardModal
        open={Boolean(bisWizard?.dataflowId)}
        onClose={() => setBisWizard(null)}
        flowRef={bisWizard?.dataflowId || ""}
        flowTitle={bisWizard?.title}
      />
    </AppShell>
  );
}
