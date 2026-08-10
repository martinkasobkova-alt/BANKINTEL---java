/**
 * ČSÚ náhled vyžaduje `dataset_code` (kód sady) pro POST celé časové řady.
 * Bez něj backend spadne na GET výběr → typicky jen jedno období v grafu.
 */

export function enrichCsuCatalogRow(row, indexedRows = null) {
  if (!row || typeof row !== "object") return row;
  if (String(row?.dataset_code || "").trim()) return row;
  const sid = String(row?.set_id || "").trim();
  if (!sid) return row;

  const rows = Array.isArray(indexedRows) ? indexedRows : [];
  const match = rows.find((candidate) => {
    if (!candidate || typeof candidate !== "object") return false;
    const kind = String(candidate?.item_kind || candidate?.kind || "");
    if (kind === "cat") return false;
    return String(candidate?.set_id || "").trim() === sid && String(candidate?.dataset_code || "").trim();
  });
  if (!match) return row;

  return {
    ...row,
    dataset_code: match.dataset_code,
    dataset_name: row.dataset_name || match.dataset_name,
    period: row.period || match.period,
    territory: row.territory || match.territory,
  };
}
