import { parseNumber } from "./format";
import { mapIso3CountryToIso2 } from "@/lib/exploreCompareGeo";

export function toNormalizedToken(value) {
  return String(value ?? "").trim().toLowerCase();
}

function toGeoDimensionToken(value) {
  return String(value ?? "")
    .trim()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

const GEO_DIMENSION_KEYS = new Set([
  "geo",
  "geography",
  "geographical_area",
  "ref_area",
  "refarea",
  "area",
  "country",
  "countries",
  "territory",
  "territories",
  "location",
  "locations",
  "region",
  "regions",
  "nuts",
  "nuts_id",
  "nuts_code",
  "uzemi",
  "uzemi_kraj",
  "kraj",
  "okres",
  "zeme",
]);

export function isGeoDimensionKeyCandidate(key) {
  const token = toGeoDimensionToken(key);
  if (!token || token.endsWith("_label") || token.endsWith("_name")) return false;
  if (GEO_DIMENSION_KEYS.has(token)) return true;
  if (/^(geo|ref_area|country|territory|location|region|nuts)_(id|code|key)$/.test(token)) return true;
  if (/^(id|code)_(geo|ref_area|country|territory|location|region|nuts)$/.test(token)) return true;
  if (/^(geo|ref_area|country|territory|location|region|nuts)_[a-z0-9]+$/.test(token)) return true;
  return false;
}

export function asGeoCode(value) {
  return String(value ?? "").trim().toUpperCase();
}

/** Platný geo kód: země (CZ), agregát (EU27_2020) nebo NUTS region (BG311, CZ010). */
export function isPlausibleGeoCode(code) {
  const c = asGeoCode(code);
  if (!c) return false;
  if (/^[A-Z]{2,3}$/.test(c)) return true;
  if (/^[A-Z0-9]{2,4}$/.test(c)) return true;
  if (/^(EU|EA)\d/.test(c) || /^EU\d+_\d{4}$/.test(c)) return true;
  if (/^[A-Z]{2}[A-Z0-9]{1,5}$/.test(c)) return true;
  return false;
}

/**
 * Volnější test než {@link isPlausibleGeoCode}. Používá se JEN pro hodnoty, u kterých už víme,
 * že pocházejí z geografické dimenze (geo/REF_AREA/ÚZEMÍ-KRAJ) — dimenzi jako geo potvrdil volající.
 * ČSÚ vrací kraje jako textové názvy ("Středočeský kraj", "Hlavní město Praha"), které nejsou
 * krátké kódy, ale jsou to legitimní geo hodnoty. Projdou jak krátké kódy (CZ, EU27_2020, CZ010),
 * tak textové názvy krajů/regionů; prázdno a zjevný balast (dlouhé věty) ne. Bez hardcodovaného
 * seznamu krajů.
 */
export function isPlausibleGeoValue(value) {
  const c = asGeoCode(value);
  if (!c) return false;
  if (isPlausibleGeoCode(c)) return true;
  if (c.length > 60) return false;
  return /\p{L}/u.test(c);
}

const GEO_AGGREGATE_LABELS_CS = {
  EU27_2020: "Evropská unie (EU27)",
  EU27: "Evropská unie (EU27)",
  EU28: "Evropská unie (EU28)",
  EA: "Eurozóna",
  EA19: "Eurozóna (EA19)",
  EA20: "Eurozóna (EA20)",
};

export function geoLabelFromCode(code) {
  const c = asGeoCode(code);
  if (!c) return "";
  if (GEO_AGGREGATE_LABELS_CS[c]) return GEO_AGGREGATE_LABELS_CS[c];
  if (typeof Intl !== "undefined" && typeof Intl.DisplayNames === "function") {
    try {
      const dnCs = new Intl.DisplayNames(["cs", "en"], { type: "region" });
      if (/^[A-Z]{2}$/.test(c)) {
        return String(dnCs.of(c) || c).trim() || c;
      }
      if (/^[A-Z]{3}$/.test(c)) {
        // Intl region names umí jen alpha-2 — ISO-3 (IMF/WB) převedeme.
        const iso2 = mapIso3CountryToIso2(c);
        if (iso2) {
          const fromIso2 = String(dnCs.of(iso2) || "").trim();
          if (fromIso2 && fromIso2.toUpperCase() !== iso2) return fromIso2;
        }
        const fromAlpha3 = String(dnCs.of(c) || "").trim();
        if (fromAlpha3 && fromAlpha3.toUpperCase() !== c) return fromAlpha3;
      }
    } catch {
      // Fallback níže.
    }
  }
  return c;
}

/** Lidský název země; preferuje hint z API (REF_AREA_LABEL, metadata). */
export function geoDisplayLabel(code, labelHint = "", labelLookup) {
  const c = asGeoCode(code);
  const hint = String(labelHint || "").trim();
  const fromLookup = labelLookup && typeof labelLookup === "object" ? String(labelLookup[c] || "").trim() : "";
  if (fromLookup && fromLookup.toUpperCase() !== c) return fromLookup;
  if (hint && hint.toUpperCase() !== c) return hint;
  const resolved = geoLabelFromCode(c);
  if (resolved && resolved.toUpperCase() !== c) return resolved;
  return hint || c;
}

/** Načte geo možnosti včetně lidských názvů z Eurostat metadata (sample_options / value_labels). */
export function readGeoOptionsFromDimensionMeta(dimMeta) {
  if (!dimMeta || typeof dimMeta !== "object") return [];
  const out = [];
  const seen = new Set();
  const push = (rawCode, rawLabel, rawCount = 0, availabilityKnown = false) => {
    const value = asGeoCode(rawCode);
    if (!value || seen.has(value)) return;
    seen.add(value);
    const hint = String(rawLabel || "").trim();
    const rowCount = Number(rawCount || 0);
    out.push({
      value,
      label: geoDisplayLabel(value, hint),
      rowCount,
      fromMetadata: true,
      availabilityKnown: availabilityKnown || rowCount > 0,
    });
  };
  const optionLists = [
    dimMeta.sample_options,
    dimMeta.options,
    dimMeta.values,
    dimMeta.sample_values,
  ].filter(Array.isArray);
  for (const list of optionLists) {
    for (const row of list) {
      if (row && typeof row === "object") {
        push(row.code ?? row.value ?? row.id, row.label ?? row.name, row.count ?? row.rowCount);
      } else {
        push(row, "");
      }
    }
  }
  const valueLabels = dimMeta.value_labels;
  if (valueLabels && typeof valueLabels === "object") {
    for (const [code, label] of Object.entries(valueLabels)) {
      push(code, label);
    }
  }
  const category = dimMeta.category;
  if (category && typeof category === "object" && category.index && typeof category.index === "object") {
    const labels = category.label && typeof category.label === "object" ? category.label : {};
    for (const code of Object.keys(category.index)) {
      push(code, labels[code]);
    }
  }
  if (!out.length) {
    for (const code of readGeoValuesFromAny(dimMeta)) {
      push(code, "");
    }
  }
  return out.sort((a, b) => a.label.localeCompare(b.label, "cs"));
}

export function buildGeoLabelLookup(availableDimensions, geoKey = "") {
  const lookup = {};
  const dimensionKeys = Object.keys(availableDimensions || {}).filter(isGeoDimensionKeyCandidate);
  const keys = [geoKey, "geo", "REF_AREA", "ref_area", "country", ...dimensionKeys].filter(Boolean);
  const seen = new Set();
  for (const key of keys) {
    const norm = toGeoDimensionToken(key);
    if (seen.has(norm)) continue;
    seen.add(norm);
    const dim = availableDimensions?.[key];
    if (!dim || typeof dim !== "object") continue;
    if (dim.value_labels && typeof dim.value_labels === "object") {
      for (const [code, label] of Object.entries(dim.value_labels)) {
        const value = asGeoCode(code);
        const human = String(label || "").trim();
        if (value && human) lookup[value] = human;
      }
    }
    for (const opt of readGeoOptionsFromDimensionMeta(dim)) {
      if (opt.value && opt.label && opt.label.toUpperCase() !== opt.value) {
        lookup[opt.value] = opt.label;
      }
    }
  }
  return lookup;
}

export function readGeoDimensionOptions(dimensions, ...keys) {
  const out = [];
  const seen = new Set();
  const dim = dimensions && typeof dimensions === "object" ? dimensions : {};
  const push = (rawValue, rawLabel, rawCount = 0, availabilityKnown = false, fromMetadata = true) => {
    const value = asGeoCode(rawValue);
    if (!value || !isPlausibleGeoValue(value) || seen.has(value)) return;
    seen.add(value);
    const label = geoDisplayLabel(value, rawLabel);
    const rowCount = Number(rawCount || 0);
    out.push({
      value,
      label,
      rowCount,
      fromMetadata,
      availabilityKnown: availabilityKnown || rowCount > 0,
    });
  };
  for (const key of keys) {
    if (!key) continue;
    const raw = dim[key];
    if (!raw) continue;
    if (Array.isArray(raw)) {
      for (const item of raw) {
        if (!item || typeof item !== "object") {
          push(item, "");
          continue;
        }
        const hasAvailability =
          Object.prototype.hasOwnProperty.call(item, "count")
          || Object.prototype.hasOwnProperty.call(item, "rowCount")
          || Object.prototype.hasOwnProperty.call(item, "has_data");
        push(
          item.id || item.code || item.value,
          item.name || item.label,
          item.count || item.rowCount || (item.has_data === "1" || item.has_data === true ? 1 : 0),
          hasAvailability,
          true,
        );
      }
      continue;
    }
    for (const opt of readGeoOptionsFromDimensionMeta(raw)) {
      push(opt.value, opt.label, opt.rowCount, opt.availabilityKnown, opt.fromMetadata !== false);
    }
  }
  return out.sort((a, b) => a.label.localeCompare(b.label, "cs"));
}

export function readGeoOptionsFromGeoDimensions(dimensions, ...preferredKeys) {
  const dim = dimensions && typeof dimensions === "object" ? dimensions : {};
  const keys = [
    ...preferredKeys,
    ...Object.keys(dim).filter(isGeoDimensionKeyCandidate),
  ].filter(Boolean);
  return readGeoDimensionOptions(dim, ...keys);
}

export function readGeoOptionsFromSelectableDimensions(selectableDimensions, ...preferredKeys) {
  const rows = Array.isArray(selectableDimensions) ? selectableDimensions : [];
  const preferred = new Set(preferredKeys.map(toGeoDimensionToken).filter(Boolean));
  const out = [];
  const seen = new Set();
  const push = (opt) => {
    const value = asGeoCode(opt?.value ?? opt?.code ?? opt?.id);
    if (!value || !isPlausibleGeoValue(value) || seen.has(value)) return;
    seen.add(value);
    const rowCount = Number(opt?.rowCount || opt?.count || 0);
    const availabilityKnown =
      Object.prototype.hasOwnProperty.call(opt, "rowCount")
      || Object.prototype.hasOwnProperty.call(opt, "count")
      || Object.prototype.hasOwnProperty.call(opt, "has_data");
    out.push({
      value,
      label: geoDisplayLabel(value, opt?.label || opt?.name),
      rowCount,
      fromMetadata: true,
      availabilityKnown: availabilityKnown || rowCount > 0,
    });
  };
  for (const dim of rows) {
    if (!dim || typeof dim !== "object") continue;
    const key = dim.field || dim.key || dim.id || dim.name;
    const keyToken = toGeoDimensionToken(key);
    if (!preferred.has(keyToken) && !isGeoDimensionKeyCandidate(key)) continue;
    for (const opt of readGeoOptionsFromDimensionMeta(dim)) {
      push(opt);
    }
  }
  return out.sort((a, b) => a.label.localeCompare(b.label, "cs"));
}

export function readGeoValuesFromAny(raw) {
  if (Array.isArray(raw)) {
    return raw.map((x) => asGeoCode(typeof x === "object" && x !== null ? x.code || x.id || x.value : x)).filter(Boolean);
  }
  if (raw && typeof raw === "object") {
    if (Array.isArray(raw.options)) {
      return raw.options.map((x) => asGeoCode(typeof x === "object" && x !== null ? x.code || x.id || x.value : x)).filter(Boolean);
    }
    if (Array.isArray(raw.values)) {
      return raw.values.map((x) => asGeoCode(typeof x === "object" && x !== null ? x.code || x.id || x.value : x)).filter(Boolean);
    }
    if (Array.isArray(raw.sample_values)) {
      return raw.sample_values.map((x) => asGeoCode(typeof x === "object" && x !== null ? x.code || x.id || x.value : x)).filter(Boolean);
    }
    if (raw.category && typeof raw.category === "object" && raw.category.index && typeof raw.category.index === "object") {
      return Object.keys(raw.category.index).map((x) => asGeoCode(x)).filter(Boolean);
    }
    const deny = new Set(["size", "label", "count", "description", "sample", "type", "codes"]);
    return Object.keys(raw)
      .filter((k) => !deny.has(toNormalizedToken(k)))
      .map((x) => asGeoCode(x))
      .filter((v) => /^[A-Z0-9_-]{2,8}$/.test(v));
  }
  const single = asGeoCode(raw);
  return single ? [single] : [];
}

export function detectGeoKeys(fields, rows) {
  const GEO_KEY_CANDIDATES = ["geo", "ref_area", "country", "territory", "location", "region", "nuts", "uzemi_kraj", "uzemi-kraj", "kraj"];
  const GEO_LABEL_KEY_CANDIDATES = [
    "geo_label",
    "ref_area_label",
    "country_label",
    "territory_label",
    "location_label",
    "region_label",
    "nuts_label",
  ];
  const keyMap = new Map();
  for (const f of fields || []) {
    const k = String(f || "").trim();
    if (!k) continue;
    keyMap.set(toNormalizedToken(k), k);
  }
  if (!keyMap.size && Array.isArray(rows) && rows.length > 0) {
    Object.keys(rows[0] || {}).forEach((k) => {
      const raw = String(k || "").trim();
      if (!raw) return;
      keyMap.set(toNormalizedToken(raw), raw);
    });
  }
  const geoKey = GEO_KEY_CANDIDATES
    .map((k) => keyMap.get(k))
    .find(Boolean) || [...keyMap.values()].find(isGeoDimensionKeyCandidate) || "";
  let geoLabelKey = GEO_LABEL_KEY_CANDIDATES
    .map((k) => keyMap.get(k))
    .find(Boolean) || "";
  if (!geoLabelKey && geoKey) {
    geoLabelKey = keyMap.get(`${toNormalizedToken(geoKey)}_label`) || "";
  }
  return { geoKey, geoLabelKey };
}

export function buildGeoOptionsFromRows(rows, geoKey, geoLabelKey) {
  if (!geoKey || !Array.isArray(rows) || rows.length === 0) return [];
  const byCode = new Map();
  for (const r of rows) {
    const code = asGeoCode(r?.[geoKey]);
    if (!code) continue;
    const labelRaw = geoLabelKey ? String(r?.[geoLabelKey] ?? "").trim() : "";
    const rec = byCode.get(code) || {
      value: code,
      label: labelRaw || geoLabelFromCode(code),
      rowCount: 0,
      fromMetadata: false,
      availabilityKnown: true,
    };
    rec.rowCount += 1;
    if (labelRaw && rec.label === code) rec.label = labelRaw;
    byCode.set(code, rec);
  }
  return Array.from(byCode.values()).sort((a, b) => a.label.localeCompare(b.label, "cs"));
}

export function collectGeoOptions(rowsOptions, metadataGeoValues) {
  const out = [];
  const seen = new Set();
  for (const opt of rowsOptions || []) {
    const v = asGeoCode(opt?.value);
    if (!v || seen.has(v)) continue;
    seen.add(v);
    out.push({
      value: v,
      label: String(opt?.label || geoLabelFromCode(v) || v).trim() || v,
      rowCount: Number(opt?.rowCount || 0),
      fromMetadata: false,
      availabilityKnown: true,
    });
  }
  for (const item of metadataGeoValues || []) {
    if (item && typeof item === "object" && (item.value || item.id || item.code)) {
      const v = asGeoCode(item.value || item.id || item.code);
      if (!v || seen.has(v)) continue;
      seen.add(v);
      const label = geoDisplayLabel(v, item.label || item.name);
      const rowCount = Number(item.rowCount || 0);
      out.push({
        value: v,
        label: label || v,
        rowCount,
        fromMetadata: item.fromMetadata !== false,
        availabilityKnown: item.availabilityKnown === true || rowCount > 0,
      });
      continue;
    }
    const raw = item && typeof item === "object" ? item.code || item.id || item.value : item;
    const v = asGeoCode(raw);
    if (!v || !isPlausibleGeoValue(v) || seen.has(v)) continue;
    seen.add(v);
    const label = item && typeof item === "object"
      ? geoDisplayLabel(v, item.label || item.name)
      : geoDisplayLabel(v, "");
    out.push({ value: v, label: label || v, rowCount: 0, fromMetadata: true, availabilityKnown: false });
  }
  return out.sort((a, b) => a.label.localeCompare(b.label, "cs"));
}

/** Omezí seznam geo kódů pro graf / výchozí výběr (např. WB360 vrací desítky zemí najednou). */
export function capGeoList(codes, options = [], max = 8) {
  const seen = new Set();
  const norm = [];
  for (const raw of codes || []) {
    const code = asGeoCode(raw);
    if (!code || seen.has(code)) continue;
    seen.add(code);
    norm.push(code);
  }
  if (norm.length <= max) return norm;
  const countBy = new Map(
    (options || []).map((o) => [asGeoCode(o?.value), Number(o?.rowCount || 0)]),
  );
  return [...norm]
    .sort((a, b) => (countBy.get(b) || 0) - (countBy.get(a) || 0))
    .slice(0, max);
}

export function geoCodesWithChartData(chartRows, geoCodes) {
  if (!Array.isArray(chartRows) || !Array.isArray(geoCodes) || !geoCodes.length) return [];
  const withData = new Set();
  for (const row of chartRows) {
    for (const code of geoCodes) {
      const key = asGeoCode(code);
      if (key && parseNumber(row?.[key]) !== null) withData.add(key);
    }
  }
  return geoCodes.map((code) => asGeoCode(code)).filter((code) => withData.has(code));
}

/** Počet nenulových bodů na zemi v pivotovaných chartRows. */
/**
 * Uses rows from the currently applied country selection as the source of truth.
 * A supplemental latest-only fetch may still contain an older geo filter, so it
 * may only fill countries that are absent from the current preview.
 */
export function mergeSelectedGeoRows(currentRows, supplementalRows, selectedGeo, geoKey) {
  if (!geoKey) return Array.isArray(currentRows) ? currentRows : [];
  const selected = new Set((selectedGeo || []).map(asGeoCode).filter(Boolean));
  const onlySelected = (rows) => (Array.isArray(rows) ? rows : []).filter((row) => {
    const code = asGeoCode(row?.[geoKey]);
    return code && (!selected.size || selected.has(code));
  });
  const current = onlySelected(currentRows);
  const covered = new Set(current.map((row) => asGeoCode(row?.[geoKey])).filter(Boolean));
  const missing = onlySelected(supplementalRows).filter(
    (row) => !covered.has(asGeoCode(row?.[geoKey])),
  );
  return [...current, ...missing];
}

export function countGeoSeriesPoints(chartRows, geoCodes) {
  const counts = new Map();
  for (const code of geoCodes || []) {
    counts.set(asGeoCode(code), 0);
  }
  for (const row of chartRows || []) {
    for (const code of geoCodes || []) {
      const key = asGeoCode(code);
      if (key && parseNumber(row?.[key]) !== null) {
        counts.set(key, (counts.get(key) || 0) + 1);
      }
    }
  }
  return counts;
}

/** True když porovnání zemí má příliš málo bodů na vykreslení spojnic (typicky WB360). */
export function geoSeriesNeedsPointMarkers(chartRows, geoCodes) {
  const counts = countGeoSeriesPoints(chartRows, geoCodes);
  const values = [...counts.values()].filter((n) => n > 0);
  if (!values.length) return false;
  return values.some((n) => n < 2);
}

export function isSparseGeoCompare(chartRows, geoCodes) {
  if (!Array.isArray(chartRows) || !Array.isArray(geoCodes) || geoCodes.length < 2) return false;
  const counts = countGeoSeriesPoints(chartRows, geoCodes);
  const active = [...counts.values()].filter((n) => n > 0);
  if (active.length < 2) return false;
  return active.every((n) => n <= 1);
}

/** Globální WB360 snapshot: mnoho zemí, max. jeden bod na zemi (ne časová řada). */
export function isGeoCrossSectionSnapshot(rows, geoKey) {
  if (!Array.isArray(rows) || !geoKey || rows.length < 2) return false;
  const perGeo = new Map();
  for (const row of rows) {
    const code = asGeoCode(row?.[geoKey]);
    if (!code) continue;
    perGeo.set(code, (perGeo.get(code) || 0) + 1);
  }
  if (perGeo.size < 2) return false;
  return [...perGeo.values()].every((n) => n <= 1);
}

export function buildGeoChartRows(rows, timeField, valueField, geoKey) {
  if (!Array.isArray(rows) || !timeField || !valueField || !geoKey) return [];
  const byX = new Map();
  for (const r of rows) {
    const x = String(r?.[timeField] ?? "").trim();
    const geoCode = asGeoCode(r?.[geoKey]);
    const y = parseNumber(r?.[valueField]);
    if (!x || !geoCode || y === null) continue;
    let row = byX.get(x);
    if (!row) {
      row = { x };
      byX.set(x, row);
    }
    row[geoCode] = y;
  }
  return Array.from(byX.values())
    .sort((a, b) => (a.x < b.x ? -1 : a.x > b.x ? 1 : 0))
    .slice(-80);
}
