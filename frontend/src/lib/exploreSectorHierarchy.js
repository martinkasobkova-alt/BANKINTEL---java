export function splitExploreRelatedValues(...values) {
  const out = [];
  const seen = new Set();
  for (const value of values) {
    const parts = String(value || "")
      .split(/[,;|/]+/)
      .map((x) => x.trim())
      .filter(Boolean);
    for (const part of parts) {
      const key = part.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(part);
    }
  }
  return out;
}

export function joinExploreRelatedValues(...values) {
  return splitExploreRelatedValues(...values).join(", ");
}

export function normalizeExploreSegmentValues(values) {
  const rawValues = Array.isArray(values) ? values : [values];
  const out = [];
  const seen = new Set();
  for (const value of rawValues) {
    const text = String(value || "").trim();
    const key = text.toLowerCase();
    if (!text || seen.has(key)) continue;
    seen.add(key);
    out.push(text);
  }
  return out;
}

/** Abecedně (cs) pro selecty — pořadí v datech nemění. */
export function sortExploreSegmentLabels(values) {
  return [...normalizeExploreSegmentValues(values)].sort((a, b) =>
    a.localeCompare(b, "cs", { sensitivity: "base" })
  );
}

export function segmentLabelKey(value) {
  return String(value || "").trim().toLowerCase();
}

export function toSupplementarySelectionRows(segments) {
  const normalized = normalizeExploreSegmentValues(segments);
  return normalized.length ? normalized : [""];
}

export function buildRelatedSegmentList({
  primarySector = "",
  supplementarySegments = [],
  relatedSegmentsText = "",
} = {}) {
  const primaryKey = segmentLabelKey(primarySector);
  const fromSupplementary = normalizeExploreSegmentValues(
    Array.isArray(supplementarySegments) ? supplementarySegments : []
  );
  const fromText = splitExploreRelatedValues(relatedSegmentsText);
  return normalizeExploreSegmentValues([...fromSupplementary, ...fromText]).filter(
    (label) => !primaryKey || segmentLabelKey(label) !== primaryKey
  );
}

export function swapPrimaryWithRelated({
  primarySector = "",
  relatedSector = "",
  supplementarySegments = [],
  relatedSegmentsText = "",
} = {}) {
  const oldPrimary = String(primarySector || "").trim();
  const newPrimary = String(relatedSector || "").trim();
  if (!oldPrimary || !newPrimary) return null;
  if (segmentLabelKey(oldPrimary) === segmentLabelKey(newPrimary)) return null;

  const currentRelated = buildRelatedSegmentList({
    primarySector: oldPrimary,
    supplementarySegments,
    relatedSegmentsText,
  });
  const nextRelated = normalizeExploreSegmentValues([
    oldPrimary,
    ...currentRelated.filter((label) => segmentLabelKey(label) !== segmentLabelKey(newPrimary)),
  ]);

  return {
    primarySector: newPrimary,
    supplementarySegmentSelections: toSupplementarySelectionRows(nextRelated),
    relatedSegmentsText: "",
    relatedSegments: nextRelated,
  };
}

export function buildExploreSectorHierarchy({
  primarySector = "",
  supplementarySegments = [],
  relatedSegmentsText = "",
  relationshipRows = [],
  excludedRelatedKeys = [],
} = {}) {
  const primary = String(primarySector || "").trim();
  const relatedSegments = buildActiveRelatedSegmentLabels({
    primarySector: primary,
    relationshipRows,
    excludedRelatedKeys,
    supplementarySegments,
    relatedSegmentsText,
  });
  return {
    primarySector: primary,
    relatedSegments,
    combinedRelatedSegments: relatedSegments.join(", "),
  };
}

function relatedRowLabel(row) {
  if (!row || typeof row !== "object") return "";
  return String(row.sector_name_cs || row.name_cs || row.name || row.sector_id || "").trim();
}

function relatedRowBrief(row) {
  const name = relatedRowLabel(row);
  const rel = String(row?.relationship_type || "").trim();
  return rel ? `${name} (${rel})` : name;
}

function relatedRowKey(row) {
  const fromId = segmentLabelKey(row?.sector_id);
  const fromName = segmentLabelKey(relatedRowLabel(row));
  return fromId || fromName;
}

