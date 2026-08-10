/**
 * Primární akce po kliknutí na řádek katalogu / výsledek hledání.
 * Ne-previewovatelné položky (BIS dataflow, FRED kategorie, …) přesměrují do příslušného katalogu
 * místo otevření prázdného detailu a toastu.
 */

import { getCatalogBrowseSemantics } from "@/lib/catalogBrowseSemantics";
import { isCatalogRowPreviewEligible } from "@/lib/catalogRowPreviewEligible";
import { eurostatAiRowNeedsOpenInCatalog } from "@/lib/eurostatQueryableSlice";

/** Kurátorované BIS aliasy z metadata indexu → dataflow id. */
const BIS_CURATED_ALIAS_FLOWS = {
  bis_commercial_property_price_index: "WS_CPP",
  bis_nominal_residential_property_price_index: "WS_SPP",
  bis_real_residential_property_price_index: "WS_SPP",
  bis_residential_property_price_index: "WS_SPP",
};

/**
 * @param {Record<string, unknown>} row
 * @returns {string}
 */
export function resolveBisFlowHint(row) {
  const bisDataflow = String(row?.bis_dataflow || "").trim();
  if (bisDataflow) return bisDataflow;

  const datasetId = String(row?.dataset_id || "").trim();
  if (datasetId && /^WS_[A-Z0-9_]+$/i.test(datasetId)) return datasetId;

  const sid = String(row?.set_id ?? "").trim();
  if (!sid) return "";

  if (/\|\|DATAFLOW$/i.test(sid)) {
    return sid.replace(/\|\|DATAFLOW$/i, "").trim();
  }

  if (sid.startsWith("BIS|")) {
    const parts = sid.split("|");
    if (parts.length >= 2 && parts[1]) return parts[1].trim();
  }

  if (sid.startsWith("bis_")) {
    return BIS_CURATED_ALIAS_FLOWS[sid] || "";
  }

  return "";
}

const PUBLIC_CATALOG_ID_ALIASES = Object.freeze({
  ecb: "ecb2",
  imf2: "imf",
  oecd: "oecd4",
  oecd2: "oecd4",
  oecd3: "oecd4",
  worldbank: "data360",
});

/**
 * Builds a public catalog URL. Search-result actions must not reuse admin-only
 * catalogPath values from source definitions.
 */
export function buildPublicCatalogPath(catalogId, { q = "", country = "", setId = "", preview = false } = {}) {
  const rawCatalog = String(catalogId || "").trim().toLowerCase();
  const catalog = PUBLIC_CATALOG_ID_ALIASES[rawCatalog] || rawCatalog;
  if (!catalog) return "";
  const params = new URLSearchParams({ catalog });
  const normalizedQuery = String(q || "").trim();
  const normalizedCountry = String(country || "").trim().toUpperCase();
  const normalizedSetId = String(setId || "").trim();
  if (normalizedQuery) params.set("q", normalizedQuery.slice(0, 180));
  if (normalizedCountry) params.set("country", normalizedCountry);
  if (normalizedSetId) params.set("set_id", normalizedSetId.slice(0, 240));
  if (preview && normalizedSetId) params.set("preview", "1");
  return `/search/catalog?${params.toString()}`;
}

function buildGlobalCatalogBrowsePath(def, row) {
  const q = String(row?.set_id || row?.series_id || row?.title || row?.name || "").trim();
  const country = String(row?.country || row?.ecb_country || row?.query_params?.country || "").trim();
  return buildPublicCatalogPath(def?.id, { q, country });
}

/**
 * @typedef {{ type: "preview" }} CatalogRowPreviewAction
 * @typedef {{ type: "navigate", path: string, toast?: string, label?: string }} CatalogRowNavigateAction
 * @typedef {{ type: "bis-dataflow-preview", flowHint: string, label?: string }} CatalogRowBisDataflowPreviewAction
 * @typedef {{ type: "blocked", toast: string }} CatalogRowBlockedAction
 * @typedef {CatalogRowPreviewAction | CatalogRowNavigateAction | CatalogRowBisDataflowPreviewAction | CatalogRowBlockedAction} CatalogRowPrimaryAction
 */

/**
 * @param {{ sourceType: string, id?: string }} def
 * @param {Record<string, unknown>} row
 * @returns {CatalogRowPrimaryAction}
 */
