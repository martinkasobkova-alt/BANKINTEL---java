/** Zrcadlová logiku backendu `services/ecb_reference.py` — platný náhled jen pro FLOW + dimenzovaný series key. */

const SERIES_DOT = /^[A-Za-z0-9][A-Za-z0-9,+._-]*(?:\.(?:[A-Za-z0-9,+._-]*)+)+$/;

function looksLikeDimensions(sk) {
  const s = String(sk || "").trim();
  if (s.length < 5) return false;
  const u = s.toUpperCase();
  if (u === "DATAFLOW" || u === "ALL") return false;
  if (s.includes("+")) return true;
  if (s.includes("..")) return true;
  if (s.includes(".") && SERIES_DOT.test(s)) return true;
  return false;
}

/**
 * Řádek katalogu je jen dataflow (ECB Statistical Data Warehouse) — nelze rozumně volat náhled.
 * @param {string} setId
 * @param {string} [kind]
 */
export function isEcbCatalogDataflowRow(setId, kind) {
  const sid = String(setId || "").toUpperCase();
  if (sid.includes("||DATAFLOW")) return true;
  if (kind === "dataflow") return true;
  return false;
}

/**
 * Platný `EXR/SERIES...` jako cíl pro /api/catalog/preview.
 * @param {string} raw
 */
export function isEcbCatalogSetIdPreviewable(raw) {
  const s = String(raw || "").trim();
  if (!s || s.includes("||DATAFLOW")) return false;
  const slash = s.indexOf("/");
  if (slash < 1) return false;
  const flowRaw = s.slice(0, slash).trim();
  const seriesRaw = s.slice(slash + 1).trim();
  if (!flowRaw || !seriesRaw) return false;
  const flUp = flowRaw.toUpperCase();
  const skUp = seriesRaw.toUpperCase();
  if (skUp === flUp && !seriesRaw.includes(".") && !seriesRaw.includes("+")) return false;
  const bl = new Set(["DATAFLOW", "ECB", "ALL", "SERIES"]);
  if (bl.has(seriesRaw.toUpperCase())) return false;
  if (
    flUp === "MPD"
    && !looksLikeDimensions(seriesRaw)
    && !seriesRaw.includes(".")
  ) {
    return false;
  }
  return looksLikeDimensions(seriesRaw);
}

/** Kurátorovaná řada `ecb:CZ:inflace_celkova` nebo query_params country + indicator. */
function rowNestedValue(row, key) {
  return String(
    row?.[key]
      || row?.query_params?.[key]
      || row?.preview_request_payload?.[key]
      || row?.preview_request_payload?.query_params?.[key]
      || row?.preview_payload?.[key]
      || row?.preview_payload?.query_params?.[key]
      || "",
  ).trim();
}

function isEcbCuratedAliasSetId(raw) {
  return /^ecb_[a-z0-9_]{3,}$/i.test(String(raw || "").trim());
}

export function isEcbCuratedRowPreviewEligible(row) {
  const country = rowNestedValue(row, "ecb_country") || rowNestedValue(row, "country");
  const indicator = rowNestedValue(row, "ecb_indicator_id") || rowNestedValue(row, "indicator");
  if (country && indicator) return true;
  if (country && isEcbCuratedAliasSetId(row?.set_id)) return true;
  return /^ecb:[A-Z0-9]{2}:[a-z0-9_]+$/i.test(String(row?.set_id ?? "").trim());
}

/**
 * Stejné použití jako `isCatalogRowPreviewEligible` v části ECB.
 */
export function isEcbRowPreviewEligible(def, row) {
  if (def?.sourceType !== "ecb") return false;
  if (isEcbCuratedRowPreviewEligible(row)) return true;
  return (
    !isEcbCatalogDataflowRow(row?.set_id, row?.kind)
    && isEcbCatalogSetIdPreviewable(row?.set_id)
  );
}
