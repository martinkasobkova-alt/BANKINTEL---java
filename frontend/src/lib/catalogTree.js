/**
 * Sjednocený strom katalogů (categories → children + sets) a vyhledávání.
 * - Více slov oddělených mezerou = všechna musí sedět (AND).
 * - Prohledává se celá cesta (složky), název, kódy a ostatní pole řady.
 */

export const MAX_CATALOG_FILTER_ROWS = 2500;

/** Pole z flatten řádku kategorie — nesmí přepsat strukturu stromu z uzlu. */
const CATEGORY_META_SKIP = new Set([
  "children",
  "sets",
  "path",
  "name",
  "depth",
  "parentPath",
  "kind",
  "count",
  "imf_browse_count",
]);

function categoryRowCount(node) {
  const n = node || {};
  const preset = n.imf_browse_count;
  if (preset !== undefined && preset !== null && preset !== "") {
    const num = Number(preset);
    if (Number.isFinite(num)) return Math.max(0, Math.floor(num));
  }
  return (n.children?.length || 0) + (n.sets?.length || 0);
}

/**
 * Počet u složky ve stromu — u lazy katalogů je před rozbalením často 0 (ještě nenačteno).
 * Vrátí null = nic nezobrazovat; číslo až po rozbalení nebo když je > 0.
 */
export function formatBrowseCategoryCount(row, isOpen) {
  const n = Number(row?.count);
  if (!Number.isFinite(n)) return null;
  if (n > 0) return n;
  if (isOpen) return n;
  return null;
}

/** JSX-friendly: null když se počet nemá zobrazit. */
export function browseCategoryCountNode(row, isOpen) {
  const n = formatBrowseCategoryCount(row, isOpen);
  return n === null ? null : n;
}

