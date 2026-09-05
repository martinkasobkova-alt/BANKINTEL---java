import React, { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
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
  Sparkles,
} from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import CatalogBackToHubButton from "@/components/catalog/CatalogBackToHubButton";
import CatalogAiSearchPanel from "@/components/catalog/CatalogAiSearchPanel";
import CatalogChartPreview from "@/components/catalog/CatalogChartPreview";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import {
  addCatalogPreviewToPersonalDashboard,
  buildCatalogChartActionsProps,
} from "@/lib/catalogPageDashboard";
import {
  flattenCatalogCategoriesBestEffort,
  buildPathIndex,
  buildFilteredPaths,
  parseSearchKeywords,
  MAX_CATALOG_FILTER_ROWS,
  browseCategoryCountNode,
} from "@/lib/catalogTree";
import { LoadingBlock, LoadingInline, LoadingSpinner } from "@/components/ui/loading";
import { OECD_DATA_API_NOTE_CZ } from "@/lib/catalogDefinitions";
import { friendlyOecdDimLabel } from "@/lib/oecdDimLabels";

const OECD_CATALOG_DEF = { id: "oecd", sourceType: "oecd", label: "OECD" };

/** Jedna operační SLA pro OECD HTTP + nekonečné UI — shodný limit. */
const OECD_FETCH_TIMEOUT_MS = 20000;
const OECD_PROBE_TIMEOUT_MS = 65000;
const OECD_UI_DEADLINE_MS = 20020;
const OECD_FLATTEN_WARN_MS = 22000;

const OECD_PREVIEW_404_CZ =
  "Tento OECD dataflow nebyl nalezen nebo už není dostupný. Zkuste ověřený dataset KEI / CLI.";
const OECD_PREVIEW_TIMEOUT_CZ =
  "Požadavek na OECD překročil 20 sekund — zkuste to znovu nebo omezte dotaz.";

const OECD_429_MSG_CZ =
  "OECD dočasně omezilo počet požadavků (HTTP 429). Zkuste později nebo použijte tlačítko Obnovit — nebo použijte výše AI vyhledávání.";

const OECD_BROWSE_FAIL_MSG_CZ =
  "OECD katalog se nepodařilo načíst do 20 sekund. OECD API může omezovat počet požadavků nebo tento browse strom není dostupný. Zkuste AI hledání nebo ověřené datasety KEI/CLI níže.";

const OECD_SIMPLE_INTRO_CZ =
  "Vyberte tématický ověřený dataset KEI nebo CLI, načtěte dimenze ze serveru OECD a nastavte zemi či ukazatel v přehledných polích níže.";

const LEGACY_DROUGHT_ADV_NOTE =
  "Legacy OECD.ENV.EPI / DSD_ECH@EXT_DROUGHT — často vrací HTTP 404; jen experimentální použití.";

const OECD_COMMON_REF_AREA_CODES = [
  { id: "CZE", name: "Czechia" },
  { id: "SVK", name: "Slovak Republic" },
  { id: "DEU", name: "Germany" },
  { id: "POL", name: "Poland" },
  { id: "AUT", name: "Austria" },
  { id: "FRA", name: "France" },
  { id: "ITA", name: "Italy" },
  { id: "ESP", name: "Spain" },
  { id: "NLD", name: "Netherlands" },
  { id: "BEL", name: "Belgium" },
  { id: "SWE", name: "Sweden" },
  { id: "DNK", name: "Denmark" },
  { id: "FIN", name: "Finland" },
  { id: "NOR", name: "Norway" },
  { id: "GBR", name: "United Kingdom" },
  { id: "USA", name: "United States" },
  { id: "CAN", name: "Canada" },
  { id: "MEX", name: "Mexico" },
  { id: "JPN", name: "Japan" },
  { id: "KOR", name: "Korea" },
  { id: "CHN", name: "China" },
  { id: "IND", name: "India" },
  { id: "BRA", name: "Brazil" },
  { id: "AUS", name: "Australia" },
  { id: "NZL", name: "New Zealand" },
  { id: "EU27_2020", name: "European Union (27)" },
  { id: "OECD", name: "OECD" },
];

const OECD_COMMON_FREQ_CODES = [
  { id: "A", name: "Annual" },
  { id: "Q", name: "Quarterly" },
  { id: "M", name: "Monthly" },
];

function fallbackOecdCodes(dimensionId) {
  const key = String(dimensionId || "").trim().toUpperCase();
  if (!key) return [];
  if (key === "REF_AREA" || key === "COUNTRY" || key === "GEO" || key === "LOCATION") {
    return OECD_COMMON_REF_AREA_CODES;
  }
  if (key === "FREQ" || key.endsWith("_FREQ")) return OECD_COMMON_FREQ_CODES;
  return [];
}

const OECD_INDICATOR_DIM_IDS = new Set([
  "MEASURE",
  "INDICATOR",
  "SUBJECT",
  "VARIABLE",
]);

const OECD_SEGMENT_DIM_IDS = new Set([
  "ACTIVITY",
  "SECTOR",
  "INDUSTRY",
  "NACE_R2",
  "ECONOMIC_ACTIVITY",
]);

const OECD_TIME_DIM_IDS = new Set(["TIME_PERIOD", "TIME", "PERIOD"]);

function oecdDimId(dim) {
  return String(dim?.id || dim?.dimensionId || "").trim();
}

function oecdDimCodes(dim) {
  const did = oecdDimId(dim);
  const rawCodes = Array.isArray(dim?.codes) ? dim.codes : [];
  return rawCodes.length ? rawCodes : fallbackOecdCodes(did);
}

function codeExists(codes, code) {
  const wanted = String(code || "").trim().toUpperCase();
  return codes.some((c) => String(c?.id || "").trim().toUpperCase() === wanted);
}

function firstMatchingCode(codes, wanted) {
  for (const code of wanted) {
    if (codeExists(codes, code)) return code;
  }
  return "";
}

function defaultOecdDimValue(dim, contextTitle = "") {
  const did = oecdDimId(dim).toUpperCase();
  const codes = oecdDimCodes(dim);
  const title = String(contextTitle || "").toLowerCase();
  if (!did || !codes.length) return "";
  // Skryté technické dimenze (UNIT_MEASURE, SCALE, EDITION, CATEGORY, …) necháváme
  // wildcard — stejně jako OECD Data Explorer. Konkrétní hodnota z první řady by
  // s jiným ukazatelem nešla zkombinovat a vracela by "žádná data".
  if (!isManagerVisibleOecdDim(dim)) {
    return codes.length === 1 ? String(codes[0]?.id || "") : "";
  }
  if (did === "REF_AREA" || did === "COUNTRY" || did === "GEO" || did === "LOCATION") {
    return firstMatchingCode(codes, ["CZE", "OECD", "EU27_2020", "USA"]) || String(codes[0]?.id || "");
  }
  if (did === "FREQ" || did.endsWith("_FREQ")) {
    const highFrequency =
      title.includes("business tendency") ||
      title.includes("short-term") ||
      title.includes("production") ||
      title.includes("sales") ||
      title.includes("orders") ||
      title.includes("financial") ||
      title.includes("market") ||
      title.includes("leading") ||
      title.includes("cli") ||
      title.includes("survey");
    return firstMatchingCode(codes, highFrequency ? ["M", "Q", "A"] : ["A", "Q", "M"]) || String(codes[0]?.id || "");
  }
  if (OECD_INDICATOR_DIM_IDS.has(did)) {
    return String(codes[0]?.id || "");
  }
  if (did === "SECTOR") {
    if (title.includes("government") || title.includes("administration")) {
      const gov = codes.find((c) => /central government|government/i.test(String(c?.name || "")));
      if (gov?.id) return String(gov.id);
    }
    return firstMatchingCode(codes, ["_T", "TOTAL", "S1", "_Z"]) || "";
  }
  if (
    did === "UNIT_MEASURE" ||
    did === "UNIT" ||
    did === "MEASURE_UNIT" ||
    did === "STATISTICAL_OPERATION" ||
    did === "STAT_OPERATION" ||
    did === "AGG_METHOD"
  ) {
    return "";
  }
  if (codes.length === 1) return String(codes[0]?.id || "");
  return firstMatchingCode(codes, ["_T", "TOTAL", "TOT", "T", "ALL", "_Z", "N", "_N", "_X"]);
}

