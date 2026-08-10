/**
 * Hlavní rozcestník katalogů — globální hledání / procházení.
 * @param {string} catalogId Hodnoty: ecb, fred, worldbank, bis, oecd, imf, … (musí odpovídat id v catalogDefinitions,
 *   případně veřejný alias worldbank_data360 pro Data360.)
 * @returns {string}
 */
export function buildCatalogHubHref(catalogId) {
  const c = String(catalogId || "").trim().toLowerCase();
  if (!c) return "/search/catalog";
  return `/search/catalog?catalog=${encodeURIComponent(c)}`;
}

/**
 * Normalizace ?catalog= z URL na `id` položky v CATALOGS (pro browse / výběr katalogu).
 * Podporuje alias `worldbank_data360` → interní `data360`.
 */
export function normalizeCatalogBrowseIdFromUrlParam(param) {
  const q = String(param || "").trim().toLowerCase();
  if (q === "worldbank_data360") return "data360";
  return q;
}
