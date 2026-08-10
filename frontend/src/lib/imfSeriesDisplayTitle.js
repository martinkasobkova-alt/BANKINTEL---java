/**
 * Lidský titulek IMF řady v katalogu — preferuje plný název z API před technickým kódem.
 */
export function imfSeriesDisplayTitle(row) {
  const code = String(
    row?.imf_indicator_code || row?.imf_indicator || row?.query_params?.imf_indicator || "",
  )
    .trim()
    .toUpperCase();
  const candidates = [row?.imf_indicator_name, row?.name]
    .map((s) => String(s || "").trim())
    .filter(Boolean);
  for (const label of candidates) {
    if (!code || label.toUpperCase() !== code) return label;
  }
  return candidates[0] || code;
}

export function imfSeriesHasHumanTitle(row) {
  const code = String(
    row?.imf_indicator_code || row?.imf_indicator || row?.query_params?.imf_indicator || "",
  )
    .trim()
    .toUpperCase();
  const title = imfSeriesDisplayTitle(row);
  return Boolean(title) && (!code || title.toUpperCase() !== code);
}
