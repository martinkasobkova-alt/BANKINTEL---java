/**
 * Sestaví uložitelnou konfiguraci osobního widgetu „Datový graf“ z náhledu katalogu,
 * pokud je řada již v systému načtená jako zdroj (shodné s api /sources/catalog-stubs).
 */

import { extractSelectedDimensionsForStorage } from "@/lib/catalogDimensions";
import { buildCatalogPreviewBody, resolveCatalogRowSetId } from "@/lib/catalogPreviewBody";

const PERIOD_FIELD_RE = /(^|[^a-z0-9])(date|datum|time|period|obs_time|timestamp|time_period|month|mesic|měsíc|mesice|měsíce|mesicu|měsíců|kumulace|year|roky|rok|obdobi|období|quarter|ctvrtleti|čtvrtletí)([^a-z0-9]|$)/i;
const VALUE_FIELD_RE = /^(value|values|obs_value|amount|hodnota|numeric|y|val)$/i;

function previewFieldNames(previewData) {
  const out = [];
  const seen = new Set();
  const push = (field) => {
    const key = String(field || "").trim();
    if (!key || seen.has(key)) return;
    seen.add(key);
    out.push(key);
  };
  const fields = Array.isArray(previewData?.fields) ? previewData.fields : [];
  fields.forEach(push);
  const columns = Array.isArray(previewData?.columns) ? previewData.columns : [];
  for (const col of columns) {
    if (typeof col === "string") {
      push(col);
    } else if (col && typeof col === "object") {
      push(col.key || col.field || col.id);
    }
  }
  const row = Array.isArray(previewData?.rows) && previewData.rows[0] ? previewData.rows[0] : null;
  if (row && typeof row === "object" && !Array.isArray(row)) {
    Object.keys(row).forEach(push);
  }
  return out;
}

function fieldLooksNumericInRows(field, rows, max = 30) {
  let seen = 0;
  let numeric = 0;
  for (const row of (Array.isArray(rows) ? rows : []).slice(0, max)) {
    if (!row || typeof row !== "object") continue;
    const raw = row[field];
    if (raw == null || raw === "") continue;
    seen += 1;
    if (!Number.isNaN(Number(String(raw).replace(/\s+/g, "").replace(",", ".")))) numeric += 1;
  }
  return seen > 0 && numeric / seen >= 0.75;
}

function normalizeMatchText(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9\s]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** Když preview nevrátí selected_indicator, zvolí nejvhodnější řadu z `indicators`. */
function pickDefaultIndicator(previewData, row) {
  const sel = previewData?.selected_indicator;
  if (sel != null && String(sel).trim()) return String(sel).trim();

  const indicators = Array.isArray(previewData?.indicators) ? previewData.indicators : [];
  if (!indicators.length) return null;

  const sourceName = previewData?.source?.name || "";
  const hint = normalizeMatchText(row?.name || row?.title || sourceName);
  if (hint) {
    let best = null;
    for (const ind of indicators) {
      const id = String(ind?.id || "").trim();
      const name = normalizeMatchText(ind?.name || id);
      if (!id || !name) continue;
      let score = 0;
      if (/kupn|cena|ceny|kc|za m2|za m²|price/.test(name)) score += 40;
      if (/index|meziro/.test(name)) score -= 30;
      if (/byt/.test(name)) score += 20;
      if (name.includes(hint) || hint.includes(name)) score = Math.max(score, 100);
      else {
        const words = hint.split(" ").filter((w) => w.length > 3);
        score += words.filter((w) => name.includes(w)).length * 5;
      }
      if (hint.includes("byt") && /byt/.test(name)) score += 25;
      if ((hint.includes("cena") || hint.includes("ceny") || hint.includes("kupn")) && /kupn|cena|ceny|kc/.test(name)) {
        score += 30;
      }
      if (score > 0 && (!best || score > best.score)) best = { id, score };
    }
    if (best && best.score > 0) return best.id;
  }

  return String(indicators[0]?.id || "").trim() || null;
}

function fieldNameLooksGeo(field) {
  const n = normalizeMatchText(field).replace(/\s+/g, "");
  return /(kraj|okres|region|geo|refarea|country|uzemi)/.test(n) && !/stat/.test(n);
}