function normalizeSearchText(value) {
  const raw = String(value || "");
  return raw
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

/** Rozparsuje dotaz na klíčová slova (malá písmena). */
export function parseSearchKeywords(raw) {
  return normalizeSearchText(raw)
    .trim()
    .split(/\s+/)
    .filter(Boolean);
}

function setRowHaystack(row, opts = {}) {
  if (row.kind !== "set") return "";
  if (opts.nameFieldsOnly) {
    return normalizeSearchText(`${row.name || ""} ${row.full_path || ""}`);
  }
  const skip = new Set(["kind", "children"]);
  const parts = [];
  for (const [k, v] of Object.entries(row)) {
    if (skip.has(k)) continue;
    if (v == null) continue;
    if (typeof v === "object" && !(v instanceof Date)) continue;
    parts.push(String(v));
  }
  return normalizeSearchText(parts.join(" "));
}

export function rowMatchesAllKeywords(row, keywords, opts = {}) {
  if (!keywords.length || row.kind !== "set") return false;
  const h = setRowHaystack(row, opts);
  return keywords.every((kw) => h.includes(kw));
}

function categoryMatchesAllKeywords(row, keywords) {
  if (!keywords.length || row.kind !== "cat") return false;
  const h = normalizeSearchText(`${row.path} ${row.name}`);
  return keywords.every((kw) => h.includes(kw));
}

function isSetUnderPath(setRow, catPath) {
  const p = setRow.parentPath || "";
  return p === catPath || p.startsWith(`${catPath} > `);
}

function addAncestorPaths(visible, startPath, rowIndex) {
  let parent = startPath;
  while (parent) {
    visible.add(parent);
    const idx = rowIndex.get(parent);
    if (idx === undefined) break;
    const segments = parent.split(" > ");
    if (segments.length <= 1) break;
    segments.pop();
    parent = segments.join(" > ");
  }
}

/**
 * Vrátí množinu path (řádků), které mají být při filtru vidět, nebo null = bez filtru.
 */
export function buildFilteredPaths(allRows, rowIndex, keywords, opts = {}) {
  if (!keywords.length) return null;
  const visible = new Set();

  for (const r of allRows) {
    if (r.kind !== "set") continue;
    if (!rowMatchesAllKeywords(r, keywords, opts)) continue;
    visible.add(r.path);
    addAncestorPaths(visible, r.parentPath, rowIndex);
  }

  for (const r of allRows) {
    if (r.kind !== "cat") continue;
    if (!categoryMatchesAllKeywords(r, keywords)) continue;
    visible.add(r.path);
    for (const s of allRows) {
      if (s.kind !== "set" || !isSetUnderPath(s, r.path)) continue;
      visible.add(s.path);
      addAncestorPaths(visible, s.parentPath, rowIndex);
    }
  }

  return visible;
}

/** Všechny cesty ke složkám (cat) — pro výchozí rozbalení celého stromu. */
export function allCategoryPathsFromTree(categories) {
  const paths = new Set();
  const walk = (nodes) => {
    for (const n of nodes || []) {
      if (n.path) paths.add(n.path);
      walk(n.children || []);
    }
  };
  walk(categories || []);
  return paths;
}

/**
 * Výchozí rozbalení — u BIS ``countries_lazy`` nechá země sbalené (bez řad v skeletonu).
 * @param {object[]} categories
 * @param {{ catalogMode?: string }} [opts]
 */
export function defaultOpenPathsFromTree(categories, opts = {}) {
  const collapseLazyCountries = opts.catalogMode === "countries_lazy";
  if (!collapseLazyCountries) {
    return allCategoryPathsFromTree(categories);
  }
  const paths = new Set();
  const walk = (nodes) => {
    for (const n of nodes || []) {
      if (n.path && !n.bis_lazy_country) paths.add(n.path);
      walk(n.children || []);
    }
  };
  walk(categories || []);
  return paths;
}

/**
 * Ověří, že všechny předkové řetězec `parentPath` (prefixy po úsecích `" > "`) jsou v `openPaths`.
 * Shodná logika dříve inline u řádků set — použito i pro uzly složek (kind cat) s polem parentPath.
 */
export function browseAncestorsOpen(openPaths, parentPath) {
  if (!parentPath) return true;
  const acc = [];
  for (const seg of parentPath.split(" > ")) {
    acc.push(seg);
    if (!openPaths.has(acc.join(" > "))) return false;
  }
  return true;
}

export function flattenCatalogCategories(categories) {
  const out = [];
  const categoryMeta = (node) => {
    const meta = {};
    for (const [k, v] of Object.entries(node || {})) {
      if (CATEGORY_META_SKIP.has(k)) continue;
      meta[k] = v;
    }
    return meta;
  };
  /** @param parentPathStr plná cesta nadřazené složky ("" pro kořen stromu) */
  const walk = (nodes, depth, parentPathStr = "") => {
    for (const n of nodes || []) {
      out.push({
        kind: "cat",
        path: n.path,
        name: n.name,
        parentPath: parentPathStr || "",
        depth,
        count: categoryRowCount(n),
        ...categoryMeta(n),
      });
      const startIdx = out.length - 1;
      const childPaths = [];
      for (const c of n.children || []) childPaths.push(c.path);
      const setRows = [];
      for (const s of n.sets || []) {
        const rowPath = `${n.path}::${s.set_id}`;
        setRows.push({
          ...s,
          item_kind: s.kind || "selection",
          kind: "set",
          path: rowPath,
          parentPath: n.path,
          depth: depth + 1,
        });
      }
      out[startIdx].children = [...childPaths, ...setRows.map((r) => r.path)];
      walk(n.children || [], depth + 1, n.path);
      for (const r of setRows) out.push(r);
    }
  };
  walk(categories, 0);
  return out;
}

/** Odhad práce: počet uzlů kategorie + počet řad (setů) — pro rozhodnutí sync vs. async. */
export function estimateCatalogWorkload(categories) {
  let n = 0;
  const walk = (nodes) => {
    for (const c of nodes || []) {
      n += 1 + (c.sets?.length || 0);
      walk(c.children || []);
    }
  };
  walk(categories || []);
  return n;
}

const DEFAULT_ASYNC_YIELD_EVERY = 850;

/**
 * Stejné výstupy jako `flattenCatalogCategories`, ale mezi dávkami předá řízení prohlížeči
 * (`setTimeout(0)`), aby UI u velkých katalogů (IMF, OECD, …) nezamrzlo na jednom snímku.
 */
export async function flattenCatalogCategoriesAsync(
  categories,
  yieldEvery = DEFAULT_ASYNC_YIELD_EVERY
) {
  const out = [];
  const categoryMeta = (node) => {
    const meta = {};
    for (const [k, v] of Object.entries(node || {})) {
      if (CATEGORY_META_SKIP.has(k)) continue;
      meta[k] = v;
    }
    return meta;
  };
  let untilYield = yieldEvery;

  const yieldToBrowser = () =>
    new Promise((resolve) => {
      setTimeout(resolve, 0);
    });

  const maybeYield = async () => {
    untilYield -= 1;
    if (untilYield <= 0) {
      untilYield = yieldEvery;
      await yieldToBrowser();
    }
  };

  /** @param parentPathStr plná cesta nadřazené složky */
  const walk = async (nodes, depth, parentPathStr = "") => {
    for (const n of nodes || []) {
      await maybeYield();
      out.push({
        kind: "cat",
        path: n.path,
        name: n.name,
        parentPath: parentPathStr || "",
        depth,
        count: categoryRowCount(n),
        ...categoryMeta(n),
      });
      const startIdx = out.length - 1;
      const childPaths = [];
      for (const c of n.children || []) childPaths.push(c.path);
      const setRows = [];
      for (const s of n.sets || []) {
        await maybeYield();
        const rowPath = `${n.path}::${s.set_id}`;
        setRows.push({
          ...s,
          item_kind: s.kind || "selection",
          kind: "set",
          path: rowPath,
          parentPath: n.path,
          depth: depth + 1,
        });
      }
      out[startIdx].children = [...childPaths, ...setRows.map((r) => r.path)];
      await walk(n.children || [], depth + 1, n.path);
      for (const r of setRows) out.push(r);
    }
  };

  await walk(categories || [], 0);
  return out;
}

/** Nad ~3k uzlů+řad rozloží flatten asynchronně — pod limit jeden synchronní průchod. */
export async function flattenCatalogCategoriesBestEffort(
  categories,
  syncMaxWorkload = 3200
) {
  const w = estimateCatalogWorkload(categories);
  if (w <= syncMaxWorkload) {
    return flattenCatalogCategories(categories);
  }
  return flattenCatalogCategoriesAsync(categories);
}

export function buildPathIndex(rows) {
  const byPath = new Map();
  rows.forEach((r, i) => byPath.set(r.path, i));
  return byPath;
}

function rowBelongsToCountryBranch(row, countryPath) {
  const p = String(row?.path || "");
  const pp = String(row?.parentPath || "");
  if (p === countryPath) return true;
  if (p.startsWith(`${countryPath} > `)) return true;
  if (p.startsWith(`${countryPath}::`)) return true;
  if (pp === countryPath || pp.startsWith(`${countryPath} > `)) return true;
  return false;
}

/**
 * Doplní zploštěné řádky jen pro jednu zemi (ECB country-first lazy) — bez přepočtu celého stromu.
 */
export function patchBrowseRowsForLazyCountry(existingRows, countryNode, lazyCountryRow) {
  const countryPath = String(lazyCountryRow?.path || countryNode?.path || "").trim();
  if (!countryPath) return Array.isArray(existingRows) ? existingRows : [];

  const offset = Number(lazyCountryRow?.depth ?? 1);
  const parentOfCountry = String(lazyCountryRow?.parentPath ?? "");

  const rows = Array.isArray(existingRows) ? existingRows : [];
  const countryIdx = rows.findIndex((r) => r.path === countryPath);

  const {
    depth: _lazyDepth,
    parentPath: _lazyParent,
    kind: _lazyKind,
    count: _lazyCount,
    children: _lazyChildren,
    ...lazyMeta
  } = lazyCountryRow || {};

  const displayName = String(lazyCountryRow?.name || countryNode?.name || "").trim();

  const branchRoot = {
    ...lazyMeta,
    ...countryNode,
    path: countryPath,
    name: displayName,
    children: Array.isArray(countryNode?.children) ? countryNode.children : [],
    sets: Array.isArray(countryNode?.sets) ? countryNode.sets : [],
  };

  const branchRows = flattenCatalogCategories([branchRoot]);
  const patched = branchRows.map((r) => {
    const isCountryRoot = r.depth === 0;
    const isDirectChildOfCountry = !isCountryRoot && r.depth === 1;
    const nextDepth = isCountryRoot ? offset : r.depth + offset;
    const nextParent = isCountryRoot
      ? parentOfCountry
      : isDirectChildOfCountry
        ? countryPath
        : r.parentPath;
    if (!isCountryRoot) {
      return { ...r, depth: nextDepth, parentPath: nextParent };
    }
    return {
      ...r,
      ...lazyMeta,
      kind: "cat",
      path: countryPath,
      name: displayName,
      depth: nextDepth,
      parentPath: nextParent,
      count: (branchRoot.children?.length || 0) + (branchRoot.sets?.length || 0),
      imf_country: lazyCountryRow?.imf_country ?? countryNode?.imf_country,
      ecb_country: lazyCountryRow?.ecb_country ?? countryNode?.ecb_country,
      ecb2_country: lazyCountryRow?.ecb2_country ?? countryNode?.ecb2_country,
      bis_lazy_country: lazyCountryRow?.bis_lazy_country ?? countryNode?.bis_lazy_country,
      data360_country: lazyCountryRow?.data360_country ?? countryNode?.data360_country,
      wb_country: lazyCountryRow?.wb_country ?? countryNode?.wb_country,
    };
  });

  if (countryIdx < 0) {
    return [...rows, ...patched];
  }

  const before = rows.slice(0, countryIdx);
  const after = rows
    .slice(countryIdx + 1)
    .filter((r) => !rowBelongsToCountryBranch(r, countryPath));
  return [...before, ...patched, ...after];
}
