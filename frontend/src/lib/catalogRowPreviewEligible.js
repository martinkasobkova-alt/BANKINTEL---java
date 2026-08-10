/** Pravda o tom, zda řádek katalogu může dát validní /api/catalog/preview (stejná pravidla jako UI). */

import { isEcbRowPreviewEligible } from "@/lib/ecbCatalogSetId";
import { isImfCompactSeriesPreviewable } from "@/lib/imfCompactSeriesId";
import { resolveCatalogRowDef } from "@/lib/catalogPreviewBody";

/**
 * @param {{ sourceType: string }} def
 * @param {{ kind?: string, set_id?: unknown, ecb_flow?: string }} row
 */
export function isCatalogRowPreviewEligible(def, row) {
  if (!def?.sourceType || !row || row.kind === "cat") return false;
  def = resolveCatalogRowDef(def, row);
  const st = def.sourceType;
  const sid = String(row.set_id ?? "").trim();
  if (st === "internal") {
    if (/^internal_visual:/i.test(sid)) return false;
    if (/^user_upload:[0-9a-f-]{36}$/i.test(sid)) return true;
    if (/^mongo_dataset:/i.test(sid)) return true;
    return false;
  }
  /** Po `flattenCatalogCategories` je u řádků `kind: "set"` a skutečný typ v `item_kind`. */
  const rowKind = row.item_kind || row.kind;

  if (st === "arad") {
    return /^[0-9]+(?::[A-Za-z0-9_./-]+)?$/.test(sid);
  }
  if (st === "csu") {
    return sid.length >= 4 && /^[A-Z0-9][A-Z0-9_-]+$/i.test(sid);
  }
  if (st === "eurostat") {
    return sid.length >= 2 && !sid.includes("/") && !sid.includes("||");
  }
  if (st === "ecb") {
    return isEcbRowPreviewEligible(def, { ...row, kind: rowKind });
  }
  if (st === "bis") {
    if (rowKind === "dataflow" || /\|\|DATAFLOW$/i.test(sid)) return false;
    if (sid.startsWith("BIS|")) {
      const rest = sid.slice(4);
      const last = rest.lastIndexOf("|");
      return last > 0 && rest.slice(last + 1).length >= 1 && rest.slice(0, last).length >= 2;
    }
    const parts = sid.split("/");
    return parts.length >= 2 && parts[0].length >= 2 && parts[1].length >= 1;
  }
  if (st === "worldbank") {
    if (rowKind === "letter" || /^LETTER\|\|/i.test(sid)) return false;
    if (rowKind === "selection" && sid && !/^[A-Z]{2,3}\.[A-Z]/i.test(sid)) {
      return /^[A-Z0-9][A-Za-z0-9._-]{2,}$/.test(sid) && sid.includes(".");
    }
    return /^[A-Z]{2,3}\.[A-Z0-9][A-Z0-9._-]+$/i.test(sid) && sid.includes(".");
  }
  if (st === "fred") {
    if (rowKind === "category" || /^CAT\|\|/i.test(sid)) return false;
    return /^[A-Za-z0-9._-]{3,32}$/.test(sid) && !/^\d+$/.test(sid);
  }
  if (st === "imf") {
    if (isImfCompactSeriesPreviewable(sid)) return true;
    const ind = String(row.imf_indicator || row.query_params?.imf_indicator || "").trim();
    const flow = String(row.imf_flow || row.query_params?.imf_flow || "").trim();
    return flow.length >= 2 && ind.length >= 2;
  }
  if (st === "oecd") {
    if (sid.startsWith("SDMX2|")) return true;
    return sid.includes("/") && !sid.includes("||DATAFLOW");
  }
  if (st === "world_bank_data360") {
    return /^[A-Za-z0-9_.-]+\|[A-Za-z0-9_.-]+$/.test(sid);
  }
  if (st === "alphavantage") {
    if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(sid)) return true;
    return /^__av_sym__[A-Za-z0-9.-]{1,20}$/i.test(sid);
  }
  return Boolean(sid);
}
