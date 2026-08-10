/**
 * 12-sloupcový CSS Grid (viz `.widget-canvas-grid` v index.css).
 * Dva řádky × 200px = 400px — čtvrtina (row-span 2) má stejnou výšku jako dva osminy (2× row-span 1).
 *
 * @typedef {{ colSpan: number, rowSpan: number }} WidgetGridSpan
 */

/** Výchozí mapování `width` z API → colSpan / rowSpan (12 sloupců, auto-rows 200px na xl). */
export const GRID_BY_WIDTH = {
  full: { colSpan: 12, rowSpan: 2 },
  "three-quarters": { colSpan: 9, rowSpan: 2 },
  "two-thirds": { colSpan: 8, rowSpan: 2 },
  half: { colSpan: 6, rowSpan: 2 },
  third: { colSpan: 4, rowSpan: 2 },
  quarter: { colSpan: 3, rowSpan: 2 },
  /** rowSpan 2 — v jednom řádku (~132px) se nevejde hlavička + minigraf. */
  sixth: { colSpan: 4, rowSpan: 2 },
  eighth: { colSpan: 3, rowSpan: 2 },
};

/** Widget šířky vhodné pro max. 2 sloupce na úzkých viewportech (<1280px). */
export const MOBILE_COMPACT_WIDTHS = new Set(["eighth", "sixth", "quarter", "third"]);

const FORCE_FULL_MOBILE_TYPES = new Set([
  "arad_view",
  "computed_view",
  "chart_net_result",
  "chart_dataset_distribution",
  "table_recent_syncs",
  "dataset_chart",
  "formula_chart",
  "rss_monitoring",
]);

const EXTERNAL_DATASET_TYPES = new Set([
  "dataset_view",
  "eurostat_view",
  "csu_view",
  "ecb_view",
  "fred_view",
  "alphavantage_view",
  "worldbank_view",
  "world_bank_data360_view",
  "bis_view",
  "imf_view",
  "oecd_view",
  "external_catalog_view",
]);

/**
 * Pod max-width 1280px používá `.widget-canvas-grid` max. dva flexibilní sloupce.
 * - `full`: přes celou šířku (grafy, tabulky, široké karty).
 * - `compact`: jedna ze dvou „polovičních“ buněk (typicky malé KPI nebo užší reklama).
 *
 * Pod 421px přepínač v CSS vyžádá jednosloupcové skládání.
 */
export function getDashboardMobileSpan(widget) {
  if (!widget || typeof widget !== "object") return "full";

  const type = widget.type || "";
  if (
    FORCE_FULL_MOBILE_TYPES.has(type) ||
    EXTERNAL_DATASET_TYPES.has(type) ||
    type === "dataset_table" ||
    type === "markdown"
  ) {
    return "full";
  }

  const widthKey = typeof widget.width === "string" ? widget.width : "";
  if (!MOBILE_COMPACT_WIDTHS.has(widthKey)) return "full";
  if (type.startsWith("kpi_")) return "compact";
  if (type === "ad") return "compact";
  return "full";
}

/**
 * Vrátí colSpan + rowSpan pro widget.
 * `width` z API/admin výběru má přednost — jinak zůstaly staré `config.colSpan` po změně šířky
 * a buňka zůstávala úzká i při „2/3“ nebo „1/2“.
 * Výška se může vrátit i ze starších uložených layoutů v `config`, proto ji
 * bereme jako override i při platném `width`.
 */
export function getWidgetGridPlacement(widget) {
  if (!widget || typeof widget !== "object") {
    return { ...GRID_BY_WIDTH.full };
  }
  const key = widget.width;
  if (key && GRID_BY_WIDTH[key]) {
    const base = { ...GRID_BY_WIDTH[key] };
    const overrideRows = getRowSpanOverride(widget);
    if (overrideRows != null) {
      base.rowSpan = Math.min(Math.max(overrideRows, 1), 10);
    }
    return base;
  }
  const fromTop = pickSpan(widget.colSpan, widget.rowSpan);
  if (fromTop) return fromTop;
  const cfg = widget.config;
  if (cfg && typeof cfg === "object") {
    const fromCfg = pickSpan(cfg.colSpan, cfg.rowSpan);
    if (fromCfg) return clampSpan(fromCfg);
  }
  return { ...GRID_BY_WIDTH.full };
}

