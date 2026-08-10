/**
 * FRED — lazy načtení podkategorií a řad po výběru kategorie ve sloupcích.
 * Miller: kořen → hlavní kategorie → podkategorie / řady (opakované expand).
 */

export const FRED_BROWSER_TIMEOUT_MS = 60_000;

/** Přepíše cesty z API (`FRED::32263`) na cestu ve stromu katalogu. */
export function rebaseFredExpandNodePaths(rootNode, newRootPath) {
  if (!rootNode || typeof rootNode !== "object" || !newRootPath) return rootNode;
  const oldRootPath = String(rootNode.path || "").trim();
  const rewritePath = (p) => {
    const cur = String(p || "").trim();
    if (!cur) return cur;
    if (oldRootPath && cur === oldRootPath) return newRootPath;
    if (oldRootPath && cur.startsWith(`${oldRootPath} > `)) {
      return `${newRootPath}${cur.slice(oldRootPath.length)}`;
    }
    return cur;
  };
  const walk = (node) => {
    if (!node || typeof node !== "object") return node;
    const nextChildren = Array.isArray(node.children) ? node.children.map((ch) => walk(ch)) : [];
    const nextSets = Array.isArray(node.sets)
      ? node.sets.map((s) => {
          if (!s || typeof s !== "object") return s;
          return {
            ...s,
            full_path: rewritePath(s.full_path),
          };
        })
      : [];
    return {
      ...node,
      path: rewritePath(node.path),
      children: nextChildren,
      sets: nextSets,
    };
  };
  return walk(rootNode);
}

/** @param {object} row */
export function resolveFredBrowseLazyAction(row) {
  const itemKind = String(row?.item_kind || row?.kind || "").trim();
  const catId = String(row?.fred_category_id || "").trim();
  if (itemKind === "category" && catId) {
    return { kind: "category", id: catId, path: String(row?.path || "").trim() };
  }
  const sid = String(row?.set_id || "").trim();
  if (/^CAT\|\|/i.test(sid)) {
    const id = sid.replace(/^CAT\|\|/i, "").trim();
    if (id) return { kind: "category", id, path: String(row?.path || "").trim() };
  }
  return null;
}