export function buildActiveRelatedSegmentLabels({
  primarySector = "",
  relationshipRows = [],
  excludedRelatedKeys = [],
  supplementarySegments = [],
  relatedSegmentsText = "",
} = {}) {
  return buildUnifiedRelatedSegmentItems({
    primarySector,
    relationshipRows,
    excludedRelatedKeys,
    supplementarySegments,
    relatedSegmentsText,
  }).map((item) => item.label);
}

export function buildUnifiedRelatedSegmentItems({
  primarySector = "",
  relationshipRows = [],
  excludedRelatedKeys = [],
  supplementarySegments = [],
  relatedSegmentsText = "",
  orderKeys = [],
} = {}) {
  const primaryKey = segmentLabelKey(primarySector);
  const excluded = new Set(
    (Array.isArray(excludedRelatedKeys) ? excludedRelatedKeys : []).map(segmentLabelKey).filter(Boolean)
  );
  const items = [];
  const seen = new Set();

  for (const row of Array.isArray(relationshipRows) ? relationshipRows : []) {
    const key = relatedRowKey(row);
    const label = relatedRowLabel(row);
    if (!label || !key || excluded.has(key) || seen.has(key) || key === primaryKey) continue;
    seen.add(key);
    items.push({
      key,
      label,
      brief: relatedRowBrief(row),
      sector_id: String(row?.sector_id || row?.related_segment_id || "").trim(),
      weight: row?.weight,
      rank: row?.rank,
      priority_tier: row?.priority_tier,
      relationship_type: row?.relationship_type,
      reason: String(row?.reason_cs || row?.reason || "").trim(),
      source: "predefined",
    });
  }

  for (const label of normalizeExploreSegmentValues(supplementarySegments)) {
    const key = segmentLabelKey(label);
    if (!key || seen.has(key) || key === primaryKey) continue;
    seen.add(key);
    items.push({
      key,
      label,
      brief: label,
      weight: null,
      rank: null,
      priority_tier: "",
      reason: "",
      source: "manual",
    });
  }

  for (const label of splitExploreRelatedValues(relatedSegmentsText)) {
    const key = segmentLabelKey(label);
    if (!key || seen.has(key) || key === primaryKey) continue;
    seen.add(key);
    items.push({
      key,
      label,
      brief: label,
      weight: null,
      rank: null,
      priority_tier: "",
      reason: "",
      source: "topic",
    });
  }

  return sortAndRenumberRelatedSegmentItems(
    orderKeys?.length ? applyRelatedSegmentOrder(items, orderKeys) : items
  );
}

const RANK_WEIGHTS = [0.8, 0.65, 0.55, 0.45, 0.35, 0.3, 0.28];

export function weightForRelatedSegmentRank(rank) {
  const r = Math.max(1, Number(rank) || 1);
  if (r <= RANK_WEIGHTS.length) return RANK_WEIGHTS[r - 1];
  return Math.max(0.22, 0.28 - (r - RANK_WEIGHTS.length) * 0.02);
}

export function applyRelatedSegmentOrder(items, orderKeys) {
  const order = (Array.isArray(orderKeys) ? orderKeys : []).map(segmentLabelKey).filter(Boolean);
  if (!order.length) return Array.isArray(items) ? [...items] : [];
  const byKey = new Map(
    (Array.isArray(items) ? items : []).map((item) => [segmentLabelKey(item.key || item.label), item])
  );
  const out = [];
  for (const key of order) {
    const item = byKey.get(key);
    if (!item) continue;
    out.push(item);
    byKey.delete(key);
  }
  for (const item of Array.isArray(items) ? items : []) {
    const key = segmentLabelKey(item.key || item.label);
    if (byKey.has(key)) out.push(item);
  }
  return out;
}

function priorityTierForRelatedItem(item, rank) {
  const weight = Number(item?.weight) || 0;
  const rel = String(item?.relationship_type || "").trim().toLowerCase();
  if (rel === "financial_market_context") return "supplementary_context";
  if (item?.source === "manual") return "manual";
  if (item?.source === "topic") return "topic";
  if (rank <= 2 || weight >= 0.75) return "core";
  if (rank <= 5 || weight >= 0.55) return "secondary";
  return "conditional";
}

export function sortAndRenumberRelatedSegmentItems(items) {
  return (Array.isArray(items) ? items : []).map((item, index) => {
    const rank = index + 1;
    const weight = weightForRelatedSegmentRank(rank);
    return {
      ...item,
      rank,
      weight,
      priority_tier: priorityTierForRelatedItem({ ...item, weight }, rank),
    };
  });
}

