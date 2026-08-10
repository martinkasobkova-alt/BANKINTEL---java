/**
 * Thin wrapper around POST /stocks/search for the main catalog search page - keeps the stock
 * pipeline (Yahoo Finance / Alpha Vantage tickers) entirely separate from the catalog search-v2
 * pipeline, mirroring the shape of `runInAppSearch` so callers can treat both as independent,
 * equally-shaped result groups rather than merging them into one ranked list.
 */
export async function runStockSearch(apiClient, { query, limit = 5 } = {}) {
  const q = String(query || "").trim();
  if (q.length < 2) return { query: q, results: [], errors: [] };

  try {
    const { data } = await apiClient.post(
      "/stocks/search",
      { query: q, limit, allow_llm: false },
      { timeout: 12_000 },
    );
    const results = Array.isArray(data?.results) ? data.results : [];
    return { query: q, results: results.slice(0, limit), errors: data?.ok === false ? [String(data?.error || "")] : [] };
  } catch (err) {
    return { query: q, results: [], errors: [String(err?.message || err || "Vyhledávání akcií selhalo.")] };
  }
}
