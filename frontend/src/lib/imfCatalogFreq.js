/** Přepínač frekvence u IMF grafů (M/Q/A) — jen frekvence dostupné u dané řady. */

export const IMF_FREQ_OPTIONS = [
  { frekvence: "M", frekvence_label: "Měsíčně" },
  { frekvence: "Q", frekvence_label: "Čtvrtletně" },
  { frekvence: "A", frekvence_label: "Ročně" },
];

const IMF_FREQ_ORDER = { M: 0, Q: 1, A: 2 };

export function normalizeImfFreq(code) {
  const c = String(code || "A").trim().toUpperCase();
  return IMF_FREQ_OPTIONS.some((o) => o.frekvence === c) ? c : "A";
}

export function imfFreqLabel(code) {
  const c = normalizeImfFreq(code);
  return IMF_FREQ_OPTIONS.find((o) => o.frekvence === c)?.frekvence_label || c;
}

export function imfFreqOptionsFromCodes(codes) {
  const allowed = new Set(
    (Array.isArray(codes) ? codes : [])
      .map((c) => String(c || "").trim().toUpperCase())
      .filter((c) => IMF_FREQ_OPTIONS.some((o) => o.frekvence === c)),
  );
  if (!allowed.size) return [];
  return IMF_FREQ_OPTIONS.filter((o) => allowed.has(o.frekvence));
}

export function imfFreqFromSetId(setId) {
  const sid = String(setId || "").trim();
  if (!sid) return "";
  const tail = sid.includes("|") ? sid.split("|").pop() : sid;
  const last = String(tail || "").split(".").pop()?.trim().toUpperCase() || "";
  return IMF_FREQ_OPTIONS.some((o) => o.frekvence === last) ? last : "";
}

export function imfFreqOptionsFromRow(row) {
  if (!row || typeof row !== "object") return [];
  const varianty = Array.isArray(row.varianty) ? row.varianty : [];
  if (varianty.length) {
    const opts = varianty
      .map((v) => ({
        frekvence: normalizeImfFreq(v?.frekvence),
        frekvence_label: String(v?.frekvence_label || imfFreqLabel(v?.frekvence)).trim(),
      }))
      .filter((o) => o.frekvence);
    const seen = new Set();
    return opts.filter((o) => {
      if (seen.has(o.frekvence)) return false;
      seen.add(o.frekvence);
      return true;
    });
  }
  const fromRow =
    imfFreqFromSetId(row.set_id) ||
    normalizeImfFreq(row?.frekvence || row?.query_params?.imf_frekvence || "");
  if (!fromRow) return [];
  return [{ frekvence: fromRow, frekvence_label: imfFreqLabel(fromRow) }];
}

/** Možnosti frekvence z API preview nebo z řádku katalogu (bez vymyšlených M/Q/A). */
export function resolveImfFreqOptions({ previewData = null, row = null } = {}) {
  const fromPreviewOpts = previewData?.imf_frequency_options;
  if (Array.isArray(fromPreviewOpts) && fromPreviewOpts.length) {
    const opts = fromPreviewOpts
      .map((o) => ({
        frekvence: normalizeImfFreq(o?.frekvence || o?.code),
        frekvence_label: String(o?.frekvence_label || o?.label || imfFreqLabel(o?.frekvence)).trim(),
      }))
      .filter((o) => o.frekvence);
    const seen = new Set();
    const deduped = opts.filter((o) => {
      if (seen.has(o.frekvence)) return false;
      seen.add(o.frekvence);
      return true;
    });
    if (deduped.length) {
      return deduped.sort(
        (a, b) => (IMF_FREQ_ORDER[a.frekvence] ?? 9) - (IMF_FREQ_ORDER[b.frekvence] ?? 9),
      );
    }
  }
  const fromCodes = imfFreqOptionsFromCodes(previewData?.imf_available_frequencies);
  if (fromCodes.length) return fromCodes;
  return imfFreqOptionsFromRow(row);
}