function getRowSpanOverride(widget) {
  const fromTop = toPositiveInt(widget.rowSpan ?? widget.row_span);
  if (fromTop != null) return fromTop;
  const cfg = widget.config;
  if (cfg && typeof cfg === "object") {
    const fromCfg = toPositiveInt(cfg.rowSpan ?? cfg.row_span);
    if (fromCfg != null) return fromCfg;
    const rect = readWidgetGridRect(widget);
    if (rect) return rect.rowEnd - rect.rowStart;
  }
  return null;
}

function pickSpan(cs, rs) {
  const colSpan = toPositiveInt(cs);
  const rowSpan = toPositiveInt(rs);
  if (colSpan != null && rowSpan != null) {
    return clampSpan({ colSpan, rowSpan });
  }
  return null;
}

function toPositiveInt(v) {
  if (v == null || v === "") return null;
  const n = typeof v === "number" ? v : parseInt(String(v), 10);
  if (!Number.isFinite(n) || n < 1) return null;
  return n;
}

function clampSpan({ colSpan, rowSpan }) {
  return {
    colSpan: Math.min(12, colSpan),
    rowSpan: Math.max(1, rowSpan),
  };
}

/** Widgety se stejnou reálnou šířkou v gridu lze skládat nad/pod sebe. */
export function canVerticalStackPair(dragWidget, targetWidget) {
  if (!dragWidget || !targetWidget) return false;
  const a = getWidgetGridPlacement(dragWidget);
  const b = getWidgetGridPlacement(targetWidget);
  return a.colSpan === b.colSpan && a.colSpan < 12;
}

/**
 * @param {number} relX 0..1 (client vůči šířce buňky)
 * @param {number} relY 0..1
 * @returns {"swap"|"before"|"after"|"above"|"below"}
 */
export function resolveDashboardDropZone(dragWidget, targetWidget, relX, relY) {
  const rx = Math.min(1, Math.max(0, relX));
  const ry = Math.min(1, Math.max(0, relY));
  // Střed buňky: přímá výměna pozic se cílem (bez přeskakování okolních widgetů).
  const cx0 = 0.36;
  const cx1 = 0.64;
  const cy0 = 0.36;
  const cy1 = 0.64;
  if (rx >= cx0 && rx <= cx1 && ry >= cy0 && ry <= cy1) {
    return "swap";
  }
  if (ry < 0.32) return "above";
  if (ry > 0.68) return "below";
  return rx < 0.5 ? "before" : "after";
}

/** Nové pořadí ID po dropu: above/before = před cíl, below/after = za cíl (po odstranění taženého). */
export function applyReorderWithDropZone(orderedIds, dragId, targetId, zone) {
  if (!dragId || !targetId || dragId === targetId) return orderedIds.slice();
  if (zone === "swap") {
    const next = orderedIds.slice();
    const i = next.indexOf(dragId);
    const j = next.indexOf(targetId);
    if (i < 0 || j < 0) return orderedIds.slice();
    next[i] = targetId;
    next[j] = dragId;
    return next;
  }
  const next = orderedIds.filter((id) => id !== dragId);
  let insertAt = next.indexOf(targetId);
  if (insertAt < 0) return orderedIds.slice();
  const insertAfter = zone === "after" || zone === "below";
  if (insertAfter) insertAt += 1;
  next.splice(insertAt, 0, dragId);
  return next;
}

/**
 * Sloučí dvojice 1/8 nebo 1/6 oddělené jedním širším widgetem (např. … eighth, quarter, eighth → eighth, eighth, quarter),
 * aby šly v mřížce skládat pod sebou.
 */
export function normalizeAdjacentHalfHeightStacks(orderedIds, getWidget) {
  const out = orderedIds.slice();
  let changed = true;
  while (changed) {
    changed = false;
    for (let i = 0; i < out.length - 2; i++) {
      const a = getWidget(out[i]);
      const mid = getWidget(out[i + 1]);
      const c = getWidget(out[i + 2]);
      if (!a || !mid || !c) continue;
      const pair =
        (a.width === "eighth" && c.width === "eighth") ||
        (a.width === "sixth" && c.width === "sixth");
      if (!pair || mid.width === a.width) continue;
      const midId = out[i + 1];
      const cId = out[i + 2];
      out.splice(i + 1, 2, cId, midId);
      changed = true;
      break;
    }
  }
  return out;
}

