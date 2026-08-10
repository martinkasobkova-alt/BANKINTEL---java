import { feature } from "topojson-client";
import bundledWorldAtlas from "@/data/world-atlas-countries-110m.json";
import {
  WORLD_ATLAS_FALLBACK_URLS,
  coordInRegionBounds,
  geographyIso2,
  geographyMatchesRegion,
  getRegionLonLatBounds,
  colorScale,
} from "@/lib/chartGeoMapData";

export function normalizeWorldAtlasTopology(raw) {
  const candidate = raw?.default && raw?.type !== "Topology" ? raw.default : raw;
  if (candidate?.type === "Topology" && candidate?.objects) return candidate;
  return null;
}

/** Jednoduchá Mercator projekce pro SVG mapu. */
export function projectMercator(lon, lat, { center = [0, 0], scale = 100, width = 800, height = 420 }) {
  const λ = ((lon - center[0]) * Math.PI) / 180;
  const φ = (lat * Math.PI) / 180;
  const x = width / 2 + scale * λ;
  const y = height / 2 - scale * Math.log(Math.tan(Math.PI / 4 + φ / 2));
  return [x, y];
}

const LON_GAP_BREAK_DEG = 25;

function ringToPath(ring, projCfg, bounds) {
  if (!Array.isArray(ring) || ring.length < 2) return "";

  let path = "";
  let penDown = false;
  let prevLon = null;

  for (let i = 0; i < ring.length; i += 1) {
    const [lon, lat] = ring[i];
    if (bounds && !coordInRegionBounds(lon, lat, bounds)) {
      penDown = false;
      prevLon = null;
      continue;
    }
    if (prevLon != null && Math.abs(lon - prevLon) > LON_GAP_BREAK_DEG) {
      penDown = false;
    }

    const [x, y] = projectMercator(lon, lat, projCfg);
    path += `${penDown ? "L" : "M"}${x.toFixed(2)},${y.toFixed(2)} `;
    penDown = true;
    prevLon = lon;
  }

  return path.trim() ? `${path.trim()} Z` : "";
}

export function geometryToPath(geometry, projCfg, bounds = null) {
  if (!geometry) return "";
  const { type, coordinates } = geometry;
  if (type === "Polygon") {
    return coordinates.map((ring) => ringToPath(ring, projCfg, bounds)).filter(Boolean).join(" ");
  }
  if (type === "MultiPolygon") {
    return coordinates
      .map((poly) => poly.map((ring) => ringToPath(ring, projCfg, bounds)).filter(Boolean).join(" "))
      .filter(Boolean)
      .join(" ");
  }
  return "";
}

function forEachGeometryCoord(geometry, fn, bounds = null) {
  if (!geometry || typeof fn !== "function") return;
  const walk = (coords) => {
    if (!Array.isArray(coords) || coords.length < 1) return;
    if (
      coords.length >= 2 &&
      typeof coords[0] === "number" &&
      typeof coords[1] === "number"
    ) {
      if (!bounds || coordInRegionBounds(coords[0], coords[1], bounds)) {
        fn(coords);
      }
      return;
    }
    coords.forEach(walk);
  };
  walk(geometry.coordinates);
}

/** ViewBox z bounding boxu projekce (jen souřadnice uvnitř regionu). */
export function computeFeaturesViewBox(features, projCfg, options = {}) {
  const padding = Number.isFinite(options.padding) ? options.padding : 12;
  const bounds = options.bounds ?? getRegionLonLatBounds(options.region);

  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;

  for (const feat of features || []) {
    forEachGeometryCoord(
      feat?.geometry,
      ([lon, lat]) => {
        const [x, y] = projectMercator(lon, lat, projCfg);
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      },
      bounds
    );
  }

  if (!Number.isFinite(minX)) {
    const w = projCfg.width || 800;
    const h = projCfg.height || 420;
    return { viewBox: `0 0 ${w} ${h}`, aspect: h / w };
  }

  const vbX = minX - padding;
  const vbY = minY - padding;
  const vbW = Math.max(1, maxX - minX + padding * 2);
  const vbH = Math.max(1, maxY - minY + padding * 2);
  return { viewBox: `${vbX} ${vbY} ${vbW} ${vbH}`, aspect: vbH / vbW };
}