function findPreviewField(previewData, ...candidates) {
  const fields = previewFieldNames(previewData);
  for (const cand of candidates) {
    const hit = fields.find((f) => normalizeMatchText(f) === normalizeMatchText(cand));
    if (hit) return hit;
  }
  return null;
}

function uniquePreviewValues(previewData, field) {
  const rows = Array.isArray(previewData?.rows) ? previewData.rows : [];
  const vals = new Set();
  for (const row of rows) {
    const v = String(row?.[field] ?? "").trim();
    if (v) vals.add(v);
  }
  return [...vals].sort();
}

function pickMetricValueFromRows(previewData, metricField, row) {
  const vals = uniquePreviewValues(previewData, metricField);
  if (!vals.length) return null;
  if (vals.length === 1) return vals[0];
  const hint = normalizeMatchText(row?.name || row?.title || "");
  let best = vals[0];
  let bestScore = -Infinity;
  for (const v of vals) {
    const name = normalizeMatchText(v);
    let score = 0;
    if (/kupn|cena|ceny|kc|za m2|za m²|price/.test(name)) score += 40;
    if (/index|meziro/.test(name)) score -= 30;
    if (/byt/.test(name)) score += 25;
    if (hint.includes("byt") && /byt/.test(name)) score += 30;
    if ((hint.includes("cena") || hint.includes("ceny") || hint.includes("kupn")) && /kupn|cena|ceny|kc/.test(name)) {
      score += 30;
    }
    if (score > bestScore) {
      bestScore = score;
      best = v;
    }
  }
  return best;
}

function applyCsuChartDefaults(previewData, cfg, row) {
  if (String(cfg.catalog || "") !== "csu") return;

  cfg.agg = "avg";

  const hodnota = findPreviewField(previewData, "Hodnota", "hodnota");
  const roky = findPreviewField(previewData, "Roky", "Tříleté období", "date", "year");
  if (roky) cfg.x_field = roky;
  if (hodnota) cfg.y_field = hodnota;

  const dimFilters =
    cfg.dimension_filters && typeof cfg.dimension_filters === "object"
      ? { ...cfg.dimension_filters }
      : {};
  const popField = findPreviewField(previewData, "Počet obyvatel obce", "Pocet obyvatel obce");
  if (popField && !dimFilters[popField]) {
    const popVals = uniquePreviewValues(previewData, popField);
    if (popVals.includes("Celkem")) dimFilters[popField] = "Celkem";
  }
  if (Object.keys(dimFilters).length > 0) cfg.dimension_filters = dimFilters;

  const gf = String(previewData?.group_field || cfg.group_field || "");
  const metricField = fieldNameLooksGeo(gf)
    ? findPreviewField(previewData, "Ukazatel", "Druh nemovitosti") || gf
    : gf || findPreviewField(previewData, "Ukazatel", "Druh nemovitosti");
  if (metricField && !fieldNameLooksGeo(metricField)) {
    cfg.group_field = metricField;
    const picked = pickMetricValueFromRows(previewData, metricField, row);
    if (picked) {
      cfg.selected_indicator = picked;
      cfg.series_field = metricField;
      cfg.series_value = picked;
      cfg.chart_series_mode = "single";
    }
  }

  const geoField =
    findPreviewField(previewData, "ÚZEMÍ-Kraj", "Území-kraj") ||
    previewFieldNames(previewData).find((f) => fieldNameLooksGeo(f)) ||
    null;
  const xField = String(cfg.x_field || "");
  const years = xField ? uniquePreviewValues(previewData, xField) : [];
  if (geoField && uniquePreviewValues(previewData, geoField).length >= 2) {
    if (years.length > 1 && metricField && cfg.series_value) {
      cfg.chart_series_dim = geoField;
      cfg.chart_data_mode = "history";
      cfg.chart_type = "line";
    } else if (years.length <= 1) {
      cfg.chart_data_mode = "latest";
      cfg.chart_type = "bar";
    }
  }
}