function isManagerVisibleOecdDim(dim) {
  const did = oecdDimId(dim).toUpperCase();
  if (!did) return false;
  return (
    did === "REF_AREA" ||
    did === "COUNTRY" ||
    did === "GEO" ||
    did === "LOCATION" ||
    did === "FREQ" ||
    did.endsWith("_FREQ") ||
    OECD_INDICATOR_DIM_IDS.has(did) ||
    OECD_SEGMENT_DIM_IDS.has(did)
  );
}

/**
 * Sirsi nez isManagerVisibleOecdDim - jen rika, ktere dimenze dostanou VIDITELNY vyber, ne
 * ktere se automaticky predvyplni hodnotou (o to se stara defaultOecdDimValue/probedDefaults,
 * beze zmeny). Zivy nalez: dimenze jako ADJUSTMENT/TRANSACTION/PRICE_BASE maji realne, ruzne
 * hodnoty, ale appka pro ne nemela zadny ovladaci prvek vubec - zustavaly navzdy jako wildcard
 * bez moznosti to rucne zmenit. Ted dostanou stejny <select>, jen defaultne prazdny (wildcard),
 * presne jako driv - zadna zmena v tom, co se posle, pokud uzivatel dropdown sam nezmeni.
 */
function isManagerPickableOecdDim(dim) {
  const did = oecdDimId(dim).toUpperCase();
  if (!did) return false;
  if (OECD_TIME_DIM_IDS.has(did)) return false;
  return true;
}

function topCategoryOpenPaths(categories) {
  return new Set((categories || []).map((c) => c.path).filter(Boolean));
}

/** Klíkové ekonomické řady KEI · CLI (seed). */
const OECD_PRESETS = [
  {
    id: "kei",
    label: "KEI — krátkodobé ekonomické ukazatele",
    techLabel: "OECD · KEI · DSD_KEI@DF_KEI",
    desc: "OECD Key Economic Indicators — ověřený základní dataflow.",
    agency: "OECD.SDD.STES",
    dataflow: "DSD_KEI@DF_KEI",
    version: "4.0",
  },
  {
    id: "cli",
    label: "CLI — složené předstihové indikátory",
    techLabel: "OECD · CLI · DSD_STES@DF_CLI",
    desc: "Composite Leading Indicators.",
    agency: "OECD.SDD.STES",
    dataflow: "DSD_STES@DF_CLI",
    version: "4.1",
  },
  {
    id: "indserv",
    label: "INDSERV — výroba, prodeje, zakázky",
    techLabel: "OECD · INDSERV · DSD_STES@DF_INDSERV",
    desc: "Production, sales, work started and orders — ověřený STES dataflow.",
    agency: "OECD.SDD.STES",
    dataflow: "DSD_STES@DF_INDSERV",
    version: "4.3",
  },
  {
    id: "finmark",
    label: "FINMARK — finanční trhy",
    techLabel: "OECD · FINMARK · DSD_STES@DF_FINMARK",
    desc: "Financial market indicators — ověřený STES dataflow.",
    agency: "OECD.SDD.STES",
    dataflow: "DSD_STES@DF_FINMARK",
    version: "4.0",
  },
];

/** @param {(e: unknown) => { status?: number }} [getErr] */
function describeBrowseFailure(e, url, qp, getErr = (x) => x?.response || {}) {
  const st = Number(getErr(e)?.status);
  let code = "";
  try {
    code = typeof e?.code === "string" ? e.code : "";
  } catch {
    code = "";
  }
  const msg = formatApiErrorFromAxios(e);
  return JSON.stringify({ endpoint: url, query: qp, axios_code: code || "(—)", http: st || "(—)", message: msg }, null, 0);
}

function buildOecdFilterExpression(advTrim, orderedIds, vals) {
  const adv = (advTrim || "").trim();
  if (adv) return adv;
  if (!orderedIds.length) return "";
  return orderedIds
    .filter((id) => !OECD_TIME_DIM_IDS.has(String(id || "").trim().toUpperCase()))
    .map((id) => ((vals[id] || "").trim() || "*"))
    .join(".");
}