// --- Explicit CSS grid lines (1-based), persisted in `widget.config` (snake_case API) ---

/**
 * @typedef {{ colStart: number, colEnd: number, rowStart: number, rowEnd: number }} GridRect
 */

function toGridInt(v) {
  if (v == null || v === "") return null;
  const n = typeof v === "number" ? v : parseInt(String(v), 10);
  if (!Number.isFinite(n)) return null;
  return n;
}

/** Přečte uložený obdélník z configu; `colEnd`/`rowEnd` jsou CSS grid lines (konec je vylučující). */
export function readWidgetGridRect(widget) {
  const c = widget?.config;
  if (!c || typeof c !== "object") return null;
  const cs = toGridInt(c.grid_column_start);
  const ce = toGridInt(c.grid_column_end);
  const rs = toGridInt(c.grid_row_start);
  const re = toGridInt(c.grid_row_end);
  if (cs == null || ce == null || rs == null || re == null) return null;
  if (ce <= cs || re <= rs) return null;
  return { colStart: cs, colEnd: ce, rowStart: rs, rowEnd: re };
}

export function gridRectToConfig(rect) {
  return {
    grid_column_start: rect.colStart,
    grid_column_end: rect.colEnd,
    grid_row_start: rect.rowStart,
    grid_row_end: rect.rowEnd,
  };
}

function rectsOverlap(a, b) {
  if (a.colEnd <= b.colStart || b.colEnd <= a.colStart) return false;
  if (a.rowEnd <= b.rowStart || b.rowEnd <= a.rowStart) return false;
  return true;
}

function cloneLayoutMap(m) {
  const n = new Map();
  for (const [k, v] of m) {
    n.set(k, { ...v });
  }
  return n;
}

/** Výměna uložených grid obdélníků mezi dvěma widgety. */
function applySwapPlacements(layoutMap, dragId, targetId) {
  const D = layoutMap.get(dragId);
  const T = layoutMap.get(targetId);
  if (!D || !T) return false;
  layoutMap.set(dragId, { ...T });
  layoutMap.set(targetId, { ...D });
  return true;
}

/**
 * Mapa pozic pro vykreslení. Uložené `grid_*` z DnD držíme; když se změní šířka widgetu
 * a rozměr obdélníku neodpovídá `width`, **necháme kotvu** (levý horní roh) a jen změníme
 * span — ne „orphan“ přes celou stránku, aby nevznikaly obří svislé mezery.
 * Widgety bez mřížky doplní packOrphansAvoiding; nakonec vertikální kompakce vyplní díry v řádcích.
 */
export function getEffectiveLayoutMap(widgetsOrdered) {
  const map = new Map();
  const orphans = [];
  for (const w of widgetsOrdered) {
    const r = readWidgetGridRect(w);
    const { colSpan, rowSpan } = getWidgetGridPlacement(w);
    if (r) {
      const wCols = r.colEnd - r.colStart;
      const wRows = r.rowEnd - r.rowStart;
      if (wCols === colSpan && wRows === rowSpan) {
        map.set(w.id, { ...r });
      } else {
        map.set(w.id, resizeGridRectToSpan(r, colSpan, rowSpan));
      }
    } else {
      orphans.push(w);
    }
  }
  if (orphans.length > 0) {
    packOrphansAvoiding(map, orphans);
  }
  if (layoutMapHasOverlap(map)) {
    const fresh = autoPackWidgets(widgetsOrdered);
    compactVerticalGapsInPlace(fresh);
    return fresh;
  }
  compactVerticalGapsInPlace(map);
  return map;
}

/** Zachová colStart/rowStart, upraví šířku/výšku; při přetečení vpravo posune vlevo. */
function resizeGridRectToSpan(r, colSpan, rowSpan) {
  let colStart = r.colStart;
  let colEnd = colStart + colSpan;
  if (colEnd > 13) {
    colEnd = 13;
    colStart = Math.max(1, colEnd - colSpan);
  }
  const rowStart = r.rowStart;
  const rowEnd = rowStart + rowSpan;
  return { colStart, colEnd, rowStart, rowEnd };
}