function guessXYFields(previewData) {
  const fields = previewFieldNames(previewData);
  if (!fields.length) return { x: null, y: null };
  const row = Array.isArray(previewData?.rows) && previewData.rows[0] ? previewData.rows[0] : null;
  const rows = Array.isArray(previewData?.rows) ? previewData.rows : [];
  const dateIdx = fields.findIndex((f) => PERIOD_FIELD_RE.test(String(f)));
  const hodnotaIdx = fields.findIndex((f) => /^hodnota$/i.test(String(f)));
  const valueIdx = fields.findIndex((f) => VALUE_FIELD_RE.test(String(f)));
  let x = dateIdx >= 0 ? fields[dateIdx] : null;
  let y = hodnotaIdx >= 0 ? fields[hodnotaIdx] : valueIdx >= 0 ? fields[valueIdx] : null;
  if (!x) {
    x = fields.find((f) => !fieldLooksNumericInRows(f, rows)) || fields[0];
  }
  if (!y) {
    y = fields.find((f) => f !== x && fieldLooksNumericInRows(f, rows)) || fields.find((f) => f !== x) || fields[1];
  }
  if (x && !y && row) {
    const rest = fields.filter((f) => f !== x);
    y = rest.find((f) => {
      const v = row[f];
      return v != null && v !== "" && !Number.isNaN(Number(String(v).replace(",", ".")));
    });
  }
  return { x, y };
}

function extractGeoFromPreview(previewData) {
  const fromApplied = previewData?.metadata?.filters_applied;
  const fromRequested = previewData?.requested_filters;
  const raw =
    (fromApplied && (fromApplied.geo || fromApplied.REF_AREA || fromApplied.ref_area)) ||
    (fromRequested && (fromRequested.geo || fromRequested.REF_AREA || fromRequested.ref_area));
  if (!raw) return [];
  const list = Array.isArray(raw) ? raw : [raw];
  return [...new Set(list.map((x) => String(x || "").trim().toUpperCase()).filter(Boolean))].slice(0, 8);
}

function extractSplitDimensions(previewData) {
  const dims = Array.isArray(previewData?.extra_dimensions) ? previewData.extra_dimensions : [];
  return dims
    .map((dim) => ({
      field: String(dim?.field || "").trim(),
      values: Array.isArray(dim?.values) ? dim.values.slice(0, 200).map((v) => String(v)) : [],
    }))
    .filter((dim) => dim.field)
    .slice(0, 12);
}

/**
 * @param {object} def — položka z CATALOGS (id, sourceType, …)
 * @param {object} source — záznam ze /sources/catalog-stubs
 * @param {object} previewData — odpověď z /sources/{id}/preview
 * @param {object} row — řádek katalogu (name, …)
 * @returns {{ title: string, config: object } | null}
 */
export function buildPersonalChartConfigFromPreview(def, source, previewData, row) {
  if (!source?.id || !def?.sourceType) return null;

  const title = (row?.name || row?.label || previewData?.source?.name || "Graf z katalogu").toString().trim();
  const st = String(def.sourceType).toLowerCase();

  if (st === "arad") {
    const ind =
      (previewData?.selected_indicator && String(previewData.selected_indicator).trim()) ||
      (Array.isArray(previewData?.indicators) && previewData.indicators[0]?.id
        ? String(previewData.indicators[0].id)
        : "");
    if (!ind) return null;
    return {
      title: title || "ARAD",
      config: {
        source_type: "arad",
        source_id: source.id,
        indicator_id: ind,
        view: "chart",
        chart_type: "line",
        limit: 0,
      },
    };
  }

  const { x, y } = guessXYFields(previewData);
  if (!x || !y) return null;

  // CSU má více regionů na každý rok → průměr je správnější než součet nebo last
  const defaultAgg = st === "csu" ? "avg" : "last";
  const cfg = {
    source_type: st,
    source_id: source.id,
    view: "chart",
    chart_type: "line",
    agg: defaultAgg,
    limit: 0,
    x_field: x,
    y_field: y,
    title,
  };
  const splitDims = extractSplitDimensions(previewData);
  if (splitDims.length > 0) cfg.available_split_dimensions = splitDims;

  const gf = previewData?.group_field;
  const sv = previewData?.selected_indicator;
  if (gf && sv != null && String(sv).trim() !== "") {
    cfg.series_field = gf;
    cfg.series_value = String(sv).trim();
  }

  // Uloží filtry ostatních dimenzí (ÚZEMÍ-STÁT, ÚZEMÍ-KRAJ, …) do configu.
  const dimFilters = previewData?.dimension_filters;
  if (dimFilters && typeof dimFilters === "object" && Object.keys(dimFilters).length > 0) {
    const nonEmpty = Object.fromEntries(
      Object.entries(dimFilters).filter(([, v]) => v && String(v).trim() !== "")
    );
    if (Object.keys(nonEmpty).length > 0) cfg.dimension_filters = nonEmpty;
  }

  return { title, config: cfg };
}

