import { normalizeEcb2BrowseBucket } from "@/lib/ecbSeriesDisplayTitle";

/**
 * Je vybraný řádek ve sloupcovém exploreru právě v lazy načítání dětí?
 * @param {object} row
 * @param {string} browseCatalogId
 * @param {object} loading sady klíčů z GlobalCatalogSearchPage
 */
export function isExplorerBrowseRowLoading(row, browseCatalogId, loading) {
  if (!row || !browseCatalogId || !loading) return false;
  const path = String(row.path || "").trim();
  const id = String(browseCatalogId || "").trim();

  if (id === "ecb2") {
    const code = String(row?.ecb_country || "").trim().toUpperCase();
    const flow = String(row?.ecb_flow || "").trim().toUpperCase();
    const letter = normalizeEcb2BrowseBucket(row?.ecb_letter);
    if (letter && flow && loading.ecb2Letters?.has(`${code}|${flow}|${letter}`)) return true;
    if (flow && loading.ecb2Flows?.has(`${code}|${flow}|${path}`)) return true;
    if (code && loading.ecb2Countries?.has(`${code}|${path}`)) return true;
    return false;
  }
  if (id === "ecb") {
    const code = String(row?.ecb_country || "").trim().toUpperCase();
    return Boolean(code && loading.ecbCountries?.has(code));
  }
  if (id === "imf") {
    const code = String(row?.imf_country || "").trim().toUpperCase();
    return Boolean(code && loading.imfCountries?.has(code));
  }
  if (id === "imf2") {
    const code = String(row?.imf_country || "").trim().toUpperCase();
    return Boolean(code && loading.imf2Countries?.has(code));
  }
  if (id === "oecd2") {
    const code = String(row?.oecd_ref_area || "").trim().toUpperCase();
    return Boolean(code && loading.oecd2Countries?.has(code));
  }
  if (id === "oecd4") {
    const dsKey = String(row?.oecd4_key || "").trim();
    const ra = String(row?.oecd4_ref_area || "").trim().toUpperCase();
    if (ra && dsKey && loading.oecd4Countries?.has(`${dsKey}|${ra}`)) return true;
    if (dsKey && loading.oecd4Datasets?.has(dsKey)) return true;
    return false;
  }
  if (id === "worldbank") {
    const code = String(row?.wb_country || "").trim().toUpperCase();
    const country = String(row?.name || "").trim().toLowerCase();
    return Boolean(code && country && loading.wbCountries?.has(`${code}|${country}`));
  }
  if (id === "data360") {
    const code = String(row?.data360_country || "").trim().toUpperCase();
    const country = String(row?.data360_country_name || row?.name || "").trim().toLowerCase();
    return Boolean(code && country && loading.d360Countries?.has(`${code}|${country}`));
  }
  if (id === "bis") {
    const flowId = String(row?.bis_dataflow || loading.bisActiveDataflowId || "").trim();
    const itemKind = String(row?.item_kind || row?.kind || "").trim();
    if (itemKind === "dataflow" && flowId && loading.bisDataflows?.has(`${flowId}|${path}`)) return true;
    if (row?.bis_lazy_country) {
      const code = String(row?.ref_area || "").trim().toUpperCase();
      if (flowId && code && loading.bisCountries?.has(`${flowId}|${code}|${path}`)) return true;
    }
    return false;
  }
  if (id === "fred") {
    const catId = String(row?.fred_category_id || "").trim();
    if (catId && loading.fredCategories?.has(`${catId}|${path}`)) return true;
    const sid = String(row?.set_id || "").trim();
    if (/^CAT\|\|/i.test(sid)) {
      const cid = sid.replace(/^CAT\|\|/i, "").trim();
      if (cid && loading.fredCategories?.has(`${cid}|${path}`)) return true;
    }
    return false;
  }
  if (id === "tradingeconomics") {
    const name = String(row?.name || "").trim().toLowerCase();
    return Boolean(name && loading.teCountries?.has(name));
  }
  return false;
}

/** Cesta řádku, u kterého právě probíhá lazy načtení (pro spinner ve sloupci). */
export function resolveExplorerLoadingRowKey(selection, allRows, browseCatalogId, loading) {
  const paths = Array.isArray(selection) ? selection : [];
  const rows = Array.isArray(allRows) ? allRows : [];
  for (let i = paths.length - 1; i >= 0; i -= 1) {
    const path = String(paths[i] || "").trim();
    if (!path) continue;
    const row = rows.find((r) => r.path === path);
    if (row && isExplorerBrowseRowLoading(row, browseCatalogId, loading)) return path;
  }
  return null;
}
