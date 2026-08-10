import { buildEurostatAddSourceBody } from "@/lib/eurostatAddSourcePayload";
import { resolveCatalogRowDef } from "@/lib/catalogPreviewBody";

/** Tělo POST „přidat řadu jako zdroj“ — stejné jako u globálního katalogu / vyhledávání. */
export function buildCatalogAddSourceBody(def, row, wbCountry) {
  const effectiveDef = resolveCatalogRowDef(def, row);
  if (!effectiveDef || !row) return { set_id: "" };
  if (effectiveDef.sourceType === "worldbank") {
    const country = String(row.wb_country || wbCountry || "").trim() || wbCountry;
    return { set_id: row.set_id, country };
  }
  if (effectiveDef.needsCountry) {
    return { set_id: row.set_id, country: wbCountry };
  }
  if (effectiveDef.sourceType === "eurostat") {
    return buildEurostatAddSourceBody(row, { fromAiSearch: Boolean(row?.fromDeepAi) });
  }
  if (effectiveDef.sourceType === "imf") {
    const body = {
      country: String(row.imf_country || row.query_params?.imf_country || "").trim(),
      flow: String(row.imf_flow || row.query_params?.imf_flow || "").trim(),
      indicator: String(row.imf_indicator || row.query_params?.imf_indicator || "").trim(),
      set_id: row.set_id,
    };
    const nm = String(row.name || "").trim();
    if (nm) body.name = nm;
    return body;
  }
  if (effectiveDef.sourceType === "alphavantage") {
    const body = { set_id: String(row.set_id ?? "").trim() };
    const nm = String(row.name || row.title || "").trim();
    if (nm) body.name = nm;
    const fn = String(row.av_function || row.function || "").trim();
    if (fn) body.function = fn;
    return body;
  }
  if (effectiveDef.sourceType === "ecb") {
    let country = String(row.ecb_country || row.query_params?.country || row.territory || wbCountry || "CZ")
      .trim()
      .toUpperCase();
    let indicator = String(
      row.ecb_indicator_id || row.query_params?.ecb_indicator_id || row.query_params?.indicator || ""
    ).trim();
    const curatedMatch = /^ecb:([A-Z0-9]{2}):(.+)$/i.exec(String(row.set_id ?? "").trim());
    if (curatedMatch) {
      country = country || curatedMatch[1].toUpperCase();
      indicator = indicator || curatedMatch[2];
    }
    const body = { country, indicator };
    const nm = String(row.name || row.title || "").trim();
    if (nm) body.name = nm;
    return body;
  }
  if (effectiveDef.sourceType === "tradingeconomics") {
    const body = { set_id: String(row.set_id ?? "").trim() };
    const nm = String(row.name || row.title || "").trim();
    if (nm) body.name = nm;
    if (row.query_params && typeof row.query_params === "object" && !Array.isArray(row.query_params)) {
      body.query_params = { ...row.query_params };
    }
    return body;
  }
  if (effectiveDef.sourceType === "oecd" && row.query_params && typeof row.query_params === "object") {
    const body = { set_id: row.set_id };
    const nm = String(row.name || row.title || "").trim();
    if (nm) body.name = nm;
    body.query_params = { ...row.query_params };
    return body;
  }
  return { set_id: row.set_id };
}
