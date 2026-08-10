import api, { formatApiErrorFromAxios } from "@/lib/api";

/**
 * Načte tematicky příbuzné ukazatele k otevřené řadě (top N napříč katalogy).
 * Backend vrací { ok, items: [{ title, source, source_label, set_id, ... }], distinct_source_count }.
 * @param {Record<string, unknown>} meta
 */
export async function fetchRelatedSeries(meta) {
  try {
    const { data } = await api.post("/catalog/related-series", meta, { timeout: 40000 });
    const items = Array.isArray(data?.items)
      ? data.items
      : Array.isArray(data?.related)
        ? data.related
        : Array.isArray(data?.results)
          ? data.results
          : [];
    const compatibleSuccess = data?.ok === true || Array.isArray(data?.items) || Array.isArray(data?.related) || Array.isArray(data?.results);
    if (compatibleSuccess) {
      return {
        ok: true,
        items,
        distinctSourceCount: Number(data.distinct_source_count || 0),
        aiUsed: data.ai_used === true,
        query: String(data.query || ""),
        messageCs: data.message_cs ? String(data.message_cs) : "",
      };
    }
    return { ok: false, messageCs: String(data?.message_cs || "Příbuzné ukazatele se nepodařilo načíst.") };
  } catch (e) {
    const status = Number(e?.response?.status || 0);
    if (status === 404) {
      return {
        ok: false,
        messageCs: "Endpoint /api/catalog/related-series není dostupný (404) — nasaďte nebo restartujte API server.",
      };
    }
    if (status === 502 || status === 503 || status === 0) {
      return { ok: false, messageCs: "Backend dočasně nedostupný — zkuste to prosím znovu." };
    }
    return { ok: false, messageCs: formatApiErrorFromAxios(e) || "Příbuzné ukazatele se nepodařilo načíst." };
  }
}
