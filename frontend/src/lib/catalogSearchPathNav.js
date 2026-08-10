/** Pomocné funkce pro navigaci z výsledku hledání do browse stromu katalogu. */

export function splitCatalogPath(rawPath) {
  return String(rawPath || "")
    .split(" > ")
    .map((p) => p.trim())
    .filter(Boolean);
}

export function buildCatalogPathPrefixes(rawPath) {
  const parts = splitCatalogPath(rawPath);
  return parts.map((_, index) => parts.slice(0, index + 1).join(" > "));
}

/**
 * Vrátí prefixy cesty, které existují jako složky (kind cat) ve stromu.
 * Poslední segment z full_path datasetu (název řady) se vynechá.
 */
export function resolveCatalogCategoryPathPrefixes(rawPath, categoryPaths) {
  const prefixes = buildCatalogPathPrefixes(rawPath);
  if (!prefixes.length) return [];

  const catSet =
    categoryPaths instanceof Set
      ? categoryPaths
      : new Set(
          (Array.isArray(categoryPaths) ? categoryPaths : [])
            .map((p) => String(p || "").trim())
            .filter(Boolean),
        );

  if (!catSet.size) return prefixes;

  let lastCatIdx = -1;
  for (let i = 0; i < prefixes.length; i += 1) {
    if (catSet.has(prefixes[i])) lastCatIdx = i;
  }
  if (lastCatIdx < 0) return [];
  return prefixes.slice(0, lastCatIdx + 1);
}

/**
 * Segmenty pro UI — klikatelné jen existující složky ve stromu.
 */
export function buildCatalogPathSegments(rawPath, categoryPaths) {
  const parts = splitCatalogPath(rawPath);
  const prefixes = buildCatalogPathPrefixes(rawPath);
  const catSet =
    categoryPaths instanceof Set
      ? categoryPaths
      : new Set(
          (Array.isArray(categoryPaths) ? categoryPaths : [])
            .map((p) => String(p || "").trim())
            .filter(Boolean),
        );

  return parts.map((label, index) => {
    const prefix = prefixes[index];
    const clickable = !catSet.size || catSet.has(prefix);
    return { label, prefix, clickable, isLast: index === parts.length - 1 };
  });
}