export default function OecdCatalogPage() {
  const nav = useNavigate();
  const [searchParams] = useSearchParams();
  const initialSdAgency = String(searchParams.get("agency") || "OECD.SDD.STES").trim() || "OECD.SDD.STES";
  const initialSdFlow = String(searchParams.get("dataflow") || "DSD_KEI@DF_KEI").trim() || "DSD_KEI@DF_KEI";
  const initialSdVer = String(searchParams.get("version") || "4.0").trim() || "4.0";
  const initialPresetId = searchParams.get("agency") || searchParams.get("dataflow") ? "" : "kei";
  const selectedDataflowTitle = String(searchParams.get("name") || "").trim();
  const isDirectSdmxUrl = Boolean(
    String(searchParams.get("agency") || "").trim() &&
      String(searchParams.get("dataflow") || "").trim()
  );
  const [tree, setTree] = useState(null);
  const [seriesBundle, setSeriesBundle] = useState(null);
  const [seriesLoading, setSeriesLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [browseLoadFail, setBrowseLoadFail] = useState("");
  const [browseLoadTech, setBrowseLoadTech] = useState("");
  const [search, setSearch] = useState("");
  const [existing, setExisting] = useState(new Set());
  const [adding, setAdding] = useState({});
  const [openPaths, setOpenPaths] = useState(new Set());
  const [allRows, setAllRows] = useState([]);
  const [rowsIndexing, setRowsIndexing] = useState(false);
  const [browseFlattenWarn, setBrowseFlattenWarn] = useState(false);

  /** SDMX pokročilé */
  const [sdAgency, setSdAgency] = useState(initialSdAgency);
  const [sdFlow, setSdFlow] = useState(initialSdFlow);
  const [sdVer, setSdVer] = useState(initialSdVer);
  const [presetId, setPresetId] = useState(initialPresetId);

  const [orderedDimIds, setOrderedDimIds] = useState([]);
  const [dimsMeta, setDimsMeta] = useState([]);
  const [dimVals, setDimVals] = useState({});
  const [sdStart, setSdStart] = useState("2020");
  const [sdEnd, setSdEnd] = useState("2030");
  const [advFilter, setAdvFilter] = useState("");
  const [structLoading, setStructLoading] = useState(false);
  const [sdmxPreviewLoading, setSdmxPreviewLoading] = useState(false);
  const [sdmxPreview, setSdmxPreview] = useState(null);
  const [structError, setStructError] = useState(null);
  const [previewError, setPreviewError] = useState(null);
  const [addingToDash, setAddingToDash] = useState(false);
  const { isSubscriber } = useAuth();
  const { allowed: canPersonalDashboard, message: personalDashMsg } = useFeatureAccess("personal_dashboard");
  const { allowed: canSaveWidget, message: saveWidgetMsg } = useFeatureAccess("save_widget");
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

  const catalogLoadSeq = useRef(0);
  const urlPrefillKeyRef = useRef("");

  const applyOecdPreset = (id) => {
    const p = OECD_PRESETS.find((x) => x.id === id);
    if (!p) return;
    setPresetId(id);
    setSdAgency(p.agency);
    setSdFlow(p.dataflow);
    setSdVer(p.version);
    setAdvFilter("");
    setOrderedDimIds([]);
    setDimsMeta([]);
    setDimVals({});
    setSdmxPreview(null);
    setStructError(null);
    setPreviewError(null);
  };

  const previewHasData = useMemo(() => {
    if (!sdmxPreview) return false;
    const rows = Array.isArray(sdmxPreview.rows) ? sdmxPreview.rows : [];
    const tc =
      typeof sdmxPreview.total_count === "number" ? sdmxPreview.total_count : rows.length;
    return tc > 0 || rows.length > 0;
  }, [sdmxPreview]);

  const filtForAdd = useMemo(
    () => buildOecdFilterExpression(advFilter, orderedDimIds, dimVals),
    [advFilter, orderedDimIds, dimVals],
  );

  const canAddSdmxSource = Boolean(
    filtForAdd && previewHasData && !previewError && sdmxPreview && !sdmxPreviewLoading,
  );

  const sdmxPreviewRow = useMemo(() => {
    const agency = String(sdAgency || "").trim();
    const dataflow = String(sdFlow || "").trim();
    const version = String(sdVer || "+").trim() || "+";
    const filt = filtForAdd;
    if (!agency || !dataflow || !filt) return null;
    return {
      set_id: `SDMX2|${agency}|${dataflow}|${version}|${filt}`,
      name: `OECD ${agency}/${dataflow}`,
    };
  }, [sdAgency, sdFlow, sdVer, filtForAdd]);

  const handleAddSdmxPreviewToDashboard = async ({ setPagePick } = {}) => {
    if (!sdmxPreview || !sdmxPreviewRow || previewError) return;
    setAddingToDash(true);
    try {
      await addCatalogPreviewToPersonalDashboard({
        api,
        nav,
        def: OECD_CATALOG_DEF,
        previewData: sdmxPreview,
        row: sdmxPreviewRow,
        feature: dashboardFeature,
        setPagePick,
      });
    } finally {
      setAddingToDash(false);
    }
  };

  const composeFilterExprState = () => buildOecdFilterExpression(advFilter, orderedDimIds, dimVals);

  const loadCatalog = async (force = false) => {
    const seq = ++catalogLoadSeq.current;
    setBrowseLoadFail("");
    setBrowseLoadTech("");
    if (force) setRefreshing(true);
    else setLoading(true);

    const url = force ? "/oecd/catalog/refresh" : "/oecd/catalog";
    const watchdog = window.setTimeout(() => {
      if (seq !== catalogLoadSeq.current) return;
      setLoading(false);
      setRefreshing(false);
      setTree(null);
      setBrowseLoadFail(OECD_BROWSE_FAIL_MSG_CZ);
      setBrowseLoadTech(
        JSON.stringify(
          {
            reason: "ui_deadline",
            deadline_ms: OECD_UI_DEADLINE_MS,
            endpoint: url,
          },
          null,
          2
        )
      );
    }, OECD_UI_DEADLINE_MS);

    try {
      const resp = force
        ? await api.post(url, {}, { timeout: OECD_FETCH_TIMEOUT_MS })
        : await api.get(url, { timeout: OECD_FETCH_TIMEOUT_MS });
      if (seq !== catalogLoadSeq.current) return;

      window.clearTimeout(watchdog);
      setBrowseLoadFail("");
      setBrowseLoadTech("");
      setTree(resp.data);
      setSeriesBundle(null);
      setOpenPaths(topCategoryOpenPaths(resp.data.categories));

      const { data: srcs } = await api.get("/sources/catalog-stubs", { timeout: OECD_FETCH_TIMEOUT_MS });
      if (seq !== catalogLoadSeq.current) return;
      setExisting(
        new Set(
          (srcs || [])
            .filter((s) => s.source_type === "oecd")
            .map((s) => {
              if (s.oecd_catalog_set_id) return String(s.oecd_catalog_set_id);
              const ds = s.oecd_dataset || "";
              const fl = s.oecd_filter || "";
              return ds && fl ? `${ds}/${fl}` : "";
            })
            .filter(Boolean)
        )
      );
    } catch (e) {
      window.clearTimeout(watchdog);
      if (seq !== catalogLoadSeq.current) return;

      const st = e?.response?.status;
      const tm = e?.code === "ECONNABORTED";
      let userMsg = OECD_BROWSE_FAIL_MSG_CZ;
      if (st === 429) userMsg = OECD_429_MSG_CZ;
      else if (tm) userMsg = OECD_BROWSE_FAIL_MSG_CZ;

      setTree(null);
      setBrowseLoadFail(userMsg);
      setBrowseLoadTech(describeBrowseFailure(e, url, force ? "(POST refresh)" : "(GET catalog)"));
      toast.error(userMsg);
    } finally {
      window.clearTimeout(watchdog);
      if (seq === catalogLoadSeq.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  };

  const loadSeries = async (datasetId) => {
    const id = String(datasetId || "").trim();
    if (!id) return;
    setSeriesLoading(true);
    try {
      const { data } = await api.get("/oecd/catalog/series", {
        params: { dataset: id },
        timeout: OECD_FETCH_TIMEOUT_MS,
      });
      setSeriesBundle({ datasetId: id, tree: data });
      setOpenPaths(topCategoryOpenPaths(data.categories));

      const { data: srcs } = await api.get("/sources/catalog-stubs");
      setExisting(
        new Set(
          (srcs || [])
            .filter((s) => s.source_type === "oecd")
            .map((s) => {
              if (s.oecd_catalog_set_id) return String(s.oecd_catalog_set_id);
              const ds = s.oecd_dataset || "";
              const fl = s.oecd_filter || "";
              return ds && fl ? `${ds}/${fl}` : "";
            })
            .filter(Boolean)
        )
      );
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setSeriesLoading(false);
  };

  useEffect(() => {
    if (isDirectSdmxUrl) {
      setLoading(false);
      setRefreshing(false);
      setTree(null);
      setBrowseLoadFail("");
      setBrowseLoadTech("");
      return () => {
        catalogLoadSeq.current += 1;
      };
    }
    loadCatalog(false);
    return () => {
      catalogLoadSeq.current += 1;
    };
  }, [isDirectSdmxUrl]);

  const activeTree = seriesBundle?.tree || tree;

  useEffect(() => {
    let cancelled = false;
    const cats = activeTree?.categories;
    let slowTimer = null;
    if (!cats?.length) {
      setAllRows([]);
      setRowsIndexing(false);
      setBrowseFlattenWarn(false);
      return undefined;
    }
    setRowsIndexing(true);
    setBrowseFlattenWarn(false);

    slowTimer = window.setTimeout(() => {
      if (!cancelled) {
        setBrowseFlattenWarn(true);
      }
    }, OECD_FLATTEN_WARN_MS);

    flattenCatalogCategoriesBestEffort(cats).then((rows) => {
      if (slowTimer) window.clearTimeout(slowTimer);
      if (!cancelled) {
        setAllRows(rows);
        setRowsIndexing(false);
        setBrowseFlattenWarn(false);
      }
    });

    return () => {
      cancelled = true;
      if (slowTimer) window.clearTimeout(slowTimer);
    };
  }, [activeTree]);

  const loadOecdProbe = async (override = {}) => {
    const a = String(override.agency ?? sdAgency).trim();
    const f = String(override.dataflow ?? sdFlow).trim();
    const version = String(override.version ?? sdVer ?? "+").trim() || "+";
    const title = String(override.name ?? selectedDataflowTitle ?? f).trim();
    if (!a || !f) {
      toast.error("Vyberte dataset výše.");
      return;
    }
    setStructLoading(true);
    setStructError(null);
    setOrderedDimIds([]);
    setDimsMeta([]);
    setDimVals({});
    setSdmxPreview(null);
    setPreviewError(null);
    try {
      const { data } = await api.get(
        `/oecd3/dataflow/${encodeURIComponent(a)}/${encodeURIComponent(f)}/probe`,
        {
          params: { version, ref_area: "CZE", name: title.slice(0, 160) },
          timeout: OECD_PROBE_TIMEOUT_MS,
        }
      );
      const ord = data.dimensions_ordered_ids || [];
      if (!ord.length) {
        setStructError("OECD probe nevrátil dimenze — zkuste to znovu nebo použijte AI hledání.");
        return;
      }
      if (data.probe_state === "empty") {
        toast.message("OECD pro tento dataset nevrátila řady — načítám strukturu DSD jako fallback.");
        await loadSdmxStructure({ ...override, agency: a, dataflow: f, version, autoPreview: false });
        return;
      }
      const rateLimited = data.probe_state === "rate_limited";
      if (rateLimited) {
        toast.message(
          "OECD má teď rate limit — zobrazuji dimenze ze struktury. Náhled dat zkuste za chvíli."
        );
      }
      const loadedDims = Array.isArray(data.dimensions) ? data.dimensions : [];
      const probedDefaults = data.default_dimension_values || {};
      const defaults = {};
      loadedDims.forEach((dim) => {
        const did = oecdDimId(dim);
        if (!did) return;
        // Probe default bereme jen pro viditelné dimenze (země, ukazatel, frekvence).
        // Skryté technické dimenze necháváme wildcard, ať jdou ukazatele libovolně
        // kombinovat a vždy se vrátí data (jako v OECD Data Exploreru).
        const visible = isManagerVisibleOecdDim(dim);
        defaults[did] = (visible ? probedDefaults[did] : "") || defaultOecdDimValue(dim, title);
        const codes = oecdDimCodes(dim);
        if (!defaults[did] && codes.length === 1) {
          defaults[did] = String(codes[0]?.id || "");
        }
      });
      setOrderedDimIds(ord);
      setDimsMeta(loadedDims);
      setDimVals(() => {
        const n = { ...defaults };
        ord.forEach((id0) => {
          if (n[id0] === undefined) n[id0] = "";
        });
        return n;
      });
      const src = String(data.probe_source || "probe");
      const srcLabel =
        src === "verified_index"
          ? "ověřený index"
          : src === "data_query"
          ? "datový dotaz OECD"
          : "live API";
      const count = Number(data.series_count) || 0;
      if (!rateLimited) {
        toast.success(`OECD probe: ${count} řad · dimenze jen s daty (${srcLabel}).`);
      }
      if (override.autoPreview && !rateLimited) {
        const filt = String(data.default_filter_expression || "").trim();
        if (filt) {
          await runSdmxPreview({
            agency: a,
            dataflow: f,
            version,
            filterExpression: filt,
          });
        } else {
          await runSdmxPreview({
            agency: a,
            dataflow: f,
            version,
            orderedDimIds: ord,
            dimVals: defaults,
          });
        }
      }
    } catch (e) {
      const msg =
        e?.code === "ECONNABORTED" || String(e?.message || "").includes("timeout")
          ? "OECD probe překročil časový limit — zkuste to znovu za chvíli."
          : formatApiErrorFromAxios(e);
      setStructError(msg);
      toast.error(msg);
    } finally {
      setStructLoading(false);
    }
  };

  const loadSdmxStructure = async (override = {}) => {
    const a = String(override.agency ?? sdAgency).trim();
    const f = String(override.dataflow ?? sdFlow).trim();
    const version = String(override.version ?? sdVer ?? "+").trim() || "+";
    if (!a || !f) {
      toast.error("Vyberte dataset výše.");
      return;
    }
    setStructLoading(true);
    setStructError(null);
    setOrderedDimIds([]);
    setDimsMeta([]);
    setDimVals({});
    setSdmxPreview(null);
    setPreviewError(null);
    try {
      const { data } = await api.get(
        `/oecd/catalog/dataflow/${encodeURIComponent(a)}/${encodeURIComponent(f)}/structure`,
        { params: { version }, timeout: OECD_FETCH_TIMEOUT_MS }
      );
      const ord = data.dimensions_ordered_ids || [];
      if (!ord.length) {
        const emptyMsg = "Nejdřív načtěte dimenze.";
        setStructError(emptyMsg);
        toast.error(emptyMsg);
        return;
      }
      const loadedDims = Array.isArray(data.dimensions) ? data.dimensions : [];
      const defaults = {};
      loadedDims.forEach((dim) => {
        const did = oecdDimId(dim);
        if (!did) return;
        defaults[did] = defaultOecdDimValue(dim, selectedDataflowTitle);
        const codes = oecdDimCodes(dim);
        if (!defaults[did] && codes.length === 1) {
          defaults[did] = String(codes[0]?.id || "");
        }
      });
      setOrderedDimIds(ord);
      setDimsMeta(loadedDims);
      setDimVals(() => {
        const n = { ...defaults };
        ord.forEach((id0) => {
          if (n[id0] === undefined) n[id0] = "";
        });
        return n;
      });
      toast.success("OECD výběr připraven: ukazatel, země a frekvence jsou nahoře; technické filtry doplňuje aplikace.");
      if (override.autoPreview) {
        await runSdmxPreview({
          agency: a,
          dataflow: f,
          version,
          orderedDimIds: ord,
          dimVals: defaults,
        });
      }
    } catch (e) {
      const st = e?.response?.status;
      const msg =
        st === 404
          ? OECD_PREVIEW_404_CZ
          : e?.code === "ECONNABORTED" || String(e?.message || "").includes("timeout")
            ? OECD_PREVIEW_TIMEOUT_CZ
            : formatApiErrorFromAxios(e);
      setStructError(msg);
      toast.error(msg);
    } finally {
      setStructLoading(false);
    }
  };

  useEffect(() => {
    const a = String(searchParams.get("agency") || "").trim();
    const f = String(searchParams.get("dataflow") || "").trim();
    if (!a || !f) return;
    const v = String(searchParams.get("version") || "+").trim() || "+";
    const useProbe = searchParams.get("probe") === "1";
    const key = `${a}|${f}|${v}|${String(searchParams.get("autoload") || "")}|${useProbe ? "probe" : ""}`;
    if (urlPrefillKeyRef.current === key) return;
    urlPrefillKeyRef.current = key;
    setSdAgency(a);
    setSdFlow(f);
    setSdVer(v);
    setPresetId("");
    setAdvFilter("");
    setSdmxPreview(null);
    setStructError(null);
    setPreviewError(null);
    if (searchParams.get("autoload") === "1" || useProbe) {
      if (useProbe) {
        void loadOecdProbe({ agency: a, dataflow: f, version: v, autoPreview: true });
      } else {
        void loadSdmxStructure({ agency: a, dataflow: f, version: v, autoPreview: true });
      }
    }
  }, [searchParams]);

  const runSdmxPreview = async (override = {}) => {
    const activeOrderedDimIds = override.orderedDimIds || orderedDimIds;
    const activeDimVals = override.dimVals || dimVals;
    const filt =
      override.filterExpression ||
      buildOecdFilterExpression(advFilter, activeOrderedDimIds, activeDimVals);
    if (!filt) {
      toast.error(
        activeOrderedDimIds.length === 0
          ? 'Nejdřív načtěte dimenze (tlačítko „Načíst dimenze z OECD“) nebo zadejte filtr pod „Pokročilé“.'
          : "Vyplňte hodnoty nebo použijte * / prázdné pole jako wildcard podle nápovědy."
      );
      return;
    }
    const agency = String(override.agency ?? sdAgency).trim();
    const dataflow = String(override.dataflow ?? sdFlow).trim();
    const version = String(override.version ?? sdVer ?? "+").trim() || "+";
    const setId = `SDMX2|${agency}|${dataflow}|${version}|${filt}`;
    const timeClause =
      sdStart && sdEnd ? `ge:${String(sdStart).trim()}+le:${String(sdEnd).trim()}` : undefined;
    setSdmxPreviewLoading(true);
    setPreviewError(null);
    setSdmxPreview(null);
    try {
      const qp = {
        attributes: "dsd",
        measures: "all",
        dimensionAtObservation: "AllDimensions",
      };
      if (timeClause) qp["c[TIME_PERIOD]"] = timeClause;
      const { data } = await api.post(
        "/catalog/preview",
        {
          source_type: "oecd",
          set_id: setId,
          name: "OECD SDMX v2",
          query_params: qp,
        },
        { timeout: OECD_FETCH_TIMEOUT_MS }
      );
      setSdmxPreview(data);
      const rows = Array.isArray(data?.rows) ? data.rows : [];
      const tc = typeof data?.total_count === "number" ? data.total_count : rows.length;
      if (tc === 0 && rows.length === 0) {
        // Konkrétní důvod prázdného výsledku, ne obecné „žádná data".
        const state = String(data?.preview_state || data?.sync_state || "").toLowerCase();
        const notice = String(data?.oecd_preview_notice || data?.message || "").trim();
        let emptyMsg;
        if (state === "rate_limited") {
          emptyMsg =
            notice ||
            "OECD právě omezuje počet dotazů (HTTP 429) — data se teď nestáhla. Zkuste to prosím za chvíli znovu (ne jiný výběr).";
        } else if (state === "no_data") {
          emptyMsg =
            "Tato konkrétní kombinace (země · ukazatel · frekvence) v OECD neobsahuje žádná data. " +
            "Zkuste jinou zemi (např. OECD agregát), jiný ukazatel nebo jinou frekvenci.";
        } else {
          emptyMsg =
            notice ||
            "OECD pro tuto kombinaci nevrátila žádná data — zkuste užší výběr nebo použijte AI vyhledávání výše.";
        }
        setPreviewError(emptyMsg);
      }
    } catch (e) {
      const st = e?.response?.status;
      const msg =
        st === 404
          ? OECD_PREVIEW_404_CZ
          : e?.code === "ECONNABORTED" || String(e?.message || "").includes("timeout")
            ? OECD_PREVIEW_TIMEOUT_CZ
            : formatApiErrorFromAxios(e);
      setPreviewError(msg);
      toast.error(msg);
      setSdmxPreview(null);
    } finally {
      setSdmxPreviewLoading(false);
    }
  };

  const addSdmxSource = async () => {
    const filt = composeFilterExprState();
    if (!filt) {
      toast.error("Nejdřív sestavte řadu.");
      return;
    }
    if (!canAddSdmxSource) {
      toast.error("Nejdřív ověřte náhled dat.");
      return;
    }
    setAdding((prev) => ({ ...prev, __sdmx__: true }));
    try {
      const timeClause =
        sdStart && sdEnd ? `ge:${String(sdStart).trim()}+le:${String(sdEnd).trim()}` : undefined;
      const body = {
        agency: sdAgency.trim(),
        dataflow: sdFlow.trim(),
        version: (sdVer || "+").trim(),
        startPeriod: sdStart,
        endPeriod: sdEnd,
        query_params: timeClause ? { "c[TIME_PERIOD]": timeClause } : {},
      };
      if (advFilter.trim()) body.filterExpression = advFilter.trim();
      else {
        body.ordered_dimensions = orderedDimIds;
        body.dimension_values = dimVals;
      }
      const { data } = await api.post("/oecd/catalog/add-source", body);
      toast.success(`Přidáno: ${data.name}`);
      setExisting((s) => new Set([...s, String(data.set_id)]));
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setAdding((prev) => ({ ...prev, __sdmx__: false }));
  };

  const rowIndex = useMemo(() => buildPathIndex(allRows), [allRows]);
  const keywords = useMemo(() => parseSearchKeywords(search), [search]);
  const filteredPaths = useMemo(
    () => buildFilteredPaths(allRows, rowIndex, keywords),
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
      let parentSegments =
        r.kind === "set" ? r.parentPath.split(" > ") : r.path.split(" > ").slice(0, -1);
      let allOpen = true;
      const acc = [];
      for (const seg of parentSegments) {
        acc.push(seg);
        if (!openPaths.has(acc.join(" > "))) {
          allOpen = false;
          break;
        }
      }
      if (allOpen) result.push(r);
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

  const addSource = async (set_id) => {
    setAdding((a) => ({ ...a, [set_id]: true }));
    try {
      const { data } = await api.post("/oecd/catalog/add-source", { set_id }, { timeout: OECD_FETCH_TIMEOUT_MS });
      toast.success(`Přidáno: ${data.name}`);
      setExisting((s) => new Set([...s, String(set_id)]));
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setAdding((a) => ({ ...a, [set_id]: false }));
  };

  const subtitle = seriesBundle
    ? `Dataset ${seriesBundle.datasetId} — ${(
        seriesBundle.tree?.total_sets ?? 0
      ).toLocaleString("cs-CZ")} řad`
    : `${tree?.total_sets?.toLocaleString("cs-CZ") || "—"} OECD datových sad (abecedně) · stats.oecd.org`;

  /** Heuristika filtru stromu = demografie / population. */
  const demographicFilterGuess = keywords.some((k) =>
    ["population", "populace", "obyvat", "demograf"].includes(k),
  );

  return (
    <AppShell
      title="Katalog OECD Data API"
      subtitle={subtitle}
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <a
            href="https://stats.oecd.org/"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
            title="OECD stats"
          >
            <ExternalLink className="h-4 w-4" /> stats.oecd.org
          </a>
          <button
            type="button"
            onClick={() => nav("/sources")}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
          >
            <ArrowLeft className="h-4 w-4" /> Zpět na zdroje
          </button>
          <CatalogBackToHubButton catalogId="oecd" />
          {seriesBundle ? (
            <button
              type="button"
              onClick={() => {
                setSeriesBundle(null);
                if (tree) setOpenPaths(topCategoryOpenPaths(tree.categories));
              }}
              className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
            >
              <Database className="h-4 w-4" /> Zpět na seznam datasetů
            </button>
          ) : null}
          <button
            type="button"
            onClick={() =>
              isDirectSdmxUrl
                ? loadSdmxStructure({ agency: sdAgency, dataflow: sdFlow, version: sdVer })
                : loadCatalog(true)
            }
            disabled={isDirectSdmxUrl ? structLoading : refreshing}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))] disabled:opacity-50"
          >
            <RefreshCw className={`h-4 w-4 ${refreshing || structLoading ? "animate-spin" : ""}`} />
            {isDirectSdmxUrl ? (structLoading ? "Nacitam..." : "Obnovit volby") : refreshing ? "Stahuji..." : "Obnovit"}
          </button>
        </div>
      }
    >
      <CatalogAiSearchPanel
        catalogId="oecd"
        className="mb-6 max-w-5xl"
        headline="Najít OECD data pomocí AI"
        description="Stejné API jako globální katalog: POST /catalog/deep-search. Pro témata jako population použijte hledání napříč zdroji; níže uvedený browse strom OECD může být pomalý nebo přerušený (429)."
        inputPlaceholder="Např. population, GDP, inflation, unemployment, CLI, business confidence…"
        crossSourceHelpText="Zaškrtnutím hledáte přes index více institucí; bez zaškrtnutí zůstává kontext „OECD“, ale řády mohou přijít z Eurostat / World Bank výše ve výsledcích."
      />

      {/* Jednoduchý průvodce */}
      <div className="mb-6 rounded-2xl border border-border bg-card p-5 shadow-sm max-w-5xl space-y-5">
        <div className="flex items-start gap-2">
          <Sparkles className="h-5 w-5 text-teal-600 shrink-0 mt-0.5" />
          <div>
            <div className="text-sm font-semibold text-foreground">Jednoduchý výběr — ověřené datasety OECD</div>
            <p className="text-sm text-foreground/90 leading-relaxed max-w-prose mt-1">{OECD_SIMPLE_INTRO_CZ}</p>
          </div>
        </div>

        <div className="space-y-3">
          <label className="block text-xs font-medium text-foreground/90">
            Dataset
            <select
              className="mt-1 w-full max-w-lg h-10 px-3 border border-border rounded-xl text-sm bg-card"
              value={presetId}
              onChange={(e) => applyOecdPreset(e.target.value)}
            >
              {OECD_PRESETS.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                </option>
              ))}
            </select>
          </label>
          <p className="text-[11px] text-muted-foreground leading-snug">
            Technický dataset (dataflow / DSD):{" "}
            <span className="font-mono text-foreground/90">
              {sdAgency} / {sdFlow} · verze {(sdVer || "+").trim()}
            </span>
          </p>
        </div>

        <div className="flex flex-wrap gap-3 items-end">
          <div>
            <label className="block text-[11px] text-muted-foreground mb-1">Období od</label>
            <input
              className="h-9 w-[7.5rem] px-2 border rounded-lg text-sm font-mono"
              value={sdStart}
              onChange={(e) => setSdStart(e.target.value)}
              placeholder="např. 2020"
            />
          </div>
          <div>
            <label className="block text-[11px] text-muted-foreground mb-1">Období do</label>
            <input
              className="h-9 w-[7.5rem] px-2 border rounded-lg text-sm font-mono"
              value={sdEnd}
              onChange={(e) => setSdEnd(e.target.value)}
              placeholder="např. 2030"
            />
          </div>
        </div>

        <p className="text-[11px] text-muted-foreground leading-snug rounded-lg border border-border/70 bg-muted/35 px-3 py-2">
          OECD se otevre jako hotovy vyber ukazatele: zvolte ukazatel, zemi a frekvenci; ostatni technicke rezy se nastavi automaticky.
        </p>

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => loadSdmxStructure()}
            disabled={structLoading}
            className="btn-mint px-3 h-9 text-xs disabled:opacity-50"
          >
            {structLoading ? (
              <span className="inline-flex items-center gap-2">
                <LoadingSpinner suppressAria size="xs" /> Nacitam volby...
              </span>
            ) : (
              "Obnovit volby OECD"
            )}
          </button>
          <button
            type="button"
            onClick={() => runSdmxPreview()}
            disabled={sdmxPreviewLoading}
            className="px-3 h-9 text-xs border rounded-xl bg-muted/25 hover:bg-muted/60 disabled:opacity-50 inline-flex items-center gap-2"
          >
            {sdmxPreviewLoading ? (
              <>
                <LoadingSpinner suppressAria size="xs" /> Náhled dat…
              </>
            ) : (
              "Náhled dat"
            )}
          </button>
          <button
            type="button"
            onClick={() => addSdmxSource()}
            disabled={Boolean(adding.__sdmx__) || !canAddSdmxSource}
            title={
              canAddSdmxSource
                ? "Přidat tento výběr jako zdroj dat"
                : "Nejdřív ověřte náhled dat."
            }
            className="btn-mint px-3 h-9 text-xs disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {adding.__sdmx__ ? "…" : "Přidat jako zdroj"}
          </button>
        </div>

        {structLoading ? (
          <div className="text-xs text-muted-foreground rounded-lg border border-sky-100 canvas-dark:border-sky-800/40 bg-sky-50/80 canvas-dark:bg-sky-950/35 px-3 py-2">
            OECD probe mapuje skutečné dimenze datasetu (max. {OECD_PROBE_TIMEOUT_MS / 1000}s)…
          </div>
        ) : null}

        {structError ? (
          <div className="rounded-lg border border-rose-200 bg-rose-50/90 px-3 py-2.5 text-xs text-rose-950 canvas-dark:text-rose-100 leading-relaxed">
            {structError}
          </div>
        ) : null}

        {previewError ? (
          <div className="rounded-lg border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 px-3 py-2.5 text-xs text-amber-950 canvas-dark:text-amber-50 leading-relaxed">
            {previewError}
          </div>
        ) : null}

        {!structLoading &&
        !structError &&
        orderedDimIds.length === 0 &&
        !advFilter.trim() &&
        dimsMeta.length === 0 ? (
          <div className="rounded-xl border border-dashed border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 px-3 py-3 text-sm text-amber-950 canvas-dark:text-amber-50">
            Ještě nejsou načtené dimenze řady OECD. Použijte tlačítko výše („Načíst dimenze…“).
          </div>
        ) : null}

        {!structLoading &&
        orderedDimIds.length === 0 &&
        advFilter.trim() === "" &&
        dimsMeta.some((dm) =>
          !(Array.isArray(dm.codes) && dm.codes.length) && (dm?.id || dm?.dimensionId)
        ) &&
        dimsMeta.length > 0 ? (
          <div className="rounded-xl border border-border bg-muted/25 px-3 py-2.5 text-sm text-foreground">
            Tento dataset zatím nemá dostupný jednoduchý výběr hodnot ze serveru OECD — zkuste použít AI výše nebo omezte
            dotaz.
          </div>
        ) : null}

        {!advFilter.trim() && orderedDimIds.length ? (
          <div className="space-y-2 pt-2">
            <div className="text-xs font-medium text-foreground">
              Vyberte ukazatel, zemi a frekvenci
              <span className="font-normal text-muted-foreground"> (ostatni technicke dimenze doplni aplikace)</span>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1">
              {dimsMeta.filter(isManagerPickableOecdDim).map((dim) => {
                const did = dim.id || dim.dimensionId;
                if (!did || !orderedDimIds.includes(String(did))) return null;
                const rawCodes = Array.isArray(dim.codes) ? dim.codes : [];
                const fallbackCodes = rawCodes.length ? [] : fallbackOecdCodes(did);
                const codes = rawCodes.length ? rawCodes : fallbackCodes;
                const usingFallbackCodes = !rawCodes.length && fallbackCodes.length > 0;
                const label = friendlyOecdDimLabel(did, dim.name);
                const hasVals = codes.length > 0;
                return (
                  <div key={String(did)} className="block text-xs space-y-1 rounded-xl border border-border/70 bg-muted/32 p-2.5">
                    <span className="block text-foreground font-semibold">{label}</span>
                    {hasVals ? (
                      <>
                        <select
                          className="w-full h-9 px-2 border rounded-lg text-[13px] bg-card"
                          value={dimVals[String(did)] ?? ""}
                          onChange={(e) =>
                            setDimVals((prev) => ({ ...prev, [String(did)]: e.target.value }))
                          }
                        >
                          {codes.slice(0, 600).map((c) => {
                            const cid = String(c?.id ?? "");
                            const cname = String(c?.name ?? c?.id ?? "");
                            return (
                              <option key={cid} value={cid}>
                                {cname && cname !== cid ? `${cname} (${cid})` : cid}
                              </option>
                            );
                          })}
                        </select>
                        {usingFallbackCodes ? (
                          <div className="text-[10px] text-muted-foreground">
                            OECD nevratilo ciselnik v metadatech; nabizim nejcastejsi kody, vlastni kod muzete napsat rucne.
                          </div>
                        ) : null}
                      </>
                    ) : (
                      <div className="text-[11px] text-muted-foreground px-2 py-1.5 border border-dashed rounded-lg bg-card/90">
                        Pro tuto dimenzi OECD nevrátilo hodnoty v jednoduchém výběru — použijte AI vyhledávání výše nebo
                        vlastní známý kód (v extrémním případě <span className="font-mono">*</span>).
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        ) : null}

        {advFilter.trim() ? (
          <div className="text-[11px] text-foreground/90 rounded-lg border border-border/70 bg-muted/30 px-3 py-2">
            Aktivní je ruční <span className="font-mono">filterExpression</span> z pokročilé sekce — mřížka dimenzí se
            pro skládání klíče ignoruje.
          </div>
        ) : null}

        <details className="rounded-xl border border-border overflow-hidden">
          <summary className="cursor-pointer px-3 py-2.5 text-sm font-semibold bg-muted/40 border-b border-border text-foreground list-none flex justify-between gap-2 [&::-webkit-details-marker]:hidden">
            Pokročilé: ruční SDMX výběr (agency · dataflow · verze · textový klíč)
            <ChevronDown className="h-4 w-4 shrink-0 opacity-70" aria-hidden />
          </summary>
          <div className="px-3 pb-4 pt-2 space-y-3 bg-card">
            <p className="text-[11px] text-muted-foreground">{OECD_DATA_API_NOTE_CZ}</p>
            <div className="grid grid-cols-1 md:grid-cols-4 gap-2">
              <input
                className="h-9 px-3 border rounded-lg text-sm font-mono"
                placeholder="agency ID"
                value={sdAgency}
                onChange={(e) => {
                  setSdAgency(e.target.value);
                  setPresetId("");
                }}
              />
              <input
                className="h-9 px-3 border rounded-lg text-sm font-mono md:col-span-2"
                placeholder="dataflow (DSD…@DF…)"
                value={sdFlow}
                onChange={(e) => {
                  setSdFlow(e.target.value);
                  setPresetId("");
                }}
              />
              <input
                className="h-9 px-3 border rounded-lg text-sm font-mono"
                placeholder="+ nebo číslo verze"
                value={sdVer}
                onChange={(e) => {
                  setSdVer(e.target.value);
                  setPresetId("");
                }}
              />
            </div>
            <div>
              <label className="text-[11px] text-muted-foreground mb-1 block">Textový filtr dimenzí (SDMX řetězec)</label>
              <textarea
                className="w-full min-h-[72px] px-3 py-2 border rounded-xl text-xs font-mono"
                placeholder="Jen pokud rozumíte přesnému SDMX klíči řady."
                value={advFilter}
                onChange={(e) => setAdvFilter(e.target.value)}
              />
            </div>
            <p className="text-[11px] text-amber-900 bg-amber-50 canvas-dark:bg-amber-950/35 border border-amber-200 canvas-dark:border-amber-600/45 rounded-lg px-2.5 py-2">
              {LEGACY_DROUGHT_ADV_NOTE}
            </p>
          </div>
        </details>

        {sdmxPreview && !previewError ? (
          <div className="rounded-lg bg-emerald-50/80 border border-emerald-100 px-3 py-2 text-[11px] text-emerald-950 space-y-1">
            Náhled: {sdmxPreview.total_count ?? (sdmxPreview.rows || []).length} záznamů (viz tabulku v globálním náhledu).
            {sdmxPreview.oecd_preview_notice ? (
              <div className="text-amber-900">{sdmxPreview.oecd_preview_notice}</div>
            ) : null}
          </div>
        ) : null}
        {(sdmxPreview || sdmxPreviewLoading) && !previewError ? (
          <div className="rounded-xl border border-border bg-card overflow-hidden p-3">
            <CatalogChartPreview
              widgetId={`oecd-sdmx-preview-${String(sdmxPreviewRow?.set_id || "row").slice(0, 48)}`}
              title={sdmxPreviewRow?.name || "OECD SDMX"}
              sourceType="oecd"
              catalogDef={OECD_CATALOG_DEF}
              catalogRow={sdmxPreviewRow || { set_id: "", name: "OECD SDMX" }}
              preview={
                sdmxPreview
                  ? {
                      ...sdmxPreview,
                      source: { name: sdmxPreviewRow?.name || "OECD SDMX", source_type: "oecd" },
                    }
                  : { source: { name: "OECD SDMX", source_type: "oecd" } }
              }
              previewLoading={sdmxPreviewLoading && !sdmxPreview}
              sourcePreviewProps={{
                onClose: () => setSdmxPreview(null),
                catalogChartActions: buildCatalogChartActionsProps({
                  feature: dashboardFeature,
                  previewData: sdmxPreview,
                  previewError,
                  previewLoading: sdmxPreviewLoading,
                  onAddToDashboard: handleAddSdmxPreviewToDashboard,
                  addingToDashboard: addingToDash,
                }),
              }}
            />
          </div>
        ) : null}
      </div>

      {!isDirectSdmxUrl ? (
        <>
      {/* Strom OECD — nízká priorita */}
      <div className="mb-3 rounded-xl border border-border bg-muted/30 px-3 py-2 text-xs text-foreground/90 leading-relaxed space-y-1">
        <strong className="text-foreground">Browse strom OECD</strong> složeny pod prvním písmenem — u velkých dotazů
        („population„) OECD často <em>nemá výsledek v tomto stromu</em>; použijte AI box.
        {(tree?.oecd_fetch_mode === "bulk+per_agency" || tree?.oecd_fetch_mode === "per_agency") &&
        (tree?.total_sets ?? 0) > 0 &&
        (tree?.total_sets ?? 0) < 80 ? (
          <span className="block text-amber-900">
            OECD často vrací HTTP 429 při složité agregaci stromu — zkuste později Obnovit.
          </span>
        ) : null}
      </div>

      <div className="mb-4 max-w-xl relative">
        <Search className="h-4 w-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
        <input
          type="text"
          className="w-full h-10 pl-9 pr-3 border border-[hsl(var(--border)/0.75)] rounded-xl text-sm bg-card shadow-sm"
          placeholder="Filtrovat již nahraný strom OECD (nezahajuje síťové hledání)…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          disabled={loading || browseLoadFail}
          data-testid="oecd-catalog-search"
        />
      </div>

      {browseLoadFail ? (
        <div className="rounded-2xl border border-rose-200 bg-rose-50/95 px-4 py-4 text-sm space-y-2 max-w-4xl mb-6">
          <div className="font-medium text-rose-950 canvas-dark:text-rose-100">{browseLoadFail}</div>
          {browseLoadTech ? (
            <details className="text-[11px] text-rose-900">
              <summary className="cursor-pointer font-medium">Technický detail</summary>
              <pre className="mt-2 whitespace-pre-wrap break-all font-mono bg-card/80 p-2 rounded-lg border">{browseLoadTech}</pre>
            </details>
          ) : null}
          <button type="button" className="btn-mint px-3 h-8 text-xs" onClick={() => loadCatalog(false)}>
            Zkusit znovu načíst
          </button>
        </div>
      ) : null}

      {seriesLoading ? (
        <LoadingBlock label="Načítám řady z OECD…" minHeightClass="min-h-[100px]" showSkeletonLines />
      ) : loading ? (
        <LoadingBlock label="Načítám browse strom OECD (max ~20 s)…" minHeightClass="min-h-[140px]" showSkeletonLines />
      ) : browseFlattenWarn ? (
        <div className="mb-4 rounded-lg border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 px-3 py-2 text-xs text-amber-950 canvas-dark:text-amber-50">
          Strom katalogu je velmi rozsáhlý — zjednodušili jsme zobrazení, použijte filtr výše.
        </div>
      ) : null}

      {!loading && !seriesLoading && !browseLoadFail && rowsIndexing ? (
        <div className="rounded-2xl border border-border/80 bg-muted/32 px-4 py-6 max-w-4xl w-full mb-4">
          <LoadingInline label="Zařazuji řádky stromu (velké katalogové sady OECD)…" size="sm" />
        </div>
      ) : null}

      {!loading && !seriesLoading && !browseLoadFail && !rowsIndexing ? (
        <>
          {!visibleRows.length && search.trim() && keywords.length ? (
            <div className="border border-dashed border-amber-300 bg-amber-50 canvas-dark:bg-amber-950/35 rounded-2xl p-8 text-sm text-foreground mb-8 max-w-4xl space-y-4">
              <p>
                {demographicFilterGuess
                  ? `Pro výraz „${search.trim()}“ nebyly v OECD browse stromu nalezeny výsledky.`
                  : `Žádný řádek v již nahraném stromu OECD pro „${search.trim()}“.`}{" "}
              </p>
              <ul className="list-disc pl-5 space-y-2 text-[13px]">
                <li>
                  Vyhledat pomocí <strong className="text-emerald-900">AI</strong> výše (napříč zdroji) — doporučeno pro
                  obecná témata.
                </li>
                <li>
                  World Bank:&nbsp;
                  <Link className="text-sky-800 underline font-medium" to="/data360/catalog">
                    Otevřít katalog
                  </Link>{" "}
                  (např. populace / WDI).
                </li>
                <li>
                  Eurostat:&nbsp;
                  <Link className="text-sky-800 underline font-medium" to="/search/catalog?q=population+eurostat&catalog=eurostat">
                    Demografie v Eurostat katalogu
                  </Link>
                  .
                </li>
                <li>
                  ČSÚ:&nbsp;
                  <Link className="text-sky-800 underline font-medium" to="/sources/csu">
                    Otevřít zdroje ČSÚ
                  </Link>
                  .
                </li>
              </ul>
              <button type="button" className="btn-mint px-4 h-9 text-xs inline-flex gap-2" onClick={() => nav("/search/catalog?q=" + encodeURIComponent(search.trim()))}>
                Globální katalogové vyhledávání…
              </button>
            </div>
          ) : !visibleRows.length && !search.trim() ? (
            <div className="border border-dashed border-border bg-muted/25 rounded-2xl p-12 text-center text-sm text-muted-foreground mb-8">
              Katalog OECD ještě nemá řádky (prázdná odpověď).
            </div>
          ) : (
            <div className="bg-card border border-border rounded-2xl overflow-hidden shadow-sm mb-12">
              {visibleRows.map((row) => {
                if (row.kind === "cat") {
                  const isOpen = openPaths.has(row.path) || Boolean(filteredPaths);
                  const browseCountLabel = browseCategoryCountNode(row, isOpen);
                  return (
                    <button
                      key={row.path}
                      type="button"
                      onClick={() => toggle(row.path)}
                      className={`w-full flex items-center gap-2 text-left px-4 py-2.5 hover:bg-muted/50 border-t border-border/60 ${
                        row.depth === 0 ? "bg-muted/30 font-medium" : ""
                      }`}
                      style={{ paddingLeft: `${16 + row.depth * 20}px` }}
                    >
                      {isOpen ? (
                        <ChevronDown className="h-4 w-4 text-muted-foreground shrink-0" />
                      ) : (
                        <ChevronRight className="h-4 w-4 text-muted-foreground shrink-0" />
                      )}
                      <Folder className="h-4 w-4 text-muted-foreground shrink-0" />
                      <span className="text-sm text-foreground truncate">{row.name}</span>
                      {browseCountLabel != null ? (
                        <span className="text-[10px] uppercase tracking-wider text-muted-foreground ml-auto pr-2">
                          {browseCountLabel}
                        </span>
                      ) : null}
                    </button>
                  );
                }
                const isDataflow = row.item_kind === "dataflow";
                const isAdded = existing.has(String(row.set_id));
                const isAdding = Boolean(adding[row.set_id]);
                const meta = [];
                if (row.period) meta.push(`období: ${row.period}`);
                if (row.territory) meta.push(`oblast: ${row.territory}`);
                if (row.oecd_agency) meta.push(`agency: ${row.oecd_agency}`);
                if (row.oecd_dataflow) meta.push(`Technický dataset: ${row.oecd_dataflow}`);
                if (row.oecd_version) meta.push(`verze: ${row.oecd_version}`);
                return (
                  <div
                    key={row.path}
                    className="flex items-center gap-3 py-2 pr-3 border-t border-border/60 hover:bg-muted/50"
                    style={{ paddingLeft: `${36 + row.depth * 20}px` }}
                  >
                    <FileBarChart2 className="h-4 w-4 text-muted-foreground shrink-0" />
                    <div className="min-w-0 flex-1">
                      <div className="text-sm text-foreground truncate" title={row.name}>
                        {row.name}
                      </div>
                      <div className="text-[11px] text-muted-foreground font-mono">
                        identifikátor: {row.set_id}
                        {meta.length ? ` · ${meta.join(" · ")}` : ""}
                      </div>
                    </div>
                    {isDataflow ? (
                      <button
                        type="button"
                        onClick={() => loadSeries(row.oecd_dataset)}
                        disabled={seriesLoading}
                        className="btn-mint flex items-center gap-1.5 px-3 h-7 text-xs disabled:opacity-50"
                      >
                        {seriesLoading ? <LoadingSpinner suppressAria size="xs" /> : <Database className="h-3 w-3" />}
                        Načíst řady
                      </button>
                    ) : isAdded ? (
                      <span
                        data-testid={`oecd-added-${row.set_id}`}
                        className="flex items-center gap-1.5 px-2.5 h-7 text-[11px] uppercase tracking-wider rounded-lg chip-mint border border-[hsl(215_45%_82%)]"
                      >
                        <Check className="h-3 w-3" /> přidáno
                      </span>
                    ) : (
                      <button
                        type="button"
                        title="Nový zdroj z položky katalogu OECD"
                        onClick={() => addSource(row.set_id)}
                        disabled={isAdding}
                        data-testid={`oecd-add-${row.set_id}`}
                        className="btn-mint flex items-center gap-1.5 px-3 h-7 text-xs disabled:opacity-40 disabled:cursor-not-allowed"
                      >
                        {isAdding ? <LoadingSpinner suppressAria size="xs" /> : <Plus className="h-3 w-3" />}
                        Přidat
                      </button>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </>
      ) : null}
        </>
      ) : null}
    </AppShell>
  );
}