function layoutMapHasOverlap(map) {
  const rects = [...map.values()];
  for (let i = 0; i < rects.length; i++) {
    for (let j = i + 1; j < rects.length; j++) {
      if (rectsOverlap(rects[i], rects[j])) return true;
    }
  }
  return false;
}

/**
 * Posune widgety o jeden řádek nahoru, dokud to jde bez kolizí — zacelí díry po DnD / změně šířky.
 */
function compactVerticalGapsInPlace(map) {
  let changed = true;
  let safety = 0;
  while (changed && safety < 400) {
    safety += 1;
    changed = false;
    const entries = [...map.entries()].sort(
      (a, b) => a[1].rowStart - b[1].rowStart || a[1].colStart - b[1].colStart
    );
    for (const [id, r] of entries) {
      if (r.rowStart <= 1) continue;
      const trial = {
        colStart: r.colStart,
        colEnd: r.colEnd,
        rowStart: r.rowStart - 1,
        rowEnd: r.rowEnd - 1,
      };
      let ok = true;
      for (const [id2, r2] of map) {
        if (id2 === id) continue;
        if (rectsOverlap(trial, r2)) {
          ok = false;
          break;
        }
      }
      if (ok) {
        r.rowStart = trial.rowStart;
        r.rowEnd = trial.rowEnd;
        changed = true;
      }
    }
  }
}

/** Doplň widgety bez uložené mřížky — jen volné sloty, existující obdélníky jsou překážky. */
function packOrphansAvoiding(layoutMap, orphanWidgets) {
  const obstacles = [...layoutMap.values()];

  function hits(cand) {
    for (const p of obstacles) {
      if (rectsOverlap(p, cand)) return true;
    }
    return false;
  }

  for (const w of orphanWidgets) {
    const { colSpan, rowSpan } = getWidgetGridPlacement(w);
    let found = false;
    for (let r0 = 1; !found && r0 < 500; r0++) {
      for (let c0 = 1; c0 <= 12 - colSpan + 1; c0++) {
        const cand = {
          colStart: c0,
          colEnd: c0 + colSpan,
          rowStart: r0,
          rowEnd: r0 + rowSpan,
        };
        if (!hits(cand)) {
          obstacles.push(cand);
          layoutMap.set(w.id, cand);
          found = true;
          break;
        }
      }
    }
  }
}

/**
 * Řádkové vyplnění 12 sloupců (shodné s min-width xl mřížkou).
 * @param {object[]} widgetsOrdered
 * @returns {Map<string, GridRect>}
 */
export function autoPackWidgets(widgetsOrdered) {
  const map = new Map();
  const placed = [];

  function hits(cand) {
    for (const p of placed) {
      if (rectsOverlap(p, cand)) return true;
    }
    return false;
  }

  for (const w of widgetsOrdered) {
    const { colSpan, rowSpan } = getWidgetGridPlacement(w);
    let found = false;
    for (let r0 = 1; !found && r0 < 500; r0++) {
      for (let c0 = 1; c0 <= 12 - colSpan + 1; c0++) {
        const colEnd = c0 + colSpan;
        const rowEnd = r0 + rowSpan;
        const cand = {
          colStart: c0,
          colEnd,
          rowStart: r0,
          rowEnd,
        };
        if (!hits(cand)) {
          placed.push(cand);
          map.set(w.id, cand);
          found = true;
          break;
        }
      }
    }
  }
  return map;
}

/** Stejný sloupec (stejné čáry) — aby pod sebou šly jen 1/8+1/8 nebo 1/6+1/6, ne celá šířka. */
function sameColumnBand(a, b) {
  return a.colStart === b.colStart && a.colEnd === b.colEnd;
}

/** Překryv v ose X (grid sloupce) — stačí částečný, ne nutně stejný band. */
function columnBandsOverlap(a, b) {
  return !(a.colEnd <= b.colStart || b.colEnd <= a.colStart);
}

function vacateWidgetAndShiftUp(layoutMap, dragId) {
  const old = layoutMap.get(dragId);
  if (!old) return;
  const span = old.rowEnd - old.rowStart;
  layoutMap.delete(dragId);
  for (const [, r] of layoutMap) {
    if (sameColumnBand(r, old) && r.rowStart >= old.rowEnd) {
      r.rowStart -= span;
      r.rowEnd -= span;
    }
  }
}

