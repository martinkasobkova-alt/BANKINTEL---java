/**
 * Human-first klasifikace ECB DSD dimenzí (zrcadlo backend/services/ecb_dimension_roles.py).
 */

const COMPARE_IDS = new Set(["REF_AREA", "GEO", "AREA", "REFAREA", "COUNT_AREA", "COUNTRY", "COUNTRY_CODE", "LOCATION"]);
const FREQ_IDS = new Set(["FREQ"]);
const EXR_AUX_DIMS = new Set(["EXR_TYPE", "EXR_SUFFIX"]);
const DERIVED_VARIATION_IDS = new Set(["TRANSFORMATION", "AME_TRANSFORMATION", "ADJUSTMENT", "CALCULATION", "METHODOLOGY"]);

const UNIT_ID_RX = /UNIT|UNIT_MEASURE|UNIT_MULT/i;
const TECHNICAL_IDS_RX =
  /DATA_PROVIDER|OBS_STATUS|OBS_CONF|COLLECTION|COMPILATION|DECIMALS|TITLE|SOURCE|TIME_FORMAT|BANK_SELECTION|SERIES_DENOM|CURRENCY_TRANS|CONF_STATUS|PUBLICATION|COMPILING_ORG|TITLE_COMPL|TITLE_TS/i;
const METRIC_ID_NAME_RX =
  /ITEM|INDICATOR|SURVEY|QUESTION|CONCEPT|MEASURE|SERIES|BALANCE|POSITION|INSTRUMENT|ASSET|LIABILITY|LOAN|DEPOSIT|CREDIT|EXPOSURE|TRANSACTION|INDEX|DATASET|BSI_ITEM|REP_ITEM|ECONOMIC|SUBJECT/i;
const CATEGORY_ID_NAME_RX =
  /SECTOR|COUNTERPART|CURRENCY|MATURITY|BORROWER|LENDER|INSTITUTION|SECURITY|ADJUSTMENT|TYPE|CLASS|BREAKDOWN|BRKDWN|COUNTERPARTY|ORIGINAL_MATURITY|REMAINING_MATURITY|VALUATION|PRICES|MARKET/i;
const COMPARE_NAME_RX = /reference\s*area|geograph|country|region|territor|euro\s*area/i;
const RATE_AS_WORD = /(^|_)(RATE|RATES)($|_)/i;

const ANON_BANK_RX = /^bank\s*\d+$/i;
const ANON_ITEM_RX = /^(item|series|code)\s*\d+$/i;
const ANON_GENERIC_RX = /^[A-Z]{2,4}\d{0,3}$/;

function sampleValues(dm, limit = 60) {
  const vals = Array.isArray(dm?.values) ? dm.values : [];
  return vals.slice(0, limit).filter((v) => v && v.id);
}

function valueLooksAnonymous(code, name) {
  const c = String(code || "").trim();
  const n = String(name || "").trim();
  const lab = n || c;
  if (!c) return true;
  if (ANON_BANK_RX.test(lab.trim())) return true;
  if (ANON_ITEM_RX.test(lab.trim())) return true;
  if (c === n && c.length <= 5 && ANON_GENERIC_RX.test(c)) return true;
  return false;
}

function codelistLooksAnonymous(values, threshold = 0.55) {
  if (!values || values.length < 4) return false;
  const n = Math.min(40, values.length);
  let bad = 0;
  for (let i = 0; i < n; i += 1) {
    const v = values[i];
    if (valueLooksAnonymous(v?.id, v?.name)) bad += 1;
  }
  return bad / n >= threshold;
}

/**
 * @param {string} flowRef
 * @param {{ id?: string, name?: string, values?: unknown[] }} dim
 * @returns {{ role: string, reason: string }}
 */
export function classifyEcbDimensionRole(_flowRef, dim) {
  const dimId = String(dim?.id || "").trim();
  const dimIdU = dimId.toUpperCase();
  const dimName = String(dim?.name || "").trim();
  const vals = sampleValues(dim);

  if (FREQ_IDS.has(dimIdU)) return { role: "frequency", reason: "FREQ" };
  if (UNIT_ID_RX.test(dimIdU)) return { role: "unit", reason: "UNIT_PATTERN" };
  if (EXR_AUX_DIMS.has(dimIdU)) return { role: "category", reason: "EXR_AUX" };
  if (DERIVED_VARIATION_IDS.has(dimIdU)) return { role: "category", reason: "DERIVED_VARIATION" };
  if (TECHNICAL_IDS_RX.test(dimIdU) || TECHNICAL_IDS_RX.test(dimName))
    return { role: "technical", reason: "TECHNICAL_ID" };
  if (dimIdU === "BANK_SELECTION") return { role: "technical", reason: "BANK_SELECTION" };

  if (dimIdU === "INSTITUTION" || dimIdU.includes("INSTITUTION")) {
    if (codelistLooksAnonymous(vals)) return { role: "technical", reason: "ANONYMOUS_INSTITUTION_CODES" };
    return { role: "category", reason: "INSTITUTION_NAMED" };
  }

  if (COMPARE_IDS.has(dimIdU) || COMPARE_NAME_RX.test(dimName)) return { role: "compare", reason: "AREA" };

  if (METRIC_ID_NAME_RX.test(dimIdU) || METRIC_ID_NAME_RX.test(dimName))
    return { role: "metric", reason: "METRIC_PATTERN" };
  if (RATE_AS_WORD.test(dimIdU)) return { role: "metric", reason: "RATE_WORD" };

  if (CATEGORY_ID_NAME_RX.test(dimIdU) || CATEGORY_ID_NAME_RX.test(dimName)) {
    if (codelistLooksAnonymous(vals) && !COMPARE_IDS.has(dimIdU))
      return { role: "technical", reason: "ANONYMOUS_CATEGORY_CODELIST" };
    return { role: "category", reason: "CATEGORY_PATTERN" };
  }

  if (codelistLooksAnonymous(vals)) return { role: "technical", reason: "ANONYMOUS_CODELIST" };
  return { role: "advanced", reason: "FALLBACK" };
}

