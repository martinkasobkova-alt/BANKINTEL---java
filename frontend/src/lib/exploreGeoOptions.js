/** Abecední řazení zemí v Explore selectu (česká locale). */

export const EXPLORE_CONTINENT_LABELS = {
  aggregates: "Agregáty a regiony",
  europe: "Evropa",
  north_america: "Severní Amerika",
  south_america: "Jižní Amerika",
  asia: "Asie",
  africa: "Afrika",
  oceania: "Australie a Oceánie",
  other: "Ostatní",
};

export function normalizeExploreCountryOptionItems(items) {
  const raw = Array.isArray(items) ? items : [];
  const out = [];
  const seen = new Set();
  for (const item of raw) {
    const code = String(item?.code || "").trim().toUpperCase();
    const label_cs = String(item?.label_cs || item?.label || item?.code || "").trim();
    if (!code || seen.has(code)) continue;
    seen.add(code);
    out.push({
      code,
      label_cs,
      continent_id: String(item?.continent_id || "other").trim() || "other",
    });
  }
  return out;
}

export function sortExploreCountryOptions(items) {
  return [...normalizeExploreCountryOptionItems(items)].sort((a, b) => {
    const byLabel = String(a.label_cs || "").localeCompare(String(b.label_cs || ""), "cs", {
      sensitivity: "base",
    });
    if (byLabel !== 0) return byLabel;
    return String(a.code || "").localeCompare(String(b.code || ""), "cs", { sensitivity: "base" });
  });
}

/** Seskupí země podle kontinentu — kontinenty i země abecedně (cs). */
export function groupExploreCountryOptions(items) {
  const normalized = normalizeExploreCountryOptionItems(items);
  const buckets = new Map();
  for (const row of normalized) {
    const cid = String(row.continent_id || "other").trim() || "other";
    if (!buckets.has(cid)) buckets.set(cid, []);
    buckets.get(cid).push(row);
  }
  const groups = [...buckets.entries()].map(([id, countries]) => ({
    id,
    label_cs: EXPLORE_CONTINENT_LABELS[id] || id,
    countries: [...countries].sort((a, b) =>
      String(a.label_cs || "").localeCompare(String(b.label_cs || ""), "cs", { sensitivity: "base" }),
    ),
  }));
  groups.sort((a, b) =>
    String(a.label_cs || "").localeCompare(String(b.label_cs || ""), "cs", { sensitivity: "base" }),
  );
  return groups.filter((g) => g.countries.length);
}
