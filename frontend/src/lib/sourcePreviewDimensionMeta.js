/** Sdílené čtení metadat dimenzí — bez závislosti na industry filtrech (prevence circular import). */

/** České popisky běžných kódů odvětví / agregátů. */
const VALUE_LABELS_CS = {
  TOT_CEPA: "Celkem — environmentální ochrana",
  TOTAL: "Celkem",
  TOT: "Celkem",
  T: "Celkem",
  CEPA1: "Ochrana ovzduší a klimatu",
  CEPA2: "Čištění odpadních vod",
  CEPA3: "Nakládání s odpady",
  CEPA9: "Ostatní environmentální aktivity",
  B: "Těžba a dobývání",
  C: "Zpracovatelský průmysl",
  D: "Výroba a rozvod energie",
  E: "Vodní hospodářství, odpady a sanace",
  G45: "Maloobchod s motorovými vozy",
  G46: "Velkoobchod kromě motorových vozidel",
  G47: "Maloobchod kromě motorových vozidel",
  P3_S13: "Vládní spotřeba",
  P3_S14: "Spotřeba domácností",
};

function looksLikeTechnicalCode(text) {
  const t = String(text || "").trim();
  if (!t) return true;
  if (t.length > 48) return false;
  return /^[A-Z0-9][A-Z0-9_.\-/]*$/i.test(t);
}

export function resolveHumanValueLabel(code, metaLabel) {
  const c = String(code ?? "").trim();
  const fromMeta = String(metaLabel ?? "").trim();
  if (fromMeta && fromMeta !== c && !looksLikeTechnicalCode(fromMeta)) {
    return fromMeta;
  }
  const cs = VALUE_LABELS_CS[c] || VALUE_LABELS_CS[c.toUpperCase()];
  if (cs) return cs;
  if (fromMeta && fromMeta !== c) return fromMeta;
  return c;
}

function buildLabelLookupFromMeta(raw) {
  const map = {};
  if (!raw || typeof raw !== "object") return map;
  if (raw.value_labels && typeof raw.value_labels === "object") {
    Object.assign(map, raw.value_labels);
  }
  if (Array.isArray(raw.sample_options)) {
    for (const row of raw.sample_options) {
      const code = String(row?.code ?? row?.value ?? "").trim();
      if (!code) continue;
      map[code] = String(row?.label || row?.name || map[code] || "").trim() || map[code];
    }
  }
  return map;
}

export function readOptionsFromDimensionMeta(raw) {
  const out = [];
  const seen = new Set();
  const push = (code, metaLabel) => {
    const c = String(code ?? "").trim();
    if (!c || seen.has(c)) return;
    seen.add(c);
    out.push({ value: c, label: resolveHumanValueLabel(c, metaLabel), rowCount: 0 });
  };
  if (!raw || typeof raw !== "object") return out;
  const labelMap = buildLabelLookupFromMeta(raw);
  if (Array.isArray(raw.sample_options)) {
    raw.sample_options.forEach((row) => push(row?.code ?? row?.value, row?.label || row?.name));
  }
  if (Array.isArray(raw.values)) {
    raw.values.forEach((code) => push(code, labelMap[code]));
  }
  if (Array.isArray(raw.sample_values)) {
    raw.sample_values.forEach((code) => push(code, labelMap[code]));
  }
  Object.entries(labelMap).forEach(([code, label]) => push(code, label));
  return out;
}

export function buildLabelLookupFromDimensionMeta(raw) {
  return buildLabelLookupFromMeta(raw);
}
