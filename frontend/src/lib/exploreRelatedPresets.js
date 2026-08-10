/** Rychlé lokální návrhy doplňkových segmentů z relationship mapy. */

import relationshipData from "@/data/managerSegmentRelationships.json";

function fold(text) {
  return String(text || "")
    .trim()
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "");
}

export function resolveRelationshipSegmentId(sector) {
  const needle = fold(sector);
  if (!needle) return "";
  const segments = Array.isArray(relationshipData?.segments) ? relationshipData.segments : [];
  for (const entry of segments) {
    const segmentId = fold(entry?.segment_id);
    const segmentName = fold(entry?.segment_name_cs);
    if (needle === segmentId || needle === segmentName || needle.includes(segmentName) || segmentName.includes(needle)) {
      return String(entry?.segment_id || "").trim();
    }
  }
  return "";
}

export function linkedSectorPriorityTier({
  weight = 0,
  rank = 0,
  source = "relationship_table",
  relationshipType = "",
} = {}) {
  const normalizedSource = String(source || "").trim().toLowerCase();
  const normalizedRel = String(relationshipType || "").trim().toLowerCase();
  const rankValue = Number(rank) || 0;
  const weightValue = Number(weight) || 0;
  if (normalizedRel === "financial_market_context") {
    return "supplementary_context";
  }
  if (normalizedSource === "relationship_table") {
    if ((rankValue && rankValue <= 2) || weightValue >= 0.75) return "core";
    if ((rankValue && rankValue <= 5) || weightValue >= 0.55) return "secondary";
    return "conditional";
  }
  if (weightValue >= 0.8) return "core";
  if (weightValue >= 0.55) return "secondary";
  return "conditional";
}

export function mapRelationshipRowToLinkedSector(row) {
  if (!row || typeof row !== "object") return null;
  const sectorId = String(row.related_segment_id || row.sector_id || "").trim();
  const sectorName = String(row.related_segment_name_cs || row.sector_name_cs || "").trim();
  if (!sectorId && !sectorName) return null;
  const weight = Number(row.weight) || 0;
  const rank = Number(row.rank) || 0;
  const relationshipType = String(row.relationship_type || "").trim();
  return {
    sector_id: sectorId,
    sector_name_cs: sectorName,
    relationship_type: relationshipType,
    weight,
    rank,
    direction: String(row.direction || "context_specific").trim(),
    reason_cs: String(row.reason_cs || row.reason || "").trim(),
    priority_tier: linkedSectorPriorityTier({
      weight,
      rank,
      source: "relationship_table",
      relationshipType,
    }),
  };
}

function findRelationshipSegmentEntry(sector) {
  const segmentId = resolveRelationshipSegmentId(sector);
  const segments = Array.isArray(relationshipData?.segments) ? relationshipData.segments : [];
  if (segmentId) {
    const byId = segments.find((entry) => fold(entry?.segment_id) === fold(segmentId));
    if (byId) return byId;
  }
  const needle = fold(sector);
  return segments.find((entry) => {
    const segmentName = fold(entry?.segment_name_cs);
    return needle && (needle === segmentName || needle.includes(segmentName) || segmentName.includes(needle));
  }) || null;
}

export function localRelatedSegmentRows(sector, { limit = 8 } = {}) {
  const entry = findRelationshipSegmentEntry(sector);
  if (!entry) return [];
  const rows = Array.isArray(entry.related_segments) ? entry.related_segments : [];
  return rows
    .map((row) => mapRelationshipRowToLinkedSector(row))
    .filter(Boolean)
    .slice(0, Math.max(1, limit));
}

/**
 * Okamžité návrhy bez volání API — jen předdefinované manager segmenty.
 */
export function localRelatedSegmentSuggestions(sector, { geoLabel = "", limit = 6 } = {}) {
  void geoLabel;
  return localRelatedSegmentRows(sector, { limit })
    .map((row) => String(row?.sector_name_cs || "").trim())
    .filter(Boolean);
}