export function resolveCatalogRowPrimaryAction(def, row) {
  if (eurostatAiRowNeedsOpenInCatalog(def, row)) {
    const sid = String(row?.set_id || "").trim();
    return {
      type: "navigate",
      path: buildPublicCatalogPath("eurostat", {
        q: sid,
        setId: sid,
        preview: Boolean(sid),
      }),
      label: "Otevřít v katalogu",
    };
  }

  if (isCatalogRowPreviewEligible(def, row)) {
    return { type: "preview" };
  }

  const semantic = getCatalogBrowseSemantics(def, row).semantic;
  const fredCatId = String(row?.fred_category_id || "").trim();
  const wbLetter = String(row?.wb_letter || "").trim();

  if (def?.sourceType === "fred" && semantic === "category" && fredCatId) {
    return {
      type: "navigate",
      path: `/fred/catalog?expand=${encodeURIComponent(fredCatId)}`,
      label: "Otevřít kategorii",
    };
  }

  if (def?.sourceType === "worldbank" && semantic === "letter_bucket" && wbLetter) {
    return {
      type: "navigate",
      path: buildPublicCatalogPath("data360", { q: wbLetter }),
      label: "Načíst indikátory",
    };
  }

  if (def?.sourceType === "bis") {
    const flowHint = resolveBisFlowHint(row);
    if (flowHint) {
      return {
        type: "bis-dataflow-preview",
        flowHint,
        label: "Zobrazit data",
      };
    }
    return {
      type: "navigate",
      path: "/bis/catalog",
      toast: "Vyberte datový tok, zemi a konkrétní řadu v BIS katalogu.",
      label: "Otevřít BIS katalog",
    };
  }

  const oecd3DataflowBrowse =
    def?.id === "oecd3"
    && (String(row?.item_kind || row?.kind || "") === "dataflow" || row?.oecd3_dataflow);
  const oecd3Agency = String(row?.oecd_agency || row?.query_params?.agency || "").trim();
  const oecd3Dataflow = String(row?.oecd_dataflow || row?.query_params?.dataflow || "").trim();
  const oecd3Version = String(row?.oecd_version || row?.query_params?.version || "+").trim() || "+";
  if (oecd3DataflowBrowse && oecd3Agency && oecd3Dataflow) {
    const qp = new URLSearchParams({
      agency: oecd3Agency,
      dataflow: oecd3Dataflow,
      version: oecd3Version,
      probe: "1",
      autoload: "1",
    });
    const title = String(row?.title || row?.name || "").trim();
    if (title) qp.set("name", title.slice(0, 160));
    return {
      type: "navigate",
      path: `/oecd/catalog?${qp.toString()}`,
      label: row?.oecd_has_verified_series ? "Vlastní dimenze" : "Načíst dimenze z OECD",
    };
  }

  if (def?.sourceType === "imf") {
    const cc = String(row?.imf_country || row?.query_params?.imf_country || "").trim();
    const flow = String(row?.imf_flow || row?.query_params?.imf_flow || "").trim();
    const ind = String(row?.imf_indicator || row?.query_params?.imf_indicator || "").trim();
    if (cc && flow && ind) {
      const qs = new URLSearchParams({ country: cc, flow, indicator: ind });
      return {
        type: "navigate",
        path: `/imf/catalog?${qs.toString()}`,
        label: "Otevřít v IMF katalogu",
      };
    }
    return {
      type: "navigate",
      path: "/imf/catalog",
      label: "Otevřít IMF katalog",
    };
  }

  if (row?.fromDeepAi && def?.id) {
    const path = buildGlobalCatalogBrowsePath(def, row);
    if (path) {
      return {
        type: "navigate",
        path,
        label: "Otevřít v katalogu",
      };
    }
  }

  if (semantic === "ecb_dataflow") {
    return {
      type: "blocked",
      toast:
        "Toto je datová oblast ECB (skupina), ne konkrétní řada. Upřesněte dotaz — vyhledávání ECB nabídne přímo konkrétní časové řady.",
    };
  }

  return {
    type: "blocked",
    toast:
      "Tato položka nemá platný identifikátor datové řady pro náhled u tohoto zdroje (rozbalte hierarchii nebo vyberte konkrétní řadu).",
  };
}

/**
 * @param {{ sourceType: string, id?: string }} def
 * @param {Record<string, unknown>} row
 * @returns {boolean}
 */
export function catalogRowHasBrowseFallback(def, row) {
  const action = resolveCatalogRowPrimaryAction(def, row);
  return (
    (action.type === "navigate" && Boolean(action.path))
    || action.type === "bis-dataflow-preview"
  );
}
