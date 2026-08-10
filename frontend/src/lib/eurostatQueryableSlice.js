function hasValidCatalogNodeRef(row) {
  if (!row?.catalog_node_ref || typeof row.catalog_node_ref !== "object") return false;
  const ref = row.catalog_node_ref;
  const src = String(ref.source || "").trim().toLowerCase();
  const setId = String(ref.set_id || "").trim();
  const endpoint = String(ref.add_source_endpoint || "").trim();
  return src === "eurostat" && setId.length > 0 && endpoint === "/api/eurostat/catalog/add-source";
}

/**
 * @param {{ sourceType?: string }} def
 * @param {{ fromDeepAi?: boolean, catalog_node_ref?: Record<string, unknown> }} row
 */
export function eurostatAiRowNeedsOpenInCatalog(def, row) {
  if (def?.sourceType !== "eurostat" || !row?.fromDeepAi) return false;
  return !hasValidCatalogNodeRef(row);
}
