/**
 * Quick AI — výběr zdrojů řeší backend AI source router (preflight /source-route).
 * Frontend posílá celý pool zaškrtnutých katalogů; backend vybere relevantní zdroje.
 */

const SOURCE_ALIASES = {
  ecb: "ecb2",
  oecd: "oecd4",
  worldbank: "data360",
  world_bank_data360: "data360",
  czso: "csu",
};

const DEFAULT_ORDER = [
  "arad",
  "csu",
  "eurostat",
  "ecb2",
  "imf",
  "oecd4",
  "fred",
  "bis",
  "data360",
  "commodities",
  "alphavantage",
  "yahoo_finance",
];

function normSourceId(raw) {
  const sid = String(raw || "").trim().toLowerCase();
  return SOURCE_ALIASES[sid] || sid;
}

/**
 * @deprecated Backend AI router nahrazuje klientský keyword odhad. Vrací allowed pool.
 * @param {string} _query
 * @param {Set<string>|string[]} selected
 * @returns {string[]}
 */
export function inferLikelyCatalogSources(_query, selected) {
  const selectedArr = [...(selected instanceof Set ? selected : selected || [])].map(normSourceId).filter(Boolean);
  if (selectedArr.length) return [...selectedArr].sort();
  return [...DEFAULT_ORDER];
}

/**
 * @param {string} query
 * @param {Set<string>|string[]} selected
 * @param {"quick"|"extended"} scope
 */
export function resolveAiSearchSources(query, selected, scope) {
  if (scope !== "extended") {
    // Quick AI: katalogový filtr se ignoruje — backend AI router vybere relevantní zdroje z celého registru.
    return [...DEFAULT_ORDER];
  }
  const selectedArr = inferLikelyCatalogSources(query, selected);
  return selectedArr.length ? selectedArr : [...DEFAULT_ORDER];
}

export const AI_SEARCH_PROFILE_QUICK = "quick_ai";
export const AI_SEARCH_PROFILE_EXTENDED = "multi";