let cachedTopology = null;
let cachePromise = null;

async function fetchAtlasFromCandidates(urls) {
  let lastErr = null;
  for (const url of urls) {
    const u = String(url || "").trim();
    if (!u) continue;
    try {
      const r = await fetch(u);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const topology = normalizeWorldAtlasTopology(await r.json());
      if (!topology) throw new Error("Invalid topology payload");
      return topology;
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr || new Error("Nepodařilo se načíst mapu");
}

export function loadWorldAtlasTopology() {
  if (cachedTopology) return Promise.resolve(cachedTopology);
  if (cachePromise) return cachePromise;

  const bundled = normalizeWorldAtlasTopology(bundledWorldAtlas);
  if (bundled) {
    cachedTopology = bundled;
    return Promise.resolve(bundled);
  }

  cachePromise = fetchAtlasFromCandidates(WORLD_ATLAS_FALLBACK_URLS)
    .then((topology) => {
      cachedTopology = topology;
      return topology;
    })
    .catch((err) => {
      cachePromise = null;
      throw err;
    });
  return cachePromise;
}

export function topologyToCountryFeatures(topology) {
  try {
    const normalized = normalizeWorldAtlasTopology(topology) || topology;
    const objectKey = normalized?.objects?.countries
      ? "countries"
      : Object.keys(normalized?.objects || {})[0];
    if (!objectKey) return [];
    const collection = feature(normalized, normalized.objects[objectKey]);
    return Array.isArray(collection?.features) ? collection.features : [];
  } catch {
    return [];
  }
}

export function featureToSvgPath(feat, projCfg, region = "world") {
  const bounds = getRegionLonLatBounds(region);
  return geometryToPath(feat?.geometry, projCfg, bounds);
}

export function featureFillColor(feat, valueByIso, min, max, baseRgb) {
  const iso = geographyIso2(feat);
  const val = iso ? valueByIso.get(iso) : undefined;
  return colorScale(val, min, max, baseRgb);
}

export function filterFeaturesByRegion(features, region) {
  return (features || []).filter((f) => geographyMatchesRegion(f, region));
}

/** Pod touto šířkou plotu přejde legenda mapy do svislého sloupce vedle mapy. */
export const GEO_MAP_SIDE_LEGEND_MAX_WIDTH = 560;
export const GEO_MAP_SIDE_LEGEND_RESERVE = 54;
/** Mezera mezi mapou a svislou legendou (px). */
export const GEO_MAP_SIDE_LEGEND_GAP = 8;

export function shouldUseGeoMapSideLegend({
  plotWidth = 0,
  veryNarrowWidget = false,
} = {}) {
  if (veryNarrowWidget) return true;
  if (plotWidth > 0) return plotWidth < GEO_MAP_SIDE_LEGEND_MAX_WIDTH;
  return false;
}

/** Šířka SVG slotu mapy tak, aby vedle seděla svislá legenda bez prázdné mezery. */
export function computeSideLegendMapLayout({
  viewBox,
  plotWidth = 0,
  plotHeight = 0,
  legendReserve = GEO_MAP_SIDE_LEGEND_RESERVE,
  gap = GEO_MAP_SIDE_LEGEND_GAP,
} = {}) {
  const parts = String(viewBox || "0 0 1 1")
    .trim()
    .split(/\s+/)
    .map(Number);
  const vbW = parts[2] || 1;
  const vbH = parts[3] || 1;
  const w = Number(plotWidth);
  const h = Number(plotHeight);
  if (!Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0) {
    return null;
  }
  const availW = Math.max(96, w - legendReserve - gap);
  const scale = Math.min(availW / vbW, h / vbH);
  return {
    mapWidth: Math.max(1, Math.ceil(vbW * scale)),
    mapHeight: Math.max(1, Math.ceil(vbH * scale)),
  };
}
