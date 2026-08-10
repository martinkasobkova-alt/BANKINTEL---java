import React, { useCallback, useEffect, useMemo, useRef, useState, startTransition } from "react";
import { useNavigate, useSearchParams, useLocation, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  FileBarChart2,
  X as XIcon,
  ExternalLink,
  TrendingUp,
} from "lucide-react";
import { toast } from "sonner";
import api, {
  API_FAILURE_CORS_OR_NETWORK,
  API_ROOT_URL,
  formatApiErrorFromAxios,
  normalizeApiFailure,
} from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import CatalogPreviewFullscreenOverlay from "@/components/catalog/search/CatalogPreviewFullscreenOverlay";
import CatalogSetPreviewPanel from "@/components/catalog/search/CatalogSetPreviewPanel";
import CatalogSetActionsPanel from "@/components/catalog/search/CatalogSetActionsPanel";
import PersonalDashboardPagePickModal from "@/components/myDashboard/PersonalDashboardPagePickModal";
import { DataLoadInline } from "@/components/ui/DataLoadIndicator";
import { LoadingBlock } from "@/components/ui/loading";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import {
  flattenCatalogCategoriesBestEffort,
  patchBrowseRowsForLazyCountry,
  buildPathIndex,
  buildFilteredPaths,
  parseSearchKeywords,
  MAX_CATALOG_FILTER_ROWS,
  browseAncestorsOpen,
} from "@/lib/catalogTree";
import {
  buildLocalCrossSearchFlatResults,
  catalogSearchHitToFlatEntry,
  CLASSIC_SEARCH_SCOPE_ALL,
  resolveClassicSearchCatalogDefs,
  runCatalogCrossSearch,
} from "@/lib/catalogCrossSearch";
import { buildExternalCatalogChartConfig } from "@/lib/catalogPersonalDashboard";
import { createExternalCatalogWidgetWithSnapshot } from "@/lib/catalogDashboardWidget";
import { isEcbCuratedRowPreviewEligible } from "@/lib/ecbCatalogSetId";
import { imfSeriesDisplayTitle } from "@/lib/imfSeriesDisplayTitle";
import { ecbSeriesDisplayTitle, normalizeEcb2BrowseBucket } from "@/lib/ecbSeriesDisplayTitle";
import { extractCountryCodesFromFilters } from "@/lib/sourcePreviewCountry";

import { resolveImfFreqOptions, normalizeImfFreq } from "@/lib/imfCatalogFreq";

import {
  CATALOGS,
  CATALOGS_DEFAULT_SELECTED_IDS,
  WB_DEFAULT_COUNTRY,
} from "@/lib/catalogDefinitions";
import { getCatalogDatabaseProfile } from "@/lib/catalogDatabaseProfiles";

import CatalogHubNav from "@/components/catalog/CatalogHubNav";


import CatalogExplorerPanel from "@/components/catalog/search/CatalogExplorerPanel";

import CatalogResultCard, { catalogResultMetaFromRow } from "@/components/catalog/search/CatalogResultCard";
import { CatalogSetDeepSearchHeader, SavedVisualCatalogCard } from "@/components/catalog/search/CatalogSetCardShell";
import CatalogSetMetadataBadges from "@/components/catalog/search/CatalogSetMetadataBadges";
import { catalogSeriesDetailMetadataItems, chartDisplayStatesEqual } from "@/lib/catalogSeriesDetailMetadata";
import { resolveSafeAppPath } from "@/lib/safeAppNavigation";
import { canRenderAradCatalogChart } from "@/lib/mapCatalogPreviewToArad";
import CatalogResultsToolbar from "@/components/catalog/search/CatalogResultsToolbar";
import CatalogDetailView from "@/components/catalog/search/CatalogDetailView";
import CatalogDeepSearchResultsPanel from "@/components/catalog/search/CatalogDeepSearchResultsPanel";
import CatalogWebFallbackSection from "@/components/catalog/search/CatalogWebFallbackSection";
import CatalogDeepSearchPlanPanel from "@/components/catalog/search/CatalogDeepSearchPlanPanel";
import CatalogPipelineDiagnosticsPanel from "@/components/catalog/search/CatalogPipelineDiagnosticsPanel";
import {
  CatalogAiSearchFeedback,
  CatalogClassicEmptyState,
  CatalogSearchStatusBanners,
  CatalogStubsLoadErrorBanner,
} from "@/components/catalog/search/CatalogSearchStatusPanels";
import ResponsiveCatalogLayout from "@/components/catalog/search/ResponsiveCatalogLayout";
import { buildCatalogExplorerColumns, buildExplorerBreadcrumbItems, explorerRowIsCategory } from "@/lib/catalogColumnExplorer";
import { ecb2CountryBranchHasFlowChildren, resolveEcb2BrowseLazyAction } from "@/lib/catalogEcb2Browse";
import { resolveExplorerLoadingRowKey } from "@/lib/catalogExplorerLoading";
import { enrichCsuCatalogRow } from "@/lib/catalogCsuPreview";
import {
  rebaseData360CountryNodePaths,
  resolveData360BrowseLazyAction,
} from "@/lib/catalogData360Browse";
import {
  BIS_BROWSER_TIMEOUT_MS,
  rebaseBisSeriesNodePaths,
  resolveBisBrowseLazyAction,
} from "@/lib/catalogBisBrowse";
import {
  FRED_BROWSER_TIMEOUT_MS,
  rebaseFredExpandNodePaths,
  resolveFredBrowseLazyAction,
} from "@/lib/catalogFredBrowse";
import { useCatalogResponsiveLayout } from "@/hooks/catalogSearch/useCatalogResponsiveLayout";
import { useCatalogDeepSearchViewModel } from "@/hooks/catalogSearch/useCatalogDeepSearchViewModel";
import { isCatalogRowPreviewEligible } from "@/lib/catalogRowPreviewEligible";
import {
  catalogRowHasBrowseFallback,
  resolveBisFlowHint,
  resolveCatalogRowPrimaryAction,
} from "@/lib/catalogRowPrimaryAction";
import { resolveBisDataflowPreviewRow } from "@/lib/bisResolveSearchPreviewRow";
import BisDimensionWizardModal from "@/components/bis/BisDimensionWizardModal";

import { eurostatAiRowNeedsOpenInCatalog } from "@/lib/eurostatQueryableSlice";
import { buildCatalogAddSourceBody } from "@/lib/catalogAddSourceBody";
import { buildExistingKeys, buildSourceByKey, rowExistingKey } from "@/lib/catalogStubKeys";
import { buildCatalogPreviewBody, resolveCatalogRowDef } from "@/lib/catalogPreviewBody";
import { buildCatalogPreviewRequestBody } from "@/lib/catalogLivePreview";
import { runInAppSearch } from "@/lib/inAppSearch";
import { runStockSearch } from "@/lib/stockSearch";
import {
  getCatalogBrowseSemantics,
  getCatalogBrowseHintCz,
  getCatalogBrowseLimitedActionHint,
} from "@/lib/catalogBrowseSemantics";
import {
  buildPreviewPayloadFromStructuredError,
  buildUnknownPreviewShapeMessage,
  formatPreviewMessage,
  normalizePreviewPayload,
  previewShapeDebug,
  unwrapApiErrorPayload,
} from "@/lib/previewNormalizer";
import { buildSourcePreviewParams } from "@/lib/previewRequestParams";
import {
  GLOBAL_CATALOG_BROWSE_UI_TIMEOUT_MS,
  GLOBAL_CATALOG_BROWSE_HTTP_TIMEOUT_MS,
  buildGlobalBrowseTimeoutHeadlineCz,
  buildGlobalBrowseTimeoutNextStepCz,
  shouldSkipUnifiedGlobeBrowseFetch,
  UNIFIED_GLOBAL_BROWSE_SKIP_CZ,
} from "@/lib/catalogBrowseStatusRegistry";
import { normalizeCatalogBrowseIdFromUrlParam } from "@/lib/catalogBackNav";
import {
  CATALOG_HEADER_FILTERS_EVENT,
  loadCatalogHeaderFilters,
  saveCatalogHeaderFilters,
} from "@/lib/catalogHeaderFilters";
import { CATALOG_HEADER_BROWSE_TOGGLE_EVENT } from "@/lib/catalogHeaderSearch";
import { resolveCatalogShareId } from "@/lib/catalogChartShare";
import { getAradCatalogRescueNotice } from "@/lib/aradCatalogRescueNotice";
import CatalogSearchErrorBoundary from "@/components/catalog/CatalogSearchErrorBoundary";
import CatalogDeepSearchLoader from "@/components/catalog/CatalogDeepSearchLoader";
import CatalogSearchPathNav from "@/components/catalog/CatalogSearchPathNav";
import { resolveCatalogCategoryPathPrefixes } from "@/lib/catalogSearchPathNav";
import {
  useCatalogSearchState,
  AI_SEARCH_SCOPE_EXTENDED,
  AI_SEARCH_SCOPE_QUICK,
} from "@/hooks/catalogSearch/useCatalogSearchState";
import { useCatalogBrowseState } from "@/hooks/catalogSearch/useCatalogBrowseState";
import { useDeepSearchRunner } from "@/hooks/catalogSearch/useDeepSearchRunner";
import {
  multiSearchPayloadFromFinal,
  runCatalogMultiSearchSseStream,
} from "@/hooks/catalogSearch/useCatalogMultiSseStream";
import { useSearchResultsMerge } from "@/hooks/catalogSearch/useSearchResultsMerge";
import {
  CATALOG_AI_QUICK_TIMEOUT_MS,
  CATALOG_DEEP_SEARCH_TIMEOUT_MS,
} from "@/lib/catalogDeepSearchClient";
import {
  normalizeSelectedIndicators,
  logCatalogPreviewDebug,
  eurostatDeepAiMessageLooksInternalDebug,
  deepCandidateHasUsablePreview,
  deepCandidateMayOpenUnverifiedPreview,
  deepAiDatabaseLabelCz,
  deepAiSourceStatusText,
  deepAiIndexProblemBannerMessageCz,
} from "@/lib/catalogGlobalSearchHelpers";
import {
  IMF_BROWSE_BETA_UNAVAILABLE_CZ,
  IMF_PREVIEW_UNAVAILABLE_CZ,
  OECD_BROWSE_BETA_UNAVAILABLE_CZ,
  CATALOG_EMPTY_BROWSE_CZ,
  CATALOG_DEEP_SEARCH_CHUNK_TIMEOUT_MS,
  CATALOG_DEEP_SEARCH_ESTIMATE_SEC,
  CATALOG_AI_QUICK_ESTIMATE_SEC,
  IN_APP_SEARCH_ID,
  CATALOG_BROWSE_FETCH_CONCURRENCY,
  isCategoryLikeSearchHit,
  DEEP_SOURCE_ORDER,
  GENERIC_AI_INDEX_NO_ROWS_CZ,
  EUROSTAT_QUERY_REFINEMENT_HINT_CZ,
  EUROSTAT_DEEP_AI_HINT_HAS_REF_CZ,
  EUROSTAT_DEEP_AI_OPEN_CATALOG_DIMS_CZ,
  EUROSTAT_DEEP_AI_PREVIEW_UNAVAILABLE_CZ,
  buildBrowseErrorTechnicalLines,
  browseLoadingPrimaryLabel,
  DEFAULT_CLASSIC_SEARCH_CATALOG_ID,
  looksLikeCatalogRefineSearch,
  looksLikeTopicSearch,
  resolveFollowupActionHint,
} from "./globalCatalogSearchConstants";
import {
  InlineLoadingDots,
  CatalogBrowseFallbackLinks,
  DeepResultTierBadge,
  SeriesLifecycleBadge,
  DeepSearchCandidateResultCard,
  EurostatDeepAiTechnicalDetails,
} from "./globalCatalogSearchDeepResultUi";

function isDeepSearchCachedPreviewUsable(payload) {
  if (!payload || typeof payload !== "object") return false;
  const rows = Array.isArray(payload.rows) ? payload.rows : [];
  const rowCountRaw = payload.total_count ?? payload.metadata?.row_count ?? rows.length;
  const rowCount = Number.isFinite(Number(rowCountRaw)) ? Number(rowCountRaw) : rows.length;
  const state = String(payload.preview_state || payload.metadata?.preview_state || "").trim().toLowerCase();
  if (["sync_failed", "unsupported", "no_data"].includes(state)) return false;
  return rows.length >= 2 || rowCount >= 2;
}

function toFollowupSeriesRef(row) {
  const source = row?.source_type || row?.catalog_id || "";
  const setId = row?.set_id || row?.series_id || "";
  return {
    ref_id: row?.key || `${source}|${setId}`,
    source_type: row?.source_type,
    catalog_id: row?.catalog_id,
    set_id: setId,
    title: row?.title,
    territory: row?.territory || "",
    query_params: row?.query_params || {},
    description: row?.description || row?.note || row?.subtitle || "",
    frequency: row?.frequency || row?.freq || "",
    unit: row?.unit || row?.units || row?.unit_label || "",
  };
}