function stackColumnForTarget(targetRect, dragColSpan) {
  let colStart = targetRect.colStart;
  let colEnd = colStart + dragColSpan;
  if (colEnd > 13) {
    colEnd = 13;
    colStart = Math.max(1, colEnd - dragColSpan);
  }
  return { colStart, colEnd };
}

function applyStackBelow(layoutMap, dragId, targetId, dragColSpan, dragRowSpan) {
  const T = layoutMap.get(targetId);
  if (!T) return;
  const ins = T.rowEnd;
  const cols = stackColumnForTarget(T, dragColSpan);
  const cand = {
    ...cols,
    rowStart: ins,
    rowEnd: ins + dragRowSpan,
  };
  for (const [id, r] of layoutMap) {
    if (id === dragId) continue;
    if (columnBandsOverlap(r, cand) && r.rowStart >= ins) {
      r.rowStart += dragRowSpan;
      r.rowEnd += dragRowSpan;
    }
  }
  layoutMap.set(dragId, cand);
}

function applyStackAbove(layoutMap, dragId, targetId, dragColSpan, dragRowSpan) {
  const T = layoutMap.get(targetId);
  if (!T) return;
  const ins = T.rowStart;
  const cols = stackColumnForTarget(T, dragColSpan);
  const cand = {
    ...cols,
    rowStart: ins,
    rowEnd: ins + dragRowSpan,
  };
  for (const [id, r] of layoutMap) {
    if (id === dragId) continue;
    if (columnBandsOverlap(r, cand) && r.rowStart >= ins) {
      r.rowStart += dragRowSpan;
      r.rowEnd += dragRowSpan;
    }
  }
  layoutMap.set(dragId, cand);
}

/** Horizontální drop: tažený zarovnat k cíli (before = vlevo, after = vpravo), stejný řádek od shora jako cíl. */
function applyHorizontalPlacement(layoutMap, dragId, targetId, zone, getWidget) {
  const T = layoutMap.get(targetId);
  const w = getWidget(dragId);
  if (!T || !w) return;
  const { colSpan, rowSpan } = getWidgetGridPlacement(w);
  const rowStart = T.rowStart;
  const rowEnd = rowStart + rowSpan;
  const canFitNaturally =
    zone === "before" ? T.colStart - colSpan >= 1 : T.colEnd + colSpan <= 13;
  if (!canFitNaturally) {
    const halfSpan = 6;
    const targetRowSpan = Math.max(1, T.rowEnd - T.rowStart);
    const dragRect =
      zone === "before"
        ? { colStart: 1, colEnd: 1 + halfSpan }
        : { colStart: 13 - halfSpan, colEnd: 13 };
    const targetRect =
      zone === "before"
        ? { colStart: 13 - halfSpan, colEnd: 13 }
        : { colStart: 1, colEnd: 1 + halfSpan };
    layoutMap.set(dragId, {
      ...dragRect,
      rowStart,
      rowEnd,
    });
    layoutMap.set(targetId, {
      ...targetRect,
      rowStart: T.rowStart,
      rowEnd: T.rowStart + targetRowSpan,
    });
    return;
  }
  let colStart;
  let colEnd;
  if (zone === "before") {
    colEnd = T.colStart;
    colStart = colEnd - colSpan;
    if (colStart < 1) {
      colStart = 1;
      colEnd = colStart + colSpan;
    }
  } else {
    colStart = T.colEnd;
    colEnd = colStart + colSpan;
    if (colEnd > 13) {
      colEnd = 13;
      colStart = colEnd - colSpan;
    }
  }
  layoutMap.set(dragId, {
    colStart,
    colEnd,
    rowStart,
    rowEnd,
  });
}

function overlapsOthers(layoutMap, excludeId, cand) {
  for (const [id, r] of layoutMap) {
    if (id === excludeId) continue;
    if (rectsOverlap(r, cand)) return true;
  }
  return false;
}

/**
 * Přesune ostatní widgety, které kolidují s preferovaným — drží `preferredId` na místě.
 * Nejdřív zkouší řádek pod preferovaným, pak posun vpravo / zalamování za okraj mřížky.
 */
