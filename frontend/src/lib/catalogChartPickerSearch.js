import api from "@/lib/api";

/** ID musí odpovídat backendu (`/api/catalog/search`, deep-search) — ecb2, oecd4. */
export const CATALOG_PICK_SOURCES = ["arad", "csu", "eurostat", "ecb2", "fred", "data360", "bis", "imf", "oecd4"];

export const CATALOG_PICKER_OPTIONS = [
  { id: "", label: "Všechny katalogy" },
  { id: "arad", label: "ČNB - ARAD" },
  { id: "csu", label: "ČSÚ" },
  { id: "eurostat", label: "Eurostat" },
  { id: "ecb2", label: "ECB" },
  { id: "fred", label: "FRED" },
  { id: "data360", label: "World Bank" },
  { id: "bis", label: "BIS" },
  { id: "imf", label: "IMF WEO" },
  { id: "oecd4", label: "OECD" },
];

const SOURCE_ALIASES = {
  ecb: "ecb2",
  oecd: "oecd4",
  worldbank: "data360",
};

const CATALOG_PICKER_LABELS = Object.fromEntries(
  CATALOG_PICKER_OPTIONS.filter((o) => o.id).map((o) => [o.id, o.label])
);
Object.assign(CATALOG_PICKER_LABELS, { ecb: "ECB", oecd: "OECD", worldbank: "World Bank" });

export function catalogPickerLabel(catalogId) {
  const id = String(catalogId || "").trim().toLowerCase();
  const resolved = SOURCE_ALIASES[id] || id;
  return CATALOG_PICKER_LABELS[resolved] || resolved.toUpperCase() || "Katalog";
}

function resolveSearchSources(catalogId) {
  let id = String(catalogId || "").trim().toLowerCase();
  if (SOURCE_ALIASES[id]) id = SOURCE_ALIASES[id];
  if (id && CATALOG_PICK_SOURCES.includes(id)) return [id];
  return CATALOG_PICK_SOURCES;
}

export function deepSearchRowsForPicker(payload) {
  const data = payload && typeof payload === "object" ? payload : {};
  const grouped = data.grouped_results && typeof data.grouped_results === "object" ? data.grouped_results : {};
  const topVerified = Array.isArray(data.verified) ? data.verified : [];
  const topPossible = Array.isArray(data.possible) ? data.possible : [];
  const groupedVerified = Array.isArray(grouped.verified) ? grouped.verified : [];
  const groupedCandidates = Array.isArray(grouped.candidates) ? grouped.candidates : [];
  const groupedBeta = Array.isArray(grouped.beta) ? grouped.beta : [];
  const rawResults = Array.isArray(data.results) ? data.results : [];

  const seen = new Set();
  const out = [];
  const append = (row) => {
    if (!row || typeof row !== "object") return;
    const source = String(row.catalog_id || row.source_type || row.source || "catalog").toLowerCase();
    const setId = String(row.set_id || row.series_id || row.code || "");
    const title = String(row.name || row.title || row.dataset_name || "");
    const key = `${source}|${setId}|${title}`;
    if (seen.has(key)) return;
    seen.add(key);
    out.push(row);
  };

  topVerified.forEach(append);
  topPossible.forEach(append);
  groupedVerified.forEach(append);
  groupedCandidates.forEach(append);
  groupedBeta.forEach(append);
  rawResults.forEach(append);

  return out;
}

export function quickCatalogSearchRowsForPicker(payload, sourceId) {
  const data = payload && typeof payload === "object" ? payload : {};
  const rows = Array.isArray(data.results) ? data.results : [];
  return rows.map((row) => ({
    ...row,
    catalog_id: String(row?.catalog_id || row?.source_type || sourceId || "catalog"),
  }));
}

export function catalogRowLinkUrl(row, query) {
  const explicit = String(row?.link_url || "").trim();
  if (explicit) return explicit;
  const cid = String(row.catalog_id || row.source_type || row.source || "").toLowerCase();
  const setId = String(row.set_id || row.series_id || row.code || "").trim();
  const q = String(row.name || row.title || row.dataset_name || query || setId || "").trim();
  if (cid && setId) {
    return `/search/catalog?q=${encodeURIComponent(q || setId)}&ai=1&catalog=${encodeURIComponent(cid)}`;
  }
  if (q) return `/search/catalog?q=${encodeURIComponent(q)}&ai=1`;
  return "/search/catalog";
}

export function catalogRowsToPickerItems(rows, query) {
  return rows.slice(0, 40).map((r, idx) => ({
    id: `cat:${r.catalog_id || "catalog"}:${r.set_id || idx}`,
    title: String(r.name || r.title || r.dataset_name || r.set_id || r.code || "Graf"),
    source_type: String(r.catalog_id || r.source_type || r.source || "catalog"),
    set_id: String(r.set_id || r.series_id || r.code || ""),
    link_url: catalogRowLinkUrl(r, query),
  }));
}

/** Klasické lexikální hledání v katalogu (POST /catalog/search). */
export async function searchCatalogPickerItemsClassic(query, options = {}) {
  const q = String(query || "").trim();
  if (q.length < 2) return [];
  const sources = resolveSearchSources(options.catalogId);
  const settled = await Promise.allSettled(
    sources.map((source) =>
      api.post("/catalog/search", { source, query: q, limit: sources.length === 1 ? 24 : 8 }, { timeout: 20000 })
    )
  );
  const rows = settled.flatMap((res, idx) => {
    if (res.status !== "fulfilled") return [];
    return quickCatalogSearchRowsForPicker(res.value?.data, sources[idx]);
  });
  return catalogRowsToPickerItems(rows, q);
}

/** Stejné hledání jako ve zprávách — deep-search (AI) + lexikální fallback. */
export async function searchCatalogPickerItems(query, options = {}) {
  if (options.mode === "classic") {
    return searchCatalogPickerItemsClassic(query, options);
  }
  const q = String(query || "").trim();
  if (q.length < 2) return [];
  const sources = resolveSearchSources(options.catalogId);

  let rows = [];
  try {
    const { data } = await api.post(
      "/catalog/deep-search",
      {
        q,
        query: q,
        sources,
        use_ai: true,
        mode: sources.length === 1 ? "single" : "multi",
        limit_per_source: sources.length === 1 ? 20 : 8,
      },
      { timeout: 20000 }
    );
    rows = deepSearchRowsForPicker(data);
  } catch {
    rows = [];
  }

  if (!rows.length) {
    return searchCatalogPickerItemsClassic(query, options);
  }

  return catalogRowsToPickerItems(rows, q);
}