export default function GlobalCatalogSearchPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const location = useLocation();
  const [params] = useSearchParams();
  /** `?catalogDebug=1` — zobrazí např. ARAD `match_reason` u výsledků globálního hledání v katalogu. */
  const catalogFromUrl = useMemo(() => (params.get("catalog") || "").trim().toLowerCase(), [params]);
  const setIdFromUrl = useMemo(() => (params.get("set_id") || "").trim(), [params]);
  const previewFromUrl = useMemo(() => (params.get("preview") || "").trim() === "1", [params]);
  const indicatorFromUrl = useMemo(() => (params.get("indicator_id") || "").trim(), [params]);
  const catalogBrowseIdFromUrl = useMemo(
    () => normalizeCatalogBrowseIdFromUrlParam(catalogFromUrl),
    [catalogFromUrl],
  );
  const { user, isAdmin, ready, isSubscriber } = useAuth();
  const canAddSources = Boolean(ready && isAdmin);
  const { allowed: canExportData, message: exportDataLockMsg, loading: exportFeLoading } =
    useFeatureAccess("export_data");
  const {
    allowed: canPersonalDashboard,
    message: personalDashMsg,
    loading: personalDashLoading,
  } = useFeatureAccess("personal_dashboard");
  const {
    allowed: canSaveWidget,
    message: saveWidgetMsg,
    loading: saveWidgetLoading,
  } = useFeatureAccess("save_widget");
  useFeatureAccess("catalog_deep_search");
  const [sources, setSources] = useState([]);
  const [catalogStubsError, setCatalogStubsError] = useState("");
  const [stubsFetchNonce, setStubsFetchNonce] = useState(0);
  const initialCatalogQ = params.get("q") || "";
  const searchState = useCatalogSearchState({
    initialCatalogQ,
    initialSelectedSet: CATALOGS_DEFAULT_SELECTED_IDS,
  });
  const {
    selected,
    setSelected,
    crossSearchQuery,
    setCrossSearchQuery,
    submittedCrossQuery,
    setSubmittedCrossQuery,
    crossSearchSubmitNonce,
    submitCrossSearch,
    aiQuery,
    setAiQuery,
    debouncedAi,
    useAiAssistant,
    setUseAiAssistant,
    aiSearchScope,
    quickAiDbId,
  } = searchState;
  const setUnifiedQuery = useCallback(
    (value) => {
      setCrossSearchQuery(value);
      setAiQuery(value);
    },
    [setCrossSearchQuery, setAiQuery],
  );
  const unifiedQuery = crossSearchQuery;
  /** Scope klasického hledání — vždy konkrétní katalog (bez „všechny katalogy“). */
  const [classicSearchScope, setClassicSearchScope] = useState(DEFAULT_CLASSIC_SEARCH_CATALOG_ID);
  const [wbCountry, setWbCountry] = useState(WB_DEFAULT_COUNTRY);
  const [trees, setTrees] = useState({});
  /** Plochý index řádků stromu po katalogu — u velkých API odpovědí se plní asynchronně (neblokuje UI). */
  const [indexedRowsByCat, setIndexedRowsByCat] = useState({});
  const [loadingCats, setLoadingCats] = useState({});
  const [errors, setErrors] = useState({});
  const flattenGenRef = useRef({});
  /** GET /browse katalog — zruší předchozí volání pro stejný id při novém pokusu i při IMF/OECD total UI timeout. */
  const browseAbortByCatRef = useRef({});
  /** Aktuální snapshot stromů pro efekty bez závislosti na `trees` (sousední doplnění katalogů nesmí opakovaně pouštět load browse). */
  const treesRef = useRef({});
  const errorsRef = useRef({});
  const loadingCatsRef = useRef({});
  const teCountryLoadingRef = useRef(new Set());
  const teCountryLoadedRef = useRef(new Set());
  const [teLoadingCountries, setTeLoadingCountries] = useState(() => new Set());
  const ecbCountryLoadingRef = useRef(new Set());
  const ecbCountryLoadedRef = useRef(new Set());
  const ecbCountryLoadQueueRef = useRef(Promise.resolve());
  const ecbFlattenGenRef = useRef(0);
  const ecbAvailabilityRevisionRef = useRef("");
  const [ecbLoadingCountries, setEcbLoadingCountries] = useState(() => new Set());
  const ecb2CountryLoadingRef = useRef(new Set());
  const ecb2CountryLoadedRef = useRef(new Set());
  const ecb2CountryLoadQueueRef = useRef(Promise.resolve());
  const ecb2FlowLoadingRef = useRef(new Set());
  const ecb2FlowLoadedRef = useRef(new Set());
  const ecb2FlowLoadQueueRef = useRef(Promise.resolve());
  const ecb2LetterLoadingRef = useRef(new Set());
  const ecb2LetterLoadedRef = useRef(new Set());
  const ecb2LetterLoadQueueRef = useRef(Promise.resolve());
  const ecb2FlattenGenRef = useRef(0);
  const ecb2AvailabilityRevisionRef = useRef("");
  const [ecb2LoadingCountries, setEcb2LoadingCountries] = useState(() => new Set());
  const [ecb2LoadingFlows, setEcb2LoadingFlows] = useState(() => new Set());
  const [ecb2LoadingLetters, setEcb2LoadingLetters] = useState(() => new Set());
  const imf2CountryLoadingRef = useRef(new Set());
  const imf2CountryLoadedRef = useRef(new Set());
  const imf2CountryLoadQueueRef = useRef(Promise.resolve());
  const imf2FlattenGenRef = useRef(0);
  const imf2AvailabilityRevisionRef = useRef("");
  const [imf2LoadingCountries, setImf2LoadingCountries] = useState(() => new Set());
  const oecd2CountryLoadingRef = useRef(new Set());
  const oecd2CountryLoadedRef = useRef(new Set());
  const oecd2CountryLoadQueueRef = useRef(Promise.resolve());
  const oecd2FlattenGenRef = useRef(0);
  const oecd2AvailabilityRevisionRef = useRef("");
  const [oecd2LoadingCountries, setOecd2LoadingCountries] = useState(() => new Set());
  const oecd4DatasetLoadingRef = useRef(new Set());
  const oecd4DatasetLoadedRef = useRef(new Set());
  const oecd4DatasetLoadQueueRef = useRef(Promise.resolve());
  const oecd4CountryLoadingRef = useRef(new Set());
  const oecd4CountryLoadedRef = useRef(new Set());
  const oecd4CountryLoadQueueRef = useRef(Promise.resolve());
  const oecd4FlattenGenRef = useRef(0);
  const [oecd4LoadingDatasets, setOecd4LoadingDatasets] = useState(() => new Set());
  const [oecd4LoadingCountries, setOecd4LoadingCountries] = useState(() => new Set());
  const wbCountryLoadingRef = useRef(new Set());
  const wbCountryLoadedRef = useRef(new Set());
  const [wbLoadingCountries, setWbLoadingCountries] = useState(() => new Set());
  const d360CountryLoadingRef = useRef(new Set());
  const d360CountryLoadedRef = useRef(new Set());
  const [d360LoadingCountries, setD360LoadingCountries] = useState(() => new Set());
  const bisDataflowLoadingRef = useRef(new Set());
  const bisDataflowLoadedRef = useRef(new Set());
  const bisCountryLoadingRef = useRef(new Set());
  const bisCountryLoadedRef = useRef(new Set());
  const bisActiveDataflowRef = useRef("");
  const [bisLoadingDataflows, setBisLoadingDataflows] = useState(() => new Set());
  const [bisLoadingCountries, setBisLoadingCountries] = useState(() => new Set());
  const [bisWizardSearch, setBisWizardSearch] = useState(null);
  const bisDataflowPreviewActiveRef = useRef(false);
  const fredCategoryLoadingRef = useRef(new Set());
  const fredCategoryLoadedRef = useRef(new Set());
  const [fredLoadingCategories, setFredLoadingCategories] = useState(() => new Set());
  const imfCountryLoadingRef = useRef(new Set());
  const imfCountryLoadedRef = useRef(new Set());
  const imfAvailabilityRevisionRef = useRef("");
  const [imfLoadingCountries, setImfLoadingCountries] = useState(() => new Set());
  const [adding, setAdding] = useState({});
  // Inline „rozkliknutý" náhled — `previewKey` je `${def.id}-${row.set_id}`,
  // `previewData` drží odpověď z /api/catalog/preview, `previewLoading` /
  // `previewError` slouží UI. `downloadingFmt` indikuje právě probíhající
  // export (CSV/XLSX/JSON), aby se stejným set_id nezobrazilo víc spinnerů.
  const [previewKey, setPreviewKey] = useState(null);
  /** Řádek otevřeného náhledu (pro fullscreen overlay ve výsledcích hledání). */
  const [previewTarget, setPreviewTarget] = useState(null);
  const [previewData, setPreviewData] = useState(null);
  /** Řádek po rozlišení ECB `FLOW/SERIES_KEY` (pro náhled a stahování). */
  const [previewEffectiveRow, setPreviewEffectiveRow] = useState(null);
  const [previewSelectedIndicators, setPreviewSelectedIndicators] = useState([]);
  const [previewCompareList, setPreviewCompareList] = useState([]);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const normalizedPreviewError = useMemo(() => formatPreviewMessage(previewError), [previewError]);
  const [previewFallbackNotice, setPreviewFallbackNotice] = useState("");
  const [downloadingFmt, setDownloadingFmt] = useState("");
  const [downloadOpen, setDownloadOpen] = useState(false);
  const [syncingPreview, setSyncingPreview] = useState(false);
  /** IMF náhled v globálním katalogu — M/Q/A přepínač (živé API, ne jen badge). */
  const [imfPreviewFreq, setImfPreviewFreq] = useState("A");
  const browseState = useCatalogBrowseState(DEFAULT_CLASSIC_SEARCH_CATALOG_ID);
  const {
    browseCatalogId,
    setBrowseCatalogId,
    browsePanelFilter,
    setBrowsePanelFilter,
    browseLocalBranchOnly,
    setBrowseLocalBranchOnly,
    debouncedBrowsePanelFilter,
    setDebouncedBrowsePanelFilter,
    openPaths,
    setOpenPaths,
  } = browseState;
  const lastBrowseCatalogIdRef = useRef(browseCatalogId);
  /** Číslo při přepnutí katalogu v „Procházet“ — spolu s AbortController omezí řádky řešící starý request po rychlé navigaci IMF↔OECD. */
  const browseCatalogSwitchSeqRef = useRef(0);
  /** Omezit opakovaný console.debug u stejné chyby na zdroj (max. 1× / podpis / krátký interval). */
  const browseErrLogRef = useRef({});
  /** Jednou za výběr katalogu v „Procházet“ inicializovat rozbalené kořeny; ne resetovat při každé změně reference `trees[id]` (setTrees přes transition / opakované objekty ze sítě). */
  const browseTreeRootsSeededRef = useRef(null);
  const browseTreeSectionRef = useRef(null);
  const pendingBrowseTreePathRef = useRef(null);
  const [addingToDash, setAddingToDash] = useState(false);
  /** { built: { title, config }, pages, selectedId } | null */
  const [pagePick, setPagePick] = useState(null);
  /** Jednorázové zpracování ?runDeep=1 — bez ref hodí efekt výjimku a může rozbít stav hydratace. */
  const autoDeepHandledRef = useRef(false);
  /** Poslední dotaz, pro který se automaticky spustila deep AI rešerše z URL (runDeep i sdílený
   *  ?q=…&ai=1). Sdílený mezi runDeep efektem a efektem pro přímý/sdílený odkaz, aby se stejný
   *  dotaz nespustil dvakrát. */
  const lastDeepRunQueryRef = useRef("");
  // Klíč posledně otevřeného deep-link náhledu (set_id|indicator). Dřív to byl boolean
  // (otevři jednou za mount) → opakovaný klik na jiný návrh v našeptávači už náhled
  // neotevřel. Klíč umožní re-open při změně set_id, ale brání nekonečnému re-runu.
  const sharePreviewOpenedRef = useRef("");
  /** Deep link ?preview=1&set_id=… — náhled mimo výsledky hledání / strom. */
  const [, setSharePreviewTarget] = useState(null);
  /** Z URL ?ai=1 — zvýraznění sekce „AI asistent pro hledání dat“ po příchodu z horního panelu. */
  const [aiSectionHighlight, setAiSectionHighlight] = useState(false);
  const [browseSidebarOpen, setBrowseSidebarOpen] = useState(true);
  const [mobileBrowseOpen, setMobileBrowseOpen] = useState(false);
  const [browseColumnSelection, setBrowseColumnSelection] = useState([]);
  const [resultsViewMode, setResultsViewMode] = useState("table");
  const [resultsSortMode, setResultsSortMode] = useState("relevance");
  const [seriesDetailOpen, setSeriesDetailOpen] = useState(false);
  const [seriesDetailTarget, setSeriesDetailTarget] = useState(null);
  const [seriesDetailChartDisplayState, setSeriesDetailChartDisplayState] = useState(null);
  const [catalogViewMode, setCatalogViewMode] = useState("browse");
  const [catalogChartExpanded, setCatalogChartExpanded] = useState(false);
  const selectedExternalCatalogs = useMemo(
    () => new Set([...selected].filter((id) => id !== IN_APP_SEARCH_ID)),
    [selected],
  );
  // Přeložení aiScope z URL parametrů na scope/selected pro deep search.
  // ?aiScope=selected → extended (prohledávat jen zaškrtnuté zdroje)
  // ?scope=<catalogId> → extended + jen tento zdroj
  // (nic) → quick (backend AI router vybere zdroje sám)
  const _urlAiScopeParam = params.get("aiScope");
  const _urlScopeParam = normalizeCatalogBrowseIdFromUrlParam(String(params.get("scope") || "").trim());
  const _isSingleSourceScope = Boolean(_urlScopeParam && CATALOGS.some((c) => c.id === _urlScopeParam));
  const effectiveAiSearchScope = useMemo(
    () => (_isSingleSourceScope || _urlAiScopeParam === "selected" ? AI_SEARCH_SCOPE_EXTENDED : AI_SEARCH_SCOPE_QUICK),
    [_isSingleSourceScope, _urlAiScopeParam],
  );
  const effectiveDeepSelected = useMemo(
    () => (_isSingleSourceScope ? new Set([_urlScopeParam]) : selectedExternalCatalogs),
    [_isSingleSourceScope, _urlScopeParam, selectedExternalCatalogs],
  );
  const {
    deepLoading,
    deepError,
    deepErrorTechnical,
    deepData,
    deepSourceStatuses,
    deepActiveSourceIds,
    deepFollowupLoading,
    deepFollowupError,
    deepChatFilteredIds,
    clearDeepChatFilter,
    deepConversation,
    deepLaneResults,
    deepStreamAwaitingFinal,
    runDeepSearch,
    runDeepSearchExtended,
    applySuggestedDeepSearch,
    cancelDeepSearch,
    runDeepFollowup,
    setDeepData,
    setDeepError,
    setDeepErrorTechnical,
    setDeepSourceStatuses,
    setDeepConversation,
    setDeepFollowupResult,
    setDeepFollowupError,
  } = useDeepSearchRunner({
    aiQuery,
    selected: effectiveDeepSelected,
    useAiAssistant,
    aiSearchScope: effectiveAiSearchScope,
    quickAiDbId,
    deepSourceOrder: DEEP_SOURCE_ORDER,
    deepSourceLabel: deepAiDatabaseLabelCz,
    chunkTimeoutMs: CATALOG_DEEP_SEARCH_CHUNK_TIMEOUT_MS,
    totalTimeoutMs: CATALOG_DEEP_SEARCH_TIMEOUT_MS,
  });

  const { mergeDeepResults } = useSearchResultsMerge();
  const catalogAiSectionRef = useRef(null);
  const lastAiScrollSearchRef = useRef("");
  const [followupInput, setFollowupInput] = useState("");
  const [followupMessages, setFollowupMessages] = useState([]);
  const [followupAvailableSeriesRefs, setFollowupAvailableSeriesRefs] = useState([]);
  const followupRootQueryRef = useRef("");
  const followupInputRef = useRef(null);
  const [catalogGlobResults, setCatalogGlobResults] = useState(null);
  const [catalogGlobLoading, setCatalogGlobLoading] = useState(false);
  const [catalogGlobError, setCatalogGlobError] = useState("");
  const [browseSearchAcrossSelected, setBrowseSearchAcrossSelected] = useState(false);
  const [browseSearchCategoriesOnly, setBrowseSearchCategoriesOnly] = useState(false);
  const [browseExcludedCatalogIds, setBrowseExcludedCatalogIds] = useState(() => new Set());
  const [catalogMultiResults, setCatalogMultiResults] = useState(null);
  const [catalogMultiLoading, setCatalogMultiLoading] = useState(false);
  const [catalogMultiError, setCatalogMultiError] = useState("");
  const [catalogMultiLaneResults, setCatalogMultiLaneResults] = useState({});
  const [catalogMultiStreamAwaitingFinal, setCatalogMultiStreamAwaitingFinal] = useState(false);
  /** Serverové cross-search (POST /catalog/search) — primární zdroj výsledků v hlavním panelu. */
  const [crossSearchBackendHits, setCrossSearchBackendHits] = useState(null);
  const [crossSearchBackendSummaries, setCrossSearchBackendSummaries] = useState([]);
  /** Katalogy skryté štítky nad výsledky klasického hledání. */
  const [crossSearchExcludedCatalogIds, setCrossSearchExcludedCatalogIds] = useState(() => new Set());
  /** Katalogy skryté štítky nad výsledky AI hledání. */
  const [deepSearchExcludedCatalogIds, setDeepSearchExcludedCatalogIds] = useState(() => new Set());
  const [crossSearchPartialIndexMissing, setCrossSearchPartialIndexMissing] = useState(false);
  const [crossSearchBackendLoading, setCrossSearchBackendLoading] = useState(false);
  const [crossSearchBackendAttempted, setCrossSearchBackendAttempted] = useState(false);
  const [crossSearchBackendAllFailed, setCrossSearchBackendAllFailed] = useState(false);
  const [crossSearchBackendError, setCrossSearchBackendError] = useState("");
  const [inAppSearchResults, setInAppSearchResults] = useState([]);
  const [inAppSearchLoading, setInAppSearchLoading] = useState(false);
  const [inAppSearchError, setInAppSearchError] = useState("");
  /** Nezávislý paralelní dotaz na /stocks/search — vlastní backend, vlastní skupina ve výsledcích. */
  const [stockSearchResults, setStockSearchResults] = useState([]);

  /** Nové AI hledání = reset skrytých zdrojů ve výsledcích. */
  useEffect(() => {
    if (deepLoading) setDeepSearchExcludedCatalogIds(new Set());
  }, [deepLoading]);

  // Zahřej katalogové indexy hned při příchodu na stránku — dřív než uživatel napíše dotaz.
  useEffect(() => {
    api.get("/catalog/warmup", { timeout: 5000 }).catch(() => {});
  }, []);

  /** Nový dotaz = reset logů chyb browse (jedna zpráva na zdroj / průběh hledání). */
  useEffect(() => {
    browseErrLogRef.current = {};
  }, [submittedCrossQuery]);

  useEffect(() => {
    treesRef.current = trees;
  }, [trees]);

  useEffect(() => {
    errorsRef.current = errors;
  }, [errors]);

  useEffect(() => {
    loadingCatsRef.current = loadingCats;
  }, [loadingCats]);

  useEffect(() => {
    if (!catalogBrowseIdFromUrl) return;
    const def = CATALOGS.find((c) => c.id === catalogBrowseIdFromUrl);
    if (!def) return;
    setBrowseCatalogId((prev) => (prev === def.id ? prev : def.id));
    setClassicSearchScope(def.id);
    setSelected((s) => {
      if (s.has(def.id)) return s;
      const next = new Set(s);
      next.add(def.id);
      return next;
    });
  }, [catalogBrowseIdFromUrl]);

  useEffect(() => {
    const prev = lastBrowseCatalogIdRef.current;
    if (prev !== browseCatalogId) {
      browseCatalogSwitchSeqRef.current += 1;
      browseAbortByCatRef.current[prev]?.abort();
      lastBrowseCatalogIdRef.current = browseCatalogId;
    }
  }, [browseCatalogId]);

  useEffect(() => {
    let cancelled = false;
    setCatalogStubsError("");
    api
      .get("/sources/catalog-stubs", { timeout: 20000 })
      .then(({ data }) => {
        if (cancelled) return;
        setSources(Array.isArray(data) ? data : []);
      })
      .catch((err) => {
        if (cancelled) return;
        setSources([]);
        const nf = normalizeApiFailure(err);
        setCatalogStubsError(nf.message || "Katalog se nepodařilo načíst.");
      });
    return () => {
      cancelled = true;
    };
  }, [stubsFetchNonce]);

  const sourcesList = useMemo(() => (Array.isArray(sources) ? sources : []), [sources]);

  /** Klasické hledání jen po Enter / Hledat — ne při psaní. */
  const searchReady = submittedCrossQuery.trim().length >= 2;
  const inAppSearchSelected = selected.has(IN_APP_SEARCH_ID) || classicSearchScope === IN_APP_SEARCH_ID;

  const classicSearchCatalogDefs = useMemo(
    () => resolveClassicSearchCatalogDefs(CATALOGS, classicSearchScope, selected),
    [classicSearchScope, selected],
  );

  const existingByCat = useMemo(() => {
    const m = {};
    for (const def of CATALOGS) {
      m[def.id] = buildExistingKeys(def, sourcesList);
    }
    return m;
  }, [sourcesList]);

  const sourceByKeyByCat = useMemo(() => {
    const m = {};
    for (const def of CATALOGS) {
      m[def.id] = buildSourceByKey(def, sourcesList);
    }
    return m;
  }, [sourcesList]);

  const loadCatalog = useCallback(async (def) => {
    browseAbortByCatRef.current[def.id]?.abort();
    if (shouldSkipUnifiedGlobeBrowseFetch(def)) {
      const msg =
        UNIFIED_GLOBAL_BROWSE_SKIP_CZ[def.id] ||
        "Tento zdroj se v globálním katalogu neprohlíží jako jednotný strom — použijte dedikovanou stránku nebo vyhledávání níže.";
      setErrors((prev) => ({ ...prev, [def.id]: msg }));
      setIndexedRowsByCat((prev) => ({ ...prev, [def.id]: [] }));
      if (def.id === "ecb2") {
        ecb2CountryLoadedRef.current.clear();
        ecb2FlowLoadedRef.current.clear();
        ecb2LetterLoadedRef.current.clear();
      }
      startTransition(() =>
        setTrees((t) => ({
          ...t,
          [def.id]: {
            unified_browse_skipped: true,
            categories: [],
            source: "unified_catalog_skipped",
          },
        }))
      );
      setLoadingCats((prev) => ({ ...prev, [def.id]: false }));
      return;
    }
    const controller = new AbortController();
    browseAbortByCatRef.current[def.id] = controller;
    const { signal } = controller;

    const gen = (flattenGenRef.current[def.id] || 0) + 1;
    flattenGenRef.current[def.id] = gen;
    setLoadingCats((prev) => ({ ...prev, [def.id]: true }));
    setErrors((e) => ({ ...e, [def.id]: null }));
    setIndexedRowsByCat((p) => {
      const next = { ...p };
      delete next[def.id];
      return next;
    });

    const axiosTmo = def.browseHttpTimeoutMs ?? GLOBAL_CATALOG_BROWSE_HTTP_TIMEOUT_MS;
    const uiTmo = def.browseUiTimeoutMs ?? GLOBAL_CATALOG_BROWSE_UI_TIMEOUT_MS;
    const isImfOecdBrowsable = def.id === "imf" || def.id === "oecd";

    if (process.env.NODE_ENV === "development") {
      console.debug(
        `[catalog-browse] ${def.id} GET ${String(def.catalogPath)} axiosTimeout=${axiosTmo}ms ` +
          `totalUIMax=${uiTmo}ms`
      );
    }

    const t0 =
      typeof performance !== "undefined" && typeof performance.now === "function"
        ? performance.now()
        : Date.now();

    /** Zneplatní async pokračování po total UI timeoutu (např. zploštění). */
    const bumpGenerationIfStale = () => {
      if (flattenGenRef.current[def.id] === gen) flattenGenRef.current[def.id] += 1;
    };

    /** Když zploštění nebo síťovka doběhnou později navzdory invalidate — nic neaplikuj. */
    const stale = () => flattenGenRef.current[def.id] !== gen;
    const browseSwitchSnapshot = browseCatalogSwitchSeqRef.current;

    const uiTimeoutReject = Object.assign(new Error("browse total ui timeout"), {
      browseTotalUiTimeout: true,
      browseUiTimeoutMs: uiTmo,
    });

    try {
      const browsePipeline = async () => {
        const { data } = await api.get(def.catalogPath, {
          timeout: axiosTmo,
          signal,
        });
        if (stale()) return null;

        if (def.id === "ecb") {
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && ecbAvailabilityRevisionRef.current
            && rev !== ecbAvailabilityRevisionRef.current
          ) {
            ecbCountryLoadedRef.current.clear();
          }
          if (rev) ecbAvailabilityRevisionRef.current = rev;
        }
        if (def.id === "ecb2") {
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && ecb2AvailabilityRevisionRef.current
            && rev !== ecb2AvailabilityRevisionRef.current
          ) {
            ecb2CountryLoadedRef.current.clear();
            ecb2FlowLoadedRef.current.clear();
            ecb2LetterLoadedRef.current.clear();
          }
          if (rev) ecb2AvailabilityRevisionRef.current = rev;
        }
        if (def.id === "imf2") {
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && imf2AvailabilityRevisionRef.current
            && rev !== imf2AvailabilityRevisionRef.current
          ) {
            imf2CountryLoadedRef.current.clear();
          }
          if (rev) imf2AvailabilityRevisionRef.current = rev;
        }
        if (def.id === "oecd2") {
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && oecd2AvailabilityRevisionRef.current
            && rev !== oecd2AvailabilityRevisionRef.current
          ) {
            oecd2CountryLoadedRef.current.clear();
          }
          if (rev) oecd2AvailabilityRevisionRef.current = rev;
        }
        if (def.id === "imf") {
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && imfAvailabilityRevisionRef.current
            && rev !== imfAvailabilityRevisionRef.current
          ) {
            imfCountryLoadedRef.current.clear();
          }
          if (rev) imfAvailabilityRevisionRef.current = rev;
        }

        const cats = data.categories || [];

        async function flattenRowsInner() {
          if (!cats.length) return [];
          return flattenCatalogCategoriesBestEffort(cats);
        }

        const rows = await flattenRowsInner();
        if (stale()) return null;
        return { data, rows };
      };

      const pipelinePromise = browsePipeline();

      let uiTimerId = null;
      let result;
      try {
        result = await Promise.race([
          pipelinePromise,
          new Promise((_, rej) => {
            uiTimerId = window.setTimeout(() => {
              controller.abort();
              rej(uiTimeoutReject);
            }, uiTmo);
          }),
        ]);
      } catch (raceErr) {
        void pipelinePromise.catch(() => {});
        if (raceErr?.browseTotalUiTimeout) bumpGenerationIfStale();
        throw raceErr;
      } finally {
        if (uiTimerId !== null) window.clearTimeout(uiTimerId);
      }

      if (!result) return;

      if (browseCatalogSwitchSeqRef.current !== browseSwitchSnapshot) return;

      const { data, rows } = result;

      startTransition(() => {
        setTrees((t) => ({ ...t, [def.id]: data }));
      });
      setIndexedRowsByCat((p) => ({ ...p, [def.id]: rows }));
      if (def.id === "ecb2") {
        ecb2CountryLoadedRef.current.clear();
        ecb2FlowLoadedRef.current.clear();
        ecb2LetterLoadedRef.current.clear();
      }

      if (process.env.NODE_ENV === "development") {
        const cats = data.categories || [];
        const t1 =
          typeof performance !== "undefined" && typeof performance.now === "function"
            ? performance.now()
            : Date.now();
        console.debug(
          `[catalog-browse] ${def.id} ok ${Math.round(t1 - t0)}ms (categories=${cats.length}, rows=${rows.length})`
        );
      }
    } catch (err) {
      const nf = normalizeApiFailure(err);
      if (nf.isCanceled) {
        return;
      }

      const gc = flattenGenRef.current[def.id];
      if (gc !== gen && gc !== gen + 1) return;
      if (gc === gen + 1 && !err?.browseTotalUiTimeout && !err?.browseFlattenGuard) return;

      const errSig = `${nf.status ?? ""}:${(nf.message || "").slice(0, 96)}`;
      if (browseErrLogRef.current[def.id] !== errSig) {
        browseErrLogRef.current[def.id] = errSig;
        if (process.env.NODE_ENV === "development") {
          console.debug(`[catalog-browse] ${def.id} failed`, err);
        }
      }

      const technicalFmt = nf.message || formatApiErrorFromAxios(err);

      /** Total UI timeout: jednotná hlavička + další krok + technické detaily. */
      if (err?.browseTotalUiTimeout) {
        const user = [buildGlobalBrowseTimeoutHeadlineCz(def, uiTmo), buildGlobalBrowseTimeoutNextStepCz(def)].join(
          "\n\n"
        );
        const techBlock = `\n\n${buildBrowseErrorTechnicalLines(def, axiosTmo, err)}`;
        setErrors((e) => ({
          ...e,
          [def.id]: `${user}${techBlock}`,
        }));
      } else if (err?.browseFlattenGuard) {
        const betaPreamble = isImfOecdBrowsable
          ? def.id === "imf"
            ? IMF_BROWSE_BETA_UNAVAILABLE_CZ
            : OECD_BROWSE_BETA_UNAVAILABLE_CZ
          : "Zploštění stromu v prohlížeči přeteklo nastaveným limitem.";
        const parts = [betaPreamble, "Zkuste později nebo jiný katalog."];
        parts.push(buildBrowseErrorTechnicalLines(def, axiosTmo, err));
        const joined = parts.filter(Boolean).join("\n\n").trim();

        setErrors((e) => ({
          ...e,
          [def.id]: joined || technicalFmt || "Něco se pokazilo při načítání katalogu.",
        }));
      } else if (
        err?.response?.status === 503 &&
        def.id === "eurostat" &&
        err?.response?.data?.detail &&
        typeof err.response.data.detail === "object" &&
        typeof err.response.data.detail.message === "string"
      ) {
        const user = err.response.data.detail.message.trim();
        const tech = isAdmin ? `\n\n${buildBrowseErrorTechnicalLines(def, axiosTmo, err)}` : "";
        setErrors((e) => ({ ...e, [def.id]: `${user}${tech}`.trim() }));
      } else {
        const betaPreambleAll = isImfOecdBrowsable
          ? def.id === "imf"
            ? IMF_BROWSE_BETA_UNAVAILABLE_CZ
            : OECD_BROWSE_BETA_UNAVAILABLE_CZ
          : "";
        const parts = [];
        if (betaPreambleAll) parts.push(betaPreambleAll);
        if (technicalFmt) parts.push(technicalFmt);
        parts.push(buildBrowseErrorTechnicalLines(def, axiosTmo, err));
        const joined = parts.filter(Boolean).join("\n\n").trim();

        setErrors((e) => ({
          ...e,
          [def.id]: joined || technicalFmt || "Něco se pokazilo při načítání katalogu.",
        }));
      }

      startTransition(() => {
        setTrees((t) => {
          const next = { ...t };
          delete next[def.id];
          return next;
        });
      });
      setIndexedRowsByCat((p) => {
        const next = { ...p };
        delete next[def.id];
        return next;
      });
    } finally {
      const g = flattenGenRef.current[def.id];
      if (g === gen || g === gen + 1) {
        setLoadingCats((prev) => ({ ...prev, [def.id]: false }));
      }
      if (browseAbortByCatRef.current[def.id] === controller) {
        delete browseAbortByCatRef.current[def.id];
      }
    }
  }, [isAdmin]);

  const retrySelectedCatalogLoads = useCallback(() => {
    const defs = CATALOGS.filter((def) => {
      if (!selected.has(def.id)) return false;
      if (shouldSkipUnifiedGlobeBrowseFetch(def)) return false;
      if (loadingCatsRef.current[def.id]) return false;
      return !treesRef.current[def.id] || Boolean(errorsRef.current[def.id]);
    });
    for (const def of defs) {
      void loadCatalog(def);
    }
  }, [selected, loadCatalog]);

  useEffect(() => {
    const pickBrowseCatalogFromSelection = () => {
      const scope = String(classicSearchScope || "").trim();
      if (
        scope &&
        scope !== CLASSIC_SEARCH_SCOPE_ALL &&
        selected.has(scope)
      ) {
        return scope;
      }
      return CATALOGS.find((c) => selected.has(c.id))?.id || "";
    };

    if (!browseCatalogId) {
      const nextId = pickBrowseCatalogFromSelection();
      if (nextId) {
        setBrowseCatalogId(nextId);
        if (classicSearchScope !== nextId) setClassicSearchScope(nextId);
      }
      return;
    }
    if (!selected.has(browseCatalogId)) {
      const nextId = pickBrowseCatalogFromSelection();
      setBrowseCatalogId(nextId);
      if (nextId) setClassicSearchScope(nextId);
    }
  }, [selected, browseCatalogId, classicSearchScope, setBrowseCatalogId]);

  /** Strom vybraného katalogu — načte se po výběru ve filtrech nebo v menu Katalog. */
  useEffect(() => {
    if (!browseCatalogId || !selected.has(browseCatalogId)) return;
    const def = CATALOGS.find((c) => c.id === browseCatalogId);
    if (!def || treesRef.current[def.id]) return;
    loadCatalog(def);
  }, [browseCatalogId, selected, loadCatalog]);

  useEffect(() => {
    if (!searchReady) return;
    let cancelled = false;
    (async () => {
      const defs = classicSearchCatalogDefs.filter((c) => !treesRef.current[c.id]);
      for (let i = 0; i < defs.length; i += CATALOG_BROWSE_FETCH_CONCURRENCY) {
        if (cancelled) return;
        const chunk = defs.slice(i, i + CATALOG_BROWSE_FETCH_CONCURRENCY);
        await Promise.allSettled(chunk.map((d) => loadCatalog(d)));
      }
    })().catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [searchReady, classicSearchCatalogDefs, loadCatalog]);

  useEffect(() => {
    browseTreeRootsSeededRef.current = null;
  }, [browseCatalogId]);

  const browseCategoryPathSet = useMemo(() => {
    const rows = indexedRowsByCat[browseCatalogId];
    if (!Array.isArray(rows) || !rows.length) return null;
    return new Set(
      rows
        .filter((r) => r.kind === "cat")
        .map((r) => String(r.path || "").trim())
        .filter(Boolean),
    );
  }, [browseCatalogId, indexedRowsByCat]);

  const applyResolvedPathsToTree = useCallback(
    (rawPath, rows) => {
      const path = String(rawPath || "").trim();
      if (!path) return false;
      const categoryPaths = (rows || [])
        .filter((r) => r.kind === "cat")
        .map((r) => String(r.path || "").trim())
        .filter(Boolean);
      const openList = resolveCatalogCategoryPathPrefixes(path, categoryPaths);
      if (!openList.length) {
        toast.message("Cestu ve stromu se nepodařilo najít — rozbalte kořen katalogu ručně.");
        return false;
      }
      setOpenPaths((prev) => {
        const next = new Set(prev);
        for (const p of openList) next.add(p);
        return next;
      });
      window.requestAnimationFrame(() => {
        browseTreeSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
      });
      return true;
    },
    [setOpenPaths],
  );

  useEffect(() => {
    const tree = trees[browseCatalogId];
    const rows = indexedRowsByCat[browseCatalogId];
    if (!tree?.categories?.length) return;

    const pending = pendingBrowseTreePathRef.current;
    if (pending?.catalogId === browseCatalogId && rows?.length) {
      applyResolvedPathsToTree(pending.path, rows);
      pendingBrowseTreePathRef.current = null;
      browseTreeRootsSeededRef.current = browseCatalogId;
      return;
    }

    if (browseTreeRootsSeededRef.current === browseCatalogId) return;
    if (pending?.catalogId === browseCatalogId) return;

    const top = new Set((tree.categories || []).map((c) => c.path));
    setOpenPaths(top);
    browseTreeRootsSeededRef.current = browseCatalogId;
  }, [browseCatalogId, trees, indexedRowsByCat, applyResolvedPathsToTree, setOpenPaths]);

  const catalogRowsStillIndexing = useCallback(
    (id) =>
      Boolean(trees[id]?.categories?.length) &&
      indexedRowsByCat[id] === undefined &&
      !loadingCats[id] &&
      !errors[id],
    [trees, indexedRowsByCat, loadingCats, errors]
  );

  const awaitingCatalogs = useMemo(() => {
    if (!searchReady) return false;
    return classicSearchCatalogDefs.some(
      (c) => loadingCats[c.id] || catalogRowsStillIndexing(c.id),
    );
  }, [searchReady, classicSearchCatalogDefs, loadingCats, catalogRowsStillIndexing]);

  /** Souhrnná chyba jen pokud selžou všechny aktivní (ne„skipped”) katalogové stromy. */
  const crossSearchAllActionableFailed = useMemo(() => {
    if (!searchReady) return false;
    const actionable = classicSearchCatalogDefs.filter(
      (c) => !shouldSkipUnifiedGlobeBrowseFetch(c),
    );
    if (!actionable.length) return false;
    return actionable.every((c) => Boolean(errors[c.id]) && !loadingCats[c.id]);
  }, [searchReady, classicSearchCatalogDefs, errors, loadingCats]);

  const localFlatResults = useMemo(
    () =>
      buildLocalCrossSearchFlatResults({
        catalogs: CATALOGS,
        selectedIds: new Set(classicSearchCatalogDefs.map((d) => d.id)),
        indexedRowsByCat,
        query: submittedCrossQuery,
      }),
    [submittedCrossQuery, classicSearchCatalogDefs, indexedRowsByCat],
  );

  /** Primárně backend /catalog/search; lokální AND filtr jen jako fallback. */
  const flatResults = useMemo(() => {
    if (!searchReady) return [];

    if (useAiAssistant) {
      return localFlatResults;
    }

    if (crossSearchBackendLoading && !crossSearchBackendAttempted) {
      return localFlatResults.length > 0 ? localFlatResults : [];
    }

    const backendFlat = (Array.isArray(crossSearchBackendHits) ? crossSearchBackendHits : [])
      .map((hit) => catalogSearchHitToFlatEntry(hit, CATALOGS))
      .filter(Boolean);
    if (backendFlat.length > 0) {
      return backendFlat;
    }

    if (crossSearchBackendAttempted && !crossSearchBackendAllFailed && !crossSearchBackendError) {
      return [];
    }

    if (crossSearchBackendAllFailed || crossSearchBackendError) {
      return localFlatResults;
    }

    if (crossSearchBackendLoading) return [];
    return localFlatResults;
  }, [
    searchReady,
    useAiAssistant,
    localFlatResults,
    crossSearchBackendHits,
    crossSearchBackendLoading,
    crossSearchBackendAttempted,
    crossSearchBackendAllFailed,
    crossSearchBackendError,
  ]);

  const crossSearchUsedLocalFallback = useMemo(
    () =>
      searchReady &&
      !useAiAssistant &&
      crossSearchBackendAttempted &&
      (crossSearchBackendAllFailed || Boolean(crossSearchBackendError)) &&
      localFlatResults.length > 0 &&
      flatResults.length > 0 &&
      flatResults.every((e) => e.resultSource === "local"),
    [
      searchReady,
      useAiAssistant,
      crossSearchBackendAttempted,
      crossSearchBackendAllFailed,
      crossSearchBackendError,
      localFlatResults.length,
      flatResults,
    ],
  );

  useEffect(() => {
    const q = String(submittedCrossQuery || "").trim();
    if (!searchReady || useAiAssistant) {
      setCrossSearchBackendHits(null);
      setCrossSearchBackendSummaries([]);
      setCrossSearchPartialIndexMissing(false);
      setCrossSearchBackendLoading(false);
      setCrossSearchBackendAttempted(false);
      setCrossSearchBackendAllFailed(false);
      setCrossSearchBackendError("");
      return undefined;
    }

    const activeDefs = classicSearchCatalogDefs;
    if (!activeDefs.length) {
      setCrossSearchBackendHits([]);
      setCrossSearchBackendSummaries([]);
      setCrossSearchPartialIndexMissing(false);
      setCrossSearchBackendLoading(false);
      setCrossSearchBackendAttempted(true);
      setCrossSearchBackendAllFailed(false);
      setCrossSearchBackendError("");
      return undefined;
    }

    let cancelled = false;
    setCrossSearchBackendHits(null);
    setCrossSearchBackendSummaries([]);
    setCrossSearchExcludedCatalogIds(new Set());
    setCrossSearchPartialIndexMissing(false);
    setCrossSearchBackendLoading(true);
    setCrossSearchBackendAttempted(false);
    setCrossSearchBackendAllFailed(false);
    setCrossSearchBackendError("");

    runCatalogCrossSearch(api, { query: q, catalogDefs: activeDefs })
      .then((result) => {
        if (cancelled) return;
        setCrossSearchBackendHits(result.hits);
        setCrossSearchBackendSummaries(Array.isArray(result.sourceSummaries) ? result.sourceSummaries : []);
        setCrossSearchPartialIndexMissing(Boolean(result.partialIndexMissing));
        setCrossSearchBackendAllFailed(Boolean(result.allFailed));
      })
      .catch((err) => {
        if (cancelled) return;
        setCrossSearchBackendHits(null);
        setCrossSearchBackendAllFailed(true);
        const nf = normalizeApiFailure(err);
        setCrossSearchBackendError(nf.message || formatApiErrorFromAxios(err));
      })
      .finally(() => {
        if (cancelled) return;
        setCrossSearchBackendLoading(false);
        setCrossSearchBackendAttempted(true);
      });

    return () => {
      cancelled = true;
    };
  }, [submittedCrossQuery, crossSearchSubmitNonce, searchReady, useAiAssistant, classicSearchCatalogDefs]);

  useEffect(() => {
    const q = String(submittedCrossQuery || "").trim();
    if (!searchReady || useAiAssistant || !inAppSearchSelected) {
      setInAppSearchResults([]);
      setInAppSearchLoading(false);
      setInAppSearchError("");
      return undefined;
    }

    let cancelled = false;
    setInAppSearchResults([]);
    setInAppSearchLoading(true);
    setInAppSearchError("");

    runInAppSearch(api, { query: q, limit: 40 })
      .then((result) => {
        if (cancelled) return;
        setInAppSearchResults(Array.isArray(result?.results) ? result.results : []);
        const errs = Array.isArray(result?.errors) ? result.errors.filter(Boolean) : [];
        setInAppSearchError(errs.length ? errs[0] : "");
      })
      .catch((err) => {
        if (cancelled) return;
        const nf = normalizeApiFailure(err);
        setInAppSearchResults([]);
        setInAppSearchError(nf.message || formatApiErrorFromAxios(err));
      })
      .finally(() => {
        if (!cancelled) setInAppSearchLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [submittedCrossQuery, crossSearchSubmitNonce, searchReady, useAiAssistant, inAppSearchSelected]);

  /**
   * Akcie mají vlastní backend (/stocks/search, Yahoo Finance / Alpha Vantage) - zcela oddělený od
   * katalogové search-v2 pipeline. Běží nezávisle na useAiAssistant, takže se zobrazí u obou
   * režimů hledání; výsledky se NIKDY neslučují do jednoho žebříčku s katalogovými výsledky, jen se
   * zobrazí jako samostatná skupina (viz sekce "Akcie" níže).
   */
  useEffect(() => {
    const q = String(submittedCrossQuery || "").trim();
    if (!searchReady) {
      setStockSearchResults([]);
      return undefined;
    }

    let cancelled = false;
    setStockSearchResults([]);

    runStockSearch(api, { query: q, limit: 5 })
      .then((result) => {
        if (cancelled) return;
        setStockSearchResults(Array.isArray(result?.results) ? result.results : []);
      })
      .catch(() => {
        if (!cancelled) setStockSearchResults([]);
      });

    return () => {
      cancelled = true;
    };
  }, [submittedCrossQuery, crossSearchSubmitNonce, searchReady]);

  const browseOptions = useMemo(
    () => CATALOGS.filter((c) => selected.has(c.id)),
    [selected]
  );

  const browseDef = useMemo(
    () => CATALOGS.find((c) => c.id === browseCatalogId) || CATALOGS[0],
    [browseCatalogId]
  );

  useEffect(() => {
    setBrowsePanelFilter("");
    setDebouncedBrowsePanelFilter("");
    setCatalogGlobResults(null);
    setCatalogGlobError("");
    setCatalogGlobLoading(false);
    setBrowseColumnSelection([]);
  }, [browseCatalogId]);

  /** Celokatalogové hledání jen po „Hledat“ / Enter — ne při každém znaku (debounce zrušen). */
  useEffect(() => {
    if (browseLocalBranchOnly) return;
    const draft = String(browsePanelFilter || "").trim();
    const committed = String(debouncedBrowsePanelFilter || "").trim();
    if (committed.length >= 2 && draft !== committed) {
      setCatalogGlobResults(null);
      setCatalogGlobError("");
      setCatalogGlobLoading(false);
    }
  }, [browsePanelFilter, debouncedBrowsePanelFilter, browseLocalBranchOnly]);

  const browseAllRows = Array.isArray(indexedRowsByCat[browseCatalogId])
    ? indexedRowsByCat[browseCatalogId]
    : [];
  const browseIndexPending =
    selected.has(browseCatalogId) &&
    Boolean(trees[browseCatalogId]?.categories?.length) &&
    indexedRowsByCat[browseCatalogId] === undefined &&
    !loadingCats[browseCatalogId] &&
    !errors[browseCatalogId];

  const browseRowIndex = useMemo(() => buildPathIndex(browseAllRows), [browseAllRows]);

  const browsePanelKeywords = useMemo(() => parseSearchKeywords(browsePanelFilter), [browsePanelFilter]);

  const browsePanelKeywordsTreeOnly = useMemo(
    () => (browseLocalBranchOnly ? browsePanelKeywords : []),
    [browseLocalBranchOnly, browsePanelKeywords]
  );

  const browseFilteredPaths = useMemo(
    () => buildFilteredPaths(browseAllRows, browseRowIndex, browsePanelKeywordsTreeOnly),
    [browseAllRows, browseRowIndex, browsePanelKeywordsTreeOnly]
  );

  const browseVisibleRows = useMemo(() => {
    if (!browseAllRows.length) return [];
    if (browseFilteredPaths) {
      return browseAllRows
        .filter((r) => browseFilteredPaths.has(r.path))
        .slice(0, MAX_CATALOG_FILTER_ROWS);
    }
    const result = [];
    for (const r of browseAllRows) {
      if (r.depth === 0) {
        result.push(r);
        continue;
      }
      const pp =
        r.kind === "set"
          ? r.parentPath
          : typeof r.parentPath === "string"
            ? r.parentPath
            : String(r.path || "")
                .split(" > ")
                .slice(0, -1)
                .join(" > ");
      if (!browseAncestorsOpen(openPaths, pp)) continue;
      result.push(r);
    }
    return result;
  }, [browseAllRows, openPaths, browseFilteredPaths]);

  /** Rozlišení: filtr níže vyřadil vše vs. celý browse strom z API opravdu nemá kategorie/nepoložky. */
  const browseRescueNotice = useMemo(
    () => getAradCatalogRescueNotice(trees[browseCatalogId]),
    [browseCatalogId, trees[browseCatalogId]]
  );
  const browseEmptyFilteredOut =
    browseLocalBranchOnly &&
    browsePanelKeywordsTreeOnly.length > 0 &&
    browseAllRows.length > 0 &&
    browseVisibleRows.length === 0;

  const selectedHasBrowseCatalog = selected.has(browseCatalogId);
  const selectedHasAnyCatalog = browseOptions.length > 0;

  useEffect(() => {
    if (browseLocalBranchOnly || browseSearchAcrossSelected) {
      setCatalogGlobResults(null);
      setCatalogGlobLoading(false);
      setCatalogGlobError("");
      return;
    }
    const q = debouncedBrowsePanelFilter;
    if (!selectedHasBrowseCatalog || q.length < 2) {
      setCatalogGlobResults(null);
      setCatalogGlobLoading(false);
      setCatalogGlobError("");
      return;
    }
    let cancelled = false;
    setCatalogGlobResults(null);
    setCatalogGlobLoading(true);
    setCatalogGlobError("");
    api
      .post("/catalog/search", { source: browseCatalogId, query: q, limit: 50 }, { timeout: 65000 })
      .then(({ data }) => {
        if (cancelled) return;
        const raw = data && typeof data === "object" ? data : {};
        setCatalogGlobResults({
          ...raw,
          results: Array.isArray(raw.results) ? raw.results : [],
          themes_triggered: Array.isArray(raw.themes_triggered) ? raw.themes_triggered : [],
        });
      })
      .catch((e) => {
        if (cancelled) return;
        setCatalogGlobResults(null);
        const nf = normalizeApiFailure(e);
        setCatalogGlobError(nf.message || formatApiErrorFromAxios(e));
      })
      .finally(() => {
        if (!cancelled) setCatalogGlobLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [
    debouncedBrowsePanelFilter,
    browseCatalogId,
    browseLocalBranchOnly,
    selectedHasBrowseCatalog,
    browseSearchAcrossSelected,
  ]);

  useEffect(() => {
    if (browseSearchAcrossSelected && !browseLocalBranchOnly) return;
    if (catalogMultiResults || catalogMultiError || catalogMultiLoading) {
      setCatalogMultiResults(null);
      setCatalogMultiError("");
      setCatalogMultiLoading(false);
    }
  }, [browseSearchAcrossSelected, browseLocalBranchOnly, catalogMultiResults, catalogMultiError, catalogMultiLoading]);

  useEffect(() => {
    const q = String(debouncedBrowsePanelFilter || "").trim();
    if (browseLocalBranchOnly || !browseSearchAcrossSelected || q.length < 2 || browseOptions.length < 1) {
      setCatalogMultiResults(null);
      setCatalogMultiLoading(false);
      setCatalogMultiError("");
      setCatalogMultiLaneResults({});
      setCatalogMultiStreamAwaitingFinal(false);
      return;
    }
    const activeDefs = CATALOGS.filter((c) => selected.has(c.id));
    if (activeDefs.length < 1) {
      setCatalogMultiResults(null);
      setCatalogMultiLoading(false);
      setCatalogMultiError("");
      setCatalogMultiLaneResults({});
      setCatalogMultiStreamAwaitingFinal(false);
      return;
    }
    const ac = new AbortController();
    let cancelled = false;
    setCatalogMultiResults(null);
    setCatalogMultiLoading(true);
    setCatalogMultiError("");
    setCatalogMultiLaneResults({});
    setCatalogMultiStreamAwaitingFinal(true);

    const applyMultiPayload = (payload) => {
      if (cancelled || !payload || typeof payload !== "object") return;
      setCatalogMultiResults(payload);
      setCatalogMultiError("");
      setCatalogMultiLaneResults({});
      setCatalogMultiStreamAwaitingFinal(false);
    };

    const runPostFallback = async () => {
      try {
        const { data } = await api.post(
          "/catalog/search/multi",
          {
            query: q,
            q,
            sources: activeDefs.map((def) => def.id),
            limit: 30,
          },
          { timeout: 65000, signal: ac.signal },
        );
        if (cancelled) return;
        applyMultiPayload(data);
      } catch (e) {
        if (cancelled || ac.signal.aborted) return;
        setCatalogMultiResults(null);
        const nf = normalizeApiFailure(e);
        setCatalogMultiError(nf.message || formatApiErrorFromAxios(e));
        setCatalogMultiLaneResults({});
        setCatalogMultiStreamAwaitingFinal(false);
      } finally {
        if (!cancelled) setCatalogMultiLoading(false);
      }
    };

    void (async () => {
      const streamParams = new URLSearchParams();
      streamParams.set("q", q);
      streamParams.set("sources", activeDefs.map((def) => def.id).join(","));
      streamParams.set("limit", "30");
      const streamResult = await runCatalogMultiSearchSseStream({
        url: `${API_ROOT_URL.replace(/\/$/, "")}/catalog/search/multi/stream?${streamParams.toString()}`,
        signal: ac.signal,
        timeoutMs: 65000,
        isRequestCurrent: () => !cancelled && !ac.signal.aborted,
        onLane: (msg) => {
          const sid = String(msg?.source || "").toLowerCase();
          const hits = Array.isArray(msg?.hits) ? msg.hits : [];
          if (!sid) return;
          setCatalogMultiLaneResults((prev) => ({
            ...(prev && typeof prev === "object" ? prev : {}),
            [sid]: hits,
          }));
        },
        onFinal: (msg) => {
          applyMultiPayload(multiSearchPayloadFromFinal(msg, q));
          setCatalogMultiLoading(false);
        },
      });
      if (cancelled) return;
      if (streamResult?.payload && typeof streamResult.payload === "object") {
        applyMultiPayload(streamResult.payload);
        setCatalogMultiLoading(false);
        return;
      }
      const hadLaneProgress =
        streamResult?.telemetry?.sse_lane_events_received > 0 ||
        Object.keys(streamResult?.laneResults || {}).length > 0;
      if (hadLaneProgress && !streamResult?.telemetry?.sse_final_received) {
        const provisional = multiSearchPayloadFromFinal(
          {
            hits: Object.values(streamResult?.laneResults || {}).flat(),
            themes: [],
            source_summaries: Object.entries(streamResult?.laneResults || {}).map(([source, hits]) => ({
              id: source,
              label: CATALOGS.find((c) => c.id === source)?.label || source.toUpperCase(),
              hits: Array.isArray(hits) ? hits.length : 0,
              upstream_unavailable: false,
            })),
            message_cs: "Stream skončil před finálním sloučením — ponechávám průběžné výsledky.",
          },
          q,
        );
        applyMultiPayload(provisional);
        setCatalogMultiLoading(false);
        return;
      }
      if (
        streamResult?.telemetry?.sse_final_received &&
        !streamResult?.payload &&
        Array.isArray(streamResult?.finalHits) &&
        streamResult.finalHits.length > 0
      ) {
        applyMultiPayload(
          multiSearchPayloadFromFinal(
            { hits: streamResult.finalHits, themes: [], source_summaries: [] },
            q,
          ),
        );
        setCatalogMultiLoading(false);
        return;
      }
      await runPostFallback();
    })();

    return () => {
      cancelled = true;
      ac.abort();
    };
  }, [debouncedBrowsePanelFilter, selected, browseSearchAcrossSelected, browseLocalBranchOnly, browseOptions.length]);

  const ensureBrowsePathOpen = useCallback((path) => {
    const normalized = String(path || "").trim();
    if (!normalized) return;
    setOpenPaths((s) => {
      const n = new Set(s);
      let acc = "";
      for (const part of normalized.split(" > ").filter(Boolean)) {
        acc = acc ? `${acc} > ${part}` : part;
        n.add(acc);
      }
      return n;
    });
  }, [setOpenPaths]);

  const openCatalogPathInBrowseTree = useCallback(
    (catalogId, rawPath) => {
      const path = String(rawPath || "").trim();
      if (!path) return;
      const targetCatalog = String(catalogId || browseCatalogId).trim();
      if (!targetCatalog) return;

      const rows = indexedRowsByCat[targetCatalog];
      if (targetCatalog !== browseCatalogId) {
        pendingBrowseTreePathRef.current = { catalogId: targetCatalog, path };
        setBrowseCatalogId(targetCatalog);
        setClassicSearchScope(targetCatalog);
        if (rows?.length) {
          applyResolvedPathsToTree(path, rows);
          pendingBrowseTreePathRef.current = null;
        }
        return;
      }
      if (!rows?.length) {
        pendingBrowseTreePathRef.current = { catalogId: targetCatalog, path };
        return;
      }
      applyResolvedPathsToTree(path, rows);
    },
    [browseCatalogId, indexedRowsByCat, setBrowseCatalogId, applyResolvedPathsToTree],
  );

  const loadTradingEconomicsCountryNode = useCallback(async (countryName) => {
    const country = String(countryName || "").trim();
    if (!country) return;
    const key = country.toLowerCase();
    if (teCountryLoadedRef.current.has(key) || teCountryLoadingRef.current.has(key)) return;
    teCountryLoadingRef.current.add(key);
    setTeLoadingCountries((prev) => new Set(prev).add(key));
    try {
      const { data } = await api.get(`/tradingeconomics/catalog/country/${encodeURIComponent(country)}`, {
        timeout: 45000,
      });
      const countryNode = data?.country_node;
      if (!countryNode || typeof countryNode !== "object") return;

      let nextTree = null;
      setTrees((t) => {
        const cur = t?.tradingeconomics;
        const categories = Array.isArray(cur?.categories) ? cur.categories : [];
        if (!categories.length) return t;
        const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
        if (!root) return t;
        const children = Array.isArray(root.children) ? root.children : [];
        root.children = children.map((ch) => {
          const nm = String(ch?.name || "").trim().toLowerCase();
          return nm === key ? countryNode : ch;
        });
        const updated = { ...(cur || {}), categories: [root, ...categories.slice(1)] };
        nextTree = updated;
        return { ...t, tradingeconomics: updated };
      });

      if (nextTree && Array.isArray(nextTree.categories)) {
        const rows = await flattenCatalogCategoriesBestEffort(nextTree.categories);
        setIndexedRowsByCat((p) => ({ ...p, tradingeconomics: rows }));
      }
      teCountryLoadedRef.current.add(key);
    } catch (e) {
      const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
      toast.error(`TradingEconomics (${country}): ${msg}`);
    } finally {
      teCountryLoadingRef.current.delete(key);
      setTeLoadingCountries((prev) => {
        const n = new Set(prev);
        n.delete(key);
        return n;
      });
    }
  }, []);

  const loadImfCountryIndicators = useCallback((countryCode) => {
    const code = String(countryCode || "").trim().toUpperCase();
    if (!code) return;
    const key = code.toLowerCase();
    if (imfCountryLoadedRef.current.has(key) || imfCountryLoadingRef.current.has(key)) return;

    imfCountryLoadingRef.current.add(key);
    setImfLoadingCountries((prev) => new Set(prev).add(code));
    (async () => {
      try {
        const { data } = await api.get(`/imf/browse-tree/country/${encodeURIComponent(code)}`, {
          timeout: 60000,
        });
        const countryNode = data?.country_node;
        if (!countryNode || typeof countryNode !== "object") return;

        const availableCount = Number(data?.available_count ?? countryNode?.imf_indicator_count ?? 0);
        if (availableCount <= 0) {
          const hint = String(countryNode?.browse_notice || "").trim();
          toast.info(
            hint ||
              `IMF pro ${code} zatím nemá ověřené řady v katalogu. Zkuste jinou zemi nebo regionální agregát (např. G603).`,
            { duration: 6000 },
          );
        }

        const rev = String(data?.availability_revision || "").trim();
        if (
          rev
          && imfAvailabilityRevisionRef.current
          && rev !== imfAvailabilityRevisionRef.current
        ) {
          imfCountryLoadedRef.current.clear();
        }
        if (rev) imfAvailabilityRevisionRef.current = rev;

        let lazyRowForPatch = null;
        setIndexedRowsByCat((p) => {
          const prior = Array.isArray(p.imf) ? p.imf : [];
          lazyRowForPatch =
            prior.find(
              (r) =>
                r?.kind === "cat" &&
                String(r?.imf_country || "")
                  .trim()
                  .toUpperCase() === code,
            ) || null;
          if (!lazyRowForPatch) return p;
          return { ...p, imf: patchBrowseRowsForLazyCountry(prior, countryNode, lazyRowForPatch) };
        });

        const lazyRow = lazyRowForPatch;
        const countryPath = String(lazyRow?.path || countryNode?.path || "").trim();

        setTrees((t) => {
          const cur = t?.imf;
          const categories = Array.isArray(cur?.categories) ? cur.categories : [];
          if (!categories.length) return t;
          const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
          if (!root) return t;
          const children = Array.isArray(root.children) ? root.children : [];
          root.children = children.map((ch) => {
            const cc = String(ch?.imf_country || "").trim().toUpperCase();
            if (cc !== code) return ch;
            return {
              ...countryNode,
              path: ch.path || countryNode.path,
              name: ch.name || countryNode.name,
              imf_country: ch.imf_country || countryNode.imf_country || code,
              imf_browse_count: availableCount,
            };
          });
          return { ...t, imf: { ...(cur || {}), categories: [root, ...categories.slice(1)] } };
        });

        if (countryPath) {
          setOpenPaths((prev) => {
            const next = new Set(prev);
            next.add(countryPath);
            const parent = String(lazyRow?.parentPath || "").trim();
            if (parent) next.add(parent);
            const cats = Array.isArray(countryNode?.children) ? countryNode.children : [];
            if (cats.length === 1 && cats[0]?.path) {
              next.add(String(cats[0].path).trim());
            }
            return next;
          });
        }

        imfCountryLoadedRef.current.add(key);
      } catch (e) {
        const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
        toast.error(`IMF (${code}): ${msg}`);
      } finally {
        imfCountryLoadingRef.current.delete(key);
        setImfLoadingCountries((prev) => {
          const n = new Set(prev);
          n.delete(code);
          return n;
        });
      }
    })();
  }, []);

  const loadEcbCountryIndicators = useCallback((countryCode) => {
    const code = String(countryCode || "").trim().toUpperCase();
    if (!code) return;
    const key = code.toLowerCase();
    if (ecbCountryLoadedRef.current.has(key) || ecbCountryLoadingRef.current.has(key)) return;

    ecbCountryLoadQueueRef.current = ecbCountryLoadQueueRef.current
      .then(async () => {
        ecbCountryLoadingRef.current.add(key);
        setEcbLoadingCountries((prev) => new Set(prev).add(code));
        const gen = (ecbFlattenGenRef.current += 1);
        try {
          const { data } = await api.get(`/ecb/browse-tree/country/${encodeURIComponent(code)}`, {
            timeout: 90000,
          });
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && ecbAvailabilityRevisionRef.current
            && rev !== ecbAvailabilityRevisionRef.current
          ) {
            ecbCountryLoadedRef.current.clear();
            ecbFlattenGenRef.current += 1;
          }
          if (rev) ecbAvailabilityRevisionRef.current = rev;
          const countryNode = data?.country_node;
          if (!countryNode || typeof countryNode !== "object") return;

          setTrees((t) => {
            const cur = t?.ecb;
            const categories = Array.isArray(cur?.categories) ? cur.categories : [];
            if (!categories.length) return t;
            const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
            if (!root) return t;
            const children = Array.isArray(root.children) ? root.children : [];
            root.children = children.map((ch) => {
              const cc = String(ch?.ecb_country || "").trim().toUpperCase();
              return cc === code ? countryNode : ch;
            });
            const updated = { ...(cur || {}), categories: [root, ...categories.slice(1)] };
            return { ...t, ecb: updated };
          });

          if (gen !== ecbFlattenGenRef.current) return;

          let patched = false;
          setIndexedRowsByCat((p) => {
            const prior = Array.isArray(p.ecb) ? p.ecb : [];
            const lazyRow = prior.find(
              (r) =>
                r?.kind === "cat" &&
                String(r?.ecb_country || "")
                  .trim()
                  .toUpperCase() === code,
            );
            if (!lazyRow) return p;
            const rows = patchBrowseRowsForLazyCountry(prior, countryNode, lazyRow);
            patched = true;
            return { ...p, ecb: rows };
          });

          if (gen !== ecbFlattenGenRef.current || !patched) return;
          ecbCountryLoadedRef.current.add(key);
        } catch (e) {
          const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
          toast.error(`ECB (${code}): ${msg}`);
        } finally {
          ecbCountryLoadingRef.current.delete(key);
          setEcbLoadingCountries((prev) => {
            const n = new Set(prev);
            n.delete(code);
            return n;
          });
        }
      })
      .catch(() => {});
  }, []);

  const loadEcb2CountryIndicators = useCallback((countryCode, countryPath, currentRows = []) => {
    const code = String(countryCode || "").trim().toUpperCase();
    const path = String(countryPath || "").trim();
    if (!code) return;
    const key = path ? `${code}|${path}` : code.toLowerCase();
    if (ecb2CountryLoadedRef.current.has(key)) {
      if (ecb2CountryBranchHasFlowChildren(currentRows, path)) return;
      ecb2CountryLoadedRef.current.delete(key);
    }
    if (ecb2CountryLoadingRef.current.has(key)) return;

    ecb2CountryLoadQueueRef.current = ecb2CountryLoadQueueRef.current
      .then(async () => {
        ecb2CountryLoadingRef.current.add(key);
        setEcb2LoadingCountries((prev) => new Set(prev).add(key));
        const gen = (ecb2FlattenGenRef.current += 1);
        try {
          const { data } = await api.get(`/ecb2/browse-tree/country/${encodeURIComponent(code)}`, {
            timeout: 90000,
          });
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && ecb2AvailabilityRevisionRef.current
            && rev !== ecb2AvailabilityRevisionRef.current
          ) {
            ecb2CountryLoadedRef.current.clear();
            ecb2FlowLoadedRef.current.clear();
            ecb2LetterLoadedRef.current.clear();
            ecb2FlattenGenRef.current += 1;
          }
          if (rev) ecb2AvailabilityRevisionRef.current = rev;
          const rawNode = data?.country_node;
          if (!rawNode || typeof rawNode !== "object") {
            toast.error(`ECB (${code}): backend nevrátil obsah země.`);
            return;
          }
          const countryNode = {
            ...rawNode,
            sets: [],
            children: Array.isArray(rawNode.children) ? rawNode.children : [],
            ecb_country_lazy: false,
          };

          setTrees((t) => {
            const cur = t?.ecb2;
            const categories = Array.isArray(cur?.categories) ? cur.categories : [];
            if (!categories.length) return t;
            const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
            if (!root) return t;
            const children = Array.isArray(root.children) ? root.children : [];
            root.children = children.map((ch) => {
              const cc = String(ch?.ecb_country || "").trim().toUpperCase();
              return cc === code ? countryNode : ch;
            });
            const updated = { ...(cur || {}), categories: [root, ...categories.slice(1)] };
            return { ...t, ecb2: updated };
          });

          if (gen !== ecb2FlattenGenRef.current) return;

          setIndexedRowsByCat((p) => {
            const prior = Array.isArray(p.ecb2) ? p.ecb2 : [];
            const lazyRow =
              (path ? prior.find((r) => r.path === path) : null) ||
              prior.find(
                (r) =>
                  r?.kind === "cat" &&
                  String(r?.ecb_country || "")
                    .trim()
                    .toUpperCase() === code,
              );
            if (!lazyRow) {
              toast.error(`ECB (${code}): nelze vložit datasety do stromu — obnovte stránku (F5).`);
              return p;
            }
            const flowChildren = Array.isArray(countryNode.children) ? countryNode.children : [];
            if (!flowChildren.length) {
              const tot = Number(data?.total ?? countryNode?.ecb2_discovery_total ?? 0);
              toast.error(
                tot > 0
                  ? `ECB (${code}): backend nevrátil skupiny datasetů (${tot} řad). Restartujte API na portu 8001.`
                  : `ECB (${code}): pro tuto zemi nejsou v mřížce žádné řady.`,
              );
              return p;
            }
            return {
              ...p,
              ecb2: patchBrowseRowsForLazyCountry(prior, countryNode, {
                ...lazyRow,
                ecb_country_lazy: false,
              }),
            };
          });

          if (gen !== ecb2FlattenGenRef.current) return;
          ecb2CountryLoadedRef.current.add(key);
        } catch (e) {
          const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
          toast.error(`ECB (${code}): ${msg}`);
        } finally {
          ecb2CountryLoadingRef.current.delete(key);
          setEcb2LoadingCountries((prev) => {
            const n = new Set(prev);
            n.delete(key);
            return n;
          });
        }
      })
      .catch(() => {});
  }, []);

  const loadImf2CountryIndicators = useCallback((countryCode) => {
    const code = String(countryCode || "").trim().toUpperCase();
    if (!code) return;
    const key = code.toLowerCase();
    if (imf2CountryLoadedRef.current.has(key) || imf2CountryLoadingRef.current.has(key)) return;

    imf2CountryLoadQueueRef.current = imf2CountryLoadQueueRef.current
      .then(async () => {
        imf2CountryLoadingRef.current.add(key);
        setImf2LoadingCountries((prev) => new Set(prev).add(code));
        const gen = (imf2FlattenGenRef.current += 1);
        try {
          const { data } = await api.get(`/imf2/browse-tree/country/${encodeURIComponent(code)}`, {
            timeout: 120000,
          });
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && imf2AvailabilityRevisionRef.current
            && rev !== imf2AvailabilityRevisionRef.current
          ) {
            imf2CountryLoadedRef.current.clear();
            imf2FlattenGenRef.current += 1;
          }
          if (rev) imf2AvailabilityRevisionRef.current = rev;
          const countryNode = data?.country_node;
          if (!countryNode || typeof countryNode !== "object") return;

          setTrees((t) => {
            const cur = t?.imf2;
            const categories = Array.isArray(cur?.categories) ? cur.categories : [];
            if (!categories.length) return t;
            const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
            if (!root) return t;
            const children = Array.isArray(root.children) ? root.children : [];
            root.children = children.map((ch) => {
              const cc = String(ch?.imf_country || "").trim().toUpperCase();
              return cc === code ? countryNode : ch;
            });
            const updated = { ...(cur || {}), categories: [root, ...categories.slice(1)] };
            return { ...t, imf2: updated };
          });

          if (gen !== imf2FlattenGenRef.current) return;

          let patched = false;
          setIndexedRowsByCat((p) => {
            const prior = Array.isArray(p.imf2) ? p.imf2 : [];
            const lazyRow = prior.find(
              (r) =>
                r?.kind === "cat" &&
                String(r?.imf_country || "")
                  .trim()
                  .toUpperCase() === code,
            );
            if (!lazyRow) return p;
            const rows = patchBrowseRowsForLazyCountry(prior, countryNode, lazyRow);
            patched = true;
            return { ...p, imf2: rows };
          });

          if (gen !== imf2FlattenGenRef.current || !patched) return;
          imf2CountryLoadedRef.current.add(key);
        } catch (e) {
          const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
          toast.error(`IMF 2 (${code}): ${msg}`);
        } finally {
          imf2CountryLoadingRef.current.delete(key);
          setImf2LoadingCountries((prev) => {
            const n = new Set(prev);
            n.delete(code);
            return n;
          });
        }
      })
      .catch(() => {});
  }, []);

  const loadEcb2FlowNode = useCallback((countryCode, flowRef, flowPath, currentRows = []) => {
    const code = String(countryCode || "").trim().toUpperCase();
    const flow = String(flowRef || "").trim().toUpperCase();
    const path = String(flowPath || "").trim();
    if (!code || !flow) return;
    const key = path ? `${code}|${flow}|${path}` : `${code}|${flow}`;
    if (ecb2FlowLoadedRef.current.has(key)) {
      const hasChildren = (Array.isArray(currentRows) ? currentRows : []).some(
        (r) =>
          String(r?.parentPath || "").trim() === path &&
          (r?.kind === "set" || String(r?.ecb_letter || "").trim()),
      );
      if (hasChildren) return;
      ecb2FlowLoadedRef.current.delete(key);
    }
    if (ecb2FlowLoadingRef.current.has(key)) return;

    ecb2FlowLoadQueueRef.current = ecb2FlowLoadQueueRef.current
      .then(async () => {
        ecb2FlowLoadingRef.current.add(key);
        setEcb2LoadingFlows((prev) => new Set(prev).add(key));
        const gen = (ecb2FlattenGenRef.current += 1);
        try {
          const { data } = await api.get(
            `/ecb2/browse-tree/country/${encodeURIComponent(code)}/flow/${encodeURIComponent(flow)}`,
            { timeout: 90000 },
          );
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && ecb2AvailabilityRevisionRef.current
            && rev !== ecb2AvailabilityRevisionRef.current
          ) {
            ecb2CountryLoadedRef.current.clear();
            ecb2FlowLoadedRef.current.clear();
            ecb2LetterLoadedRef.current.clear();
            ecb2FlattenGenRef.current += 1;
          }
          if (rev) ecb2AvailabilityRevisionRef.current = rev;
          const flowNode = data?.flow_node;
          if (!flowNode || typeof flowNode !== "object") {
            toast.error(`ECB (${code} · ${flow}): backend nevrátil obsah datasetu.`);
            return;
          }
          const apiSets = Array.isArray(data?.rows)
            ? data.rows
            : Array.isArray(flowNode.sets)
              ? flowNode.sets
              : [];
          const patchedNode = {
            ...flowNode,
            sets: apiSets,
            children: Array.isArray(flowNode.children) ? flowNode.children : [],
            ecb_flow_lazy: false,
            ecb_flow_letter_lazy: false,
          };

          setTrees((t) => {
            const cur = t?.ecb2;
            const categories = Array.isArray(cur?.categories) ? cur.categories : [];
            if (!categories.length) return t;
            const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
            if (!root) return t;
            root.children = (Array.isArray(root.children) ? root.children : []).map((countryCh) => {
              const cc = String(countryCh?.ecb_country || "").trim().toUpperCase();
              if (cc !== code) return countryCh;
              return {
                ...countryCh,
                children: (Array.isArray(countryCh.children) ? countryCh.children : []).map((flowCh) => {
                  const ff = String(flowCh?.ecb_flow || "").trim().toUpperCase();
                  return ff === flow ? patchedNode : flowCh;
                }),
              };
            });
            return { ...t, ecb2: { ...(cur || {}), categories: [root, ...categories.slice(1)] } };
          });

          if (gen !== ecb2FlattenGenRef.current) return;

          setIndexedRowsByCat((p) => {
            const prior = Array.isArray(p.ecb2) ? p.ecb2 : [];
            const lazyRow =
              (path ? prior.find((r) => r.path === path) : null) ||
              prior.find(
                (r) =>
                  r?.kind === "cat" &&
                  String(r?.ecb_country || "").trim().toUpperCase() === code &&
                  String(r?.ecb_flow || "").trim().toUpperCase() === flow,
              );
            if (!lazyRow) {
              toast.error(`ECB (${code} · ${flow}): nelze vložit řady do stromu — obnovte stránku (F5).`);
              return p;
            }
            return { ...p, ecb2: patchBrowseRowsForLazyCountry(prior, patchedNode, lazyRow) };
          });

          if (gen !== ecb2FlattenGenRef.current) return;
          ecb2FlowLoadedRef.current.add(key);
        } catch (e) {
          const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
          toast.error(`ECB (${code} · ${flow}): ${msg}`);
        } finally {
          ecb2FlowLoadingRef.current.delete(key);
          setEcb2LoadingFlows((prev) => {
            const n = new Set(prev);
            n.delete(key);
            return n;
          });
        }
      })
      .catch(() => {});
  }, []);

  const loadOecd2CountryIndicators = useCallback((countryCode) => {
    const code = String(countryCode || "").trim().toUpperCase();
    if (!code) return;
    const key = code.toLowerCase();
    if (oecd2CountryLoadedRef.current.has(key) || oecd2CountryLoadingRef.current.has(key)) return;

    oecd2CountryLoadQueueRef.current = oecd2CountryLoadQueueRef.current
      .then(async () => {
        oecd2CountryLoadingRef.current.add(key);
        setOecd2LoadingCountries((prev) => new Set(prev).add(code));
        const gen = (oecd2FlattenGenRef.current += 1);
        try {
          const { data } = await api.get(`/oecd2/browse-tree/country/${encodeURIComponent(code)}`, {
            timeout: 120000,
          });
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && oecd2AvailabilityRevisionRef.current
            && rev !== oecd2AvailabilityRevisionRef.current
          ) {
            oecd2CountryLoadedRef.current.clear();
            oecd2FlattenGenRef.current += 1;
          }
          if (rev) oecd2AvailabilityRevisionRef.current = rev;
          const countryNode = data?.country_node;
          if (!countryNode || typeof countryNode !== "object") return;

          setTrees((t) => {
            const cur = t?.oecd2;
            const categories = Array.isArray(cur?.categories) ? cur.categories : [];
            if (!categories.length) return t;
            const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
            if (!root) return t;
            const children = Array.isArray(root.children) ? root.children : [];
            root.children = children.map((ch) => {
              const cc = String(ch?.oecd_ref_area || "").trim().toUpperCase();
              return cc === code ? countryNode : ch;
            });
            const updated = { ...(cur || {}), categories: [root, ...categories.slice(1)] };
            return { ...t, oecd2: updated };
          });

          if (gen !== oecd2FlattenGenRef.current) return;

          let patched = false;
          setIndexedRowsByCat((p) => {
            const prior = Array.isArray(p.oecd2) ? p.oecd2 : [];
            const lazyRow = prior.find(
              (r) =>
                r?.kind === "cat" &&
                String(r?.oecd_ref_area || "")
                  .trim()
                  .toUpperCase() === code,
            );
            if (!lazyRow) return p;
            const rows = patchBrowseRowsForLazyCountry(prior, countryNode, lazyRow);
            patched = true;
            return { ...p, oecd2: rows };
          });

          if (gen !== oecd2FlattenGenRef.current || !patched) return;
          oecd2CountryLoadedRef.current.add(key);
        } catch (e) {
          const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
          toast.error(`OECD 2 (${code}): ${msg}`);
        } finally {
          oecd2CountryLoadingRef.current.delete(key);
          setOecd2LoadingCountries((prev) => {
            const n = new Set(prev);
            n.delete(code);
            return n;
          });
        }
      })
      .catch(() => {});
  }, []);

  const loadOecd4DatasetCountries = useCallback((datasetKey) => {
    const key = String(datasetKey || "").trim();
    if (!key) return;
    const cacheKey = key.toLowerCase();
    if (oecd4DatasetLoadedRef.current.has(cacheKey) || oecd4DatasetLoadingRef.current.has(cacheKey)) return;

    oecd4DatasetLoadQueueRef.current = oecd4DatasetLoadQueueRef.current
      .then(async () => {
        oecd4DatasetLoadingRef.current.add(cacheKey);
        setOecd4LoadingDatasets((prev) => new Set(prev).add(key));
        const gen = (oecd4FlattenGenRef.current += 1);
        try {
          const { data } = await api.get(`/oecd4/browse-tree/dataset/${encodeURIComponent(key)}`, {
            timeout: 120000,
          });
          const datasetNode = data?.dataset_node;
          if (!datasetNode || typeof datasetNode !== "object") return;

          setTrees((t) => {
            const cur = t?.oecd4;
            const categories = Array.isArray(cur?.categories) ? cur.categories : [];
            if (!categories.length) return t;
            const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
            if (!root) return t;
            root.children = (Array.isArray(root.children) ? root.children : []).map((catCh) => ({
              ...catCh,
              children: (Array.isArray(catCh.children) ? catCh.children : []).map((dsCh) => {
                if (String(dsCh?.oecd4_key || "").trim() === key) {
                  return {
                    ...datasetNode,
                    oecd4_dataset_lazy: false,
                  };
                }
                return dsCh;
              }),
            }));
            return { ...t, oecd4: { ...(cur || {}), categories: [root, ...categories.slice(1)] } };
          });

          if (gen !== oecd4FlattenGenRef.current) return;

          setIndexedRowsByCat((p) => {
            const prior = Array.isArray(p.oecd4) ? p.oecd4 : [];
            const lazyRow = prior.find(
              (r) => r?.kind === "cat" && String(r?.oecd4_key || "").trim() === key,
            );
            if (!lazyRow) return p;
            return { ...p, oecd4: patchBrowseRowsForLazyCountry(prior, datasetNode, lazyRow) };
          });

          if (gen !== oecd4FlattenGenRef.current) return;
          oecd4DatasetLoadedRef.current.add(cacheKey);
        } catch (e) {
          const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
          toast.error(`OECD (${key}): ${msg}`);
        } finally {
          oecd4DatasetLoadingRef.current.delete(cacheKey);
          setOecd4LoadingDatasets((prev) => {
            const n = new Set(prev);
            n.delete(key);
            return n;
          });
        }
      })
      .catch(() => {});
  }, []);

  const loadOecd4CountryMeasures = useCallback((datasetKey, refArea) => {
    const key = String(datasetKey || "").trim();
    const ra = String(refArea || "").trim().toUpperCase();
    if (!key || !ra) return;
    const cacheKey = `${key.toLowerCase()}|${ra}`;
    if (oecd4CountryLoadedRef.current.has(cacheKey) || oecd4CountryLoadingRef.current.has(cacheKey)) return;

    oecd4CountryLoadQueueRef.current = oecd4CountryLoadQueueRef.current
      .then(async () => {
        oecd4CountryLoadingRef.current.add(cacheKey);
        setOecd4LoadingCountries((prev) => new Set(prev).add(`${key}|${ra}`));
        const gen = (oecd4FlattenGenRef.current += 1);
        try {
          const { data } = await api.get(
            `/oecd4/browse-tree/dataset/${encodeURIComponent(key)}/country/${encodeURIComponent(ra)}`,
            { timeout: 120000 },
          );
          const countryNode = data?.country_node;
          if (!countryNode || typeof countryNode !== "object") return;

          setTrees((t) => {
            const cur = t?.oecd4;
            const categories = Array.isArray(cur?.categories) ? cur.categories : [];
            if (!categories.length) return t;
            const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
            if (!root) return t;
            root.children = (Array.isArray(root.children) ? root.children : []).map((catCh) => ({
              ...catCh,
              children: (Array.isArray(catCh.children) ? catCh.children : []).map((dsCh) => {
                if (String(dsCh?.oecd4_key || "").trim() !== key) return dsCh;
                return {
                  ...dsCh,
                  children: (Array.isArray(dsCh.children) ? dsCh.children : []).map((cCh) => {
                    if (String(cCh?.oecd4_ref_area || "").trim().toUpperCase() === ra) {
                      return { ...countryNode, oecd4_country_lazy: false };
                    }
                    return cCh;
                  }),
                };
              }),
            }));
            return { ...t, oecd4: { ...(cur || {}), categories: [root, ...categories.slice(1)] } };
          });

          if (gen !== oecd4FlattenGenRef.current) return;

          setIndexedRowsByCat((p) => {
            const prior = Array.isArray(p.oecd4) ? p.oecd4 : [];
            const lazyRow = prior.find(
              (r) =>
                r?.kind === "cat" &&
                String(r?.oecd4_key || "").trim() === key &&
                String(r?.oecd4_ref_area || "").trim().toUpperCase() === ra,
            );
            if (!lazyRow) return p;
            return { ...p, oecd4: patchBrowseRowsForLazyCountry(prior, countryNode, lazyRow) };
          });

          if (gen !== oecd4FlattenGenRef.current) return;
          oecd4CountryLoadedRef.current.add(cacheKey);
        } catch (e) {
          const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
          toast.error(`OECD (${key} · ${ra}): ${msg}`);
        } finally {
          oecd4CountryLoadingRef.current.delete(cacheKey);
          setOecd4LoadingCountries((prev) => {
            const n = new Set(prev);
            n.delete(`${key}|${ra}`);
            return n;
          });
        }
      })
      .catch(() => {});
  }, []);

  const loadEcb2LetterIndicators = useCallback((countryCode, flowRef, letter) => {
    const code = String(countryCode || "").trim().toUpperCase();
    const flow = String(flowRef || "").trim().toUpperCase();
    const bucket = normalizeEcb2BrowseBucket(letter);
    if (!code || !flow || !bucket) return;
    const key = `${code}|${flow}|${bucket}`;
    if (ecb2LetterLoadedRef.current.has(key) || ecb2LetterLoadingRef.current.has(key)) return;

    ecb2LetterLoadQueueRef.current = ecb2LetterLoadQueueRef.current
      .then(async () => {
        ecb2LetterLoadingRef.current.add(key);
        setEcb2LoadingLetters((prev) => new Set(prev).add(key));
        const gen = (ecb2FlattenGenRef.current += 1);
        try {
          const { data } = await api.get(
            `/ecb2/browse-tree/country/${encodeURIComponent(code)}/flow/${encodeURIComponent(flow)}/letter/${encodeURIComponent(bucket)}`,
            { timeout: 90000 },
          );
          const rev = String(data?.availability_revision || "").trim();
          if (
            rev
            && ecb2AvailabilityRevisionRef.current
            && rev !== ecb2AvailabilityRevisionRef.current
          ) {
            ecb2CountryLoadedRef.current.clear();
            ecb2FlowLoadedRef.current.clear();
            ecb2LetterLoadedRef.current.clear();
            ecb2FlattenGenRef.current += 1;
          }
          if (rev) ecb2AvailabilityRevisionRef.current = rev;
          const letterNode = data?.letter_node;
          if (!letterNode || typeof letterNode !== "object") return;
          const apiSets = Array.isArray(data?.rows)
            ? data.rows
            : Array.isArray(letterNode.sets)
              ? letterNode.sets
              : [];
          const patchedNode = { ...letterNode, sets: apiSets, children: letterNode.children || [] };

          setTrees((t) => {
            const cur = t?.ecb2;
            const categories = Array.isArray(cur?.categories) ? cur.categories : [];
            if (!categories.length) return t;
            const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
            if (!root) return t;
            root.children = (Array.isArray(root.children) ? root.children : []).map((countryCh) => {
              const cc = String(countryCh?.ecb_country || "").trim().toUpperCase();
              if (cc !== code) return countryCh;
              return {
                ...countryCh,
                children: (Array.isArray(countryCh.children) ? countryCh.children : []).map((flowCh) => {
                  const ff = String(flowCh?.ecb_flow || "").trim().toUpperCase();
                  if (ff !== flow) return flowCh;
                  return {
                    ...flowCh,
                    children: (Array.isArray(flowCh.children) ? flowCh.children : []).map((ltr) => {
                      const lb = normalizeEcb2BrowseBucket(ltr?.ecb_letter);
                      return lb === bucket ? patchedNode : ltr;
                    }),
                  };
                }),
              };
            });
            return { ...t, ecb2: { ...(cur || {}), categories: [root, ...categories.slice(1)] } };
          });

          if (gen !== ecb2FlattenGenRef.current) return;

          setIndexedRowsByCat((p) => {
            const prior = Array.isArray(p.ecb2) ? p.ecb2 : [];
            const lazyRow = prior.find(
              (r) =>
                r?.kind === "cat" &&
                String(r?.ecb_country || "").trim().toUpperCase() === code &&
                String(r?.ecb_flow || "").trim().toUpperCase() === flow &&
                normalizeEcb2BrowseBucket(r?.ecb_letter) === bucket,
            );
            if (!lazyRow) return p;
            return { ...p, ecb2: patchBrowseRowsForLazyCountry(prior, patchedNode, lazyRow) };
          });

          if (gen !== ecb2FlattenGenRef.current) return;
          ecb2LetterLoadedRef.current.add(key);
        } catch (e) {
          const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
          toast.error(`ECB (${code} · ${flow} · ${bucket}): ${msg}`);
        } finally {
          ecb2LetterLoadingRef.current.delete(key);
          setEcb2LoadingLetters((prev) => {
            const n = new Set(prev);
            n.delete(key);
            return n;
          });
        }
      })
      .catch(() => {});
  }, []);

  const loadWorldBankCountryIndicators = useCallback(async (countryCode, countryName) => {
    const code = String(countryCode || "").trim().toUpperCase();
    const country = String(countryName || "").trim();
    if (!code || !country) return;
    const key = `${code}|${country.toLowerCase()}`;
    if (wbCountryLoadedRef.current.has(key) || wbCountryLoadingRef.current.has(key)) return;
    wbCountryLoadingRef.current.add(key);
    setWbLoadingCountries((prev) => new Set(prev).add(key));
    try {
      const { data } = await api.get(`/worldbank/catalog/country/${encodeURIComponent(code)}/indicators`, {
        timeout: 65000,
      });
      const rows = Array.isArray(data?.rows) ? data.rows : [];

      let nextTree = null;
      setTrees((t) => {
        const cur = t?.worldbank;
        const categories = Array.isArray(cur?.categories) ? cur.categories : [];
        if (!categories.length) return t;
        const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
        if (!root) return t;
        const recurse = (node) => {
          if (!node || typeof node !== "object") return node;
          const nm = String(node.name || "").trim().toLowerCase();
          if (nm === country.toLowerCase()) {
            return { ...node, sets: rows, children: [] };
          }
          const ch = Array.isArray(node.children) ? node.children : [];
          if (!ch.length) return node;
          return { ...node, children: ch.map((x) => recurse(x)) };
        };
        const newRoot = recurse(root);
        const updated = { ...(cur || {}), categories: [newRoot, ...categories.slice(1)] };
        nextTree = updated;
        return { ...t, worldbank: updated };
      });

      if (nextTree && Array.isArray(nextTree.categories)) {
        const flat = await flattenCatalogCategoriesBestEffort(nextTree.categories);
        setIndexedRowsByCat((p) => ({ ...p, worldbank: flat }));
      }
      wbCountryLoadedRef.current.add(key);
    } catch (e) {
      const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
      toast.error(`World Bank (${code}): ${msg}`);
    } finally {
      wbCountryLoadingRef.current.delete(key);
      setWbLoadingCountries((prev) => {
        const n = new Set(prev);
        n.delete(key);
        return n;
      });
    }
  }, []);

  const loadData360CountryIndicators = useCallback(async (countryCode, countryName) => {
    const code = String(countryCode || "").trim().toUpperCase();
    const country = String(countryName || countryCode || "").trim();
    if (!code || !country) return;
    const key = `${code}|${country.toLowerCase()}`;
    if (d360CountryLoadedRef.current.has(key) || d360CountryLoadingRef.current.has(key)) return;
    d360CountryLoadingRef.current.add(key);
    setD360LoadingCountries((prev) => new Set(prev).add(key));
    try {
      const { data } = await api.get(`/data360/catalog/country/${encodeURIComponent(code)}/indicators`, {
        timeout: 65000,
      });
      const rows = Array.isArray(data?.rows) ? data.rows : [];
      const countryNode =
        data?.country_node && typeof data.country_node === "object" ? data.country_node : null;

      const buildUpdatedData360Tree = (curTree) => {
        const cur = curTree;
        const categories = Array.isArray(cur?.categories) ? cur.categories : [];
        if (!categories.length) return null;
        const root = categories[0] && typeof categories[0] === "object" ? { ...categories[0] } : null;
        if (!root) return null;
        const recurse = (node) => {
          if (!node || typeof node !== "object") return node;
          const codeNode = String(node.data360_country || "").trim().toUpperCase();
          if (codeNode && codeNode === code) {
            if (countryNode) {
              const rebasedNode = rebaseData360CountryNodePaths(countryNode, node.path || countryNode.path);
              return {
                ...rebasedNode,
                path: node.path || rebasedNode.path,
                name: node.name || countryNode.name,
                data360_country: code,
                data360_country_name: country || rebasedNode.data360_country_name,
                data360_country_lazy: false,
              };
            }
            return { ...node, sets: rows, children: [], data360_country_lazy: false };
          }
          const ch = Array.isArray(node.children) ? node.children : [];
          if (!ch.length) return node;
          return { ...node, children: ch.map((x) => recurse(x)) };
        };
        const newRoot = recurse(root);
        return { ...(cur || {}), categories: [newRoot, ...categories.slice(1)] };
      };

      const updatedTree = buildUpdatedData360Tree(treesRef.current?.data360);
      if (updatedTree) {
        treesRef.current = { ...(treesRef.current || {}), data360: updatedTree };
        setTrees((t) => ({ ...t, data360: updatedTree }));
      }

      setIndexedRowsByCat((p) => {
        const prior = Array.isArray(p.data360) ? p.data360 : [];
        const lazyRow = prior.find(
          (r) =>
            r?.kind === "cat" &&
            String(r?.data360_country || "")
              .trim()
              .toUpperCase() === code,
        );
        if (!lazyRow) {
          toast.error(`Data360 (${code}): nelze vložit ukazatele do stromu — obnovte stránku (F5).`);
          return p;
        }
        if (countryNode) {
          const rebasedNode = rebaseData360CountryNodePaths(countryNode, lazyRow.path);
          const branchRoot = {
            ...rebasedNode,
            path: lazyRow.path,
            name: lazyRow.name || rebasedNode.name,
            data360_country: code,
            data360_country_name: country || rebasedNode.data360_country_name,
            data360_country_lazy: false,
          };
          return { ...p, data360: patchBrowseRowsForLazyCountry(prior, branchRoot, lazyRow) };
        }
        if (!rows.length) return p;
        const branchRoot = {
          ...lazyRow,
          children: [],
          sets: rows,
          data360_country_lazy: false,
        };
        return { ...p, data360: patchBrowseRowsForLazyCountry(prior, branchRoot, lazyRow) };
      });

      const countryNodeChildren = Array.isArray(countryNode?.children) ? countryNode.children : [];
      const hasData = rows.length > 0 || countryNodeChildren.length > 0;
      if (hasData) d360CountryLoadedRef.current.add(key);
      else d360CountryLoadedRef.current.delete(key);
    } catch (e) {
      const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
      toast.error(`Data360 (${code}): ${msg}`);
    } finally {
      d360CountryLoadingRef.current.delete(key);
      setD360LoadingCountries((prev) => {
        const n = new Set(prev);
        n.delete(key);
        return n;
      });
    }
  }, []);

  const loadFredCategoryExpand = useCallback(async (categoryId, categoryPath) => {
    const id = String(categoryId || "").trim();
    const path = String(categoryPath || "").trim();
    if (!id || !path) return;
    const key = `${id}|${path}`;
    if (fredCategoryLoadedRef.current.has(key) || fredCategoryLoadingRef.current.has(key)) return;
    fredCategoryLoadingRef.current.add(key);
    setFredLoadingCategories((prev) => new Set(prev).add(key));
    try {
      const { data } = await api.get("/fred/catalog/expand", {
        params: { category_id: id },
        timeout: FRED_BROWSER_TIMEOUT_MS,
      });
      const rawRoot = Array.isArray(data?.categories) ? data.categories[0] : null;
      if (!rawRoot || typeof rawRoot !== "object") {
        toast.error(`FRED (${id}): backend nevrátil obsah kategorie.`);
        fredCategoryLoadedRef.current.delete(key);
        return;
      }
      const expandRoot = rebaseFredExpandNodePaths(rawRoot, path);

      setIndexedRowsByCat((p) => {
        const prior = Array.isArray(p.fred) ? p.fred : [];
        const categoryRow = prior.find((r) => r.path === path);
        if (!categoryRow) {
          toast.error(`FRED (${id}): nelze vložit položky do stromu — obnovte stránku (F5).`);
          return p;
        }
        const branchRoot = {
          ...expandRoot,
          fred_category_id: id,
          item_kind: "category",
        };
        return { ...p, fred: patchBrowseRowsForLazyCountry(prior, branchRoot, categoryRow) };
      });
      fredCategoryLoadedRef.current.add(key);
    } catch (e) {
      const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
      toast.error(`FRED (${id}): ${msg}`);
      fredCategoryLoadedRef.current.delete(key);
    } finally {
      fredCategoryLoadingRef.current.delete(key);
      setFredLoadingCategories((prev) => {
        const n = new Set(prev);
        n.delete(key);
        return n;
      });
    }
  }, []);

  const loadBisDataflowSeries = useCallback(async (dataflowId, dataflowPath) => {
    const id = String(dataflowId || "").trim();
    const path = String(dataflowPath || "").trim();
    if (!id || !path) return;
    const key = `${id}|${path}`;
    if (bisDataflowLoadedRef.current.has(key) || bisDataflowLoadingRef.current.has(key)) return;
    bisDataflowLoadingRef.current.add(key);
    setBisLoadingDataflows((prev) => new Set(prev).add(key));
    try {
      const { data } = await api.get("/bis/catalog/series", {
        params: { dataflow: id, availability_only: "false" },
        timeout: BIS_BROWSER_TIMEOUT_MS,
      });
      const rawRoot = Array.isArray(data?.categories) ? data.categories[0] : null;
      if (!rawRoot || typeof rawRoot !== "object") {
        toast.error(`BIS (${id}): backend nevrátil řady tohoto toku.`);
        bisDataflowLoadedRef.current.delete(key);
        return;
      }
      const seriesRoot = rebaseBisSeriesNodePaths(rawRoot, path);
      bisActiveDataflowRef.current = id;
      bisCountryLoadedRef.current.clear();

      setIndexedRowsByCat((p) => {
        const prior = Array.isArray(p.bis) ? p.bis : [];
        const dataflowRow = prior.find((r) => r.path === path);
        if (!dataflowRow) {
          toast.error(`BIS (${id}): nelze vložit řady do stromu — obnovte stránku (F5).`);
          return p;
        }
        const branchRoot = {
          ...seriesRoot,
          bis_dataflow: id,
          item_kind: "dataflow",
        };
        return { ...p, bis: patchBrowseRowsForLazyCountry(prior, branchRoot, dataflowRow) };
      });
      bisDataflowLoadedRef.current.add(key);
    } catch (e) {
      const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
      toast.error(`BIS (${id}): ${msg}`);
      bisDataflowLoadedRef.current.delete(key);
    } finally {
      bisDataflowLoadingRef.current.delete(key);
      setBisLoadingDataflows((prev) => {
        const n = new Set(prev);
        n.delete(key);
        return n;
      });
    }
  }, []);

  const loadBisCountrySeries = useCallback(async (countryCode, countryPath, dataflowId) => {
    const code = String(countryCode || "").trim().toUpperCase();
    const path = String(countryPath || "").trim();
    const flowId = String(dataflowId || bisActiveDataflowRef.current || "").trim();
    if (!code || !path || !flowId) return;
    const key = `${flowId}|${code}|${path}`;
    if (bisCountryLoadedRef.current.has(key) || bisCountryLoadingRef.current.has(key)) return;
    bisCountryLoadingRef.current.add(key);
    setBisLoadingCountries((prev) => new Set(prev).add(key));
    try {
      const { data } = await api.get("/bis/catalog/series", {
        params: {
          dataflow: flowId,
          ref_areas: code,
          availability_only: "false",
        },
        timeout: BIS_BROWSER_TIMEOUT_MS,
      });
      const rawCountry =
        data?.country_node ||
        (Array.isArray(data?.categories?.[0]?.children) ? data.categories[0].children[0] : null);
      if (!rawCountry || typeof rawCountry !== "object") {
        toast.error(`BIS (${flowId} / ${code}): backend nevrátil řady pro zemi.`);
        bisCountryLoadedRef.current.delete(key);
        return;
      }
      const countryNode = rebaseBisSeriesNodePaths(rawCountry, path);

      setIndexedRowsByCat((p) => {
        const prior = Array.isArray(p.bis) ? p.bis : [];
        const lazyRow = prior.find((r) => r.path === path);
        if (!lazyRow) {
          toast.error(`BIS (${flowId} / ${code}): nelze vložit řady — obnovte stránku (F5).`);
          return p;
        }
        return { ...p, bis: patchBrowseRowsForLazyCountry(prior, countryNode, lazyRow) };
      });
      bisCountryLoadedRef.current.add(key);
    } catch (e) {
      const msg = normalizeApiFailure(e).message || formatApiErrorFromAxios(e);
      toast.error(`BIS (${flowId} / ${code}): ${msg}`);
      bisCountryLoadedRef.current.delete(key);
    } finally {
      bisCountryLoadingRef.current.delete(key);
      setBisLoadingCountries((prev) => {
        const n = new Set(prev);
        n.delete(key);
        return n;
      });
    }
  }, []);

  const loadBrowseCategoryChildren = useCallback(
    (row, columnIndex = null) => {
      if (browseCatalogId === "tradingeconomics") {
        if (Number(row?.depth ?? -1) !== 1) return;
        void loadTradingEconomicsCountryNode(String(row?.name || ""));
        return;
      }
      if (browseCatalogId === "imf") {
        if (Number(row?.depth ?? -1) !== 1) return;
        const code = String(row?.imf_country || "").trim().toUpperCase();
        if (!code) return;
        void loadImfCountryIndicators(code);
        return;
      }
      if (browseCatalogId === "ecb") {
        if (Number(row?.depth ?? -1) !== 1) return;
        const code = String(row?.ecb_country || "").trim().toUpperCase();
        if (!code) return;
        void loadEcbCountryIndicators(code);
        return;
      }
      if (browseCatalogId === "ecb2") {
        const action = resolveEcb2BrowseLazyAction(row, columnIndex);
        if (!action) return;
        if (action.kind === "country") {
          void loadEcb2CountryIndicators(action.code, action.path, browseAllRows);
          return;
        }
        if (action.kind === "flow") {
          void loadEcb2FlowNode(action.code, action.flow, action.path, browseAllRows);
          return;
        }
        if (action.kind === "letter") {
          void loadEcb2LetterIndicators(action.code, action.flow, action.letter);
        }
        return;
      }
      if (browseCatalogId === "imf2") {
        if (Number(row?.depth ?? -1) !== 1) return;
        const code = String(row?.imf_country || "").trim().toUpperCase();
        if (!code) return;
        void loadImf2CountryIndicators(code);
        return;
      }
      if (browseCatalogId === "oecd2") {
        if (Number(row?.depth ?? -1) !== 1) return;
        const code = String(row?.oecd_ref_area || "").trim().toUpperCase();
        if (!code) return;
        void loadOecd2CountryIndicators(code);
        return;
      }
      if (browseCatalogId === "oecd4") {
        const depth = Number(row?.depth ?? -1);
        const dsKey = String(row?.oecd4_key || "").trim();
        if (!dsKey) return;
        if (depth === 2 && row?.oecd4_dataset_lazy !== false) {
          void loadOecd4DatasetCountries(dsKey);
          return;
        }
        if (depth === 3 && row?.oecd4_country_lazy !== false) {
          const ra = String(row?.oecd4_ref_area || "").trim().toUpperCase();
          if (ra) void loadOecd4CountryMeasures(dsKey, ra);
        }
        return;
      }
      if (browseCatalogId === "worldbank") {
        if (Number(row?.depth ?? -1) !== 2) return;
        const code = String(row?.wb_country || "").trim().toUpperCase();
        const country = String(row?.name || "").trim();
        if (!code || !country) return;
        void loadWorldBankCountryIndicators(code, country);
        return;
      }
      if (browseCatalogId === "data360") {
        const action = resolveData360BrowseLazyAction(row, columnIndex);
        if (!action) return;
        void loadData360CountryIndicators(action.code, action.country);
        return;
      }
      if (browseCatalogId === "bis") {
        const action = resolveBisBrowseLazyAction(row, columnIndex, bisActiveDataflowRef.current);
        if (!action) return;
        if (action.kind === "dataflow") {
          void loadBisDataflowSeries(action.id, action.path);
          return;
        }
        if (action.kind === "country") {
          void loadBisCountrySeries(action.code, action.path, action.flowId);
        }
        return;
      }
      if (browseCatalogId === "fred") {
        const action = resolveFredBrowseLazyAction(row);
        if (!action) return;
        void loadFredCategoryExpand(action.id, action.path);
      }
    },
    [
      browseCatalogId,
      browseAllRows,
      loadTradingEconomicsCountryNode,
      loadImfCountryIndicators,
      loadEcbCountryIndicators,
      loadEcb2CountryIndicators,
      loadImf2CountryIndicators,
      loadOecd2CountryIndicators,
      loadOecd4DatasetCountries,
      loadOecd4CountryMeasures,
      loadEcb2FlowNode,
      loadEcb2LetterIndicators,
      loadWorldBankCountryIndicators,
      loadData360CountryIndicators,
      loadBisDataflowSeries,
      loadBisCountrySeries,
      loadFredCategoryExpand,
    ],
  );

  const addSource = async (def, row) => {
    const effectiveDef = resolveCatalogRowDef(def, row);
    if (!isCatalogRowPreviewEligible(effectiveDef, row)) {
      toast.error("Tuto položku nelze přidat — není platná konkrétní řada pro tento typ katalogu.");
      return;
    }
    const rk = rowExistingKey(effectiveDef, row, wbCountry);
    setAdding((a) => ({ ...a, [rk]: true }));
    try {
      if (!effectiveDef.addPath) {
        toast.error("Z tohoto katalogu nelze řadu přidat tlačítkem — použijte Zdroje (např. Alpha Vantage).");
        return;
      }
      const body = buildCatalogAddSourceBody(effectiveDef, row, wbCountry);
      if (!canAddSources) return;
      const { data } = await api.post(effectiveDef.addPath, body);
      toast.success(`Přidáno (${effectiveDef.label}): ${data.name}`);
      const { data: srcs } = await api.get("/sources/catalog-stubs");
      setSources(Array.isArray(srcs) ? srcs : []);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setAdding((a) => ({ ...a, [rk]: false }));
  };

  const applyPreviewSelectionToRow = (def, row, data) => {
    if (!row || !data || typeof data !== "object") return row;
    const selected = String(data.selected_indicator || "").trim();
    const groupField = String(data.group_field || "").trim().toLowerCase();
    if (!selected) return row;
    if (def?.sourceType === "eurostat" && (groupField === "geo" || groupField === "ref_area")) {
      return {
        ...row,
        eurostat_geo: selected.toUpperCase(),
        query_params: {
          ...(row.query_params && typeof row.query_params === "object" ? row.query_params : {}),
          geo: selected.toUpperCase(),
        },
      };
    }
    return row;
  };

  const isSparseCatalogPreviewRow = (row) => {
    if (!row || typeof row !== "object") return true;
    const informativeKeys = Object.entries(row)
      .filter(([, value]) => value != null && String(value).trim() !== "")
      .map(([key]) => key);
    const sparseKeys = new Set(["set_id", "name", "title", "kind", "indicator_id", "source_type"]);
    return informativeKeys.every((key) => sparseKeys.has(key));
  };

  const resolveIndexedCatalogPreviewRow = (def, row) => {
    if (!def || !row || typeof row !== "object") return row;
    const requestedSetId = String(row.set_id ?? row.code ?? row.series_id ?? "").trim();
    if (!requestedSetId) return row;
    const rows = Array.isArray(indexedRowsByCat[def.id]) ? indexedRowsByCat[def.id] : [];
    if (!rows.length) return row;
    const requestedSetKey = requestedSetId.toLowerCase();
    const requestedIndicator = String(row.indicator_id || "").trim().toLowerCase();
    const exact = rows.find((candidate) => {
      const sid = String(candidate?.set_id ?? candidate?.code ?? candidate?.series_id ?? "").trim().toLowerCase();
      if (!sid || sid !== requestedSetKey) return false;
      if (!requestedIndicator) return true;
      return String(candidate?.indicator_id || "").trim().toLowerCase() === requestedIndicator;
    });
    const matched =
      exact ||
      rows.find((candidate) => {
        const sid = String(candidate?.set_id ?? candidate?.code ?? candidate?.series_id ?? "").trim().toLowerCase();
        return sid && sid === requestedSetKey;
      });
    if (!matched) return row;
    if (isSparseCatalogPreviewRow(row)) {
      return {
        ...row,
        ...matched,
        ...(row.indicator_id ? { indicator_id: row.indicator_id } : {}),
      };
    }
    return {
      ...matched,
      ...row,
      query_params: row.query_params || matched.query_params,
      available_dimensions: row.available_dimensions || matched.available_dimensions,
      full_path: row.full_path || matched.full_path,
      parentPath: row.parentPath || matched.parentPath,
      path: row.path || matched.path,
    };
  };

  const collectEurostatPreviewFallbackRows = (def, row) => {
    if (def?.sourceType !== "eurostat") return [];
    const currentSetId = String(row?.set_id || "").trim();
    const out = [];
    const seen = new Set(currentSetId ? [currentSetId] : []);
    const push = (candidate) => {
      if (!candidate || !isCatalogRowPreviewEligible(def, candidate)) return;
      const sid = String(candidate.set_id || "").trim();
      if (!sid || seen.has(sid)) return;
      seen.add(sid);
      out.push(candidate);
    };

    for (const entry of flatResults || []) {
      if (entry?.def?.id !== def.id) continue;
      push(entry.row);
      if (out.length >= 12) break;
    }
    if (out.length >= 12) return out;

    const allRows = Array.isArray(indexedRowsByCat[def.id]) ? indexedRowsByCat[def.id] : [];
    const parent = String(row?.parentPath || "").trim();
    for (const r of allRows) {
      if (!r || r.kind !== "set") continue;
      if (parent && String(r.parentPath || "").trim() !== parent) continue;
      push(r);
      if (out.length >= 12) break;
    }
    if (out.length >= 12) return out;

    for (const r of allRows) {
      if (!r || r.kind !== "set") continue;
      push(r);
      if (out.length >= 12) break;
    }
    return out;
  };

  const fetchPreview = async (def, row, indicatorId, indicatorIds = [], geoValues = [], dimensionFilters = null) => {
    row = resolveIndexedCatalogPreviewRow(def, row);
    const effectiveDef = resolveCatalogRowDef(def, row);
    const indexedRowsForDef = Array.isArray(indexedRowsByCat[def.id]) ? indexedRowsByCat[def.id] : [];
    const previewRow =
      effectiveDef?.sourceType === "csu" ? enrichCsuCatalogRow(row, indexedRowsForDef) : row;
    setPreviewData(null);
    setPreviewEffectiveRow(null);
    setPreviewError("");
    setPreviewFallbackNotice("");
    setPreviewLoading(true);
    setDownloadOpen(false);
    logCatalogPreviewDebug("loading_start", {
      sourceType: def?.sourceType,
      setId: row?.set_id,
      indicatorId: indicatorId ?? null,
      indicatorIds,
    });
    const assignPreviewData = (payload, targetRow = previewRow) => {
      const normalized = normalizePreviewPayload(payload, def?.sourceType || "");
      const shape = previewShapeDebug(payload);
      logCatalogPreviewDebug("preview_state_assign", {
        sourceType: def?.sourceType,
        setId: targetRow?.set_id,
        rows: normalized?.rows?.length || 0,
        columns: normalized?.columns?.length || 0,
        shape,
      });
      setPreviewEffectiveRow(applyPreviewSelectionToRow(def, targetRow, normalized));
      setPreviewData(normalized);
      return normalized;
    };
    if (def?.sourceType === "internal") {
      const sid = String(row?.set_id || "").trim();
      const m = /^user_upload:([0-9a-f-]{36})$/i.exec(sid);
      if (!m) {
        setPreviewError("Náhled pro tento typ interní položky zatím není v tomto pohledu k dispozici.");
        setPreviewLoading(false);
        return;
      }
      try {
        const { data } = await api.get(`/me/uploads/${m[1]}/preview`);
        const sample = Array.isArray(data?.sample_rows) ? data.sample_rows : [];
        const err = data?.error ? formatPreviewMessage(data.error) : "";
        const payload = {
          rows: sample,
          columns: Array.isArray(data?.columns) ? data.columns : [],
          source: { name: row.name || sid, source_type: "internal" },
        };
        assignPreviewData(payload, row);
        setPreviewError(formatPreviewMessage(err));
      } catch (e) {
        const err = formatPreviewMessage(formatApiErrorFromAxios(e) || e?.response?.data);
        setPreviewError(err);
        logCatalogPreviewDebug("error_state_set", { endpoint: `/me/uploads/${m[1]}/preview`, error: err });
      } finally {
        logCatalogPreviewDebug("loading_end", { sourceType: def?.sourceType, setId: row?.set_id });
        setPreviewLoading(false);
      }
      return;
    }
    const normalizedIds = normalizeSelectedIndicators(indicatorIds);
    const fromArg =
      indicatorId != null && String(indicatorId).trim() !== "" ? String(indicatorId).trim() : "";
    const selectedPrimary = String(
      fromArg || String(row?.indicator_id || "").trim() || normalizedIds[0] || "",
    ).trim();
    const selectedMany = normalizedIds.length
      ? normalizedIds
      : selectedPrimary
        ? [selectedPrimary]
        : [];
    const selectedGeo = Array.isArray(geoValues)
      ? [...new Set(geoValues.map((x) => String(x || "").trim().toUpperCase()).filter(Boolean))]
      : [];
    const selectedDimensionFilters =
      dimensionFilters && typeof dimensionFilters === "object" && !Array.isArray(dimensionFilters)
        ? Object.fromEntries(Object.entries(dimensionFilters).filter(([k, v]) => k && v != null && k !== "indicator_id"))
        : {};
    const multiSelectionActive = selectedMany.length > 1;
    const cachedDeepPreview =
      row?.fromDeepAi && row?.previewPayload && typeof row.previewPayload === "object"
        ? row.previewPayload
        : null;
    const rowIndicatorId = String(row?.indicator_id || "").trim();
    const manualPreviewSelection =
      selectedMany.length > 1 ||
      selectedGeo.length > 0 ||
      Object.keys(selectedDimensionFilters).length > 0 ||
      (fromArg && (!rowIndicatorId || fromArg !== rowIndicatorId));
    if (cachedDeepPreview && !manualPreviewSelection && isDeepSearchCachedPreviewUsable(cachedDeepPreview)) {
      assignPreviewData(cachedDeepPreview, row);
      setPreviewError("");
      setPreviewLoading(false);
      logCatalogPreviewDebug("deep_search_cached_preview_used", {
        sourceType: def?.sourceType,
        setId: row?.set_id,
      });
      return;
    }
    let usedSourceId = null;
    let attemptedLivePreview = false;
    let canRetryLiveFallback = false;
    const loadLivePreview = async (
      targetRow = previewRow,
      selection = { selectedPrimary, selectedMany },
    ) => {
      attemptedLivePreview = true;
      const previewUserQuery = String(
        (useAiAssistant ? (debouncedAi || aiQuery) : (submittedCrossQuery || crossSearchQuery)) || "",
      ).trim();
      const body = buildCatalogPreviewRequestBody({
        def: effectiveDef,
        row: targetRow,
        previewData,
        dimensionFilters: selectedDimensionFilters,
        geoValues: selectedGeo,
        indicatorId: selection?.selectedPrimary,
        indicatorIds: selection?.selectedMany,
      });
      if (previewUserQuery) body.user_query = previewUserQuery;
      if (effectiveDef?.sourceType === "imf") {
        const freq = normalizeImfFreq(
          targetRow?.query_params?.imf_frekvence || targetRow?.frekvence || imfPreviewFreq,
        );
        body.query_params = {
          ...(body.query_params && typeof body.query_params === "object" ? body.query_params : {}),
          imf_frekvence: freq,
        };
      }
      logCatalogPreviewDebug("endpoint_call", { endpoint: "/api/catalog/preview", method: "POST", body });
      const { data } = await api.post("/catalog/preview", body);
      logCatalogPreviewDebug("endpoint_response", {
        endpoint: "/api/catalog/preview",
        status: 200,
        shape: previewShapeDebug(data),
      });
      const normalized = assignPreviewData(data, targetRow);
      const shape = previewShapeDebug(data);
      const hasKnownDataArrays =
        shape.hasRows || shape.hasData || shape.hasObservations;
      const normalizedRows = Array.isArray(normalized?.rows) ? normalized.rows : [];
      if (effectiveDef?.sourceType === "imf" && normalizedRows.length === 0) {
        const imfNotice = String(data?.imf_catalog_notice || data?.metadata?.imf_catalog_notice || data?.message || "").trim();
        setPreviewError(formatPreviewMessage(imfNotice ? `${IMF_PREVIEW_UNAVAILABLE_CZ}\n\nDetail: ${imfNotice}` : IMF_PREVIEW_UNAVAILABLE_CZ));
      } else if (!hasKnownDataArrays && !String(data?.message || "").trim() && !String(data?.error || "").trim()) {
        const errMsg = buildUnknownPreviewShapeMessage(data);
        setPreviewError(formatPreviewMessage(errMsg));
        logCatalogPreviewDebug("error_state_set", { endpoint: "/api/catalog/preview", error: errMsg });
      } else {
        setPreviewError("");
      }
      return normalized;
    };
    const loadLivePreviewWithEurostatFallback = async () => {
      try {
        return await loadLivePreview(previewRow, { selectedPrimary, selectedMany });
      } catch (primaryErr) {
        if (def?.sourceType !== "eurostat") throw primaryErr;
        const candidates = collectEurostatPreviewFallbackRows(def, row);
        for (const cand of candidates) {
          try {
            const candPrimary = String(cand?.indicator_id || "").trim();
            const data = await loadLivePreview(
              cand,
              candPrimary
                ? { selectedPrimary: candPrimary, selectedMany: [candPrimary] }
                : { selectedPrimary: "", selectedMany: [] },
            );
            const fallbackName = String(cand?.name || cand?.set_id || "").trim();
            if (fallbackName) {
              setPreviewFallbackNotice(`Automaticky zvolena alternativní řada: ${fallbackName}`);
              toast.info(`Původní Eurostat řada nešla načíst, zobrazuji alternativu: ${fallbackName}`);
            }
            return data;
          } catch {
            // Try next candidate.
          }
        }
        throw primaryErr;
      }
    };
    try {
      const rk = rowExistingKey(def, row, wbCountry);
      const source = sourceByKeyByCat[def.id]?.get(rk) || null;

      // 1) Když je řada už interní zdroj, preferujeme DB preview + auto sync.
      if (source?.id) {
        if (
          def.sourceType === "eurostat" &&
          (selectedGeo.length > 0 || Object.keys(selectedDimensionFilters).length > 0)
        ) {
          await loadLivePreviewWithEurostatFallback();
          return;
        }
        if (def.sourceType === "csu" && Object.keys(selectedDimensionFilters).length > 0) {
          await loadLivePreviewWithEurostatFallback();
          return;
        }
        if (multiSelectionActive) {
          await loadLivePreviewWithEurostatFallback();
          return;
        }
        usedSourceId = source.id;
        canRetryLiveFallback = true;
        const params = buildSourcePreviewParams({
          sourceType: def?.sourceType || "",
          limit: 1000,
          indicatorId: selectedPrimary,
          groupField: preview?.group_field || "",
          geoValues: selectedGeo,
          dimensionFilters: selectedDimensionFilters,
        });
        logCatalogPreviewDebug("endpoint_call", {
          endpoint: `/api/sources/${source.id}/preview`,
          method: "GET",
          params,
        });
        let { data } = await api.get(`/sources/${source.id}/preview`, { params });
        logCatalogPreviewDebug("endpoint_response", {
          endpoint: `/api/sources/${source.id}/preview`,
          status: 200,
          shape: previewShapeDebug(data),
        });
        data = normalizePreviewPayload(data, def?.sourceType || "");
        const empty = !Array.isArray(data?.rows) || data.rows.length === 0;
        // Pro ČSÚ s ≤1 řádkem v DB (stará data před fix dedupu) jdi rovnou na
        // live API preview — DB data jsou nepoužitelná a live načtení je rychlejší
        // než spouštění plné synchronizace.
        const thinCsu = def.sourceType === "csu" && (data?.rows?.length ?? 0) <= 1;
        // TradingEconomics: nejdřív live náhled z API (sync může trvat desítky sekund a často skončí prázdně).
        if ((empty || thinCsu) && def.sourceType === "tradingeconomics") {
          try {
            await loadLivePreviewWithEurostatFallback();
            return;
          } catch (teLiveErr) {
            logCatalogPreviewDebug("tradingeconomics_live_preview_failed", {
              message: teLiveErr?.message || String(teLiveErr),
            });
          }
        }
        if ((empty || thinCsu) && def.sourceType === "ecb" && isEcbCuratedRowPreviewEligible(row)) {
          try {
            await loadLivePreviewWithEurostatFallback();
            return;
          } catch (ecbLiveErr) {
            logCatalogPreviewDebug("ecb_live_preview_failed", {
              message: ecbLiveErr?.message || String(ecbLiveErr),
            });
          }
        }
        if (empty || thinCsu) {
          if (def.sourceType === "eurostat") {
            logCatalogPreviewDebug("source_preview_empty_fallback_live", {
              sourceId: source.id,
              reason: "eurostat_empty_rows",
              selectedPrimary,
            });
            await loadLivePreviewWithEurostatFallback();
            return;
          }
          if (!thinCsu) {
            setSyncingPreview(true);
            try {
              logCatalogPreviewDebug("endpoint_call", {
                endpoint: `/api/sources/${source.id}/${canAddSources ? "sync" : "sync-public"}`,
                method: "POST",
              });
              await api.post(`/sources/${source.id}/${canAddSources ? "sync" : "sync-public"}`);
            } catch {
              // i když sync request selže, zkusíme fallback níže.
            }
            const pollMax = def.sourceType === "tradingeconomics" ? 12 : 30;
            const pollMs = def.sourceType === "tradingeconomics" ? 2000 : 3000;
            for (let i = 0; i < pollMax; i += 1) {
              await new Promise((resolve) => setTimeout(resolve, pollMs));
              if (canAddSources) {
                logCatalogPreviewDebug("endpoint_call", {
                  endpoint: `/api/sources/${source.id}`,
                  method: "GET",
                });
                const { data: st } = await api.get(`/sources/${source.id}`);
                if (st?.last_sync_status && st.last_sync_status !== "running") break;
              } else {
                logCatalogPreviewDebug("endpoint_call", {
                  endpoint: `/api/sources/${source.id}/preview`,
                  method: "GET",
                  params,
                });
                const { data: pr } = await api.get(`/sources/${source.id}/preview`, { params });
                if (pr?.rows?.length > 0) break;
              }
            }
            ({ data } = await api.get(`/sources/${source.id}/preview`, { params }));
            data = normalizePreviewPayload(data, def?.sourceType || "");
            setSyncingPreview(false);
          }
          const stillEmptyAfterSync = !Array.isArray(data?.rows) || data.rows.length === 0;
          if (stillEmptyAfterSync || thinCsu) {
            await loadLivePreviewWithEurostatFallback();
            return;
          }
        }
        // Pokud jsme sem dorazili, data jsou platná (rows.length > 0 a není thinCsu).
        const stillEmpty = !Array.isArray(data?.rows) || data.rows.length === 0;
        if (stillEmpty) {
          await loadLivePreviewWithEurostatFallback();
        } else {
          const patchedData = selectedMany.length
            ? {
                ...data,
                selected_indicator: selectedMany[0],
                selected_indicators: selectedMany,
              }
            : data;
          assignPreviewData(patchedData, row);
        }
      } else {
        // 2) Fallback: přímý live preview z katalogového API (bez DB source).
        canRetryLiveFallback = false;
        await loadLivePreviewWithEurostatFallback();
      }
    } catch (e) {
      // Poslední fallback jen po chybě při preview z interního source.
      let finalErr = e;
      if (canRetryLiveFallback && !attemptedLivePreview) {
        try {
          await loadLivePreviewWithEurostatFallback();
          return;
        } catch (e2) {
          finalErr = e2;
        }
      }
      const status = finalErr?.response?.status;
      const errPayloadRaw = finalErr?.response?.data;
      const errPayload = unwrapApiErrorPayload(errPayloadRaw);
      const errStatus = String(errPayload?.status || "").toLowerCase();
      const isNeedsFilters = Number(status) === 422 && errStatus === "needs_filters";
      const isEurostat413 =
        def?.sourceType === "eurostat" &&
        (Number(status) === 413 || Number(errPayload?.upstream_status) === 413);
      const isStructuredEurostat422 =
        Number(status) === 422 &&
        errStatus === "error" &&
        String(errPayload?.error || "").toUpperCase().includes("EUROSTAT_UPSTREAM_422");
      if (isEurostat413) {
        const msg =
          (typeof errPayload?.detail_cs === "string" && errPayload.detail_cs.trim()) ||
          "Eurostat odmítl požadavek — náhled je příliš rozsáhlý. Zkuste méně zemí (max. 3), jednu hodnotu na dimenzi nebo kratší období.";
        setPreviewError(msg);
        logCatalogPreviewDebug("eurostat_413_state_set", {
          sourceType: def?.sourceType,
          setId: row?.set_id,
          requested_filters: errPayload?.requested_filters,
        });
      } else if (isNeedsFilters) {
        const normalizedNeeds = normalizePreviewPayload(
          {
            source: { source_type: def?.sourceType, name: row?.name, set_id: row?.set_id },
            rows: [],
            fields: [],
            metadata: {
              ...(errPayload || {}),
              filters_applied: errPayload?.suggested_filters || {},
            },
            status: "needs_filters",
            dataset_id: errPayload?.dataset_id || row?.set_id || "",
            request_id: errPayload?.request_id || "",
            available_dimensions: errPayload?.available_dimensions || {},
            missing_filters: errPayload?.missing_filters || [],
            requested_filters: errPayload?.requested_filters || errPayload?.filters_applied || {},
            message:
              errPayload?.message ||
              "Datova sada je prilis siroka pro nahled. Vyberte dimenze/filtry a zkuste to znovu.",
          },
          def?.sourceType || "",
        );
        setPreviewData(normalizedNeeds);
        setPreviewError("");
        logCatalogPreviewDebug("needs_filters_state_set", {
          sourceType: def?.sourceType,
          setId: row?.set_id,
          responseKeys: Object.keys(errPayload || {}),
        });
      } else if (isStructuredEurostat422) {
        const diagnosticPreview = buildPreviewPayloadFromStructuredError(
          errPayloadRaw,
          { source_type: def?.sourceType, set_id: row?.set_id, name: row?.name },
        );
        setPreviewData(diagnosticPreview);
        setPreviewError("");
        logCatalogPreviewDebug("structured_422_state_set", {
          sourceType: def?.sourceType,
          setId: row?.set_id,
          errorCode: errPayload?.error,
          requestId: errPayload?.request_id,
        });
      } else {
        const baseErrMsg =
          formatPreviewMessage(formatApiErrorFromAxios(finalErr)) ||
          formatPreviewMessage(finalErr?.response?.data);
        const errMsg =
          def?.sourceType === "imf"
            ? `${IMF_PREVIEW_UNAVAILABLE_CZ}${baseErrMsg ? `\n\nDetail: ${baseErrMsg}` : ""}`
            : baseErrMsg;
        setPreviewError(formatPreviewMessage(errMsg, "Nahled dat se nepodarilo nacist."));
        logCatalogPreviewDebug("error_state_set", {
          sourceType: def?.sourceType,
          setId: row?.set_id,
          status,
          code: finalErr?.code || "",
          message: finalErr?.message || "",
          response: errPayloadRaw || null,
          error: errMsg,
        });
      }
      setSyncingPreview(false);
    } finally {
      if (usedSourceId) {
        try {
          const { data: srcs } = await api.get("/sources/catalog-stubs");
          setSources(Array.isArray(srcs) ? srcs : []);
        } catch {
          /* ignore */
        }
      }
      logCatalogPreviewDebug("loading_end", { sourceType: def?.sourceType, setId: row?.set_id });
      setPreviewLoading(false);
    }
  };

  const catalogPreviewKey = useCallback(
    (def, row) => {
      const previewInd = String(row?.indicator_id || "").trim();
      return `${def.id}-${row.set_id}-${def.needsCountry ? wbCountry : ""}${previewInd ? `__${previewInd}` : ""}`;
    },
    [wbCountry],
  );

  const runCatalogRowPrimaryAction = useCallback(
    (def, row, { showToast = true } = {}) => {
      const action = resolveCatalogRowPrimaryAction(def, row);
      if (action.type === "navigate" && action.path) {
        if (showToast && action.toast) toast.info(action.toast, { duration: 4200 });
        nav(action.path);
        return true;
      }
      if (action.type === "blocked" && showToast) {
        toast.info(action.toast, { duration: 5000 });
      }
      return false;
    },
    [nav],
  );

  const openBisDataflowQuickPreview = useCallback(
    async (def, row) => {
      const flowId = resolveBisFlowHint(row);
      if (!flowId) {
        runCatalogRowPrimaryAction(def, row);
        return;
      }
      bisDataflowPreviewActiveRef.current = true;
      const shellRow = {
        ...row,
        name: String(row?.name || row?.title || flowId).trim(),
        set_id: String(row?.set_id || `${flowId}||DATAFLOW`).trim(),
        item_kind: row?.item_kind || "dataflow",
        kind: "set",
      };
      setSeriesDetailTarget({ def, row: shellRow });
      setSeriesDetailOpen(true);
      setSeriesDetailChartDisplayState(null);
      setCatalogViewMode("detail");
      setMobileBrowseOpen(false);
      setPreviewLoading(true);
      setPreviewError("");
      setPreviewFallbackNotice("");

      try {
        const result = await resolveBisDataflowPreviewRow(api, flowId, row, def, {
          timeout: BIS_BROWSER_TIMEOUT_MS,
        });
        if (result.kind === "wizard") {
          setPreviewLoading(false);
          setBisWizardSearch({
            dataflowId: result.dataflowId,
            title: result.title || shellRow.name,
          });
          return;
        }
        const previewRow = result.row;
        const key = catalogPreviewKey(def, previewRow);
        setPreviewKey(key);
        setPreviewTarget({ def, row: previewRow });
        setSeriesDetailTarget({ def, row: previewRow });
        await fetchPreview(def, previewRow);
      } catch (e) {
        setPreviewLoading(false);
        const errMsg = formatPreviewMessage(formatApiErrorFromAxios(e) || e?.response?.data);
        setPreviewError(errMsg);
        toast.error(errMsg || "BIS náhled se nepodařilo načíst.");
      } finally {
        bisDataflowPreviewActiveRef.current = false;
      }
    },
    [catalogPreviewKey, runCatalogRowPrimaryAction],
  );

  const dispatchCatalogRowPrimaryAction = useCallback(
    (def, row, opts = {}) => {
      const action = resolveCatalogRowPrimaryAction(def, row);
      if (action.type === "bis-dataflow-preview") {
        void openBisDataflowQuickPreview(def, row);
        return true;
      }
      return runCatalogRowPrimaryAction(def, row, opts);
    },
    [openBisDataflowQuickPreview, runCatalogRowPrimaryAction],
  );

  const openPreviewForRow = useCallback(
    async (def, row) => {
      if (!isCatalogRowPreviewEligible(def, row)) {
        dispatchCatalogRowPrimaryAction(def, row);
        return;
      }
      const key = catalogPreviewKey(def, row);
      setPreviewKey(key);
      setPreviewTarget({ def, row });
      const eff = resolveCatalogRowDef(def, row);
      if (eff?.sourceType === "imf") {
        setImfPreviewFreq(
          normalizeImfFreq(row?.frekvence || row?.query_params?.imf_frekvence || "A"),
        );
      }
      const initialInd = String(row?.indicator_id || "").trim();
      await fetchPreview(def, row, initialInd || undefined);
    },
    [catalogPreviewKey, fetchPreview, dispatchCatalogRowPrimaryAction],
  );

  const togglePreview = async (def, row) => {
    if (!isCatalogRowPreviewEligible(def, row)) {
      dispatchCatalogRowPrimaryAction(def, row);
      return;
    }
    const key = catalogPreviewKey(def, row);
    if (previewKey === key) {
      setPreviewKey(null);
      setPreviewTarget(null);
      setPreviewData(null);
      setPreviewError("");
      setPreviewFallbackNotice("");
      setPreviewSelectedIndicators([]);
      setPreviewCompareList([]);
      setDownloadOpen(false);
      setSyncingPreview(false);
      return;
    }
    await openPreviewForRow(def, row);
  };

  useEffect(() => {
    if (!seriesDetailOpen || !seriesDetailTarget) return;
    if (bisDataflowPreviewActiveRef.current) return;
    const { def, row } = seriesDetailTarget;
    if (!isCatalogRowPreviewEligible(def, row)) return;
    const key = catalogPreviewKey(def, row);
    if (previewKey === key) return;
    void openPreviewForRow(def, row);
  }, [
    seriesDetailOpen,
    seriesDetailTarget,
    previewKey,
    catalogPreviewKey,
    openPreviewForRow,
  ]);

  useEffect(() => {
    if (!seriesDetailOpen) return;
    setSeriesDetailChartDisplayState(null);
  }, [seriesDetailOpen, previewKey, previewData?.request_id]);

  const handleExplorerRowSelect = useCallback(
    (row, columnIndex) => {
      const path = String(row?.path || "");
      if (!path) return;
      const isCategory = explorerRowIsCategory(row);

      setBrowseColumnSelection((prev) => {
        const next = prev.slice(0, columnIndex);
        next[columnIndex] = path;
        return next;
      });

      if (isCategory) {
        setCatalogViewMode("browse");
        ensureBrowsePathOpen(path);
        loadBrowseCategoryChildren(row, columnIndex);
        return;
      }
      if (!isCatalogRowPreviewEligible(browseDef, row)) {
        if (dispatchCatalogRowPrimaryAction(browseDef, row)) return;
      }
      setSeriesDetailTarget({ def: browseDef, row });
      setSeriesDetailOpen(true);
      setCatalogViewMode("detail");
      setMobileBrowseOpen(false);
      void openPreviewForRow(browseDef, row);
    },
    [
      browseDef,
      ensureBrowsePathOpen,
      loadBrowseCategoryChildren,
      openPreviewForRow,
      dispatchCatalogRowPrimaryAction,
    ],
  );

  const extractGeoValuesFromDimensionFilters = (filters) =>
    extractCountryCodesFromFilters(filters);

  const closePreview = () => {
    setPreviewKey(null);
    setPreviewTarget(null);
    setPreviewData(null);
    setPreviewEffectiveRow(null);
    setPreviewSelectedIndicators([]);
    setPreviewCompareList([]);
    setPreviewError("");
    setPreviewFallbackNotice("");
    setDownloadOpen(false);
    setSyncingPreview(false);
    setSharePreviewTarget(null);
  };

  useEffect(() => {
    if (!previewFromUrl || !setIdFromUrl) return;
    const catalogParam = catalogBrowseIdFromUrl || catalogFromUrl;
    if (!catalogParam) return;
    const resolvedId = resolveCatalogShareId(catalogParam);
    const def = CATALOGS.find((c) => c.id === resolvedId || c.sourceType === resolvedId);
    if (!def) return;
    const baseRow = {
      set_id: setIdFromUrl,
      name: initialCatalogQ || setIdFromUrl,
      kind: "set",
      ...(indicatorFromUrl ? { indicator_id: indicatorFromUrl } : {}),
    };
    const row = resolveIndexedCatalogPreviewRow(def, baseRow);
    const rowSource = row === baseRow || isSparseCatalogPreviewRow(row) ? "url" : "catalog";
    const openKey = `${def.id}|${setIdFromUrl}|${indicatorFromUrl}|${rowSource}`;
    if (sharePreviewOpenedRef.current === openKey) return;
    sharePreviewOpenedRef.current = openKey;
    setSharePreviewTarget({ def, row });
    setPreviewTarget({ def, row });
    const key = `${def.id}-${setIdFromUrl}-${def.needsCountry ? wbCountry : ""}${
      indicatorFromUrl ? `__${indicatorFromUrl}` : ""
    }`;
    setPreviewKey(key);
    if (def.sourceType === "imf") {
      setImfPreviewFreq(normalizeImfFreq("A"));
    }
    void fetchPreview(def, row, indicatorFromUrl || undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    previewFromUrl,
    setIdFromUrl,
    catalogFromUrl,
    catalogBrowseIdFromUrl,
    indicatorFromUrl,
    initialCatalogQ,
    indexedRowsByCat,
  ]);

  useEffect(() => {
    if (!previewKey) {
      setPreviewSelectedIndicators([]);
      setPreviewCompareList([]);
      return;
    }
    const fromMany = normalizeSelectedIndicators(previewData?.selected_indicators);
    if (fromMany.length) {
      setPreviewSelectedIndicators(fromMany);
      return;
    }
    const one = String(previewData?.selected_indicator || "").trim();
    setPreviewSelectedIndicators(one ? [one] : []);
  }, [previewKey, previewData]);

  const downloadCurrent = async (fmt, def, row) => {
    if (!canExportData) {
      toast.error(exportDataLockMsg || "Stahování není s vaším účtem k dispozici.");
      return;
    }
    setDownloadingFmt(fmt);
    setDownloadOpen(false);
    try {
      if (def?.sourceType === "internal") {
        const sid = String(row?.set_id || "").trim();
        const m = /^user_upload:([0-9a-f-]{36})$/i.exec(sid);
        if (!m) {
          toast.error("Stahování je zatím dostupné jen pro nahrané soubory (interní upload).");
          setDownloadingFmt("");
          return;
        }
        const resp = await api.get(`/me/uploads/${m[1]}/file`, { responseType: "blob" });
        const cd = resp.headers?.["content-disposition"] || "";
        const fnm = /filename\s*=\s*"?([^";]+)"?/i.exec(cd);
        const fname = fnm?.[1] || `upload-${m[1]}`;
        const url = URL.createObjectURL(resp.data);
        const a = document.createElement("a");
        a.href = url;
        a.download = fname;
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(url);
        toast.success(`Stahuji ${fname}`);
        setDownloadingFmt("");
        return;
      }
      const body = { ...buildCatalogPreviewBody(def, row, wbCountry), format: fmt };
      // Musí být stejná série jako v náhledu (ARAD: jeden `indicator_id` z celého setu).
      const many = normalizeSelectedIndicators(previewSelectedIndicators);
      const sel = String(many[0] || previewData?.selected_indicator || "").trim();
      if (sel) body.selected_indicator = sel;
      if (many.length > 1) body.selected_indicators = many;
      const resp = await api.post("/catalog/download", body, { responseType: "blob" });
      // Z Content-Disposition vytáhneme jméno; když není, sestavíme z set_id.
      const cd = resp.headers?.["content-disposition"] || "";
      const m = /filename\s*=\s*"?([^";]+)"?/i.exec(cd);
      const fname = m?.[1] || `${def.sourceType}-${String(row.set_id).replace(/\//g, "-")}.${fmt}`;
      const url = URL.createObjectURL(resp.data);
      const a = document.createElement("a");
      a.href = url; a.download = fname;
      document.body.appendChild(a); a.click(); a.remove();
      URL.revokeObjectURL(url);
      toast.success(`Stahuji ${fname}`);
    } catch (e) {
      // Když API vrátí JSON chybu uvnitř blobu, axios ji nedá do detail —
      // pokusíme se ji přečíst a zobrazit, jinak fallback na obecnou hlášku.
      let msg = formatApiErrorFromAxios(e);
      try {
        const blob = e?.response?.data;
        if (blob && typeof blob.text === "function") {
          const txt = await blob.text();
          const parsed = JSON.parse(txt);
          if (parsed?.detail) msg = String(parsed.detail);
        }
      } catch { /* keep generic msg */ }
      toast.error(msg);
    }
    setDownloadingFmt("");
  };

  const syncAndReloadPreview = async (def, row) => {
    setSyncingPreview(true);
    try {
      const rk = rowExistingKey(def, row, wbCountry);
      let source = sourceByKeyByCat[def.id]?.get(rk) || null;
      if (!source) {
        if (!canAddSources) {
          toast.error("Zdroj zatím neexistuje. O synchronizaci požádejte administrátora.");
          return;
        }
        if (!def.addPath) {
          toast.error("Zdroj zatím neexistuje a tento katalog neumožňuje jeho vytvoření odtud — použijte Zdroje.");
          return;
        }
        // Pokud zdroj ještě není přidaný, vytvoříme ho z katalogové položky.
        const body = buildCatalogAddSourceBody(def, row, wbCountry);
        const { data } = await api.post(def.addPath, body);
        source = data || null;
        const { data: srcs } = await api.get("/sources/catalog-stubs");
        setSources(Array.isArray(srcs) ? srcs : []);
        if (!source?.id) {
          const nextMap = buildSourceByKey(def, srcs || []);
          source = nextMap.get(rk) || source;
        }
      }
      if (!source?.id) {
        toast.error("Zdroj se nepodařilo najít ani vytvořit.");
        return;
      }

      const { data: syncKickoff } = await api.post(
        `/sources/${source.id}/${canAddSources ? "sync" : "sync-public"}`
      );
      const kickoffSyncState = String(syncKickoff?.sync_state || "").trim().toLowerCase();
      const kickoffQueued =
        String(syncKickoff?.status || "").trim().toLowerCase() === "queued" &&
        (kickoffSyncState === "rate_limited" || Boolean(syncKickoff?.retry_at));
      if (kickoffQueued) {
        toast.info(
          syncKickoff?.message ||
            "OECD API dočasně omezuje počet dotazů. Další pokus je zařazen do fronty."
        );
      } else {
        toast.info("Synchronizace spuštěna. Čekám na dokončení…");
      }
      const geoFromApplied = extractGeoValuesFromDimensionFilters(previewData?.metadata?.filters_applied);
      const selectedGeoFromPreview = geoFromApplied.length
        ? geoFromApplied
        : extractGeoValuesFromDimensionFilters(previewData?.requested_filters);

      // Poll status zdroje do dokončení (nebo timeout), potom obnov preview.
      const syncPollMax = def?.sourceType === "tradingeconomics" ? 12 : 25;
      const syncPollMs = def?.sourceType === "tradingeconomics" ? 2000 : 4000;
      let lastSourceStatus = null;
      for (let i = 0; i < syncPollMax; i += 1) {
        await new Promise((resolve) => setTimeout(resolve, syncPollMs));
        if (canAddSources) {
          const { data: s } = await api.get(`/sources/${source.id}`);
          lastSourceStatus = s || null;
          if (s?.last_sync_status && s.last_sync_status !== "running") break;
        } else {
          const pollParams = buildSourcePreviewParams({
            sourceType: def?.sourceType || "",
            limit: 20,
            indicatorId: previewData?.selected_indicator || "",
            groupField: previewData?.group_field || "",
            geoValues: selectedGeoFromPreview,
          });
          const { data: pr } = await api.get(`/sources/${source.id}/preview`, {
            params: pollParams,
          });
          if (pr?.rows?.length > 0) break;
        }
      }
      const many = normalizeSelectedIndicators(previewSelectedIndicators);
      await fetchPreview(
        def,
        row,
        many[0] || previewData?.selected_indicator || "",
        many.length ? many : undefined,
      );
      const finalSyncState = String(lastSourceStatus?.sync_state || "").trim().toLowerCase();
      const finalQueueState = String(lastSourceStatus?.sync_queue_state || "").trim().toLowerCase();
      if (finalSyncState === "rate_limited" || finalQueueState === "pending") {
        toast.info(
          lastSourceStatus?.last_sync_message ||
            "OECD API dočasně omezuje počet dotazů. Data zatím nebyla stažena."
        );
      } else if (finalSyncState === "synced_empty") {
        toast.info("Synchronizace proběhla, ale zdroj zatím neobsahuje žádné hodnoty.");
      } else {
        toast.success("Náhled obnoven. Data lze zobrazit i stáhnout.");
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setSyncingPreview(false);
    }
  };

  const confirmPersonalDashboardPage = useCallback(async () => {
    if (!pagePick?.built) return;
    setAddingToDash(true);
    try {
      const pid = pagePick.selectedId;
      if (!pid) {
        toast.error("Vyberte stránku.");
        return;
      }
      await createExternalCatalogWidgetWithSnapshot(api, pid, pagePick.built);
      toast.success("Graf byl přidán do vašeho dashboardu.", {
        action: {
          label: "Otevřít můj dashboard",
          onClick: () => nav("/my-dashboard"),
        },
      });
      setPagePick(null);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se přidat widget");
    } finally {
      setAddingToDash(false);
    }
  }, [pagePick, nav]);

  const addSeriesToPersonalDashboard = useCallback(
    async (def, row, seriesConfig = null) => {
      if (!isSubscriber || !canPersonalDashboard) {
        toast.error(personalDashMsg || "Osobní dashboard není u vašeho účtu k dispozici.");
        return;
      }
      if (!canSaveWidget) {
        toast.error(saveWidgetMsg || "Uložení widgetů není s vaším plánem k dispozici.");
        return;
      }
      const built = buildExternalCatalogChartConfig(
        def,
        previewData && previewKey === catalogPreviewKey(def, row) ? previewData : null,
        row,
        wbCountry,
        seriesConfig
      );
      if (!built) {
        toast.error("Vyčkejte na dokončení náhledu a případně vyberte ukazatel v přehledu dat.");
        return;
      }
      setAddingToDash(true);
      try {
        let { data: pages } = await api.get("/me/dashboard/pages");
        let pl = Array.isArray(pages) ? pages : [];
        if (!pl.length) {
          const { data: created } = await api.post("/me/dashboard/pages", { title: "Můj přehled" });
          if (created?.id) pl = [created];
          else {
            toast.error("Nepodařilo se vytvořit výchozí stránku dashboardu.");
            return;
          }
        }
        if (pl.length > 1) {
          const defId = (pl.find((p) => p.is_default) || pl[0])?.id;
          setPagePick({
            built,
            pages: pl,
            selectedId: defId || pl[0].id,
          });
          return;
        }
        const page = pl[0];
        await createExternalCatalogWidgetWithSnapshot(api, page.id, built);
        toast.success("Graf byl přidán do vašeho dashboardu.", {
          action: {
            label: "Otevřít můj dashboard",
            onClick: () => nav("/my-dashboard"),
          },
        });
      } catch (e) {
        toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se přidat widget");
      } finally {
        setAddingToDash(false);
      }
    },
    [
      isSubscriber,
      canPersonalDashboard,
      canSaveWidget,
      personalDashMsg,
      saveWidgetMsg,
      wbCountry,
      previewData,
      nav,
    ]
  );

  /** Společný blok řádku řady + rozbalený náhled (seznam výsledků / strom). */
  const renderCatalogSetBlock = (def, row, variant = "flat", browseTreeRowOrdinal = null, deepSearchMeta = null) => {
    const isDeepSearch = Boolean(deepSearchMeta);
    const isCompactFlat = variant === "flat-compact";
    const nativePreviewable = isCatalogRowPreviewEligible(def, row);
    const deepSearchCachedPreviewable =
      isDeepSearch &&
      row?.fromDeepAi &&
      row?.previewPayload &&
      typeof row.previewPayload === "object" &&
      isDeepSearchCachedPreviewUsable(row.previewPayload);
    const deepSearchHasUsablePreview = !isDeepSearch || deepCandidateHasUsablePreview(deepSearchMeta?.cand);
    const deepSearchCanOpenPreview =
      deepSearchHasUsablePreview
      || (nativePreviewable && deepCandidateMayOpenUnverifiedPreview(deepSearchMeta?.cand));
    const previewable = (nativePreviewable || deepSearchCachedPreviewable) && deepSearchCanOpenPreview;
    const semanticsPack = getCatalogBrowseSemantics(def, row);
    const semantic = semanticsPack.semantic;
    const browseBadgeLabel = semanticsPack.badge;
    /** Po zploštění `kind` řádku je `"set"` — typ je vždy z `item_kind`. */
    const itemKind = row.item_kind || row.kind;
    const bisFlowHint = def.sourceType === "bis" ? resolveBisFlowHint(row) : "";
    const bisDataflowBrowse =
      def.sourceType === "bis" && !previewable && Boolean(bisFlowHint);
    const oecd3DataflowBrowse =
      def.id === "oecd3" && (itemKind === "dataflow" || row.oecd3_dataflow);
    const oecd3Agency = oecd3DataflowBrowse
      ? String(row.oecd_agency || row.query_params?.agency || "").trim()
      : "";
    const oecd3Dataflow = oecd3DataflowBrowse
      ? String(row.oecd_dataflow || row.query_params?.dataflow || "").trim()
      : "";
    const oecd3Version = oecd3DataflowBrowse
      ? String(row.oecd_version || row.query_params?.version || "+").trim() || "+"
      : "+";

    const ecbWildcardNotice =
      previewable && def.sourceType === "ecb" && String(row.set_id ?? "").includes("..");
    const meta = [];
    if (def.sourceType === "world_bank_data360") {
      if (row.data360_indicator) meta.push(String(row.data360_indicator));
    }
    if (row.period) meta.push(`období: ${row.period}`);
    if (row.territory) meta.push(String(row.territory));
    if (row.dataset_code) meta.push(String(row.dataset_code));
    if (row.last_update) meta.push(`upd: ${row.last_update}`);
    const previewInd = String(row?.indicator_id || "").trim();
    const previewId = `${def.id}-${row.set_id}-${def.needsCountry ? wbCountry : ""}${previewInd ? `__${previewInd}` : ""}`;
    const isDetailPanel = variant === "detail";
    const isPreviewOverlay = variant === "preview-overlay";
    const isEmbeddedPreview = isDetailPanel || isPreviewOverlay;
    const isOpen = isDetailPanel
      ? previewKey === previewId || previewLoading
      : previewKey === previewId;
    const currentPreviewHasRows =
      isOpen &&
      Array.isArray(previewData?.rows) &&
      previewData.rows.length > 0;
    const rowPreviewError = currentPreviewHasRows ? "" : normalizedPreviewError;
    const displayRow =
      isOpen && previewEffectiveRow && previewKey === previewId ? previewEffectiveRow : row;
    const effectivePreviewDef = resolveCatalogRowDef(def, row);
    const isImfPreviewRow = effectivePreviewDef?.sourceType === "imf";
    const imfFreqActive = normalizeImfFreq(
      previewData?.chart_frequency ||
        displayRow?.frekvence ||
        displayRow?.query_params?.imf_frekvence ||
        imfPreviewFreq,
    );
    const catalogRowTitle =
      def.sourceType === "imf" && row.kind === "set"
        ? imfSeriesDisplayTitle(row)
        : def.sourceType === "ecb" || def.id === "ecb2"
          ? ecbSeriesDisplayTitle(row)
          : String(row.name || "").trim();
    // Sync/add actions must use the original catalog row, not preview-effective row.
    // Preview can auto-focus one geo/ref_area for chart readability; persisting that
    // as source filter would unintentionally narrow "all countries" datasets.
    const actionKey = rowExistingKey(def, row, wbCountry);
    const busy = Boolean(adding[actionKey]);
    const added = existingByCat[def.id]?.has(actionKey) ?? false;
    const previewSource = sourceByKeyByCat[def.id]?.get(actionKey) || null;
    const sourceIsRunning = previewSource?.last_sync_status === "running";
    const sourceSyncState = String(previewSource?.sync_state || "").trim().toLowerCase();
    const sourceQueueState = String(previewSource?.sync_queue_state || "").trim().toLowerCase();
    const sourceIsQueued =
      previewSource?.last_sync_status === "queued" ||
      sourceQueueState === "pending" ||
      (sourceSyncState === "rate_limited" && Boolean(previewSource?.sync_retry_at));
    const previewGroupField = String(previewData?.group_field || "").trim().toLowerCase();
    const previewState = String(previewData?.preview_state || "").trim().toLowerCase();
    const previewIndicators = Array.isArray(previewData?.indicators) ? previewData.indicators : [];
    const allowGroupMultiSelection =
      isOpen &&
      (def.sourceType === "arad" || def.sourceType === "eurostat" || def.sourceType === "ecb" || def.sourceType === "csu") &&
      Boolean(previewGroupField) &&
      previewIndicators.length > 1;
    const eurostatAiOpenCatalog = eurostatAiRowNeedsOpenInCatalog(def, row);
    const eurostatAiNodeHint =
      def.sourceType === "eurostat" && row.fromDeepAi
        ? eurostatAiOpenCatalog
          ? EUROSTAT_DEEP_AI_OPEN_CATALOG_DIMS_CZ
          : EUROSTAT_DEEP_AI_HINT_HAS_REF_CZ
        : "";
    const eurostatDeepAiPreviewErrorSanitized =
      Boolean(rowPreviewError && isOpen) &&
      def.sourceType === "eurostat" &&
      Boolean(row.fromDeepAi) &&
      eurostatDeepAiMessageLooksInternalDebug(rowPreviewError);
    const previewErrorForSourcePreview = eurostatDeepAiPreviewErrorSanitized
      ? EUROSTAT_DEEP_AI_PREVIEW_UNAVAILABLE_CZ
      : rowPreviewError;
    const canShowSyncButton =
      previewable &&
      deepSearchHasUsablePreview &&
      canAddSources &&
      !syncingPreview &&
      !sourceIsRunning &&
      !sourceIsQueued &&
      !(def.sourceType === "eurostat" && eurostatAiOpenCatalog);
    const isTree = variant === "tree";
    const deepSearchVerified = isDeepSearch && deepSearchMeta.isVerified !== false;
    const browseHintMain =
      semantic === "ecb_dataflow"
        ? getCatalogBrowseHintCz(def, row, "ecb_dataflow")
        : semantic !== "other" && semantic !== "series"
          ? getCatalogBrowseHintCz(def, row, semantic)
          : "";
    const browseHintFallback =
      !previewable && semantic !== "series" && semantic !== "other"
        ? getCatalogBrowseLimitedActionHint(def, row, semantic)
        : "";
    const fredCatId = row.fred_category_id ? String(row.fred_category_id).trim() : "";
    const wbLetter = row.wb_letter ? String(row.wb_letter).trim() : "";
    const treeRowHasPrimaryAction =
      variant === "tree"
      && (
        previewable ||
        (def.sourceType === "fred" && semantic === "category" && Boolean(fredCatId))
        || (def.sourceType === "worldbank" && semantic === "letter_bucket" && Boolean(wbLetter))
        || (bisDataflowBrowse && Boolean(bisFlowHint))
        || (oecd3DataflowBrowse && Boolean(oecd3Agency) && Boolean(oecd3Dataflow))
        || def.sourceType === "imf"
      );
    const handleTreeRowPrimary = async () => {
      if (variant !== "tree") return;
      if (previewable) {
        await togglePreview(def, row);
        return;
      }
      const action = resolveCatalogRowPrimaryAction(def, row);
      if (action.type === "bis-dataflow-preview" && isTree) {
        const path = String(row?.path || "").trim();
        if (path) void loadBisDataflowSeries(action.flowHint, path);
        return;
      }
      if (action.type === "bis-dataflow-preview") {
        void openBisDataflowQuickPreview(def, row);
        return;
      }
      if (action.type === "navigate" && action.path) {
        if (action.toast) toast.info(action.toast, { duration: 4200 });
        nav(action.path);
      }
    };
    const rowPrimaryAction = !previewable ? resolveCatalogRowPrimaryAction(def, row) : null;
    const detailFallbackAllowed =
      rowPrimaryAction?.type === "navigate" || rowPrimaryAction?.type === "bis-dataflow-preview";
    const dataActionBlocked = isDeepSearch && !previewable;
    const detailActionBlocked =
      isDeepSearch && !previewable && !detailFallbackAllowed;
    const deepSearchCardActionBlocked = isDeepSearch && detailActionBlocked;
    const detailButtonLabel =
      rowPrimaryAction?.type === "navigate" || rowPrimaryAction?.type === "bis-dataflow-preview"
        ? rowPrimaryAction.label || "Detail"
        : "Detail";
    const detailActionIsBrowse = rowPrimaryAction?.type === "navigate";
    const detailActionIsQuickPreview = rowPrimaryAction?.type === "bis-dataflow-preview";
    const deepSearchDataBlockedMessage =
      "Tato řada zatím není datově ověřená v AI hledání. Zůstává jako katalogový kandidát, ale datový náhled nespouštím.";
    const handlePreviewRequest = () => {
      if (dataActionBlocked) {
        toast.info(deepSearchDataBlockedMessage, { duration: 4200 });
        return;
      }
      void togglePreview(def, row);
    };
    const handleCompactDataRequest = () => {
      if (dataActionBlocked) {
        toast.info(deepSearchDataBlockedMessage, { duration: 4200 });
        return;
      }
      if (previewable) {
        openSeriesDetailPanel(def, displayRow || row);
        return;
      }
      activateCatalogRow(def, row);
    };
    const handleActivateRequest = () => {
      if (detailActionBlocked) {
        toast.info(deepSearchDataBlockedMessage, { duration: 4200 });
        return;
      }
      activateCatalogRow(def, previewable ? (displayRow || row) : row);
    };
    const blockKey =
      browseTreeRowOrdinal != null ? `${def.id}-${row.path}-${browseTreeRowOrdinal}` : `${def.id}-${row.path}`;
    const compactRowActions = (
      <CatalogSetActionsPanel
        variant="compact"
        def={def}
        row={row}
        catalogRowTitle={catalogRowTitle}
        {...{ previewable, isOpen, canAddSources, added, busy, detailActionIsBrowse, detailActionIsQuickPreview, detailButtonLabel }}
        detailDisabled={detailActionBlocked}
        onTogglePreview={(e) => { e.stopPropagation(); handleCompactDataRequest(); }} onActivate={(e) => { e.stopPropagation(); handleActivateRequest(); }} onAddSource={(e) => { e.stopPropagation(); addSource(def, row); }}
      />
    );
    if (row.internalVisualAsset) {
      const va = row.visualActions || {};
      const openHref = resolveSafeAppPath(va.open_url);
      const dlSupported = Boolean(va.download_chart?.supported);
      const savedGraphActions = <CatalogSetActionsPanel variant="savedVisual" dlSupported={dlSupported} onOpenSavedVisual={() => { if (openHref) nav(openHref); else toast.error("Graf nemá platný odkaz. Obnovte vyhledávání nebo jej otevřete z Mého dashboardu."); }} onSavedDashboardInfo={() => toast.info("Tento graf už máte uložený na Můj dashboard.", { duration: 4200 })} onSavedDownload={() => { if (dlSupported) toast.info("Export spusťte z Můj dashboard u konkrétního widgetu (katalogový export)."); }} />;

      return (
        <div key={blockKey} className="min-w-0 h-full">
          <SavedVisualCatalogCard
            title={String(row.name || "").trim()}
            setId={row.set_id ? String(row.set_id) : ""}
            actions={savedGraphActions}
          />
        </div>
      );
    }
    return (
      <div key={blockKey}>
        {!isEmbeddedPreview ? (
        isCompactFlat ? (
        isDeepSearch ? (
        <article
          className={`catalog-result-card rounded-lg border bg-card px-3 py-2 shadow-sm transition-colors min-w-0 h-full ${
            deepSearchVerified ? "border-border/80" : "border-amber-300/70 canvas-dark:border-amber-600/50"
          } ${
            isOpen
              ? "border-sky-300 ring-1 ring-sky-200/60"
              : deepSearchCardActionBlocked
                ? "cursor-default"
                : "hover:border-sky-200/70 hover:bg-muted/20 cursor-pointer"
          }`}
          data-testid={deepSearchMeta.showRank ? "deep-top-result-card" : undefined}
          onClick={deepSearchCardActionBlocked ? undefined : handleCompactDataRequest}
          role={deepSearchCardActionBlocked ? undefined : "button"}
          tabIndex={deepSearchCardActionBlocked ? undefined : 0}
          onKeyDown={
            deepSearchCardActionBlocked
              ? undefined
              : (e) => {
                  if (e.key !== "Enter" && e.key !== " ") return;
                  e.preventDefault();
                  handleCompactDataRequest();
                }
          }
        >
          <CatalogSetDeepSearchHeader
            showRank={deepSearchMeta.showRank}
            rankLabel={deepSearchMeta.rankLabel}
            sourceLabel={def.label}
            tierBadge={(
              <>
                <DeepResultTierBadge tier={deepSearchMeta.cand?.result_tier || (deepSearchVerified ? "verified" : undefined)} />
                <SeriesLifecycleBadge row={deepSearchMeta.cand} />
              </>
            )}
            matchQuality={deepSearchMeta.cand?.match_quality_cz}
            setId={deepSearchMeta.cand?.set_id ? String(deepSearchMeta.cand.set_id) : ""}
          />
          <h3 className="text-[13px] font-medium text-foreground leading-snug line-clamp-2 mt-1">
            {deepSearchMeta.title || catalogRowTitle}
          </h3>
          {deepSearchMeta.aiSlot ? <div className="mt-1 space-y-1 min-w-0">{deepSearchMeta.aiSlot}</div> : null}
          <div className="mt-2 pt-1.5 border-t border-border/40">{compactRowActions}</div>
        </article>
        ) : (
        <div
          className={`rounded-lg border border-border/80 bg-card px-3 py-2 shadow-sm transition-colors ${
            previewable ? "cursor-pointer hover:bg-muted/35" : "cursor-default"
          } ${isOpen ? "border-sky-200/80 ring-1 ring-sky-100/80 bg-sky-50/20" : ""}`}
          onClick={() => {
            if (previewable) void togglePreview(def, row);
          }}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key !== "Enter" && e.key !== " ") return;
            if (!previewable) return;
            e.preventDefault();
            void togglePreview(def, row);
          }}
        >
          {row.parentPath ? (
            <p className="text-[10px] text-muted-foreground truncate mb-0.5" title={row.parentPath}>
              {row.parentPath}
            </p>
          ) : null}
          <p className="text-[13px] font-medium leading-snug text-foreground line-clamp-2">{catalogRowTitle}</p>
          <div className="flex flex-wrap items-center justify-between gap-2 mt-1.5 pt-1.5 border-t border-border/50">
            <span className="text-[10px] text-muted-foreground truncate min-w-0">
              <span className="font-semibold text-foreground/80">{def.label}</span>
              {displayRow.set_id ? (
                <span className="font-mono ml-1.5">{String(displayRow.set_id)}</span>
              ) : null}
            </span>
            {compactRowActions}
          </div>
        </div>
        )
        ) : (
        <div
          className={
            isTree
              ? `flex items-start gap-2 py-2 pr-3 border-t border-border/60 hover:bg-muted/45 ${treeRowHasPrimaryAction ? "cursor-pointer" : "cursor-default"} ${isOpen ? "bg-muted/25" : ""}`
              : `flex items-start gap-3 py-3 px-4 hover:bg-muted/45 ${previewable || catalogRowHasBrowseFallback(def, row) ? "cursor-pointer" : "cursor-default"} ${isOpen ? "bg-muted/25" : ""}`
          }
          style={isTree ? { paddingLeft: `${24 + row.depth * 20}px` } : undefined}
          onClick={() => {
            if (isTree) void handleTreeRowPrimary();
            else if (previewable) void togglePreview(def, row);
            else activateCatalogRow(def, row);
          }}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key !== "Enter" && e.key !== " ") return;
            if (isTree && treeRowHasPrimaryAction) {
              e.preventDefault();
              void handleTreeRowPrimary();
              return;
            }
            if (previewable) {
              e.preventDefault();
              void togglePreview(def, row);
              return;
            }
            if (catalogRowHasBrowseFallback(def, row)) {
              e.preventDefault();
              activateCatalogRow(def, row);
            }
          }}
        >
          <FileBarChart2 className="h-4 w-4 text-muted-foreground shrink-0 mt-0.5" />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              {!isTree ? (
                <span className="text-[10px] uppercase tracking-wider px-1.5 py-0.5 rounded-md bg-muted text-foreground/90 font-semibold">
                  {def.label}
                </span>
              ) : null}
              {isTree ? (
                <span className="text-[10px] font-semibold px-2 py-0.5 rounded-md border border-border/90 bg-card text-foreground shadow-sm">
                  {browseBadgeLabel}
                </span>
              ) : null}
              <span className="text-sm text-foreground font-medium line-clamp-2">{catalogRowTitle}</span>
            </div>
            {!isTree ? (
              <CatalogSetMetadataBadges sourceLabel={def.label} row={row} />
            ) : null}
            {(def.sourceType === "ecb" || def.id === "ecb2") &&
            Array.isArray(row.ecb_series_diff) &&
            row.ecb_series_diff.length > 0 ? (
              <div
                className="flex flex-wrap gap-1 mt-1"
                data-testid="ecb-series-diff"
                title={row.ecb_series_explanation || row.ecb_series_diff_text}
              >
                {row.ecb_series_diff.map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex max-w-full truncate rounded-md border border-indigo-200/80 bg-indigo-50 px-1.5 py-0.5 text-[10px] font-medium text-indigo-950"
                  >
                    {tag}
                  </span>
                ))}
              </div>
            ) : null}
            {(def.sourceType === "ecb" || def.id === "ecb2") && row.ecb_value_descriptor ? (
              <p
                className="text-[11px] font-medium text-teal-900 bg-teal-50/90 border border-teal-200/70 rounded-md px-2 py-1 mt-1 leading-snug"
                title={row.ecb_value_descriptor}
                data-testid="ecb-value-descriptor"
              >
                <span className="text-teal-700/90 uppercase tracking-wide text-[9px] mr-1.5">
                  Co ukazuje:
                </span>
                {row.ecb_value_descriptor}
              </p>
            ) : null}
            {(def.sourceType === "ecb" || def.id === "ecb2") &&
            row.ecb_subtitle &&
            !(Array.isArray(row.ecb_series_diff) && row.ecb_series_diff.length) ? (
              <p className="text-[11px] text-muted-foreground mt-0.5 leading-snug line-clamp-2">{row.ecb_subtitle}</p>
            ) : null}
            {(def.sourceType === "ecb" || def.id === "ecb2") &&
            row.ecb_series_explanation &&
            !(Array.isArray(row.ecb_series_diff) && row.ecb_series_diff.length) ? (
              <p
                className="text-[11px] text-muted-foreground leading-snug mt-0.5 line-clamp-3"
                title={row.ecb_series_explanation}
              >
                {row.ecb_series_explanation}
              </p>
            ) : null}
            {def.sourceType === "imf" && row.kind === "set" ? (() => {
              const code = String(
                row.imf_indicator_code || row.imf_indicator || row.query_params?.imf_indicator || "",
              ).trim();
              const label = imfSeriesDisplayTitle(row);
              const showDescriptor = label && (!code || label.toUpperCase() !== code.toUpperCase());
              if (!showDescriptor) return null;
              const unit = String(row.imf_unit || "").trim();
              return (
                <p
                  className="text-[11px] font-medium text-indigo-950 bg-indigo-50/90 border border-indigo-200/70 rounded-md px-2 py-1 mt-1 leading-snug"
                  title={label}
                  data-testid="imf-indicator-descriptor"
                >
                  <span className="text-indigo-700/90 uppercase tracking-wide text-[9px] mr-1.5">
                    Co ukazuje:
                  </span>
                  {label}
                  {unit ? (
                    <span className="text-indigo-800/80 font-normal">
                      {` · jednotka: ${unit}`}
                    </span>
                  ) : null}
                  {row.imf_has_projections ? (
                    <span className="text-indigo-800/80 font-normal">
                      {" · obsahuje projekce IMF"}
                    </span>
                  ) : null}
                </p>
              );
            })() : null}
            {def.sourceType === "imf" && row.kind === "set" ? (() => {
              const code = String(
                row.imf_indicator_code || row.imf_indicator || row.query_params?.imf_indicator || "",
              ).trim();
              const label = imfSeriesDisplayTitle(row);
              if (!code || !label || label.toUpperCase() === code.toUpperCase()) return null;
              return (
                <p className="text-[10px] text-muted-foreground mt-0.5 font-mono" data-testid="imf-indicator-code">
                  kód ukazatele: {code}
                </p>
              );
            })() : null}
            {def.sourceType === "world_bank_data360" && (row.data360_subtitle || row.data360_summary) ? (
              <p className="text-[11px] text-foreground/85 mt-1 leading-snug line-clamp-3">
                {row.data360_subtitle ? <span>{row.data360_subtitle}</span> : null}
                {row.data360_subtitle && row.data360_summary ? (
                  <span className="text-muted-foreground"> · </span>
                ) : null}
                {row.data360_summary ? (
                  <span className="text-muted-foreground">{row.data360_summary}</span>
                ) : null}
              </p>
            ) : null}
            <div className="text-[11px] text-muted-foreground mt-1 leading-snug text-normal-wrap">
              <span className="font-mono text-technical-wrap">{`kód: ${String(displayRow.set_id)}`}</span>
              {meta.length ? <span className="font-sans">{`  ·  ${meta.join("  ·  ")}`}</span> : null}
            </div>
            {def.sourceType === "fred" && semantic === "series" && row.fred_series_id ? (
              <div className="text-[11px] text-foreground/90 mt-1">
                <span className="font-mono font-semibold">{String(row.fred_series_id)}</span>
                {row.period ? ` · frekvence: ${row.period}` : ""}
                {row.units ? ` · jednotky: ${row.units}` : ""}
              </div>
            ) : null}
            {def.sourceType === "worldbank" && semantic === "indicator" && row.wb_indicator ? (
              <div className="text-[11px] text-sky-950 canvas-dark:text-sky-100 mt-1.5 rounded-lg border border-sky-200 canvas-dark:border-sky-700/50 bg-sky-50/90 canvas-dark:bg-sky-950/40 px-2 py-1.5 leading-snug">
                Indikátor <span className="font-mono">{String(row.wb_indicator)}</span>. Pro časovou řadu je potřeba
                země — aktuálně <span className="font-mono">{wbCountry}</span> (Filtry → World Bank). Zdroj v datech
                World Bank.
              </div>
            ) : null}
            {eurostatAiNodeHint ? (
              <div
                className={`rounded-lg px-2 py-1.5 mt-1.5 text-[11px] leading-snug ${
                  eurostatAiOpenCatalog
                    ? "border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 text-amber-950 canvas-dark:text-amber-50"
                    : "border border-sky-100 canvas-dark:border-sky-800/45 bg-sky-50/95 canvas-dark:bg-sky-950/35 text-sky-950 canvas-dark:text-sky-100"
                }`}
              >
                {eurostatAiNodeHint}
              </div>
            ) : null}
            {browseHintMain ? (
              <div className="rounded-lg border border-sky-100 canvas-dark:border-sky-800/45 bg-sky-50/95 canvas-dark:bg-sky-950/35 text-[11px] text-sky-950 canvas-dark:text-sky-100 px-2 py-1.5 mt-1.5 leading-snug">
                {browseHintMain}
              </div>
            ) : null}
            {browseHintFallback ? (
              <div className="text-[11px] text-amber-900 mt-1.5 leading-snug">{browseHintFallback}</div>
            ) : null}
            {ecbWildcardNotice ? (
              <div className="text-[10px] text-amber-800/90 mt-1">
                Tento dotaz obsahuje vynechanou dimenzi (wildcard); ECB může vrátit více časových řad v jedné odpovědi.
              </div>
            ) : null}
            {def.sourceType === "internal" && Array.isArray(row.actions) && row.actions.length > 0 ? (
              <div
                className="flex flex-wrap gap-1.5 mt-2"
                onClick={(e) => e.stopPropagation()}
                role="group"
                aria-label="Akce pro interní dataset"
              >
                {row.actions.map((a) => {
                  const aid = String(a.id || "").trim();
                  const label = String(a.label_cs || a.label || aid).trim();
                  if (String(a.kind || "") === "copy_value" && a.value != null && String(a.value).trim()) {
                    return (
                      <button
                        key={aid || label}
                        type="button"
                        className="h-7 px-2 text-[11px] rounded-lg border border-border/80 bg-card hover:bg-muted/60 text-foreground"
                        onClick={(e) => {
                          e.stopPropagation();
                          void navigator.clipboard?.writeText?.(String(a.value));
                          toast.success("ID zkopírováno do schránky");
                        }}
                      >
                        {label}
                      </button>
                    );
                  }
                  return (
                    <button
                      key={aid || label}
                      type="button"
                      className="h-7 px-2 text-[11px] rounded-lg border border-emerald-200 bg-emerald-50 text-emerald-950 hover:bg-emerald-100"
                      onClick={(e) => {
                        e.stopPropagation();
                        if (previewable) void togglePreview(def, row);
                      }}
                    >
                      {label}
                    </button>
                  );
                })}
              </div>
            ) : null}
            {def.sourceType === "internal" &&
            String(row.contact_user_id || "").trim() &&
            String(row.contact_user_id || "").trim() !== String(user?.id || "") ? (
              <div
                className="flex flex-wrap gap-1.5 mt-2"
                onClick={(e) => e.stopPropagation()}
                role="group"
                aria-label="Kontakt na vlastníka dat"
              >
                <button
                  type="button"
                  className="h-7 px-2 text-[11px] rounded-lg border border-indigo-200 bg-indigo-50 text-indigo-900 hover:bg-indigo-100"
                  onClick={(e) => {
                    e.stopPropagation();
                    const uid = String(row.contact_user_id || "").trim();
                    const setId = String(row.set_id || "").trim();
                    const title = String(row.name || setId || "interní dataset").trim();
                    if (!uid) return;
                    const qp = new URLSearchParams({
                      user: uid,
                      context: `Dotaz ke katalogu: ${title}`,
                      chart_title: title,
                      chart_set_id: setId,
                      chart_source_type: String(def.sourceType || ""),
                      chart_url: window.location.href,
                    });
                    nav(`/messages?${qp.toString()}`);
                  }}
                >
                  Napsat autorovi
                </button>
              </div>
            ) : null}
            <div className="text-[10px] text-muted-foreground mt-1 line-clamp-2" title={row.parentPath}>
              {row.parentPath}
            </div>
          </div>
          <CatalogSetActionsPanel
            {...{ def, row, semantic, fredCatId, wbLetter, bisDataflowBrowse, bisFlowHint, oecd3DataflowBrowse, oecd3Agency, oecd3Dataflow, oecd3Version, catalogRowTitle }} {...{ previewable, isOpen, isTree, canAddSources, added, busy, eurostatAiOpenCatalog }}
            onOpenFredCategory={(e) => { e.stopPropagation(); nav(`/fred/catalog?expand=${encodeURIComponent(fredCatId)}`); }}
            onOpenWorldBankLetter={(e) => { e.stopPropagation(); nav(`/worldbank/catalog?letter=${encodeURIComponent(wbLetter)}`); }}
            onOpenBisDimensions={(e) => { e.stopPropagation(); nav(`/bis/catalog?expandSeries=${encodeURIComponent(bisFlowHint)}`); }}
            onOpenOecdDimensions={(e) => { e.stopPropagation(); const qp = new URLSearchParams({ agency: oecd3Agency, dataflow: oecd3Dataflow, version: oecd3Version, probe: "1", autoload: "1" }); const title = String(row.title || row.name || "").trim(); if (title) qp.set("name", title.slice(0, 160)); nav(`/oecd/catalog?${qp.toString()}`); }}
            onTogglePreview={(e) => { e.stopPropagation(); handlePreviewRequest(); }} onOpenEurostatCatalog={(e) => { e.stopPropagation(); nav(`/sources/eurostat?q=${encodeURIComponent(String(row.set_id || ""))}`); }} onAddSource={(e) => { e.stopPropagation(); addSource(def, row); }}
          />
        </div>
        )
        ) : null}

        {/* CatalogSetPreviewPanel keeps the CatalogLiveChartPreview onCompareSave wiring controlled by previewCompareList here. */}
        <CatalogSetPreviewPanel
          isOpen={isOpen}
          isEmbeddedPreview={isEmbeddedPreview}
          isPreviewOverlay={isPreviewOverlay}
          isDetailPanel={isDetailPanel}
          isCompactFlat={isCompactFlat}
          isDeepSearch={isDeepSearch}
          previewData={previewData}
          previewFallbackNotice={previewFallbackNotice}
          isImfPreviewRow={isImfPreviewRow}
          displayRow={displayRow}
          row={row}
          def={def}
          previewLoading={previewLoading}
          setImfPreviewFreq={setImfPreviewFreq}
          fetchPreview={fetchPreview}
          imfFreqActive={imfFreqActive}
          previewState={previewState}
          syncingPreview={syncingPreview}
          sourceIsRunning={sourceIsRunning}
          sourceIsQueued={sourceIsQueued}
          previewSource={previewSource}
          previewError={rowPreviewError}
          canShowSyncButton={canShowSyncButton}
          syncAndReloadPreview={syncAndReloadPreview}
          isSubscriber={isSubscriber}
          addSeriesToPersonalDashboard={addSeriesToPersonalDashboard}
          addingToDash={addingToDash}
          personalDashLoading={personalDashLoading}
          saveWidgetLoading={saveWidgetLoading}
          canPersonalDashboard={canPersonalDashboard}
          canSaveWidget={canSaveWidget}
          canExportData={canExportData}
          personalDashMsg={personalDashMsg}
          saveWidgetMsg={saveWidgetMsg}
          downloadOpen={downloadOpen}
          setDownloadOpen={setDownloadOpen}
          downloadingFmt={downloadingFmt}
          exportFeLoading={exportFeLoading}
          exportDataLockMsg={exportDataLockMsg}
          downloadCurrent={downloadCurrent}
          closePreview={closePreview}
          catalogRowTitle={catalogRowTitle}
          previewErrorForSourcePreview={previewErrorForSourcePreview}
          seriesDetailOpen={seriesDetailOpen}
          catalogChartExpanded={catalogChartExpanded}
          handleSeriesDetailChartDisplayState={handleSeriesDetailChartDisplayState}
          previewCompareList={previewCompareList}
          setPreviewCompareList={setPreviewCompareList}
          previewSelectedIndicators={previewSelectedIndicators}
          extractGeoValuesFromDimensionFilters={extractGeoValuesFromDimensionFilters}
          setPreviewSelectedIndicators={setPreviewSelectedIndicators}
          allowGroupMultiSelection={allowGroupMultiSelection}
          setUseAiAssistant={setUseAiAssistant}
          setAiQuery={setAiQuery}
          applySuggestedDeepSearch={applySuggestedDeepSearch}
          catalogAiSectionRef={catalogAiSectionRef}
          eurostatDeepAiPreviewErrorSanitized={eurostatDeepAiPreviewErrorSanitized}
          TechnicalDetailsComponent={EurostatDeepAiTechnicalDetails}
          previewable={previewable}
        />
      </div>
    );
  };

  const {
    deepVerifiedList,
    deepPossibleList,
    deepLowRelevanceList,
    deepDiscardedCandidatesList,
    topDeepRecommendations,
    deepLanePreviewEntries,
    showDeepLaneProvisional,
    deepSuggestedQueriesList,
    groupedDeepVerified,
    groupedDeepPossible,
    followupSeriesOptions,
    followupSuggestedActions,
    deepClarificationPayload,
    showDeepFollowupPanel,
    deepFollowupClarificationMode,
    followupChartPayload,
    followupComposedChartData,
    deepAiAnalysisDatasets,
    deepPrimaryChart,
    deepStatusesForUi,
    deepPipelineDiagnostics,
    deepAiSearchPlanForUi,
    deepCombinedWarnings,
    deepStatusesForDisplay,
    deepSearchSourceSummaries,
    deepSearchAllSourcesHidden,
    showPartialNoValidCandidates,
    eurostatRetryableIndexStatus,
    singleAiSourceProblemBanner,
    showMultiAiIndexProblemNoHitsNote,
    showGenericAiIndexNoHitsMessage,
    deepErrorFriendly,
  } = useCatalogDeepSearchViewModel({
    deepData,
    deepSearchExcludedCatalogIds,
    deepStreamAwaitingFinal,
    deepLaneResults,
    useAiAssistant,
    deepLoading,
    deepConversation,
    deepSourceStatuses,
    selectedExternalCatalogs,
    deepError,
  });

  useEffect(() => {
    const msgs = Array.isArray(deepConversation?.messages) ? deepConversation.messages : [];
    setFollowupMessages(msgs.filter((m) => m && typeof m === "object").slice(-20));
  }, [deepConversation]);

  useEffect(() => {
    const rootQuery = String(aiQuery || "").trim();
    const incoming = (followupSeriesOptions || []).slice(0, 24).map(toFollowupSeriesRef);
    if (followupRootQueryRef.current !== rootQuery) {
      followupRootQueryRef.current = rootQuery;
      setFollowupAvailableSeriesRefs(incoming);
      return;
    }
    setFollowupAvailableSeriesRefs((previous) => {
      const merged = new Map(previous.map((row) => [row.ref_id, row]));
      for (const row of incoming) merged.set(row.ref_id, row);
      return [...merged.values()].slice(0, 40);
    });
  }, [aiQuery, followupSeriesOptions]);

  const buildLocalSearchEconomistReply = useCallback(
    (message, selectedRefs = []) => {
      const picked =
        selectedRefs.length > 0
          ? selectedRefs
          : (followupSeriesOptions || []).slice(0, 8).map((x) => ({
              title: x.title || x.label || x.name || x.set_id,
              source_type: x.source_type || x.source,
              set_id: x.set_id,
            }));
      const titles = picked
        .map((x) => String(x?.title || x?.label || x?.name || x?.set_id || "").trim())
        .filter(Boolean)
        .slice(0, 8);
      if (!titles.length) {
        return "Nad aktuálním hledáním zatím nemám dost zobrazených řad pro ekonomickou odpověď. Spusťte nejdřív AI hledání nebo vyberte několik řad.";
      }
      const sourceCount = new Set(
        picked.map((x) => String(x?.source_type || x?.source || "").trim()).filter(Boolean),
      ).size;
      const intro = String(message || "").trim().endsWith("?")
        ? "Ekonomicky bych aktuální výsledky četl takto:"
        : "K aktuálním výsledkům vyhledávání:";
      return [
        intro,
        `Na stránce jsou hlavně ukazatele typu ${titles.slice(0, 3).join("; ")}${titles.length > 3 ? "…" : "."}`,
        sourceCount > 1
          ? `Výsledky jsou z více zdrojů (${sourceCount}), takže je před složením grafu dobré sjednotit definici, zemi, frekvenci a jednotku.`
          : "Výsledky vypadají jako jedna tematická skupina; další krok je vybrat konkrétní země/varianty a dát je do grafu.",
        "Doporučení: vyberte 2-4 nejrelevantnější řady, ověřte náhled dat a potom porovnejte trend, poslední hodnotu a rozdíly v metodice.",
      ].join(" ");
    },
    [followupSeriesOptions],
  );
  const catalogGlobThemes = useMemo(
    () => (Array.isArray(catalogGlobResults?.themes_triggered) ? catalogGlobResults.themes_triggered : []),
    [catalogGlobResults],
  );
  const catalogGlobHits = useMemo(
    () => (Array.isArray(catalogGlobResults?.results) ? catalogGlobResults.results : []),
    [catalogGlobResults],
  );
  const catalogMultiThemes = useMemo(
    () => (Array.isArray(catalogMultiResults?.themes_triggered) ? catalogMultiResults.themes_triggered : []),
    [catalogMultiResults],
  );
  const catalogMultiHits = useMemo(
    () => (Array.isArray(catalogMultiResults?.results) ? catalogMultiResults.results : []),
    [catalogMultiResults],
  );
  const catalogMultiLanePreviewEntries = useMemo(() => {
    if (!catalogMultiStreamAwaitingFinal || !catalogMultiLaneResults || typeof catalogMultiLaneResults !== "object") {
      return [];
    }
    return Object.entries(catalogMultiLaneResults)
      .map(([source, hits]) => {
        const list = Array.isArray(hits) ? hits.filter((h) => h && typeof h === "object") : [];
        if (!list.length) return null;
        const def = CATALOGS.find((c) => c.id === source);
        return {
          source: String(source || "").toLowerCase(),
          label: def?.label || String(source || "").toUpperCase(),
          count: list.length,
          titles: list
            .slice(0, 3)
            .map((h) => String(h.name || h.title || h.set_id || h.code || "").trim())
            .filter(Boolean),
        };
      })
      .filter(Boolean);
  }, [catalogMultiLaneResults, catalogMultiStreamAwaitingFinal]);
  const showCatalogMultiLaneProvisional =
    browseSearchAcrossSelected && catalogMultiStreamAwaitingFinal && catalogMultiLanePreviewEntries.length > 0;
  useEffect(() => {
    if (!browseSearchAcrossSelected) {
      setBrowseExcludedCatalogIds((prev) => (prev.size ? new Set() : prev));
      return;
    }
    const allowed = new Set(
      Array.isArray(catalogMultiResults?.source_summaries)
        ? catalogMultiResults.source_summaries.map((s) => String(s?.id || "").trim()).filter(Boolean)
        : []
    );
    setBrowseExcludedCatalogIds((prev) => {
      if (!prev.size) return prev;
      const next = new Set(Array.from(prev).filter((id) => allowed.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [browseSearchAcrossSelected, catalogMultiResults]);
  const browseGlobalResultsVisible =
    !browseLocalBranchOnly &&
    debouncedBrowsePanelFilter.length >= 2 &&
    (browseSearchAcrossSelected
      ? selectedHasAnyCatalog
      : selectedHasBrowseCatalog && browseOptions.some((c) => c.id === browseCatalogId));
  const browseSearchLoading = browseSearchAcrossSelected ? catalogMultiLoading : catalogGlobLoading;
  const browseSearchError = browseSearchAcrossSelected ? catalogMultiError : catalogGlobError;
  const browseSearchThemes = browseSearchAcrossSelected ? catalogMultiThemes : catalogGlobThemes;
  const browseSearchHitsRaw = browseSearchAcrossSelected ? catalogMultiHits : catalogGlobHits;
  const browseSearchHits = useMemo(() => {
    const base = !browseSearchCategoriesOnly
      ? browseSearchHitsRaw
      : browseSearchHitsRaw.filter((hit) => isCategoryLikeSearchHit(hit));
    const seen = new Set();
    return base.filter((hit) => {
      const key = `${String(hit?.catalog_id || "").trim()}::${String(hit?.set_id || "").trim().toLowerCase()}::${String(hit?.indicator_id || "").trim().toLowerCase()}`;
      if (!key || key === "::") return true;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }, [browseSearchHitsRaw, browseSearchCategoriesOnly]);
  const browseSearchHitsVisible = useMemo(() => {
    if (!browseSearchAcrossSelected || !browseExcludedCatalogIds.size) return browseSearchHits;
    return browseSearchHits.filter((hit) => {
      const id = String(hit?.catalog_id || "").trim();
      return id ? !browseExcludedCatalogIds.has(id) : true;
    });
  }, [browseSearchAcrossSelected, browseExcludedCatalogIds, browseSearchHits]);
  const browseCategoryMatches = useMemo(() => {
    if (!browseSearchCategoriesOnly || browseSearchAcrossSelected) return [];
    const q = String(debouncedBrowsePanelFilter || "").trim();
    if (q.length < 2) return [];
    const kws = parseSearchKeywords(q);
    if (!kws.length) return [];
    const filtered = buildFilteredPaths(browseAllRows, browseRowIndex, kws);
    if (!filtered) return [];
    return browseAllRows
      .filter((row) => row?.kind === "cat" && filtered.has(row.path))
      .slice(0, 180)
      .map((row, idx) => ({
        set_id: `cat:${browseCatalogId}:${String(row.path || row.name || idx)}`,
        code: "",
        name: String(row.name || "").trim(),
        title: String(row.name || "").trim(),
        catalog_path: String(row.path || row.name || "").trim(),
        catalog_id: browseCatalogId,
        catalog_label: browseDef.label,
        source_type: browseDef.sourceType,
        tier: browseDef.tier || "production",
        result_badge: "category",
        previewable: false,
        description: "",
        row,
        __category_match: true,
      }));
  }, [
    browseSearchCategoriesOnly,
    browseSearchAcrossSelected,
    debouncedBrowsePanelFilter,
    browseAllRows,
    browseRowIndex,
    browseCatalogId,
    browseDef,
  ]);
  const browseSearchHitsEffective = useMemo(() => {
    if (browseSearchCategoriesOnly && !browseSearchAcrossSelected) return browseCategoryMatches;
    return browseSearchHitsVisible;
  }, [browseSearchCategoriesOnly, browseSearchAcrossSelected, browseCategoryMatches, browseSearchHitsVisible]);
  const browseSearchResults = browseSearchAcrossSelected ? catalogMultiResults : catalogGlobResults;
  const totalClassicResultCount = flatResults.length + inAppSearchResults.length;

  const catalogResultsSummary = searchReady
    ? crossSearchUsedLocalFallback
      ? `Nalezeno ${totalClassicResultCount} položek (lokální fallback — serverové hledání selhalo).`
      : !useAiAssistant && crossSearchBackendLoading && !crossSearchBackendAttempted
        ? `Vyhledávám na serveru${classicSearchCatalogDefs.length ? `: ${classicSearchCatalogDefs.map((d) => d.label).join(", ")}` : ""}…`
      : !useAiAssistant && crossSearchBackendAttempted && flatResults.some((e) => e.resultSource === "backend")
        ? `Nalezeno ${totalClassicResultCount} položek (seřazeno podle relevance serveru).`
        : totalClassicResultCount > 0
          ? `Nalezeno až ${totalClassicResultCount} položek.`
          : crossSearchBackendLoading || inAppSearchLoading
            ? "Vyhledávám…"
            : "Nalezeno 0 položek."
    : null;

  const deepContentActive =
    deepLoading ||
    Boolean(deepError) ||
    Boolean(deepData) ||
    (Array.isArray(deepSourceStatuses) && deepSourceStatuses.length > 0);

  // Rámeček sekce AI rešerše jen když má reálný obsah — samotné zvýraznění
  // (?ai=1) jinak vykreslovalo prázdný zelený box a sbalilo browse layout.
  const showDeepChrome = useAiAssistant && deepContentActive;
  const showClassicSearchResults = searchReady && !showDeepChrome;

  const hasActiveSearch =
    searchReady ||
    showDeepChrome ||
    browseGlobalResultsVisible ||
    (browseLocalBranchOnly && browsePanelKeywordsTreeOnly.length >= 2);

  const hadActiveSearchRef = useRef(false);
  useEffect(() => {
    if (hasActiveSearch && !hadActiveSearchRef.current) {
      setBrowseSidebarOpen(false);
      setMobileBrowseOpen(false);
    }
    hadActiveSearchRef.current = hasActiveSearch;
  }, [hasActiveSearch]);

  const responsiveLayout = useCatalogResponsiveLayout({ hasActiveSearch });

  const handleCatalogToggle = useCallback(() => {
    if (responsiveLayout.width < 1280) {
      setMobileBrowseOpen((open) => !open);
      return;
    }
    setBrowseSidebarOpen((open) => !open);
  }, [responsiveLayout.width]);

  useEffect(() => {
    const onHeaderBrowseToggle = () => handleCatalogToggle();
    window.addEventListener(CATALOG_HEADER_BROWSE_TOGGLE_EVENT, onHeaderBrowseToggle);
    return () => window.removeEventListener(CATALOG_HEADER_BROWSE_TOGGLE_EVENT, onHeaderBrowseToggle);
  }, [handleCatalogToggle]);

  useEffect(() => {
    if (responsiveLayout.explorerDefaultOpen && !hasActiveSearch) {
      setBrowseSidebarOpen(true);
    }
  }, [responsiveLayout.explorerDefaultOpen, hasActiveSearch]);

  const explorerColumns = useMemo(
    () =>
      buildCatalogExplorerColumns(
        browseAllRows,
        browseColumnSelection,
        browseFilteredPaths,
        responsiveLayout.maxExplorerColumns,
      ),
    [browseAllRows, browseColumnSelection, browseFilteredPaths, responsiveLayout.maxExplorerColumns],
  );

  /** Miller sloupce: po výběru složky spustit lazy načtení (ECB 2, Data360, …). */
  useEffect(() => {
    if (!browseColumnSelection.length || !browseAllRows.length) return;
    if (browseCatalogId !== "ecb2" && browseCatalogId !== "data360" && browseCatalogId !== "bis" && browseCatalogId !== "fred") return;
    const rowByPath = new Map(browseAllRows.map((r) => [String(r.path || ""), r]));
    for (let i = 0; i < browseColumnSelection.length; i += 1) {
      const path = String(browseColumnSelection[i] || "").trim();
      if (!path) continue;
      const row = rowByPath.get(path);
      if (!row || !explorerRowIsCategory(row)) continue;
      loadBrowseCategoryChildren(row, i);
    }
  }, [browseCatalogId, browseColumnSelection, browseAllRows, loadBrowseCategoryChildren]);

  const explorerLoadingState = useMemo(
    () => ({
      ecbCountries: ecbLoadingCountries,
      ecb2Countries: ecb2LoadingCountries,
      ecb2Flows: ecb2LoadingFlows,
      ecb2Letters: ecb2LoadingLetters,
      imfCountries: imfLoadingCountries,
      imf2Countries: imf2LoadingCountries,
      oecd2Countries: oecd2LoadingCountries,
      oecd4Datasets: oecd4LoadingDatasets,
      oecd4Countries: oecd4LoadingCountries,
      wbCountries: wbLoadingCountries,
      d360Countries: d360LoadingCountries,
      bisDataflows: bisLoadingDataflows,
      bisCountries: bisLoadingCountries,
      bisActiveDataflowId: bisActiveDataflowRef.current,
      fredCategories: fredLoadingCategories,
      teCountries: teLoadingCountries,
    }),
    [
      ecbLoadingCountries,
      ecb2LoadingCountries,
      ecb2LoadingFlows,
      ecb2LoadingLetters,
      imfLoadingCountries,
      imf2LoadingCountries,
      oecd2LoadingCountries,
      oecd4LoadingDatasets,
      oecd4LoadingCountries,
      wbLoadingCountries,
      d360LoadingCountries,
      bisLoadingDataflows,
      bisLoadingCountries,
      fredLoadingCategories,
      teLoadingCountries,
    ],
  );

  const explorerLoadingRowKey = useMemo(
    () =>
      resolveExplorerLoadingRowKey(
        browseColumnSelection,
        browseAllRows,
        browseCatalogId,
        explorerLoadingState,
      ),
    [browseColumnSelection, browseAllRows, browseCatalogId, explorerLoadingState],
  );

  const sortedFlatResults = useMemo(() => {
    let list = [...(flatResults || [])];
    if (!useAiAssistant && crossSearchExcludedCatalogIds.size > 0) {
      list = list.filter((e) => !crossSearchExcludedCatalogIds.has(String(e?.def?.id || "")));
    }
    if (resultsSortMode === "name") {
      list.sort((a, b) =>
        String(a.row?.name || "").localeCompare(String(b.row?.name || ""), "cs"),
      );
    } else if (resultsSortMode === "catalog") {
      list.sort((a, b) =>
        String(a.def?.label || "").localeCompare(String(b.def?.label || ""), "cs"),
      );
    }
    return list;
  }, [flatResults, resultsSortMode, useAiAssistant, crossSearchExcludedCatalogIds]);

  const seriesDetailMetadataItems = useMemo(() => {
    if (!seriesDetailTarget) return [];
    const { def, row } = seriesDetailTarget;
    const key = catalogPreviewKey(def, row);
    const previewActive = previewKey === key;
    return catalogSeriesDetailMetadataItems(
      def,
      row,
      previewActive ? previewData : null,
      previewActive ? previewEffectiveRow : null,
      previewActive ? seriesDetailChartDisplayState : null,
    );
  }, [
    seriesDetailTarget,
    previewKey,
    previewData,
    previewEffectiveRow,
    catalogPreviewKey,
    seriesDetailChartDisplayState,
  ]);

  const seriesDetailFrequencyEditor = useMemo(() => {
    if (!seriesDetailTarget) return null;
    const { def, row } = seriesDetailTarget;
    const key = catalogPreviewKey(def, row);
    const previewActive = previewKey === key;
    if (def.sourceType === "imf") {
      const displayRow =
        previewActive && previewEffectiveRow ? previewEffectiveRow : row;
      const options = resolveImfFreqOptions({
        previewData: previewActive ? previewData : null,
        row: displayRow,
      });
      if (options.length <= 1 && !(previewLoading && previewActive)) {
        return null;
      }
      const active = normalizeImfFreq(
        previewActive
          ? previewData?.chart_frequency ||
              displayRow?.frekvence ||
              displayRow?.query_params?.imf_frekvence ||
              imfPreviewFreq
          : displayRow?.frekvence || displayRow?.query_params?.imf_frekvence || imfPreviewFreq,
      );
      const activeInOptions = options.some((o) => o.frekvence === active);
      return {
        kind: "imf",
        active: activeInOptions ? active : options[0]?.frekvence || active,
        options,
        loading: previewLoading && previewActive,
      };
    }
    const currentPreviewHasRows =
      previewActive &&
      Array.isArray(previewData?.rows) &&
      previewData.rows.length > 0;
    const blockingPreviewError = currentPreviewHasRows ? "" : normalizedPreviewError;
    if (
      previewActive &&
      canRenderAradCatalogChart(previewData, blockingPreviewError, def.sourceType)
    ) {
      return { kind: "chart_options" };
    }
    return null;
  }, [
    seriesDetailTarget,
    previewKey,
    previewData,
    previewEffectiveRow,
    normalizedPreviewError,
    previewLoading,
    imfPreviewFreq,
    catalogPreviewKey,
  ]);

  const handleSeriesDetailImfFrequencyChange = useCallback(
    (freq) => {
      if (!seriesDetailTarget) return;
      const { def, row } = seriesDetailTarget;
      const normalized = normalizeImfFreq(freq);
      setImfPreviewFreq(normalized);
      const rowWithFreq = {
        ...row,
        frekvence: normalized,
        query_params: {
          ...(row?.query_params && typeof row.query_params === "object" ? row.query_params : {}),
          imf_frekvence: normalized,
        },
      };
      const ind = String(
        previewData?.selected_indicator || row?.imf_indicator || row?.query_params?.imf_indicator || "",
      ).trim();
      void fetchPreview(def, rowWithFreq, ind || undefined);
    },
    [seriesDetailTarget, previewData?.selected_indicator, fetchPreview],
  );

  const explorerBreadcrumbItems = useMemo(() => {
    if (!seriesDetailTarget) return [];
    return buildExplorerBreadcrumbItems(
      browseColumnSelection,
      browseAllRows,
      seriesDetailTarget.def?.label || browseDef?.label || "",
      seriesDetailTarget.row,
    );
  }, [seriesDetailTarget, browseColumnSelection, browseAllRows, browseDef?.label]);

  const handleBackToCatalog = useCallback(() => {
    setCatalogViewMode("browse");
    setSeriesDetailOpen(false);
    setCatalogChartExpanded(false);
  }, []);

  const handleCloseExplorerPanel = useCallback(() => {
    setBrowseSidebarOpen(false);
    setMobileBrowseOpen(false);
  }, []);

  const handleDismissDeepSearchPanel = useCallback(() => {
    cancelDeepSearch();
    setDeepData(null);
    setDeepError("");
    setDeepErrorTechnical("");
    setDeepSourceStatuses([]);
    setDeepConversation(null);
    setDeepFollowupResult(null);
    setDeepFollowupError("");
    setDeepSearchExcludedCatalogIds(new Set());
  }, [
    cancelDeepSearch,
    setDeepData,
    setDeepError,
    setDeepErrorTechnical,
    setDeepSourceStatuses,
    setDeepConversation,
    setDeepFollowupResult,
    setDeepFollowupError,
  ]);

  const handleDismissBrowseSearchResults = useCallback(() => {
    setBrowsePanelFilter("");
    setDebouncedBrowsePanelFilter("");
    setCatalogGlobResults(null);
    setCatalogGlobError("");
    setCatalogGlobLoading(false);
    setCatalogMultiResults(null);
    setCatalogMultiError("");
    setCatalogMultiLoading(false);
    setBrowseExcludedCatalogIds(new Set());
  }, [
    setBrowsePanelFilter,
    setDebouncedBrowsePanelFilter,
    setCatalogGlobResults,
    setCatalogGlobError,
    setCatalogGlobLoading,
    setCatalogMultiResults,
    setCatalogMultiError,
    setCatalogMultiLoading,
  ]);

  const handleDismissCrossSearchResults = useCallback(() => {
    cancelDeepSearch();
    setSubmittedCrossQuery("");
    setCrossSearchBackendHits(null);
    setCrossSearchBackendSummaries([]);
    setCrossSearchExcludedCatalogIds(new Set());
    setCrossSearchPartialIndexMissing(false);
    handleDismissDeepSearchPanel();
  }, [
    cancelDeepSearch,
    setSubmittedCrossQuery,
    handleDismissDeepSearchPanel,
  ]);

  const openSeriesDetailPanel = useCallback(
    (def, row) => {
      setSeriesDetailTarget({ def, row });
      setSeriesDetailOpen(true);
      setSeriesDetailChartDisplayState(null);
      setCatalogViewMode("detail");
      setMobileBrowseOpen(false);
      void openPreviewForRow(def, row);
    },
    [openPreviewForRow],
  );

  const activateCatalogRow = useCallback(
    (def, row) => {
      if (isCatalogRowPreviewEligible(def, row)) {
        openSeriesDetailPanel(def, row);
        return;
      }
      dispatchCatalogRowPrimaryAction(def, row);
    },
    [openSeriesDetailPanel, dispatchCatalogRowPrimaryAction],
  );

  const closeSeriesDetail = useCallback(() => {
    setSeriesDetailOpen(false);
    setSeriesDetailTarget(null);
    setSeriesDetailChartDisplayState(null);
    setCatalogViewMode("browse");
    setCatalogChartExpanded(false);
    closePreview();
  }, [closePreview]);

  const handleSeriesDetailChartDisplayState = useCallback((state) => {
    setSeriesDetailChartDisplayState((prev) =>
      chartDisplayStatesEqual(prev, state) ? prev : state,
    );
  }, []);

  // Stejná podmínka jako v ResponsiveCatalogLayout — browse bez hledání jde
  // přes celou šířku na všech viewportech.
  const showFullWidthBrowse = catalogViewMode === "browse" && !hasActiveSearch;

  const loadingAnySelected = CATALOGS.some(
    (c) => selected.has(c.id) && (loadingCats[c.id] || catalogRowsStillIndexing(c.id))
  );

  useEffect(() => {
    if (!deepData) return;
    mergeDeepResults(deepData);
  }, [deepData, mergeDeepResults]);

  useEffect(() => {
    if (classicSearchScope !== CLASSIC_SEARCH_SCOPE_ALL) return;
    const first = browseOptions[0]?.id || DEFAULT_CLASSIC_SEARCH_CATALOG_ID;
    if (!first) return;
    setClassicSearchScope(first);
    setBrowseCatalogId(first);
  }, [classicSearchScope, browseOptions, setBrowseCatalogId]);

  const handleClassicSearchFallback = useCallback(() => {
    const q = aiQuery.trim();
    if (q.length < 2) {
      toast.error("Zadejte alespoň 2 znaky.");
      return;
    }
    cancelDeepSearch();
    setDeepError("");
    setDeepData(null);
    setUseAiAssistant(false);
    submitCrossSearch(q);
    toast.info("Přepnuto na hybridní katalogové hledání (intent + synonyma).");
  }, [aiQuery, cancelDeepSearch, submitCrossSearch, setDeepData, setDeepError, setUseAiAssistant]);

  const submitFollowup = useCallback(
    async (text, actionHint = "") => {
      const availableRefs = followupAvailableSeriesRefs.length > 0
        ? followupAvailableSeriesRefs
        : (followupSeriesOptions || []).slice(0, 24).map(toFollowupSeriesRef);
      const msg = String(text || "").trim();
      if (msg.length < 2) {
        toast.error("Follow-up dotaz je příliš krátký.");
        return;
      }
      setFollowupInput("");
      setDeepFollowupError("");
      // Nechat rozhodnout LLM, jestli zpráva navazuje na nalezené výsledky, nebo je to
      // nové téma — regex (looksLikeCatalogRefineSearch/looksLikeTopicSearch) bral i krátké
      // navazující věty jako "chci řady z eurostatu" mylně jako nové hledání. Regex zůstává
      // jen jako fallback, když LLM roving selže/timeoutne.
      let routedIntent = "unknown";
      let routedActionHint = "";
      let routedPlan = null;
      try {
        const { data: intentData } = await api.post("/catalog/deep-search/results-intent", {
          message: msg,
          has_selected_series: false,
          root_query: String(aiQuery || "").trim(),
          current_sources: Array.from(effectiveDeepSelected || []),
          found_summary: availableRefs,
          conversation_history: followupMessages.slice(-8).map((m) => ({
            role: m?.role,
            content: m?.content,
          })),
        });
        if (
          intentData?.ok &&
          (intentData.intent === "continue" ||
            intentData.intent === "refine_search" ||
            intentData.intent === "new_search")
        ) {
          routedIntent = intentData.intent;
          routedActionHint = String(intentData.action_hint || "").trim().toLowerCase();
          routedPlan =
            intentData.followup_plan && typeof intentData.followup_plan === "object"
              ? intentData.followup_plan
              : null;
        }
      } catch {
        routedIntent = "unknown";
      }
      const isNewSearch =
        routedIntent === "new_search" ||
        (routedIntent === "unknown" && (looksLikeCatalogRefineSearch(msg) || looksLikeTopicSearch(msg)));
      if (isNewSearch) {
        setFollowupInput("");
        setDeepFollowupError("");
        setAiQuery(msg);
        setUnifiedQuery(msg);
        cancelDeepSearch();
        catalogAiSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
        await applySuggestedDeepSearch(msg);
        return;
      }
      const resolvedHint =
        String(actionHint || "").trim().toLowerCase() ||
        routedActionHint ||
        resolveFollowupActionHint(msg, 0, followupSuggestedActions);
      const hasFollowupContext = Boolean(deepConversation?.id || deepData?.conversation?.id);
      if (!hasFollowupContext) {
        let assistantReply = "";
        try {
          const foundSummary = (followupSeriesOptions || []).slice(0, 50).map((x) => ({
            source_type: x.source_type || x.source || x.catalog_id || "",
            catalog_id: x.catalog_id || x.source_type || x.source || "",
            set_id: x.set_id || x.id || "",
            title: x.title || x.label || x.name || x.set_id || "",
            query_params: x.query_params || {},
            // Send metadata so the economist chat can reason over more than the title.
            description: x.description || x.note || x.subtitle || "",
            frequency: x.frequency || x.freq || "",
            unit: x.unit || x.units || x.unit_label || "",
            last_value: x.last_value ?? null,
            last_period: x.last_period || x.last_date || "",
          }));
          const { data } = await api.post("/catalog/deep-search/results-chat", {
            message: msg,
            selected_series_refs: [],
            found_summary: foundSummary,
            conversation_history: followupMessages.slice(-8).map((m) => ({
              role: m?.role,
              content: m?.content,
            })),
          });
          assistantReply = String(data?.assistant_answer || "").trim();
        } catch  {
          assistantReply = "";
        }
        if (!assistantReply) {
          assistantReply = buildLocalSearchEconomistReply(msg, availableRefs);
        }
        setFollowupMessages((prev) =>
          [
            ...prev,
            { id: `local-user-${Date.now()}`, role: "user", content: msg },
            { id: `local-ai-${Date.now()}`, role: "assistant", content: assistantReply },
          ].slice(-20),
        );
        setFollowupInput("");
        setDeepFollowupError("");
        return;
      }
      const resp = await runDeepFollowup({
        message: msg,
        actionHint: resolvedHint,
        followupPlan: routedPlan,
        availableSeriesRefs: availableRefs,
      });
      if (!resp || resp.ok === false) {
        toast.error(String(resp?.error || "Follow-up se nepodařilo dokončit."));
        return;
      }
      if (resp?.conversation?.messages && Array.isArray(resp.conversation.messages)) {
        setFollowupMessages(resp.conversation.messages.slice(-20));
      }
      if (resp?.deep_search_result?.conversation) {
        setDeepConversation(resp.deep_search_result.conversation);
      }
    },
    [
      runDeepFollowup,
      followupSuggestedActions,
      followupSeriesOptions,
      followupAvailableSeriesRefs,
      followupMessages,
      buildLocalSearchEconomistReply,
      deepConversation?.id,
      deepData?.conversation?.id,
      setDeepConversation,
      applySuggestedDeepSearch,
      cancelDeepSearch,
      setAiQuery,
      setUnifiedQuery,
      aiQuery,
      effectiveDeepSelected,
    ],
  );

  const submitClarification = useCallback(
    async (text) => {
      const msg = String(text || "").trim();
      if (msg.length < 2) {
        toast.error("Upřesnění je příliš krátké.");
        return;
      }
      const hasFollowupContext = Boolean(deepConversation?.id || deepData?.conversation?.id);
      if (hasFollowupContext) {
        setFollowupInput(msg);
        if (!useAiAssistant) setUseAiAssistant(true);
        await submitFollowup(msg, "refine_search");
        catalogAiSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
        return;
      }
      const prior = String(aiQuery || unifiedQuery || "").trim();
      const shortRefine = msg.split(/\s+/).length <= 4;
      const needsPriorContext =
        shortRefine &&
        prior.length >= 2 &&
        !msg.toLowerCase().includes(prior.toLowerCase()) &&
        !prior.toLowerCase().includes(msg.toLowerCase());
      const effectiveQuery = needsPriorContext ? `${msg} ${prior}`.trim() : msg;
      setAiQuery(effectiveQuery);
      if (!useAiAssistant) setUseAiAssistant(true);
      await applySuggestedDeepSearch(effectiveQuery);
      catalogAiSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    },
    [
      deepConversation?.id,
      deepData?.conversation?.id,
      submitFollowup,
      aiQuery,
      unifiedQuery,
      applySuggestedDeepSearch,
      useAiAssistant,
      setUseAiAssistant,
    ],
  );

  /** Sjednotit řetězec vyhledávání s navigací (horní panel, odkazy ?q=…). */
  useEffect(() => {
    const sp = new URLSearchParams(location.search);
    const qp = sp.get("q");
    if (qp !== null) {
      setCrossSearchQuery((prev) => (qp !== prev ? qp : prev));
      setAiQuery((prev) => (qp !== prev ? qp : prev));
      if (qp.trim().length >= 2) {
        setSubmittedCrossQuery(qp.trim());
      } else {
        setSubmittedCrossQuery("");
      }
      return;
    }
    setCrossSearchQuery((prev) => (prev ? "" : prev));
    setAiQuery((prev) => (prev ? "" : prev));
    setSubmittedCrossQuery("");
  }, [location.search, setAiQuery, setCrossSearchQuery, setSubmittedCrossQuery]);

  /** ?scope= — výběr katalogu z globální horní lišty (nepřepisuje preview param catalog=). */
  useEffect(() => {
    const scopeRaw = String(params.get("scope") || "").trim();
    if (!scopeRaw) return;
    const resolved = normalizeCatalogBrowseIdFromUrlParam(scopeRaw);
    if (!CATALOGS.some((c) => c.id === resolved)) return;
    setClassicSearchScope(resolved);
    setBrowseCatalogId(resolved);
    setSelected((s) => {
      if (s.has(resolved)) return s;
      const next = new Set(s);
      next.add(resolved);
      return next;
    });
  }, [params, setBrowseCatalogId, setSelected]);

  /** Filtry z globální horní lišty (localStorage + live sync). */
  const headerFiltersHydratedRef = useRef(false);
  const selectedIdsKey = useMemo(
    () => [...selected].sort().join("\0"),
    [selected],
  );

  useEffect(() => {
    const applyHeaderFilters = (prefs) => {
      if (!prefs || typeof prefs !== "object") return;
      if (Array.isArray(prefs.selectedIds)) {
        const nextKey = [...prefs.selectedIds].sort().join("\0");
        setSelected((prev) => {
          const prevKey = [...prev].sort().join("\0");
          return prevKey === nextKey ? prev : new Set(prefs.selectedIds);
        });
      }
      if (typeof prefs.browseLocalBranchOnly === "boolean") {
        setBrowseLocalBranchOnly(prefs.browseLocalBranchOnly);
      }
      if (typeof prefs.browseSearchAcrossSelected === "boolean") {
        setBrowseSearchAcrossSelected(prefs.browseSearchAcrossSelected);
      }
      if (typeof prefs.browseSearchCategoriesOnly === "boolean") {
        setBrowseSearchCategoriesOnly(prefs.browseSearchCategoriesOnly);
      }
      if (prefs.wbCountry) setWbCountry(String(prefs.wbCountry));
    };
    applyHeaderFilters(loadCatalogHeaderFilters());
    headerFiltersHydratedRef.current = true;
    const onHeaderFilters = (event) => applyHeaderFilters(event?.detail);
    window.addEventListener(CATALOG_HEADER_FILTERS_EVENT, onHeaderFilters);
    return () => window.removeEventListener(CATALOG_HEADER_FILTERS_EVENT, onHeaderFilters);
  }, [
    setBrowseLocalBranchOnly,
    setBrowseSearchAcrossSelected,
    setBrowseSearchCategoriesOnly,
    setSelected,
    setWbCountry,
  ]);

  useEffect(() => {
    if (!headerFiltersHydratedRef.current) return;
    saveCatalogHeaderFilters({
      selectedIds: [...selected],
      browseLocalBranchOnly,
      browseSearchAcrossSelected,
      browseSearchCategoriesOnly,
      wbCountry,
    });
  }, [
    selectedIdsKey,
    browseLocalBranchOnly,
    browseSearchAcrossSelected,
    browseSearchCategoriesOnly,
    wbCountry,
  ]);


  useEffect(() => {
    if (!location.state || typeof location.state !== "object" || !location.state.openBrowse) return;
    if (typeof window !== "undefined" && window.matchMedia("(max-width: 1279px)").matches) {
      setMobileBrowseOpen(true);
    } else {
      setBrowseSidebarOpen(true);
    }
    nav({ pathname: location.pathname, search: location.search }, { replace: true, state: {} });
  }, [location.state, location.pathname, location.search, nav]);

  /** ?ai=1 — scroll na výsledky AI hloubkového hledání; AI zapnout jen s dotazem nebo runDeep (ne při čistém ?ai=1). */
  useEffect(() => {
    const sp = new URLSearchParams(location.search);
    if (sp.get("ai") !== "1") return;
    const q = String(sp.get("q") || "").trim();
    const runDeep = sp.get("runDeep") === "1";
    if (!runDeep && q.length < 2) return;
    setUseAiAssistant(true);
    const scrollKey = `${sp.get("q") || ""}|ai1`;
    if (lastAiScrollSearchRef.current === scrollKey) return;
    lastAiScrollSearchRef.current = scrollKey;
    setAiSectionHighlight(true);
    const scrollT = window.setTimeout(() => {
      const el = catalogAiSectionRef.current;
      // Sekce je `sr-only`, dokud AI rešerše nemá obsah — pak nescrollovat.
      if (!el || el.getAttribute("aria-hidden") === "true") return;
      el.scrollIntoView({ behavior: "smooth", block: "center" });
    }, 150);
    const unhlT = window.setTimeout(() => setAiSectionHighlight(false), 5200);
    return () => {
      window.clearTimeout(scrollT);
      window.clearTimeout(unhlT);
    };
  }, [location.search]);

  /** ?runDeep=1 — jednorázově spustit hloubkové API a param odstranit. */
  useEffect(() => {
    const sp = new URLSearchParams(location.search);
    const headerSubmitNonce = sp.get("hs");
    if (headerSubmitNonce && sp.get("runDeep") === "1") {
      autoDeepHandledRef.current = false;
    }
    if (sp.get("runDeep") !== "1") {
      autoDeepHandledRef.current = false;
      return;
    }
    if (autoDeepHandledRef.current) return;
    // Čteme q přímo z URL — ne z debouncedAi, aby stará (debounced) query nepřepsala novou.
    const dq = String(sp.get("q") || "").trim();
    if (dq.length < 2) return;
    autoDeepHandledRef.current = true;
    lastDeepRunQueryRef.current = dq;
    const nextSp = new URLSearchParams(location.search);
    nextSp.set("q", dq);
    nextSp.set("ai", "1");
    nextSp.delete("runDeep");
    nextSp.delete("hs");
    nav(
      {
        pathname: location.pathname,
        search: `?${nextSp.toString()}`,
      },
      { replace: true }
    );
    void applySuggestedDeepSearch(dq).catch(() => {});
  // debouncedAi je v deps kvůli stabilitě počtu závislostí (HMR); v efektu se nepoužívá.
  }, [debouncedAi, location.pathname, location.search, nav, applySuggestedDeepSearch]);

  /**
   * Sdílený / přímý odkaz na výsledky: ?q=…&ai=1 BEZ runDeep=1. Appka sama po odeslání hledání
   * přepíše URL právě do téhle podoby (runDeep se odstraní výše), takže reload nebo sdílení té URL
   * dřív ukázalo „Nalezeno 0 položek" (klasické hledání je při zapnutém AI potlačené a deep se
   * bez runDeep nespustil). Spustíme deep rešerši jednou pro daný dotaz; lastDeepRunQueryRef sdílí
   * s runDeep efektem, takže se stejný dotaz po odeslání tlačítkem nespustí podruhé.
   */
  useEffect(() => {
    const sp = new URLSearchParams(location.search);
    if (sp.get("ai") !== "1" || sp.get("runDeep") === "1") return;
    const dq = String(sp.get("q") || "").trim();
    if (dq.length < 2) return;
    if (lastDeepRunQueryRef.current === dq) return;
    // Deep rešerše běží jen se zapnutým AI asistentem (runDeepSearch se jinak ukončí). Když je
    // vypnutý (uživatel přepnul na klasické), zapneme ho a počkáme na re-render (efekt má
    // useAiAssistant v deps), teprve pak spustíme — ref proto zapisujeme až při reálném spuštění.
    if (!useAiAssistant) {
      setUseAiAssistant(true);
      return;
    }
    lastDeepRunQueryRef.current = dq;
    void applySuggestedDeepSearch(dq).catch(() => {});
  }, [location.search, useAiAssistant, applySuggestedDeepSearch]);

  const handleDeepSearchSourceToggle = (id) => {
    setDeepSearchExcludedCatalogIds((prev) => {
      if (!id) return prev;
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const submitDeepFollowupInput = () => {
    void submitFollowup(followupInput);
  };

  const submitDeepFollowupAction = () => {
    const msg = String(followupInput || "").trim();
    const hint = resolveFollowupActionHint(msg, 0, followupSuggestedActions);
    void submitFollowup(msg, hint);
  };

  const applyDeepSuggestedQuery = (text) => {
    const q = String(text || "").trim();
    if (q.length < 2) return;
    setAiQuery(q);
    if (useAiAssistant) {
      void applySuggestedDeepSearch(q).catch(() => {});
    } else {
      setUseAiAssistant(true);
      void applySuggestedDeepSearch(q).catch(() => {});
    }
    catalogAiSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  return (
    <AppShell
      title={t("pages.catalogHub.title")}
      subtitle={t("pages.catalogHub.subtitle")}
    >
      <CatalogSearchErrorBoundary onRetry={() => window.location.reload()}>
      <div
        className={`catalog-dark-scope min-w-0 w-full max-w-full overflow-x-hidden ${
          hasActiveSearch ? "catalog-search-active-layout" : "space-y-4"
        }`}
      >
        <CatalogStubsLoadErrorBanner
          title={t("pages.catalogHub.loadFailed")}
          message={catalogStubsError}
          retryLabel={t("pages.catalogHub.retry")}
          onRetry={() => setStubsFetchNonce((n) => n + 1)}
        />
        {/* Vyhledávací hlavička skryta — ovládání převzala globální horní lišta (AppShellCatalogSearchBar).
            Necháváme jen AI feedback (gating hláška, průběh/zrušení hloubkového hledání). */}
        <CatalogAiSearchFeedback
          visible={useAiAssistant && deepLoading}
          loading={deepLoading}
          onCancel={() => {
            cancelDeepSearch();
            setDeepError("Vyhledávání bylo zrušeno.");
          }}
        />

        <CatalogHubNav />

        <div
          className={
            hasActiveSearch
              ? "catalog-search-results-scroll catalog-search-results-active min-w-0"
              : "min-w-0"
          }
        >
        <ResponsiveCatalogLayout
          layoutVariant={responsiveLayout.layoutVariant}
          viewportWidth={responsiveLayout.width}
          browseDetailWorkflow={responsiveLayout.browseDetailWorkflow}
          viewMode={catalogViewMode}
          hasActiveSearch={hasActiveSearch}
          explorerWidthClass={responsiveLayout.explorerWidthClass}
          explorerOpen={browseSidebarOpen}
          onExplorerClose={() => setBrowseSidebarOpen(false)}
          mobileExplorerOpen={mobileBrowseOpen}
          onMobileExplorerClose={() => setMobileBrowseOpen(false)}
          explorer={
            <CatalogExplorerPanel
              t={t}
              browseCatalogId={browseCatalogId}
              browseDef={browseDef}
              browseOptions={browseOptions}
              selected={selected}
              trees={trees}
              loadingCats={loadingCats}
              errors={errors}
              browseIndexPending={browseIndexPending}
              browseEmptyFilteredOut={browseEmptyFilteredOut}
              browseRescueNotice={browseRescueNotice}
              emptyBrowseMessage={CATALOG_EMPTY_BROWSE_CZ}
              explorerColumns={explorerColumns}
              openPaths={openPaths}
              explorerLoadingRowKey={explorerLoadingRowKey}
              onSelectRow={handleExplorerRowSelect}
              onClearBrowseFilter={() => setBrowsePanelFilter("")}
              loadingPrimaryLabel={browseLoadingPrimaryLabel(browseDef)}
              errorFallbackLinks={<CatalogBrowseFallbackLinks def={browseDef} />}
              catalogProfileTagline={
                getCatalogDatabaseProfile(browseCatalogId)?.tagline || ""
              }
              explorerLayoutMode={showFullWidthBrowse ? "browse-full" : "default"}
              onClose={
                hasActiveSearch && browseSidebarOpen && responsiveLayout.width >= 1280
                  ? handleCloseExplorerPanel
                  : undefined
              }
            />
          }
          results={
            <>
          <div className="catalog-search-results-pane space-y-4 min-w-0 w-full">

                  {browseGlobalResultsVisible ? (
            <section
              className="rounded-2xl border border-border/80 bg-gradient-to-br from-card via-muted/25 to-muted/40 px-4 py-4 shadow-sm space-y-3"
              aria-label={browseSearchAcrossSelected ? "Výsledky hledání napříč aktivními katalogy" : "Globální výsledky vyhledávání v katalogu"}
              data-testid="catalog-global-search-results"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0 flex-1">
                <div className="text-sm font-semibold text-foreground">
                  {browseSearchAcrossSelected ? "Výsledky napříč aktivními katalogy" : "Výsledky hledání v katalogu"}
                  {browseSearchCategoriesOnly ? " · jen kategorie" : ""}
                </div>
                <p className="text-[12px] text-muted-foreground mt-1 leading-snug">
                  {browseSearchAcrossSelected ? (
                    <>
                      Dotaz „{debouncedBrowsePanelFilter}“ ve {browseOptions.length} vybraných katalozích
                    </>
                  ) : (
                    <>
                      Výsledky pro „{debouncedBrowsePanelFilter}“ ve zdroji{" "}
                      <span className="font-medium">{browseDef.label}</span>
                    </>
                  )}
                  {!browseSearchLoading && browseSearchResults?.elapsed_ms != null ? (
                    <span className="text-muted-foreground">
                      {" "}
                      · {Number(browseSearchResults.elapsed_ms).toLocaleString("cs-CZ")} ms
                    </span>
                  ) : null}
                </p>
                </div>
                <button
                  type="button"
                  className="h-7 w-7 inline-flex shrink-0 items-center justify-center rounded-md border border-border/70 bg-card text-muted-foreground hover:bg-muted/50 hover:text-foreground"
                  onClick={handleDismissBrowseSearchResults}
                  aria-label="Zavřít výsledky hledání v katalogu"
                >
                  <XIcon className="h-3.5 w-3.5" aria-hidden />
                </button>
              </div>
              {browseSearchLoading ? (
                <DataLoadInline label={browseSearchAcrossSelected ? "Prohledávám aktivní katalogy na serveru…" : "Prohledávám celý katalog na serveru…"} />
              ) : null}
              {showCatalogMultiLaneProvisional ? (
                <div
                  className="space-y-3 rounded-xl border border-dashed border-sky-300/80 bg-sky-50/30 px-4 py-3"
                  data-testid="catalog-multi-lane-provisional"
                >
                  <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-sky-900">
                    <InlineLoadingDots text="Načítám další katalogy" />
                  </div>
                  <p className="text-[11px] text-muted-foreground">
                    Průběžné výsledky z jednotlivých katalogů — finální sloučení ještě běží.
                  </p>
                  <div className="space-y-2">
                    {catalogMultiLanePreviewEntries.map((lane) => (
                      <div
                        key={`catalog-multi-lane-${lane.source}`}
                        className="rounded-lg border border-sky-200/70 bg-background/80 px-3 py-2"
                        data-testid={`catalog-multi-lane-preview-${lane.source}`}
                      >
                        <div className="flex flex-wrap items-center gap-2 text-[11px]">
                          <span className="inline-flex items-center rounded-md bg-sky-100 px-2 py-0.5 font-semibold text-sky-900">
                            {lane.label}
                          </span>
                          <span className="text-muted-foreground">{lane.count} shod</span>
                        </div>
                        {lane.titles.length > 0 ? (
                          <ul className="mt-1.5 space-y-0.5 text-xs text-foreground/90">
                            {lane.titles.map((title, idx) => (
                              <li key={`${lane.source}-title-${idx}`} className="truncate">
                                {title}
                              </li>
                            ))}
                          </ul>
                        ) : null}
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
              {browseSearchError ? (
                <div
                  className="text-sm rounded-xl border border-rose-200 bg-rose-50/95 text-rose-950 canvas-dark:text-rose-100 px-3 py-2 whitespace-pre-wrap"
                  role="alert"
                  data-testid={browseSearchAcrossSelected ? "catalog-multi-search-error" : "catalog-global-search-error"}
                >
                  {browseSearchError}
                </div>
              ) : null}
              {!browseSearchAcrossSelected && catalogGlobResults?.admin_config_hint_cs && isAdmin ? (
                <p className="text-[11px] text-muted-foreground border border-amber-300/70 canvas-dark:border-amber-600/50 bg-amber-50 canvas-dark:bg-amber-950/35 rounded-lg px-3 py-2">
                  <span className="font-medium">Admin:</span> {catalogGlobResults.admin_config_hint_cs}
                </p>
              ) : null}
              {browseSearchThemes.length ? (
                <p className="text-[10px] uppercase tracking-wide text-muted-foreground">
                  Témata ze slovníku synonym: {browseSearchThemes.slice(0, 8).join(", ")}
                </p>
              ) : null}
              {browseSearchResults?.upstream_unavailable ? (
                <p className="text-[11px] text-amber-950 canvas-dark:text-amber-50 bg-amber-50 canvas-dark:bg-amber-950/35 border border-amber-200 canvas-dark:border-amber-600/45 rounded-lg px-3 py-2">
                  Nadřazená služba pro tento dotaz mohla nedokončit odpověď — zkuste užší výraz později.
                </p>
              ) : null}
              {browseSearchAcrossSelected &&
              Array.isArray(catalogMultiResults?.source_summaries) &&
              catalogMultiResults.source_summaries.length > 0 ? (
                <div className="flex flex-wrap gap-1.5 items-center">
                  {catalogMultiResults.source_summaries.map((sum) => {
                    const id = String(sum?.id || "").trim();
                    const excluded = id ? browseExcludedCatalogIds.has(id) : false;
                    return (
                      <button
                        key={`multi-src-summary-${sum.id}`}
                        type="button"
                        onClick={() =>
                          setBrowseExcludedCatalogIds((prev) => {
                            if (!id) return prev;
                            const next = new Set(prev);
                            if (next.has(id)) next.delete(id);
                            else next.add(id);
                            return next;
                          })
                        }
                        className={`text-[10px] px-2 py-1 rounded-md border inline-flex items-center gap-1 ${
                          excluded
                            ? "border-border bg-muted/40 text-muted-foreground line-through"
                            : sum.hits > 0
                              ? "border-emerald-300/75 bg-emerald-50/80 text-emerald-900"
                              : sum.local_index_missing
                                ? "border-amber-300/80 bg-amber-50 text-amber-950"
                              : sum.upstream_unavailable
                                ? "border-rose-300/80 bg-rose-50 text-rose-900"
                                : "border-border bg-muted/40 text-muted-foreground"
                        }`}
                        title={
                          excluded
                            ? `${sum.label} je skrytý ve výsledcích`
                            : sum.local_index_missing
                              ? `${sum.label}: index zatím není připraven`
                            : sum.message_cs || "Kliknutím skrýt tento zdroj"
                        }
                        data-testid={`catalog-multi-source-chip-${id || "unknown"}`}
                      >
                        <span>{sum.label}: {sum.hits}</span>
                        <XIcon className="h-2.5 w-2.5" />
                      </button>
                    );
                  })}
                  {browseExcludedCatalogIds.size > 0 ? (
                    <button
                      type="button"
                      className="text-[10px] px-2 py-1 rounded-md border border-border bg-card hover:bg-muted/50 text-foreground"
                      onClick={() => setBrowseExcludedCatalogIds(new Set())}
                      data-testid="catalog-multi-source-chip-reset"
                    >
                      Zobrazit vše
                    </button>
                  ) : null}
                </div>
              ) : null}
              {browseSearchResults?.message_cs && !browseSearchLoading ? (
                <p
                  className={`text-[13px] leading-relaxed ${browseSearchHitsEffective.length ? "text-foreground/90" : "text-foreground font-medium"}`}
                  data-testid={browseSearchAcrossSelected ? "catalog-multi-search-message" : "catalog-global-search-message"}
                >
                  {browseSearchResults.message_cs}
                </p>
              ) : null}
              {browseSearchCategoriesOnly && browseSearchAcrossSelected && browseSearchHitsRaw.length > 0 && browseSearchHits.length === 0 ? (
                <p className="text-[12px] text-muted-foreground">
                  Pro tento dotaz existují shody, ale žádná z nich není kategorie stromu.
                </p>
              ) : null}
              {browseSearchAcrossSelected &&
              browseExcludedCatalogIds.size > 0 &&
              browseSearchHits.length > 0 &&
              browseSearchHitsVisible.length === 0 ? (
                <p className="text-[12px] text-muted-foreground">
                  Všechny nalezené zdroje jsou právě skryté. Klikněte na „Zobrazit vše“ nebo znovu zapněte některý štítek.
                </p>
              ) : null}
              {browseSearchHitsEffective.length > 0 ? (
                <ol className="catalog-search-hits-grid list-none m-0 p-0">
                  {browseSearchHitsEffective.map((hit, idx) => {
                    const def = CATALOGS.find((d) => d.id === hit.catalog_id);
                    if (!def || !hit.row) return null;
                    if (hit.__category_match) {
                      return (
                        <li
                          key={`cat-hit-${hit.catalog_id}-${hit.catalog_path}-${idx}`}
                          className="rounded-lg border border-border bg-card px-3 py-2 shadow-sm min-w-0"
                        >
                          <div className="flex flex-wrap gap-1.5 items-center">
                            <span className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">{hit.catalog_label}</span>
                            <span className="text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded border border-border bg-muted/25 text-foreground">
                              kategorie
                            </span>
                          </div>
                          <div className="mt-0.5 text-[13px] font-medium text-foreground line-clamp-2">{hit.name || hit.title}</div>
                          {hit.catalog_path ? (
                            <div className="mt-1">
                              <CatalogSearchPathNav
                                catalogPath={hit.catalog_path}
                                categoryPaths={
                                  hit.catalog_id === browseCatalogId ? browseCategoryPathSet : null
                                }
                                onOpenPath={(prefix) => openCatalogPathInBrowseTree(hit.catalog_id, prefix)}
                                showOpenAll
                              />
                            </div>
                          ) : null}
                        </li>
                      );
                    }
                    return (
                      <li key={`glob-${hit.set_id}-${hit.indicator_id || "set"}-${idx}`} className="min-w-0">
                        {renderCatalogSetBlock(def, hit.row, "flat-compact")}
                      </li>
                    );
                  })}
                </ol>
              ) : !browseSearchLoading && !browseSearchError && !browseSearchResults?.message_cs ? (
                <p
                  className="text-sm text-foreground/90 text-center py-5 border border-dashed border-border rounded-xl bg-card/80"
                  role="status"
                  data-testid={browseSearchAcrossSelected ? "catalog-multi-search-empty" : "catalog-global-search-empty"}
                >
                  {browseSearchCategoriesOnly
                    ? "Pro tento dotaz jsme nenašli žádné kategorie."
                    : browseSearchAcrossSelected
                      ? "V aktivních katalozích jsme pro tento dotaz nenašli žádné položky."
                      : "V katalogu zatím nejsou žádné datové sady."}
                </p>
              ) : null}
            </section>
          ) : null}

        {stockSearchResults.length > 0 ? (
          <section className="soft-card mb-3 p-3" data-testid="stock-search-results">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-amber-700 canvas-dark:text-amber-400">
                <TrendingUp className="h-3.5 w-3.5" aria-hidden />
                Akcie
              </h3>
              <span className="text-[11px] text-muted-foreground">
                {stockSearchResults.length} {stockSearchResults.length === 1 ? "nález" : "nálezů"} · Yahoo Finance
              </span>
            </div>
            <div className="mt-2 grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
              {stockSearchResults.map((row) => {
                const ticker = String(row?.ticker || "").trim();
                const price = Number(row?.market_snapshot?.last_price);
                const currency = String(row?.market_snapshot?.currency || "").trim();
                return (
                  <Link
                    key={ticker || row?.name}
                    to={`/search/stocks?q=${encodeURIComponent(ticker || row?.name || submittedCrossQuery)}`}
                    className="catalog-result-card rounded-lg border bg-card px-3 py-2 shadow-sm transition-colors hover:bg-muted/40 min-w-0"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-mono text-sm font-medium text-foreground">{ticker}</span>
                      {Number.isFinite(price) ? (
                        <span className="shrink-0 text-[11px] tabular-nums text-muted-foreground">
                          {price.toLocaleString("cs-CZ", { maximumFractionDigits: 2 })} {currency}
                        </span>
                      ) : null}
                    </div>
                    {row?.name && row.name !== ticker ? (
                      <div className="mt-1 truncate text-[11px] text-muted-foreground">{row.name}</div>
                    ) : null}
                  </Link>
                );
              })}
            </div>
          </section>
        ) : null}

        <div
          ref={catalogAiSectionRef}
          id="catalog-datova-reserse"
          className={
            showDeepChrome
              ? `relative rounded-2xl border border-[hsl(var(--border)/0.85)] bg-card p-3 space-y-2 shadow-sm transition-[box-shadow,ring] duration-300 ${
                  aiSectionHighlight ? "ring-2 ring-emerald-500/50 shadow-lg shadow-emerald-900/10" : ""
                }`
              : "sr-only"
          }
          aria-hidden={showDeepChrome ? undefined : true}
        >
          {showDeepChrome && !deepLoading ? (
            <button
              type="button"
              className="absolute top-2.5 right-2.5 z-20 h-7 w-7 inline-flex items-center justify-center rounded-md border border-border/70 bg-card/95 text-muted-foreground hover:bg-muted/50 hover:text-foreground shadow-sm"
              onClick={handleDismissDeepSearchPanel}
              aria-label="Zavřít AI vyhledávání"
            >
              <XIcon className="h-3.5 w-3.5" aria-hidden />
            </button>
          ) : null}
          {deepContentActive ? (
            <>
          {deepLoading ? (
            <CatalogDeepSearchLoader
              active
              query={aiQuery}
              sourceIds={deepActiveSourceIds}
              sourceLabelFn={deepAiDatabaseLabelCz}
              sourceStatuses={deepStatusesForUi}
              estimateSec={
                aiSearchScope === AI_SEARCH_SCOPE_EXTENDED
                  ? CATALOG_DEEP_SEARCH_ESTIMATE_SEC
                  : CATALOG_AI_QUICK_ESTIMATE_SEC
              }
              onStop={cancelDeepSearch}
            />
          ) : null}

          {deepLoading && groupedDeepVerified.length === 0 && groupedDeepPossible.length === 0 ? (
            deepLanePreviewEntries.length > 0 ? (
              <div className="space-y-4 mt-2">
                <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-sky-900">
                  <InlineLoadingDots text="Načítám zdroje" />
                </div>
                {deepLanePreviewEntries.map((lane) => (
                  <div key={`deep-lane-load-${lane.source}`} className="space-y-2">
                    <div className="flex items-center gap-2 text-[11px] font-semibold text-foreground/70 border-b border-sky-200/60 pb-1">
                      <span className="inline-flex items-center rounded-md bg-sky-100 px-2 py-0.5 text-sky-900">
                        {deepAiDatabaseLabelCz(lane.source)}
                      </span>
                      <span className="text-muted-foreground font-normal">{lane.count} kandidátů</span>
                    </div>
                    <div className="space-y-1.5">
                      {lane.rows.map((row, idx) => {
                        const title = String(row.name || row.title || row.set_id || "").trim();
                        const path = String(row.full_path || "").trim();
                        if (!title) return null;
                        return (
                          <div key={`lane-load-${lane.source}-${row.set_id || idx}`} className="rounded-md border border-border/50 bg-background px-3 py-2 text-xs">
                            <div className="font-medium text-foreground truncate">{title}</div>
                            {path ? <div className="text-muted-foreground truncate mt-0.5">{path}</div> : null}
                          </div>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="space-y-2 mt-2 pointer-events-none select-none" aria-hidden="true">
                {Array.from({ length: 6 }).map((_, i) => (
                  <div
                    key={`deep-skel-${i}`}
                    className="rounded-xl border border-border/50 bg-card/60 px-4 py-3 space-y-2 animate-pulse motion-reduce:animate-none"
                    style={{ animationDelay: `${i * 80}ms` }}
                  >
                    <div className="flex items-center gap-2">
                      <div className="h-2.5 w-2.5 rounded-full bg-slate-200/90" />
                      <div className="h-2.5 rounded bg-slate-200/90" style={{ width: `${48 + (i % 3) * 14}%` }} />
                    </div>
                    <div className="h-2 rounded bg-slate-100/80" style={{ width: `${62 + (i % 4) * 8}%` }} />
                    {i % 2 === 0 ? (
                      <div className="h-2 rounded bg-slate-100/60" style={{ width: "40%" }} />
                    ) : null}
                  </div>
                ))}
              </div>
            )
          ) : null}

          {deepData?.preview_attempts_note ? (
            <div className="text-[11px] text-amber-900 bg-amber-50 canvas-dark:bg-amber-950/35 border border-amber-200 canvas-dark:border-amber-600/45 rounded-xl px-3 py-2 leading-snug space-y-1">
              {eurostatDeepAiMessageLooksInternalDebug(deepData.preview_attempts_note) ? (
                <>
                  <p>{EUROSTAT_DEEP_AI_PREVIEW_UNAVAILABLE_CZ}</p>
                  <EurostatDeepAiTechnicalDetails raw={deepData.preview_attempts_note} />
                </>
              ) : (
                <p>{deepData.preview_attempts_note}</p>
              )}
            </div>
          ) : null}

          <CatalogPipelineDiagnosticsPanel diagnostics={deepPipelineDiagnostics} />

          {(() => {
            const llmPlanner = deepData?.llm_planner || deepData?.query_plan?.llm_planner || {};
            const fallbackTrace = deepData?.fallback_trace || deepData?.query_plan?.fallback_trace || {};
            const plannerCalled = Boolean(llmPlanner?.called);
            const fallbackUsed = Boolean(fallbackTrace?.engine) || Boolean(llmPlanner?.fallback_used);
            if (!plannerCalled && !fallbackUsed) return null;
            const plannerOk = Boolean(llmPlanner?.success);
            const errorType = String(llmPlanner?.error_type || fallbackTrace?.reason || "").trim();
            return (
              <div className={`text-[12px] rounded-xl px-3 py-2 border leading-snug ${
                plannerOk
                  ? "border-emerald-200 bg-emerald-50/85 text-emerald-950"
                  : "border-amber-200 bg-amber-50/90 text-amber-950"
              }`}>
                <span className="font-semibold">
                  AI planner: {plannerOk ? "pouzit" : "nepouzit"}
                </span>
                {errorType ? <span> · duvod: {errorType}</span> : null}
                {fallbackUsed ? <span> · deterministic fallback pouzit</span> : null}
              </div>
            );
          })()}

          <CatalogDeepSearchPlanPanel
            plan={deepAiSearchPlanForUi}
            sourceStatusText={deepAiSourceStatusText}
          />

          {deepCombinedWarnings.length > 0 ? (
            <ul className="text-[11px] text-amber-950 canvas-dark:text-amber-50 bg-amber-50/90 canvas-dark:bg-amber-950/35 border border-amber-200/80 canvas-dark:border-amber-600/45 rounded-lg px-3 py-2 list-disc pl-4 space-y-0.5 leading-snug">
              {deepCombinedWarnings.map((w, i) => (
                <li key={i} className="marker:text-amber-800">
                  {eurostatDeepAiMessageLooksInternalDebug(w) ? (
                    <div className="space-y-1">
                      <span>{EUROSTAT_DEEP_AI_PREVIEW_UNAVAILABLE_CZ}</span>
                      <EurostatDeepAiTechnicalDetails raw={w} />
                    </div>
                  ) : (
                    w
                  )}
                </li>
              ))}
            </ul>
          ) : null}

          {deepData?.fallback_notice ? (
            <p className="text-[12px] text-amber-900/90 bg-amber-50 canvas-dark:bg-amber-950/35 border border-amber-300/70 canvas-dark:border-amber-600/50 rounded-xl px-3 py-2">
              {deepData.fallback_notice}
            </p>
          ) : null}

          {singleAiSourceProblemBanner ? (
            <div
              role="status"
              className="text-sm text-foreground rounded-xl px-4 py-3 border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 space-y-2 leading-snug"
            >
              <p className="font-medium">
                {deepAiIndexProblemBannerMessageCz(
                  singleAiSourceProblemBanner.sid,
                  singleAiSourceProblemBanner.status,
                )}
              </p>
              {singleAiSourceProblemBanner.sid === "eurostat" &&
              ["not_ready", "timeout", "error"].includes(singleAiSourceProblemBanner.status) ? (
                <p className="text-[13px] text-foreground/90">{EUROSTAT_QUERY_REFINEMENT_HINT_CZ}</p>
              ) : null}
            </div>
          ) : null}

          {showGenericAiIndexNoHitsMessage ? (
            <p role="status" className="text-sm text-foreground/90 rounded-xl px-4 py-3 border border-border bg-muted/38 leading-snug">
              {GENERIC_AI_INDEX_NO_ROWS_CZ}
            </p>
          ) : null}

          {deepData?.ecb_roe_series_hint_cz ? (
            <p className="text-[12px] text-foreground/90 rounded-xl px-3 py-2 border border-sky-100 canvas-dark:border-sky-800/40 bg-sky-50/85 canvas-dark:bg-sky-950/35">
              {deepData.ecb_roe_series_hint_cz}
            </p>
          ) : null}

          {deepData?.ecb_flow_ambiguous_notice_cz ? (
            <p className="text-[12px] text-foreground/90 rounded-xl px-3 py-2 border border-violet-100 bg-violet-50/85">
              {deepData.ecb_flow_ambiguous_notice_cz}
            </p>
          ) : null}


          {deepStatusesForDisplay.length > 0 ? (
            <div className="flex flex-wrap items-center gap-1 text-[10px]">
              {deepStatusesForDisplay.map((st) => {
                const sourceId = String(st.source || "").toLowerCase();
                const rawStatus = String(st.status || "").toLowerCase();
                const isProblem = ["timeout", "error", "not_ready", "warming"].includes(rawStatus);
                const isRunning = rawStatus === "running";
                return (
                  <span
                    key={sourceId || st.label}
                    className={`inline-flex items-center gap-1 rounded-md border px-1.5 py-0.5 ${
                      isProblem
                        ? "border-amber-300/80 bg-amber-50/90 text-amber-950"
                        : isRunning
                          ? "border-sky-300/80 bg-sky-50/90 text-sky-950"
                          : "border-border/70 bg-card text-muted-foreground"
                    }`}
                    title={String(st.message_cs || st.message || "")}
                  >
                    <span className="font-medium text-foreground/90">{st.label || deepAiDatabaseLabelCz(sourceId)}</span>
                    <span>{deepAiSourceStatusText(st)}</span>
                  </span>
                );
              })}
            </div>
          ) : null}

          {deepError ? (
            <div
              className="text-sm rounded-xl p-4 border bg-card space-y-2"
              role="alert"
              style={{ borderColor: "hsl(354 60% 90%)", color: "hsl(354 35% 32%)" }}
            >
              <p className="font-sans text-foreground font-medium leading-snug">Vyhledávání katalogu selhalo.</p>
              {deepError.includes("timeout") || /vypršel|TIMEOUT/i.test(deepError) ? (
                <p className="font-sans text-foreground text-sm leading-snug">
                  Požadavek vypršel (limit{" "}
                  {Math.round(
                    (aiSearchScope === AI_SEARCH_SCOPE_EXTENDED
                      ? CATALOG_DEEP_SEARCH_TIMEOUT_MS
                      : CATALOG_AI_QUICK_TIMEOUT_MS) / 1000,
                  )}{" "}
                  s). Backend může být vytížený nebo katalogové ověřování překročilo čas — zkuste užší dotaz nebo
                  zkuste později.
                </p>
              ) : null}
              <p className="text-sm text-foreground leading-snug">{deepErrorFriendly}</p>
              {(deepErrorTechnical && String(deepErrorTechnical).trim()) ||
              (deepError && deepErrorFriendly.trim() !== String(deepError).trim()) ? (
                <details className="text-[11px] text-muted-foreground">
                  <summary className="cursor-pointer select-none font-medium text-foreground/90">Technické detaily</summary>
                  <pre className="mt-1 font-mono whitespace-pre-wrap break-words">
                    {String(deepErrorTechnical || deepError || "").trim()}
                  </pre>
                </details>
              ) : null}
              <button
                type="button"
                className="text-[12px] font-medium underline text-foreground/90"
                onClick={handleClassicSearchFallback}
              >
                Zkusit klasické hledání stejným dotazem (bez AI)
              </button>
              {aiSearchScope !== AI_SEARCH_SCOPE_EXTENDED ? (
                <button
                  type="button"
                  className="btn-mint inline-flex items-center justify-center gap-1.5 px-4 h-9 text-[13px] font-semibold shadow-sm"
                  onClick={() => runDeepSearchExtended(aiQuery)}
                  disabled={deepLoading}
                >
                  Rozšířit hledání na všechny vybrané zdroje
                </button>
              ) : null}
            </div>
          ) : null}

          {!deepLoading &&
          !deepError &&
          deepData?.ok !== false &&
          aiSearchScope !== AI_SEARCH_SCOPE_EXTENDED ? (
            <p className="text-[11px] text-muted-foreground px-0.5" data-testid="catalog-ai-scope-hint">
              Výsledky z rychlého AI výběru zdrojů — pro prohledání všech zdrojů změňte rozsah v horní liště na „Vybrané zdroje".
            </p>
          ) : null}

          {deepData?.ok === false ? (
            <div
              className="rounded-xl border border-rose-200 bg-rose-50/90 text-rose-950 canvas-dark:text-rose-100 px-4 py-3 space-y-1 text-sm"
              role="alert"
            >
              <p className="font-semibold">
                {deepData?.message_cs ||
                  deepData?.message ||
                  "AI vyhledávání se nepodařilo dokončit. Zkuste vybrat méně databází nebo kratší dotaz."}
              </p>
              <details className="text-[11px] text-rose-900/95">
                <summary className="cursor-pointer font-medium">Technické detaily</summary>
                <pre className="mt-1 font-mono whitespace-pre-wrap break-words">
                  {String(deepData?.error || "all_sources_failed")}
                </pre>
              </details>
              <button
                type="button"
                className="text-[12px] font-medium underline text-rose-950"
                onClick={handleClassicSearchFallback}
              >
                Zkusit klasické hledání stejným dotazem (bez AI)
              </button>
            </div>
          ) : null}

          <CatalogDeepSearchResultsPanel
            deepData={deepData}
            deepVerifiedList={deepVerifiedList}
            deepPossibleList={deepPossibleList}
            deepLowRelevanceList={deepLowRelevanceList}
            deepDiscardedCandidatesList={deepDiscardedCandidatesList}
            deepSuggestedQueriesList={deepSuggestedQueriesList}
            showPartialNoValidCandidates={showPartialNoValidCandidates}
            singleAiSourceProblemBanner={singleAiSourceProblemBanner}
            showMultiAiIndexProblemNoHitsNote={showMultiAiIndexProblemNoHitsNote}
            showGenericAiIndexNoHitsMessage={showGenericAiIndexNoHitsMessage}
            deepSearchSourceSummaries={deepSearchSourceSummaries}
            showDeepFollowupPanel={showDeepFollowupPanel}
            deepFollowupClarificationMode={deepFollowupClarificationMode}
            deepClarificationPayload={deepClarificationPayload}
            followupInputRef={followupInputRef}
            followupInput={followupInput}
            deepLoading={deepLoading}
            deepSearchExcludedCatalogIds={deepSearchExcludedCatalogIds}
            deepSearchAllSourcesHidden={deepSearchAllSourcesHidden}
            deepPrimaryChart={deepPrimaryChart}
            followupMessages={followupMessages}
            deepChatFilteredIds={deepChatFilteredIds}
            followupChartPayload={followupChartPayload}
            followupComposedChartData={followupComposedChartData}
            deepFollowupLoading={deepFollowupLoading}
            deepFollowupError={deepFollowupError}
            deepAiAnalysisDatasets={deepAiAnalysisDatasets}
            eurostatRetryableIndexStatus={eurostatRetryableIndexStatus}
            deepStatusesForUi={deepStatusesForUi}
            topDeepRecommendations={topDeepRecommendations}
            groupedDeepVerified={groupedDeepVerified}
            showDeepLaneProvisional={showDeepLaneProvisional}
            deepLanePreviewEntries={deepLanePreviewEntries}
            groupedDeepPossible={groupedDeepPossible}
            renderCatalogSetBlock={renderCatalogSetBlock}
            CandidateResultCard={DeepSearchCandidateResultCard}
            LoadingDots={InlineLoadingDots}
            onClarificationOption={(optText) => {
              setFollowupInput(optText);
              void submitClarification(optText);
            }}
            onClarificationInputChange={setFollowupInput}
            onSubmitClarification={submitClarification}
            onDeepSearchSourceToggle={handleDeepSearchSourceToggle}
            onDeepSearchSourceReset={() => setDeepSearchExcludedCatalogIds(new Set())}
            onScrollToCandidates={() =>
              document.getElementById("catalog-deep-candidates-section")?.scrollIntoView({
                behavior: "smooth",
                block: "start",
              })
            }
            onFollowupInputChange={setFollowupInput}
            onSubmitFollowupInput={submitDeepFollowupInput}
            onSubmitFollowupAction={submitDeepFollowupAction}
            onRetryDeepSearch={() => void runDeepSearch()}
            onStructuredNoAnswerOption={applyDeepSuggestedQuery}
            onSuggestedQuery={applyDeepSuggestedQuery}
            onClearChatFilter={clearDeepChatFilter}
          />
            </>
          ) : null}
        </div>

        {/* Web-search fallback: its own separate section (like "Akcie"), shown only when the
            catalog deep search returned no valid result and the web was queried. Never mixed into
            catalog hits. Self-gates via web_research_status, so it renders nothing on normal queries. */}
        <CatalogWebFallbackSection deepData={deepData} />

        {showClassicSearchResults ? (
          <div className="flex items-start justify-between gap-2">
            {catalogResultsSummary ? (
              <p className="text-[11px] text-muted-foreground px-0.5 flex-1 min-w-0">{catalogResultsSummary}</p>
            ) : (
              <span className="flex-1" />
            )}
            <button
              type="button"
              className="h-7 w-7 inline-flex shrink-0 items-center justify-center rounded-md border border-border/70 bg-card text-muted-foreground hover:bg-muted/50 hover:text-foreground"
              onClick={handleDismissCrossSearchResults}
              aria-label="Zavřít výsledky hledání"
            >
              <XIcon className="h-3.5 w-3.5" aria-hidden />
            </button>
          </div>
        ) : null}

        {crossSearchAllActionableFailed ? (
          <div
            role="alert"
            className="rounded-xl border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 text-amber-950 canvas-dark:text-amber-50 px-4 py-3 text-sm leading-snug mb-1"
          >
            <p className="font-medium">Žádnému aktivnímu katalogu se nepodařilo načíst data pro průřezové hledání.</p>
            <p className="text-[13px] text-amber-900/95 mt-1">{API_FAILURE_CORS_OR_NETWORK}</p>
            <div className="mt-2">
              <button
                type="button"
                onClick={retrySelectedCatalogLoads}
                disabled={loadingAnySelected}
                className="inline-flex items-center rounded-lg border border-amber-300/80 px-3 py-1.5 text-xs font-medium bg-amber-100/70 hover:bg-amber-100 disabled:opacity-70"
              >
                Zkusit načíst katalogy znovu
              </button>
            </div>
          </div>
        ) : null}

        {showClassicSearchResults && (
          <div className="text-[11px] font-mono text-muted-foreground flex flex-wrap gap-3">
            {CATALOGS.filter((c) => selected.has(c.id)).map((c) => (
              <span key={c.id}>
                {c.label}:{" "}
                {loadingCats[c.id] ? (
                  "načítání"
                ) : trees[c.id] ? (
                  "načteno"
                ) : errors[c.id] ? (
                  <>
                    chyba:{" "}
                    {String(errors[c.id])
                      .replace(/\s+/g, " ")
                      .slice(0, 100)}
                    {String(errors[c.id]).length > 100 ? "…" : ""}
                  </>
                ) : (
                  "—"
                )}
              </span>
            ))}
          </div>
        )}

        {showClassicSearchResults && !useAiAssistant && crossSearchBackendLoading && !crossSearchBackendAttempted && totalClassicResultCount === 0 ? (
          <LoadingBlock
            label="Vyhledávám v katalozích na serveru…"
            sublabel={
              classicSearchCatalogDefs.length === 1
                ? "První dotaz může trvat desítky sekund (načtení indexu z ČNB / jiného API). Další dotazy jsou obvykle rychlejší."
                : "Výsledky se řadí podle relevance (soft-AND, geo boost). U více katalogů běží dotazy paralelně."
            }
            minHeightClass="min-h-[160px]"
            showSkeletonLines
          />
        ) : showClassicSearchResults &&
          (crossSearchUsedLocalFallback ? awaitingCatalogs || loadingAnySelected : false) &&
          totalClassicResultCount === 0 ? (
          <LoadingBlock
            label="Načítám vybrané katalogy…"
            sublabel="Lokální fallback — načítání stromů pro offline filtr."
            minHeightClass="min-h-[160px]"
            showSkeletonLines
          />
        ) : showClassicSearchResults && totalClassicResultCount === 0 && !inAppSearchLoading ? (
          <CatalogClassicEmptyState />
        ) : showClassicSearchResults ? (
          <>
          {!useAiAssistant && inAppSearchLoading ? (
            <div className="mb-3 rounded-xl border border-sky-200/80 bg-sky-50/50 px-3 py-2">
              <DataLoadInline label="Prohledávám přehled, sekce a osobní dashboard…" />
            </div>
          ) : null}
          <CatalogSearchStatusBanners
            useAiAssistant={useAiAssistant}
            inAppSearchError={inAppSearchError}
            crossSearchPartialIndexMissing={crossSearchPartialIndexMissing}
            crossSearchUsedLocalFallback={crossSearchUsedLocalFallback}
            awaitingCatalogs={awaitingCatalogs}
            hasBackendResults={flatResults.some((e) => e.resultSource === "backend")}
          />
          {!useAiAssistant && inAppSearchResults.length > 0 ? (
            <section
              className="mb-3 rounded-xl border border-violet-200/80 bg-violet-50/45 px-3 py-3"
              data-testid="in-app-search-results"
            >
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <h3 className="text-xs font-semibold uppercase tracking-wide text-violet-950">
                  V aplikaci
                </h3>
                <span className="text-[11px] text-muted-foreground">
                  {inAppSearchResults.length} shod v přehledu, sekcích a dashboardu
                </span>
              </div>
              <div className="mt-2 grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
                {inAppSearchResults.map((item) => (
                  <Link
                    key={item.id}
                    to={item.path || "/"}
                    className="rounded-lg border border-border/75 bg-card px-3 py-2 text-left shadow-sm hover:bg-muted/40 transition-colors min-w-0"
                  >
                    <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                      <span>{item.surface || "Aplikace"}</span>
                      <span>·</span>
                      <span className="truncate">{item.pageTitle || item.section || ""}</span>
                    </div>
                    <div className="mt-1 text-sm font-medium text-foreground line-clamp-2">{item.title}</div>
                    <div className="mt-1 flex items-center gap-2 text-[11px] text-muted-foreground">
                      {item.type ? <span className="truncate">{item.type}</span> : null}
                      {item.view ? <span className="truncate">{item.view}</span> : null}
                      <ExternalLink className="ml-auto h-3 w-3 shrink-0" aria-hidden />
                    </div>
                  </Link>
                ))}
              </div>
            </section>
          ) : null}
          {!useAiAssistant && crossSearchBackendSummaries.length > 0 ? (
            <div className="flex flex-wrap gap-1.5 items-center mb-3" data-testid="cross-search-source-chips">
              {crossSearchBackendSummaries.map((sum) => {
                const id = String(sum?.id || "").trim();
                const excluded = id ? crossSearchExcludedCatalogIds.has(id) : false;
                return (
                  <button
                    key={`cross-src-summary-${id || "unknown"}`}
                    type="button"
                    onClick={() =>
                      setCrossSearchExcludedCatalogIds((prev) => {
                        if (!id) return prev;
                        const next = new Set(prev);
                        if (next.has(id)) next.delete(id);
                        else next.add(id);
                        return next;
                      })
                    }
                    className={`text-[10px] px-2 py-1 rounded-md border inline-flex items-center gap-1 ${
                      excluded
                        ? "border-border bg-muted/40 text-muted-foreground line-through"
                        : sum.hits > 0
                          ? "border-emerald-300/75 bg-emerald-50/80 text-emerald-900"
                          : sum.local_index_missing
                            ? "border-amber-300/80 bg-amber-50 text-amber-950"
                          : sum.upstream_unavailable
                            ? "border-rose-300/80 bg-rose-50 text-rose-900"
                            : "border-border bg-muted/40 text-muted-foreground"
                    }`}
                    title={
                      excluded
                        ? `${sum.label} je skrytý ve výsledcích — kliknutím znovu zobrazit`
                        : sum.local_index_missing
                          ? `${sum.label}: index zatím není připraven`
                        : sum.message_cs || "Kliknutím skrýt tento zdroj ve výsledcích"
                    }
                    data-testid={`cross-search-source-chip-${id || "unknown"}`}
                  >
                    <span>{sum.label}: {sum.hits}</span>
                    <XIcon className="h-2.5 w-2.5" />
                  </button>
                );
              })}
              {crossSearchExcludedCatalogIds.size > 0 ? (
                <button
                  type="button"
                  className="text-[10px] px-2 py-1 rounded-md border border-border bg-card hover:bg-muted/50 text-foreground"
                  onClick={() => setCrossSearchExcludedCatalogIds(new Set())}
                  data-testid="cross-search-source-chip-reset"
                >
                  Zobrazit vše
                </button>
              ) : null}
            </div>
          ) : null}
          {!useAiAssistant &&
          crossSearchExcludedCatalogIds.size > 0 &&
          flatResults.length > 0 &&
          sortedFlatResults.length === 0 ? (
            <p className="text-[12px] text-muted-foreground mb-3">
              Všechny nalezené zdroje jsou právě skryté. Klikněte na „Zobrazit vše“ nebo znovu zapněte některý štítek.
            </p>
          ) : null}
          {flatResults.length > 0 ? (
            <>
          <CatalogResultsToolbar
            count={sortedFlatResults.length}
            viewMode={resultsViewMode}
            onViewModeChange={setResultsViewMode}
            sortMode={resultsSortMode}
            onSortChange={setResultsSortMode}
          />
          {resultsViewMode === "cards" ? (
            <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-3">
              {sortedFlatResults.map(({ def, row }, idx) => {
                if (row.internalVisualAsset) {
                  return renderCatalogSetBlock(def, row, "flat-compact", idx);
                }
                const meta = catalogResultMetaFromRow(def, row);
                const previewable = isCatalogRowPreviewEligible(def, row);
                const previewInd = String(row?.indicator_id || "").trim();
                const previewId = `${def.id}-${row.set_id}-${def.needsCountry ? wbCountry : ""}${previewInd ? `__${previewInd}` : ""}`;
                const isOpen = previewKey === previewId;
                const actionKey = rowExistingKey(def, row, wbCountry);
                const busy = Boolean(adding[actionKey]);
                const added = existingByCat[def.id]?.has(actionKey) ?? false;
                const aiExplanation = String(
                  row.explanation_cs || row.relevance_explanation || row.ai_reason || "",
                ).trim();
                // set_id + indikátor + ordinál: samotná path se mezi hity opakuje a duplicitní klíče rozbíjí React reconciliaci.
                const cardKey = `${def.id}-${row.set_id || ""}-${row.indicator_id || ""}-${row.path || ""}-${idx}`;
                return (
                  <CatalogResultCard
                    key={cardKey}
                    {...meta}
                    aiExplanation={useAiAssistant ? aiExplanation : ""}
                    selected={
                      seriesDetailOpen &&
                      seriesDetailTarget?.def?.id === def.id &&
                      String(seriesDetailTarget?.row?.set_id || "") === String(row.set_id || "")
                    }
                    previewOpen={isOpen}
                    previewable={previewable}
                    canAdd={canAddSources}
                    added={added}
                    addBusy={busy}
                    onSelect={() => activateCatalogRow(def, row)}
                    onPreview={() => void togglePreview(def, row)}
                    onAdd={() => addSource(def, row)}
                    onDetail={() => activateCatalogRow(def, row)}
                    detailActionLabel={
                      !previewable
                        ? resolveCatalogRowPrimaryAction(def, row).label || "Detail"
                        : "Detail"
                    }
                    detailActionIsBrowse={
                      !previewable && resolveCatalogRowPrimaryAction(def, row).type === "navigate"
                    }
                    detailActionIsQuickPreview={
                      !previewable
                        && resolveCatalogRowPrimaryAction(def, row).type === "bis-dataflow-preview"
                    }
                  />
                );
              })}
            </div>
          ) : (
          <div className="catalog-search-hits-grid">
            {/* Ordinál v klíči: backendové hity sdílí path (např. řady jedné kategorie) — bez něj React kvůli duplicitním klíčům nechává v mřížce staré karty. */}
            {sortedFlatResults.map(({ def, row }, idx) => renderCatalogSetBlock(def, row, "flat-compact", idx))}
          </div>
          )}
            </>
          ) : null}
          </>
        ) : null}
          </div>
            </>
          }
          detail={
            seriesDetailTarget && seriesDetailOpen ? (
            <CatalogDetailView
              open={seriesDetailOpen}
              presentation={responsiveLayout.detailPresentation}
              useFullscreenShell
              onBack={handleBackToCatalog}
              onClose={closeSeriesDetail}
              breadcrumbItems={explorerBreadcrumbItems}
              autoPreview
              previewLoading={
                previewLoading &&
                previewKey === catalogPreviewKey(seriesDetailTarget.def, seriesDetailTarget.row)
              }
              previewError={
                previewKey === catalogPreviewKey(seriesDetailTarget.def, seriesDetailTarget.row) &&
                !(Array.isArray(previewData?.rows) && previewData.rows.length > 0)
                  ? normalizedPreviewError
                  : ""
              }
              onRetryPreview={() => {
                if (seriesDetailTarget) {
                  void openPreviewForRow(seriesDetailTarget.def, seriesDetailTarget.row);
                }
              }}
              title={String(seriesDetailTarget.row?.name || seriesDetailTarget.row?.set_id || "")}
              code={String(seriesDetailTarget.row?.set_id || "")}
              catalogLabel={seriesDetailTarget.def?.label || ""}
              metadata={seriesDetailMetadataItems}
              frequencyEditor={
                seriesDetailFrequencyEditor?.kind === "imf"
                  ? {
                      ...seriesDetailFrequencyEditor,
                      onChange: handleSeriesDetailImfFrequencyChange,
                    }
                  : seriesDetailFrequencyEditor
              }
              previewContent={renderCatalogSetBlock(seriesDetailTarget.def, seriesDetailTarget.row, "detail")}
              chartExpanded={catalogChartExpanded}
              onChartExpandToggle={() => setCatalogChartExpanded((open) => !open)}
            />
            ) : null
          }
        />
        </div>

        <PersonalDashboardPagePickModal
          open={Boolean(pagePick)}
          pages={pagePick?.pages}
          selectedId={pagePick?.selectedId || ""}
          onSelectedIdChange={(id) =>
            setPagePick((prev) => (prev ? { ...prev, selectedId: id } : prev))
          }
          onConfirm={confirmPersonalDashboardPage}
          onCancel={() => setPagePick(null)}
          loading={addingToDash}
        />
      </div>
      </CatalogSearchErrorBoundary>

      {previewKey && previewTarget && !seriesDetailOpen ? (
        <CatalogPreviewFullscreenOverlay
          open
          onClose={closePreview}
          title={String(previewTarget.row?.name || previewTarget.row?.title || "")}
          catalogLabel={String(previewTarget.def?.label || "")}
          code={String(previewTarget.row?.set_id || "")}
          previewLoading={previewLoading}
        >
          {renderCatalogSetBlock(previewTarget.def, previewTarget.row, "preview-overlay")}
        </CatalogPreviewFullscreenOverlay>
      ) : null}
      <BisDimensionWizardModal
        open={Boolean(bisWizardSearch?.dataflowId)}
        onClose={() => setBisWizardSearch(null)}
        flowRef={bisWizardSearch?.dataflowId || ""}
        flowTitle={bisWizardSearch?.title}
      />
    </AppShell>
  );
}

