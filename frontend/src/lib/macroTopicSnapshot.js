import api from "@/lib/api";
import { normalizePreviewPayload } from "@/lib/previewNormalizer";

export function formatSnapshotGeneratedAt(iso) {
  const raw = String(iso || "").trim();
  if (!raw) return null;
  const dt = new Date(raw);
  if (Number.isNaN(dt.getTime())) return null;
  return dt.toLocaleString("cs-CZ", {
    dateStyle: "medium",
    timeStyle: "short",
  });
}

export async function fetchMacroTopicSeriesPreview(row) {
  const catalogId = String(row?.catalog_id || row?.source || "").trim();
  const setId = String(row?.set_id || "").trim();
  const geo = String(row?.geo || "").trim().toUpperCase();
  const topicId = String(row?.topic_id || "").trim();
  if (!catalogId || !setId || !geo) {
    throw new Error("Chybí identifikace řady pro snapshot náhled.");
  }
  const { data } = await api.get("/catalog/macro-topics/series-preview", {
    params: {
      catalog_id: catalogId,
      set_id: setId,
      geo,
      ...(topicId ? { topic_id: topicId } : {}),
    },
    timeout: 30_000,
  });
  const sourceType = String(data?.source_type || data?.source?.source_type || catalogId).trim();
  return normalizePreviewPayload(data, sourceType);
}
