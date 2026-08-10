/** Najde definici katalogu pro řadu ze srovnávací tabulky / makro browse. */

const CATALOG_ID_ALIASES = {
  ecb: "ecb2",
  worldbank: "data360",
  world_bank: "data360",
  world_bank_data360: "data360",
  worldbank_pink_sheet: "commodities",
  pink_sheet: "commodities",
  data360: "data360",
  ecb2: "ecb2",
  eurostat: "eurostat",
  imf: "imf",
  imf2: "imf",
  fred: "fred",
  bis: "bis",
  arad: "arad",
  csu: "csu",
};

/**
 * @param {Map<string, object>} catalogById
 * @param {{ catalog_id?: string, source?: string, source_type?: string }} row
 */
export function resolveMacroCatalogDef(catalogById, row) {
  const raw = String(row?.catalog_id || row?.source || row?.source_type || "")
    .trim()
    .toLowerCase();
  const resolvedId = CATALOG_ID_ALIASES[raw] || raw;
  if (!resolvedId) return null;
  return catalogById.get(resolvedId) || null;
}

/** Řada ze srovnávací tabulky má vše pro snapshot náhled bez dimenzí. */
export function isMacroComparisonPreviewRow(row) {
  if (!row || typeof row !== "object") return false;
  const topicId = String(row.topic_id || "").trim();
  const setId = String(row.set_id || "").trim();
  const geo = String(row.geo || row.query_params?.geo || "").trim();
  const catalogId = String(row.catalog_id || row.source || "").trim();
  return Boolean(topicId && setId && geo && catalogId);
}
