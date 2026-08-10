import { browseCategoryCountNode } from "@/lib/catalogTree";

/** Normalizuje parentPath kořenových řádků stromu. */
export function rowParentPathKey(row) {
  if (row?.kind === "set") {
    return String(row.parentPath || "").trim();
  }
  const pp = typeof row?.parentPath === "string" ? row.parentPath.trim() : "";
  if (pp) return pp;
  const parts = String(row?.path || "")
    .split(" > ")
    .slice(0, -1);
  return parts.join(" > ");
}

/**
 * Miller sloupce pro katalogový explorer.
 * @param {Array} allRows plochý index stromu
 * @param {string[]} selectedPaths cesty vybraných položek po sloupcích
 * @param {Set|null} filteredPaths omezení při lokálním filtru
 * @param {number} maxColumns max. počet sloupců (responzivita)
 */
export function buildCatalogExplorerColumns(allRows, selectedPaths = [], filteredPaths = null, maxColumns = 4) {
  const rows = Array.isArray(allRows) ? allRows : [];
  const visible = filteredPaths
    ? rows.filter((r) => filteredPaths.has(r.path))
    : rows;

  const activeSelections = (Array.isArray(selectedPaths) ? selectedPaths : []).filter((p) =>
    String(p || "").trim(),
  );
  /** Po poslední vybrané složce vždy zobrazit ještě jeden sloupec s dětmi / řadami. */
  const loopLimit = Math.min(8, Math.max(maxColumns, activeSelections.length + 1));

  const columns = [];
  let parentKey = "";

  for (let col = 0; col < loopLimit; col += 1) {
    const selectedPath = selectedPaths[col] || null;
    const items = visible
      .filter((row) => rowParentPathKey(row) === parentKey)
      .sort((a, b) => {
        const aCat = explorerRowIsCategory(a) ? 0 : 1;
        const bCat = explorerRowIsCategory(b) ? 0 : 1;
        if (aCat !== bCat) return aCat - bCat;
        return String(a.name || "").localeCompare(String(b.name || ""), "cs");
      });

    columns.push({
      index: col,
      parentPath: parentKey,
      selectedPath,
      items,
    });

    if (!selectedPath) break;
    parentKey = selectedPath;
  }

  return columns;
}

export function explorerRowIsCategory(row) {
  const itemKind = String(row?.item_kind || row?.kind || "").trim();
  if (itemKind === "dataflow") return true;
  if (itemKind === "category") return true;
  if (row?.bis_lazy_country) return true;
  return row?.kind !== "set" && itemKind !== "set";
}

export function explorerRowCountLabel(row, isOpen) {
  return browseCategoryCountNode(row, isOpen);
}

export function formatExplorerBreadcrumb(path) {
  const p = String(path || "").trim();
  if (!p) return "";
  return p.split(" > ").filter(Boolean).join(" › ");
}

/**
 * Položky drobečkové navigace pro detail panel (katalog → složky → řada).
 */
export function buildExplorerBreadcrumbItems(
  selectedPaths = [],
  allRows = [],
  catalogLabel = "",
  seriesRow = null,
) {
  const rowByPath = new Map(
    (Array.isArray(allRows) ? allRows : []).map((row) => [String(row.path || ""), row]),
  );
  const items = [];

  if (catalogLabel) {
    items.push({ label: String(catalogLabel).trim(), path: null, kind: "catalog" });
  }

  for (const rawPath of Array.isArray(selectedPaths) ? selectedPaths : []) {
    const path = String(rawPath || "").trim();
    if (!path) continue;
    const row = rowByPath.get(path);
    items.push({
      label: String(row?.name || formatExplorerBreadcrumb(path)).trim() || path,
      path,
      kind: "folder",
    });
  }

  if (seriesRow) {
    const seriesLabel = String(seriesRow.name || seriesRow.set_id || "").trim();
    if (seriesLabel) {
      items.push({
        label: seriesLabel,
        path: String(seriesRow.path || "").trim() || null,
        kind: "series",
      });
    }
  }

  return items;
}