/**
 * Osobní widget z globálního katalogu — uloží se jen pro uživatele, bez záznamu v /api/sources.
 * `def.id` musí být whitelisted katalog (stejné jako CATALOGS v `catalogDefinitions.js`).
 */
/** Ne-prázdné texty ze seznamu, bez duplicit a bez mezer navíc. */
function uniqueTexts(raw) {
  const list = Array.isArray(raw) ? raw : [];
  return [...new Set(list.map((v) => String(v ?? "").trim()).filter(Boolean))];
}

/**
 * Přenese do konfigurace widgetu to, co uživatel nastavil v náhledu: podle které dimenze
 * se graf dělí, které její hodnoty vybral a jestli kreslí časovou řadu, nebo srovnání hodnot.
 *
 * Proč to existuje: náhled tohle všechno uměl, ale při ukládání se z toho posílala jen
 * dimenze a hodnoty — a `chart_series_dim_values` navíc nikdo nečetl. Widget se proto vrátil
 * ke „všem hodnotám, prvních 12 abecedně" a přepnul se zpátky na čáru. Na dashboard tak
 * přistálo něco jiného, než co člověk před uložením viděl.
 */
function applySeriesSelection(cfg, seriesConfig) {
  if (!seriesConfig) return;
  const crossSection = seriesConfig.displayMode === "bars_latest";

  // U srovnání hodnot určuje dimenzi graf sám (case „Proměnná (osa X)"), u časové řady
  // je to volba „Série:". Hodnoty se berou z téhož zdroje, aby si neodporovaly.
  const dim = String(
    (crossSection ? seriesConfig.crossSectionDim : "") || seriesConfig.seriesGroupDim || ""
  ).trim();
  const values = crossSection
    ? uniqueTexts(seriesConfig.crossSectionValues)
    : uniqueTexts(seriesConfig.seriesSelection);

  if (dim) {
    cfg.chart_series_dim = dim;
    if (values.length > 0) cfg.chart_series_dim_values = values;
  }

  if (crossSection) {
    cfg.chart_data_mode = "latest";
    // Srovnání hodnot je sloupcový graf; čára přes jedno období nedává smysl.
    cfg.chart_type = "bar";
    // Srovnání hodnot je JEDNA řada o N bodech, ne N řad. Dokud si widget nesl příznaky
    // více řad, kreslil legendu se všemi zeměmi, ta sebrala celou výšku a na sloupce
    // zbylo 77 pixelů — graf byl prázdný, i když data dorazila správně.
    // Pozor: "single" tu nejde použít, tím by se zahodila i dimenze rozdělení.
    delete cfg.chart_series_mode;
    delete cfg.chart_compare_with;
  }
}

