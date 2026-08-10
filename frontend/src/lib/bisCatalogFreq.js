/** BIS katalog — frekvence v SDMX klíči (např. M.AR = měsíčně, Argentina). */

export const BIS_FREQ_LABEL_CS = {
  A: "Roční",
  Q: "Čtvrtletní",
  M: "Měsíční",
  W: "Týdenní",
  D: "Denní",
  H: "Pololetní",
  S: "Pololetní",
};

/** Pořadí tlačítek v náhledu. */
export const BIS_FREQ_UI_ORDER = ["D", "W", "M", "Q", "A", "H", "S"];

export function bisFreqFromSeriesKey(seriesKey) {
  const sk = String(seriesKey || "").trim();
  if (!sk) return "";
  return sk.split(".")[0].trim().toUpperCase();
}

export function getBisFreqVariants(row) {
  const raw = row?.bis_freq_variants;
  if (!Array.isArray(raw) || raw.length < 2) {
    const sid = String(row?.set_id || "").trim();
    const sk = String(row?.bis_series_key || "").trim();
    const fq = bisFreqFromSeriesKey(sk);
    if (!fq || !sid) return [];
    return [{ freq: fq, set_id: sid, bis_series_key: sk, label_cs: BIS_FREQ_LABEL_CS[fq] || fq }];
  }
  return raw
    .map((v) => ({
      freq: String(v.freq || "").trim().toUpperCase(),
      set_id: String(v.set_id || "").trim(),
      bis_series_key: String(v.bis_series_key || "").trim(),
      label_cs: String(v.label_cs || BIS_FREQ_LABEL_CS[v.freq] || v.freq || "").trim(),
    }))
    .filter((v) => v.freq && v.set_id);
}

export function sortBisFreqVariants(variants) {
  const order = new Map(BIS_FREQ_UI_ORDER.map((f, i) => [f, i]));
  return [...variants].sort(
    (a, b) => (order.get(a.freq) ?? 99) - (order.get(b.freq) ?? 99) || a.freq.localeCompare(b.freq)
  );
}

/** Jedinečný klíč náhledu — vždy jedna řada, ne celá země (flow::ref otevíralo X panelů). */
export function bisPreviewGroupKey(row) {
  const sid = String(row?.set_id || row?.id || "").trim();
  if (sid) return sid;
  const flow = String(row?.bis_dataflow || "").trim();
  const sk = String(row?.bis_series_key || "").trim();
  if (flow && sk) return `${flow}::${sk}`;
  return String(row?.path || "").trim();
}

export function resolveBisVariantRow(row, freq) {
  const want = String(freq || "").trim().toUpperCase();
  const hit = getBisFreqVariants(row).find((v) => v.freq === want);
  if (!hit) return { ...row, set_id: String(row?.set_id || "").trim() };
  return {
    ...row,
    set_id: hit.set_id,
    bis_series_key: hit.bis_series_key || row.bis_series_key,
    bis_active_freq: hit.freq,
  };
}

export function bisRowAnyVariantAdded(row, existingSet) {
  const sid = String(row?.set_id || "").trim();
  if (sid && existingSet.has(sid)) return true;
  return getBisFreqVariants(row).some((v) => existingSet.has(v.set_id));
}

export function bisSeriesKeyForCountryAndFreq(row, countryCode, freq) {
  const fq = String(freq || bisDefaultPreviewFreq(row)).trim().toUpperCase();
  const code = String(countryCode || row?.ref_area || "").trim().toUpperCase();
  const variants = getBisFreqVariants(row);
  const template = variants.find((v) => v.freq === fq)?.bis_series_key || row?.bis_series_key || "";
  const parts = String(template || `${fq}.${code}`).split(".");
  if (parts.length >= 2) {
    parts[0] = fq;
    parts[parts.length - 1] = code;
    return parts.join(".");
  }
  return `${fq}.${code}`;
}

export function bisSetIdForCountryAndFreq(row, countryCode, freq) {
  const flow = String(row?.bis_dataflow || "").trim();
  const m = /^BIS\|([^|]+)\|/i.exec(String(row?.set_id || "").trim());
  const fid = flow || (m ? m[1] : "");
  const key = bisSeriesKeyForCountryAndFreq(row, countryCode, freq);
  return fid && key ? `BIS|${fid}|${key}` : "";
}

export function buildBisCountryOptionsFromRows(allRows, dataflowId) {
  const byCode = new Map();
  for (const r of allRows || []) {
    if (r.kind !== "set" || r.item_kind === "dataflow") continue;
    if (dataflowId && r.bis_dataflow !== dataflowId) continue;
    const code = String(r.ref_area || "").trim().toUpperCase();
    if (!code) continue;
    let label = String(r.territory || "").trim();
    if (!label || label.toUpperCase() === code) {
      label = String(r.name || code).trim();
    }
    if (label.includes(" — ")) {
      label = label.split(" — ")[0].trim();
    }
    byCode.set(code, label || code);
  }
  return [...byCode.entries()]
    .sort((a, b) => a[1].localeCompare(b[1], "cs"))
    .map(([code, label]) => ({ code, label }));
}

export function mergeBisCountryOptions(catalogRows, refAreasFromApi) {
  const byCode = new Map();
  for (const item of refAreasFromApi || []) {
    const code = String(item.id || item.code || "").trim().toUpperCase();
    const label = String(item.name || item.label || "").trim();
    if (code && label) byCode.set(code, label);
  }
  for (const item of catalogRows || []) {
    const code = String(item.code || "").trim().toUpperCase();
    const label = String(item.label || "").trim();
    if (code && label) byCode.set(code, label);
  }
  return [...byCode.entries()]
    .sort((a, b) => a[1].localeCompare(b[1], "cs"))
    .map(([code, label]) => ({ code, label }));
}

export function bisDefaultPreviewFreq(row) {
  const pref = String(row?.bis_default_freq || "").trim().toUpperCase();
  const variants = sortBisFreqVariants(getBisFreqVariants(row));
  if (pref && variants.some((v) => v.freq === pref)) return pref;
  for (const f of ["M", "Q", "A", "W", "D"]) {
    if (variants.some((v) => v.freq === f)) return f;
  }
  return variants[0]?.freq || bisFreqFromSeriesKey(row?.bis_series_key) || "M";
}