function nudgeNonPreferredOutOfWay(layoutMap, preferredId) {
  const maxIter = 100;
  for (let it = 0; it < maxIter; it++) {
    const pref = layoutMap.get(preferredId);
    if (!pref) return;
    let moved = false;
    for (const [id, r] of layoutMap) {
      if (id === preferredId) continue;
      if (!rectsOverlap(r, pref)) continue;
      const rowSpan = r.rowEnd - r.rowStart;
      const colSpan = r.colEnd - r.colStart;
      const candBelow = {
        colStart: r.colStart,
        colEnd: r.colEnd,
        rowStart: pref.rowEnd,
        rowEnd: pref.rowEnd + rowSpan,
      };
      if (!overlapsOthers(layoutMap, id, candBelow)) {
        Object.assign(r, candBelow);
        moved = true;
        continue;
      }
      let dx = pref.colEnd - r.colStart;
      if (dx < 1) dx = 1;
      r.colStart += dx;
      r.colEnd += dx;
      moved = true;
      if (r.colEnd > 13) {
        r.colStart = Math.max(1, 13 - colSpan);
        r.colEnd = r.colStart + colSpan;
        r.rowStart = pref.rowEnd;
        r.rowEnd = r.rowStart + rowSpan;
      }
    }
    if (!moved) break;
  }
}

function sortWidgetIdsByLayout(layoutMap, originalIds) {
  const known = originalIds.filter((id) => layoutMap.has(id));
  return known.sort((a, b) => {
    const ra = layoutMap.get(a);
    const rb = layoutMap.get(b);
    if (ra.rowStart !== rb.rowStart) return ra.rowStart - rb.rowStart;
    if (ra.colStart !== rb.colStart) return ra.colStart - rb.colStart;
    return originalIds.indexOf(a) - originalIds.indexOf(b);
  });
}

export function sortWidgetsByGridLayout(widgetsOrdered) {
  const layoutMap = getEffectiveLayoutMap(widgetsOrdered);
  const ids = sortWidgetIdsByLayout(
    layoutMap,
    widgetsOrdered.map((w) => w.id)
  );
  const byId = new Map(widgetsOrdered.map((w) => [w.id, w]));
  return ids.map((id) => byId.get(id)).filter(Boolean);
}

/**
 * Po dropu: nové pořadí ID + `widget_layout` pro POST /reorder (všechny widgety).
 */
export function computeLayoutAfterDashboardDrop(
  widgetsOrdered,
  dragId,
  targetId,
  zone,
  getWidget
) {
  const dragW = getWidget(dragId);
  const targetW = getWidget(targetId);
  if (!dragW || !targetW) {
    return {
      nextIds: widgetsOrdered.map((w) => w.id),
      widgetLayout: null,
    };
  }

  let m;
  if (zone === "swap") {
    m = cloneLayoutMap(getEffectiveLayoutMap(widgetsOrdered));
    if (!applySwapPlacements(m, dragId, targetId) || layoutMapHasOverlap(m)) {
      return {
        nextIds: widgetsOrdered.map((w) => w.id),
        widgetLayout: null,
      };
    }
    const widgetLayout = {};
    for (const [id, r] of m) {
      widgetLayout[id] = gridRectToConfig(r);
    }
    const nextIds = sortWidgetIdsByLayout(
      m,
      widgetsOrdered.map((w) => w.id)
    );
    return { nextIds, widgetLayout };
  }
  if (zone === "above" || zone === "below") {
    m = cloneLayoutMap(getEffectiveLayoutMap(widgetsOrdered));
    vacateWidgetAndShiftUp(m, dragId);
    const { colSpan, rowSpan } = getWidgetGridPlacement(dragW);
    if (zone === "below") {
      applyStackBelow(m, dragId, targetId, colSpan, rowSpan);
    } else {
      applyStackAbove(m, dragId, targetId, colSpan, rowSpan);
    }
  } else {
    m = cloneLayoutMap(getEffectiveLayoutMap(widgetsOrdered));
    vacateWidgetAndShiftUp(m, dragId);
    applyHorizontalPlacement(m, dragId, targetId, zone, getWidget);
    nudgeNonPreferredOutOfWay(m, dragId);
  }

  const widgetLayout = {};
  for (const [id, r] of m) {
    widgetLayout[id] = gridRectToConfig(r);
  }
  const nextIds = sortWidgetIdsByLayout(
    m,
    widgetsOrdered.map((w) => w.id)
  );
  return { nextIds, widgetLayout };
}
