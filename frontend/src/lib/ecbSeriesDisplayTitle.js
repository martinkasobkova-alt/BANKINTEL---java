/**
 * Lidský titulek ECB / ECB 2 řady — bez SDMX klíče a technických diff tagů.
 */
const SDMX_KEY_RX = /^[MQADWH]\.[A-Z0-9._]+$/i;
const GENERIC_SECTOR_RX =
  /deposit-taking corporations|except the central bank|credit institution|money.?market funds|mfis excluding/i;
const TECH_DIFF_RX = /^(BS ITEM|MATURITY|DATA TYPE|AMOUNT CAT|FREQ|REF_AREA):/i;

const CURRENCY_LABELS = {
  EUR: "Euro",
  LTL: "Litevský litas",
  USD: "US dolar",
  GBP: "Britská libra",
};

function looksLikeEcbSeriesKey(text, seriesKey = "") {
  const s = String(text || "").trim();
  const sk = String(seriesKey || "").trim();
  if (!s) return true;
  if (sk && s === sk) return true;
  if (SDMX_KEY_RX.test(s)) return true;
  return s.includes(".") && s.split(".").length >= 5 && /^[A-Z0-9._]+$/i.test(s);
}

function isGenericSectorTitle(text) {
  return GENERIC_SECTOR_RX.test(String(text || "").trim());
}

function currencyFromSeriesKey(seriesKey = "") {
  const sk = String(seriesKey || "").trim().toUpperCase();
  for (const [code, label] of Object.entries(CURRENCY_LABELS)) {
    if (sk.includes(`.${code}.`)) return label;
  }
  return "";
}

function diffTagsToTitle(diff, seriesKey = "") {
  if (!Array.isArray(diff) || !diff.length) return "";
  const parts = diff
    .map((tag) => {
      const t = String(tag || "").trim();
      if (TECH_DIFF_RX.test(t) && t.includes(": ")) {
        return t.split(": ").slice(1).join(": ").trim();
      }
      return t.includes(": ") ? t.split(": ").slice(1).join(": ").trim() : t;
    })
    .filter(Boolean);
  let title = parts.slice(0, 4).join(" · ");
  const cur = currencyFromSeriesKey(seriesKey);
  if (cur && !title.toLowerCase().includes(cur.toLowerCase())) {
    title = title ? `${cur} · ${title}` : cur;
  }
  return title;
}

export function ecbSeriesDisplayTitle(row) {
  const sk = String(row?.ecb_series_key || row?.set_id || "")
    .split("/")
    .pop()
    .trim();
  const cur = String(row?.ecb_currency || "").trim() || currencyFromSeriesKey(sk);

  const candidates = [
    row?.ecb_browse_row_title,
    row?.name,
    ...(Array.isArray(row?.ecb_code_labels) ? row.ecb_code_labels : []),
    diffTagsToTitle(row?.ecb_series_diff, sk),
    row?.ecb_value_descriptor,
    row?.ecb_series_explanation,
  ]
    .map((s) => String(s || "").trim())
    .filter(Boolean);

  for (let label of candidates) {
    if (looksLikeEcbSeriesKey(label, sk)) continue;
    if (isGenericSectorTitle(label)) continue;
    if (TECH_DIFF_RX.test(label)) continue;
    if (cur && !label.toLowerCase().includes(cur.toLowerCase())) {
      label = `${cur} · ${label}`;
    }
    return label;
  }

  if (cur) return cur;
  return sk;
}

export function ecbSeriesHasHumanTitle(row) {
  const sk = String(row?.ecb_series_key || row?.set_id || "")
    .split("/")
    .pop()
    .trim();
  const title = ecbSeriesDisplayTitle(row);
  return Boolean(title) && !looksLikeEcbSeriesKey(title, sk);
}

/** Klíč ECB 2 skupiny — slug zůstane v původním tvaru, staré písmeno A–Z se normalizuje. */
export function normalizeEcb2BrowseBucket(letter) {
  const raw = String(letter || "").trim();
  if (!raw) return "";
  if (/^[a-z0-9][a-z0-9-]*$/.test(raw) && raw.length > 1) return raw;
  return raw.toUpperCase();
}
