/**
 * BIS Stats API — lazy načtení řad po výběru dataflow / země ve sloupcích.
 * Miller: kořen → písmeno → dataflow → země → řady.
 */

export const BIS_BROWSER_TIMEOUT_MS = 20_000;

/** Přepíše cesty z API (`BIS::flow > …`) na cestu ve stromu katalogu. */
export function rebaseBisSeriesNodePaths(rootNode, newRootPath) {
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

/**
 * @param {object} row
 * @param {number|null} columnIndex
 * @param {string} activeDataflowId načtený dataflow (pro lazy země)
 */
export function resolveBisBrowseLazyAction(row, columnIndex = null, activeDataflowId = "") {
  void columnIndex;
  const itemKind = String(row?.item_kind || row?.kind || "").trim();
  if (itemKind === "dataflow") {
    const id = String(row?.bis_dataflow || "").trim();
    if (!id) return null;
    return { kind: "dataflow", id, path: String(row?.path || "").trim() };
  }
  if (row?.bis_lazy_country) {
    const code = String(row?.ref_area || "").trim().toUpperCase();
    const flowId = String(activeDataflowId || row?.bis_dataflow || "").trim();
    if (!code || !flowId) return null;
    return { kind: "country", code, flowId, path: String(row?.path || "").trim() };
  }
  return null;
}