export function buildExternalCatalogChartConfig(def, previewData, row, wbCountry, seriesConfig = null) {
  if (!def?.id || !row) return null;
  const rawSetId = String(row?.set_id ?? row?.series_id ?? row?.code ?? "").trim();
  const setId = resolveCatalogRowSetId(row, previewData);
  if (!setId) return null;
  const title = (row?.name || previewData?.source?.name || "Graf z katalogu").toString().trim();
  const { x, y } = guessXYFields(previewData);
  // Výběr zemí primárně z náhledu. Když ho odpověď nezopakuje, vezmi ho z toho, co má
  // vybráno UI — jinak by se widget uložil bez zemí a vykreslil jednu řadu místo srovnání.
  const geoFromPreview = extractGeoFromPreview(previewData);
  const geoSel = geoFromPreview.length > 0 ? geoFromPreview : uniqueTexts(seriesConfig?.selectedGeo);
  const rowFetch = buildCatalogPreviewBody(def, row, wbCountry);
  const defaultAgg = def.id === "csu" ? "avg" : "last";
  const cfg = {
    source_type: "external_catalog",
    catalog: def.id,
    set_id: String(rowFetch.set_id || setId).trim(),
    title,
    chart_type: "line",
    view: "chart",
    limit: 0,
    agg: defaultAgg,
    chart_data_mode: "history",
  };
  if (x) cfg.x_field = x;
  if (y) cfg.y_field = y;
  if (def.needsCountry) cfg.country = rowFetch.country || wbCountry;
  const queryParams = {
    ...(rowFetch.query_params && typeof rowFetch.query_params === "object" ? rowFetch.query_params : {}),
  };
  if (geoSel.length > 0) {
    const geoVal = geoSel.length === 1 ? geoSel[0] : geoSel;
    queryParams.geo = geoVal;
    // REF_AREA je SDMX geo dimenze (ECB/Data360/…). Eurostat JSON-stat API používá jen `geo` a
    // REF_AREA odmítne (HTTP 400 „INVALID_QUERY_DIMENSION: Dimension REF_AREA is not defined") —
    // náhled v editoru ho taky nikdy neposílá, takže ho pro Eurostat do uloženého configu nepíšeme.
    if (def.id === "eurostat") {
      delete queryParams.REF_AREA;
    } else {
      queryParams.REF_AREA = geoVal;
    }
  }
  if (Object.keys(queryParams).length > 0) cfg.query_params = queryParams;
  const sel = previewData?.selected_indicator;
  const pickedIndicator = fieldNameLooksGeo(String(previewData?.group_field || ""))
    ? null
    : pickDefaultIndicator(previewData, row);
  if (sel != null && String(sel).trim() !== "") cfg.selected_indicator = String(sel).trim();
  else if (pickedIndicator) cfg.selected_indicator = pickedIndicator;
  const selectedMany = Array.isArray(previewData?.selected_indicators)
    ? [...new Set(previewData.selected_indicators.map((v) => String(v ?? "").trim()).filter(Boolean))]
    : [];
  if (selectedMany.length > 1) cfg.selected_indicators = selectedMany;
  const primarySel = String(cfg.selected_indicator || selectedMany[0] || "").trim();
  const compareIds = selectedMany.filter((id) => id && id !== primarySel).slice(0, 6);
  if (compareIds.length > 0) {
    cfg.chart_series_mode = "multi";
    cfg.chart_compare_with = compareIds.map((id) => {
      const ind = Array.isArray(previewData?.indicators)
        ? previewData.indicators.find((item) => String(item?.id ?? "").trim() === id)
        : null;
      return {
        selected_indicator: id,
        chart_type: "line",
        y_axis: "left",
        name: String(ind?.name || id).trim(),
      };
    });
  } else if (geoSel.length > 1) {
    cfg.chart_series_mode = "multi";
  }
  const gf = previewData?.group_field;
  if (gf) cfg.group_field = String(gf);
  if (gf && primarySel && !fieldNameLooksGeo(String(gf))) {
    cfg.series_field = String(gf);
    cfg.series_value = primarySel;
  }
  const meta = {
    preview_row_cap: Number(previewData?.rows?.length || 0),
    preview_truncated: Boolean(previewData?.truncated),
  };
  if (def.id === "eurostat" && rawSetId && rawSetId.toLowerCase() !== String(setId).toLowerCase()) {
    meta.sidecar_series_id = rawSetId;
    cfg.series_id = rawSetId;
  }
  if (row?.dataset_id) meta.dataset_id = String(row.dataset_id).trim();
  if (previewData?.total_count != null) meta.total_count = previewData.total_count;
  const splitDims = extractSplitDimensions(previewData);
  if (splitDims.length > 0) cfg.available_split_dimensions = splitDims;
  applySeriesSelection(cfg, seriesConfig);
  const selectedDims = extractSelectedDimensionsForStorage(previewData);
  if (selectedDims && Object.keys(selectedDims).length > 0) {
    cfg.selected_dimensions = selectedDims;
  }
  const dimFilters = previewData?.dimension_filters;
  if (dimFilters && typeof dimFilters === "object" && Object.keys(dimFilters).length > 0) {
    const nonEmpty = Object.fromEntries(
      Object.entries(dimFilters).filter(([, v]) => v && String(v).trim() !== "")
    );
    if (Object.keys(nonEmpty).length > 0) cfg.dimension_filters = nonEmpty;
  }

  applyCsuChartDefaults(previewData, cfg, row);

  cfg.metadata = meta;
  return { title: title || "Graf z katalogu", config: cfg };
}
