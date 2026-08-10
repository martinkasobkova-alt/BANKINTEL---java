export const ISO_CURRENCY_CODES = new Set([
  "AED", "ARS", "AUD", "BGN", "BRL", "CAD", "CHF", "CLP", "CNY", "COP", "CZK", "DKK",
  "EUR", "GBP", "HKD", "HUF", "IDR", "ILS", "INR", "ISK", "JPY", "KRW", "MXN", "MYR",
  "NOK", "NZD", "PHP", "PLN", "RON", "RUB", "SEK", "SGD", "THB", "TRY", "TWD", "USD",
  "ZAR",
]);

export function normalizeQueryText(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

export function compactCurrencyPairFromText(text) {
  const explicit = String(text || "").toUpperCase().match(/\b([A-Z]{3})\s*[/-]\s*([A-Z]{3})\b/);
  if (explicit) {
    const base = explicit[1];
    const quote = explicit[2];
    if (base !== quote && ISO_CURRENCY_CODES.has(base) && ISO_CURRENCY_CODES.has(quote)) {
      return { base, quote, compact: `${base}${quote}` };
    }
  }

  const words = normalizeQueryText(text).toUpperCase().match(/[A-Z]{6}/g) || [];
  for (const word of words) {
    const base = word.slice(0, 3);
    const quote = word.slice(3, 6);
    if (base !== quote && ISO_CURRENCY_CODES.has(base) && ISO_CURRENCY_CODES.has(quote)) {
      return { base, quote, compact: word };
    }
  }
  return null;
}

export function currencyPairSearchVariants(text) {
  const pair = compactCurrencyPairFromText(text);
  if (!pair) return [];
  return [
    `${pair.base} ${pair.quote} exchange rate`,
    `${pair.base} ${pair.quote} spot exchange rate`,
    `${pair.quote} ${pair.base} exchange rate`,
    `${pair.compact} exchange rate`,
  ];
}

export function uniqueNonEmpty(items) {
  const out = [];
  const seen = new Set();
  for (const raw of items || []) {
    const value = String(raw || "").replace(/\s+/g, " ").trim();
    const key = normalizeQueryText(value);
    if (!value || seen.has(key)) continue;
    seen.add(key);
    out.push(value);
  }
  return out;
}

export function addSeriesSearchQueries(rawQuery) {
  const query = String(rawQuery || "").trim();
  return uniqueNonEmpty([
    ...currencyPairSearchVariants(query),
    query,
  ]);
}

export function contextualAddSeriesSearchQueries(rawQuery, context = {}) {
  const baseQueries = uniqueNonEmpty([
    ...(Array.isArray(context?.searchQueries) ? context.searchQueries : []),
    ...addSeriesSearchQueries(rawQuery),
  ]).slice(0, 3);
  const contextTerms = normalizedDistinctTerms(context?.terms).slice(0, 4);
  if (String(context?.mode || "global") === "global" || !contextTerms.length) {
    return baseQueries;
  }
  return uniqueNonEmpty([
    ...baseQueries.flatMap((query) => contextTerms.map((term) => `${query} ${term}`)),
    ...baseQueries,
  ]).slice(0, 12);
}

export function suggestionDedupeKey(item) {
  return `${String(item?.source || item?.catalog || item?.source_type || item?.catalog_id || "").toLowerCase()}::${String(item?.set_id || item?.series_id || item?.code || "").toLowerCase()}`;
}

function normalizedDistinctTerms(values) {
  const seen = new Set();
  const terms = [];
  for (const value of values || []) {
    const term = normalizeQueryText(value).replace(/\s+/g, " ").trim();
    if (term.length < 3 || seen.has(term)) continue;
    seen.add(term);
    terms.push(term);
  }
  return terms;
}

export function buildAddSeriesSelectionContext(intent, chartContract) {
  const mode = ["inherit_chart", "explicit", "global"].includes(String(intent?.context_mode || ""))
    ? String(intent.context_mode)
    : "global";
  const activeSeriesLabels = normalizedDistinctTerms(
    (Array.isArray(chartContract?.series) ? chartContract.series : []).map((series) => series?.label || series?.name),
  );
  const llmTerms = normalizedDistinctTerms(intent?.context_terms);
  return {
    mode,
    terms: llmTerms.length || mode !== "inherit_chart" ? llmTerms : activeSeriesLabels,
    activeSeriesLabels,
    searchQueries: uniqueNonEmpty([
      ...(Array.isArray(intent?.catalog_queries) ? intent.catalog_queries : []),
      intent?.catalog_query,
    ]),
  };
}

export function suggestionMatchesSelectionContext(item, context = {}) {
  const mode = String(context?.mode || "global");
  const terms = normalizedDistinctTerms(context?.terms);
  if (mode === "global" || !terms.length) return true;
  const hay = normalizeQueryText([
    item?.title,
    item?.name,
    item?.dataset_name,
    item?.full_path,
    item?.set_id,
    item?.series_id,
    item?.code,
  ].filter(Boolean).join(" "));
  return terms.some((term) => hay.includes(term));
}

const ADD_SERIES_QUERY_STOPWORDS = new Set([
  "add",
  "and",
  "also",
  "chart",
  "data",
  "graf",
  "grafu",
  "hledej",
  "indikator",
  "najdi",
  "please",
  "pridej",
  "pridat",
  "prosim",
  "rada",
  "radu",
  "series",
  "serie",
  "serii",
  "taky",
  "take",
  "ukazatel",
  "vyvoj",
]);

function supportTokenRoot(token) {
  const value = String(token || "");
  if (value.length <= 5) return value;
  return value.slice(0, 7);
}

function supportTokens(value) {
  return normalizeQueryText(value)
    .replace(/[^a-z0-9]+/g, " ")
    .split(/\s+/)
    .filter((token) => token.length >= 3 && !ADD_SERIES_QUERY_STOPWORDS.has(token))
    .map(supportTokenRoot);
}

export function candidateHasQuerySupport(item, queries = []) {
  const queryTexts = Array.isArray(queries) ? queries : [queries];
  const queryTokens = new Set(queryTexts.flatMap(supportTokens));
  if (!queryTokens.size) return true;

  const hay = normalizeQueryText([
    item?.title,
    item?.name,
    item?.dataset_name,
    item?.description,
    item?.full_path,
    item?.set_id,
    item?.series_id,
    item?.code,
  ].filter(Boolean).join(" "));
  const compactQueries = queryTexts
    .map((query) => normalizeQueryText(query).replace(/[^a-z0-9]+/g, " ").trim())
    .filter((query) => query.length >= 3);
  if (compactQueries.some((query) => hay.includes(query))) return true;

  const hayTokens = new Set(supportTokens(hay));
  for (const token of queryTokens) {
    if (hayTokens.has(token)) return true;
  }
  return false;
}

export function addSeriesSuggestionScore(item, query, context = {}) {
  const pair = compactCurrencyPairFromText(query);
  const source = String(item?.source || item?.catalog || item?.source_type || item?.catalog_id || "").toLowerCase();
  const sourceHints = new Set([
    ...(Array.isArray(context?.sourceIds) ? context.sourceIds : []),
    ...(Array.isArray(context?.preferredSourceIds) ? context.preferredSourceIds : []),
  ].map((id) => String(id || "").toLowerCase()));
  const sid = String(item?.set_id || item?.series_id || item?.code || "");
  const title = String(item?.title || item?.name || item?.dataset_name || "");
  const hay = normalizeQueryText(`${sid} ${title} ${item?.full_path || ""}`);
  let score = 0;

  if (source === "ecb2" || source === "ecb") score += 16;
  if (source === "fred") score += 8;
  if (sourceHints.has(source)) score += 160;
  if (/^EXR\//i.test(sid)) score += 70;
  if (/\bexchange\b|\bspot\b|\bfx\b|\bforeign exchange\b/i.test(`${title} ${item?.full_path || ""}`)) score += 28;

  if (pair) {
    const base = pair.base.toLowerCase();
    const quote = pair.quote.toLowerCase();
    if (hay.includes(base)) score += 35;
    if (hay.includes(quote)) score += 35;
    if (sid.toUpperCase().includes(`${pair.base}.${pair.quote}`)) score += 90;
    if (sid.toUpperCase().includes(`${pair.quote}.${pair.base}`)) score += 55;
  }

  if (/\binterest|loan|lending|debt securities|bank lending|credit\b/i.test(title)) score -= 45;
  if (/\bUS dollar exchange rates\b/i.test(title) && pair && pair.base !== "USD" && pair.quote !== "USD") score -= 35;
  if (suggestionMatchesSelectionContext(item, context) && String(context?.mode || "global") !== "global") score += 180;
  return score;
}

export function rankAddSeriesSuggestions(items, query, context = {}) {
  return [...(Array.isArray(items) ? items : [])].sort((a, b) => {
    const diff = addSeriesSuggestionScore(b, query, context) - addSeriesSuggestionScore(a, query, context);
    if (diff) return diff;
    return String(a?.title || "").localeCompare(String(b?.title || ""), "cs");
  });
}

export function addSeriesFallbackResult(query, items = [], reason = "", options = {}) {
  const fallbackQuery = String(options?.fallbackQuery || addSeriesSearchQueries(query)[0] || query || "").trim();
  const sourceIds = uniqueNonEmpty(options?.sourceIds || []);
  const action = { type: "open_catalog_search", query: fallbackQuery };
  if (sourceIds.length === 1) {
    action.catalog = sourceIds[0];
    action.source = sourceIds[0];
  } else if (sourceIds.length > 1) {
    action.sources = sourceIds;
  }
  const suffix = reason ? ` (${reason})` : "";
  return {
    answer_cz: `Nenašel jsem bezpečně ověřitelnou řadu pro "${query}". Otevřu výběr v katalogu, aby se do grafu nepřidala špatná nebo prázdná řada.${suffix}`,
    methodology_cz: "Požadavek jsem vyhodnotil jako přidání řady do grafu. Pokud automatické ověření nedá jednoznačný výsledek, předám dotaz do katalogového výběru.",
    chart_actions: [action],
    related_items: items,
  };
}
