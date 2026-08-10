export function getAradCatalogRescueNotice(treePayload) {
  const pl = treePayload && typeof treePayload === "object" ? treePayload : null;
  if (!pl) return null;
  const source = String(pl.source || "").toLowerCase();
  if (source !== "arad") return null;
  const rescue = String(pl.catalog_rescue || "").trim();
  const stale = Boolean(pl.stale);
  const hasCategories = Array.isArray(pl.categories) && pl.categories.length > 0;
  if (!hasCategories) return null;
  if (rescue === "bootstrap_file") {
    return "ARAD běží v omezeném záchranném režimu. Některé větve katalogu nemusí být dostupné.";
  }
  if (stale || rescue === "mongo_snapshot" || rescue === "memory") {
    return "ARAD katalog je zobrazen z poslední uložené verze, protože ČNB API je momentálně pomalé.";
  }
  return null;
}