/**
 * @param {string} role
 * @param {string} dimId
 * @param {string} dimName
 * @param {string} flowRef
 */
export function getHumanEcbFieldLabel(role, dimId, dimName, flowRef) {
  const id = String(dimId || "").trim();
  const name = String(dimName || "").trim();
  const fr = String(flowRef || "").trim().toUpperCase();

  if (id === "BANK_SELECTION" && role === "technical") return "Technický výběr banky z ECB";

  const byRole = {
    compare: "Země nebo oblast",
    metric: "Ukazatel",
    category: "Upřesnění (sektor, měna, typ…)",
    frequency: "Frekvence dat",
    unit: "Jednotka",
    technical: "Technická volba",
    advanced: "Další volba",
  };
  if (role && byRole[role]) return byRole[role];

  if (id === "REF_AREA" || id === "GEO" || id === "AREA") return "Země nebo oblast";
  if (id === "FREQ") return "Frekvence dat";
  if (/SURVEY_ITEM|INDICATOR|ITEM|MEASURE|CONCEPT/i.test(id)) return "Ukazatel";
  if (id === "CURRENCY") return "Měna";
  if (id === "CURRENCY_DENOM") return "Měna vyjádření";
  if (id === "UNIT_MEASURE" || id === "UNIT") return "Jednotka";
  if (fr === "EXR" && id === "EXR_TYPE") return "Typ kurzu";
  if (fr === "EXR" && id === "EXR_SUFFIX") return "Doplňkový typ kurzu";

  const raw = name && name.toLowerCase() !== id.toLowerCase() ? name : "";
  if (raw) return raw.length > 48 ? `${raw.slice(0, 47)}…` : raw;
  return "Pole";
}

/**
 * Lokální plán když ještě není odpověď z /valid-combinations.
 * @param {string} flowRef
 * @param {Array<{ id?: string, name?: string, values?: unknown[] }>} dimensionsSorted
 */
export function buildLocalWizardPlan(flowRef, dimensionsSorted) {
  const dims = Array.isArray(dimensionsSorted) ? dimensionsSorted : [];
  const roles = {};
  const metric = [];
  const category = [];
  const compareList = [];
  const technical = [];
  const advanced = [];

  for (const dm of dims) {
    const id = String(dm?.id || "").trim();
    if (!id) continue;
    const { role, reason } = classifyEcbDimensionRole(flowRef, dm);
    roles[id] = { role, reason };
    if (role === "metric") metric.push(id);
    else if (role === "category") category.push(id);
    else if (role === "compare") compareList.push(id);
    else if (role === "technical") technical.push(id);
    else if (role === "advanced") advanced.push(id);
  }

  let compareDim = null;
  for (const c of ["REF_AREA", "GEO", "AREA", "COUNT_AREA"]) {
    if (compareList.includes(c)) {
      compareDim = c;
      break;
    }
  }
  if (!compareDim && compareList.length) compareDim = compareList[0];

  const technicalIds = new Set(technical);
  if (String(flowRef || "").trim().toUpperCase() === "EXR") {
    const exrOrder = ["FREQ", "CURRENCY", "CURRENCY_DENOM", "EXR_TYPE", "EXR_SUFFIX"];
    const basicExr = [];
    for (const x of exrOrder) {
      if (dims.some((d) => String(d?.id) === x) && !technicalIds.has(x)) basicExr.push(x);
    }
    return {
      dimensionRoles: roles,
      recommendedBasic: basicExr.length ? basicExr : [],
      technicalIds,
      advancedIds: advanced,
      compareDim: null,
    };
  }

  const recommendedBasic = [];
  const seen = new Set();
  const add = (id) => {
    if (!id || seen.has(id) || roles[id]?.role === "technical") return;
    seen.add(id);
    recommendedBasic.push(id);
  };
  for (const mid of metric.slice(0, 2)) add(mid);
  if (compareDim) add(compareDim);
  if (dims.some((d) => String(d?.id) === "FREQ")) add("FREQ");
  for (const cid of category.slice(0, 2)) add(cid);
  for (const dm of dims) {
    const id = String(dm?.id || "");
    if (!id || seen.has(id)) continue;
    if (roles[id]?.role === "unit" && recommendedBasic.length < 7) add(id);
  }

  return {
    dimensionRoles: roles,
    recommendedBasic,
    technicalIds,
    advancedIds: advanced,
    compareDim,
  };
}
