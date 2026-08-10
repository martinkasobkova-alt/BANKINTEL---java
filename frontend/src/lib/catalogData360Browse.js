/**
 * World Bank Data360 — lazy načtení ukazatelů po výběru země ve sloupcích.
 * Miller: 0 = kořen, 1 = písmeno, 2 = země, 3+ = témata/skupiny.
 */
export function resolveData360BrowseLazyAction(row, columnIndex = null) {
  const code = String(row?.data360_country || "").trim().toUpperCase();
  if (!code) return null;

  const depth = Number(row?.depth ?? -1);
  const col =
    columnIndex != null && Number.isFinite(Number(columnIndex))
      ? Number(columnIndex)
      : null;
  const country = String(row?.data360_country_name || row?.name || code).trim();

  if (row?.data360_country_lazy || depth === 2 || col === 2) {
    return { code, country };
  }

  return null;
}

/** Přepíše cesty v country_node z API na cestu ve stromu katalogu (včetně písmenné skupiny). */
export function rebaseData360CountryNodePaths(rootNode, newRootPath) {
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
    return {
      ...node,
      path: rewritePath(node.path),
      children: nextChildren,
    };
  };
  return walk(rootNode);
}
