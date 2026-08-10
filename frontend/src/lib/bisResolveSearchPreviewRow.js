/**
 * Z BIS dataflow výsledku hledání vyřeší konkrétní previewovatelnou řadu pro náhled grafu.
 */

import { flattenCatalogCategories } from "@/lib/catalogTree";
import { isCatalogRowPreviewEligible } from "@/lib/catalogRowPreviewEligible";

const REF_AREA_PRIORITY = ["CZ", "DE", "US", "GB", "EA", "EU", "JP", "FR", "AT", "PL"];

/**
 * @param {string[]} codes
 * @param {unknown[]} hints
 * @returns {string}
 */
export function pickPreferredBisRefArea(codes, hints = []) {
  const available = new Set(
    (codes || []).map((c) => String(c || "").trim().toUpperCase()).filter(Boolean),
  );
  const pool = [];
  for (const hint of hints || []) {
    const code = String(hint || "").trim().toUpperCase();
    if (code) pool.push(code);
  }
  pool.push(...REF_AREA_PRIORITY);
  for (const code of pool) {
    if (available.has(code)) return code;
  }
  return [...available][0] || "";
}

/**
 * @param {Record<string, unknown>} treeData
 * @param {{ sourceType: string }} bisDef
 * @returns {Record<string, unknown>[]}
 */
export function collectBisPreviewCandidates(treeData, bisDef) {
  const cats = Array.isArray(treeData?.categories) ? treeData.categories : [];
  const flat = flattenCatalogCategories(cats);
  return flat.filter((r) => {
    if (r.kind !== "set") return false;
    const row = { ...r, kind: "set", item_kind: r.item_kind || "selection" };
    return isCatalogRowPreviewEligible(bisDef, row);
  });
}

/**
 * @param {Record<string, unknown>[]} candidates
 * @param {unknown[]} hints
 * @returns {Record<string, unknown> | null}
 */
export function pickBestBisPreviewRow(candidates, hints = []) {
  if (!Array.isArray(candidates) || !candidates.length) return null;
  const prefs = [];
  for (const hint of hints || []) {
    if (hint && typeof hint === "object") {
      prefs.push(String(hint.ref_area || "").trim().toUpperCase());
      prefs.push(String(hint.territory || "").trim().toUpperCase());
    } else {
      prefs.push(String(hint || "").trim().toUpperCase());
    }
  }
  prefs.push(...REF_AREA_PRIORITY);
  for (const code of prefs) {
    if (!code) continue;
    const hit = candidates.find((r) => String(r.ref_area || "").trim().toUpperCase() === code);
    if (hit) return hit;
  }
  return candidates[0] || null;
}

function bisRefAreaHints(sourceRow) {
  const hints = [
    sourceRow?.ref_area,
    sourceRow?.territory,
    ...(Array.isArray(sourceRow?.geo_tags) ? sourceRow.geo_tags : []),
  ];
  return hints.filter(Boolean);
}

/**
 * @param {{ get: Function }} api
 * @param {string} flowId
 * @param {Record<string, unknown>} sourceRow
 * @param {{ sourceType: string }} bisDef
 * @param {{ timeout?: number }} [opts]
 * @returns {Promise<
 *   | { kind: "series", row: Record<string, unknown>, dataflowId: string }
 *   | { kind: "wizard", dataflowId: string, title: string }
 * >}
 */
export async function resolveBisDataflowPreviewRow(api, flowId, sourceRow, bisDef, opts = {}) {
  const id = String(flowId || "").trim();
  if (!id) {
    return { kind: "wizard", dataflowId: "", title: String(sourceRow?.name || "") };
  }

  const hints = bisRefAreaHints(sourceRow);
  const timeout = opts.timeout;

  const fetchSeries = async (refArea) => {
    const params = { dataflow: id, availability_only: "false" };
    if (refArea) params.ref_areas = refArea;
    const { data } = await api.get("/bis/catalog/series", { params, timeout });
    return data;
  };

  let data = await fetchSeries();

  if (Number(data?.total_sets) === 0) {
    return {
      kind: "wizard",
      dataflowId: id,
      title: String(sourceRow?.name || sourceRow?.title || id).trim(),
    };
  }

  if (data?.catalog_mode === "countries_lazy") {
    const children = data?.categories?.[0]?.children || [];
    const codes = children
      .map((ch) => String(ch?.ref_area || "").trim().toUpperCase())
      .filter(Boolean);
    const refArea = pickPreferredBisRefArea(codes, hints);
    if (!refArea) {
      return {
        kind: "wizard",
        dataflowId: id,
        title: String(sourceRow?.name || sourceRow?.title || id).trim(),
      };
    }
    data = await fetchSeries(refArea);
  }

  const candidates = collectBisPreviewCandidates(data, bisDef);
  const picked = pickBestBisPreviewRow(candidates, [...hints, sourceRow]);
  if (!picked) {
    return {
      kind: "wizard",
      dataflowId: id,
      title: String(sourceRow?.name || sourceRow?.title || id).trim(),
    };
  }

  const row = {
    ...picked,
    kind: "set",
    item_kind: picked.item_kind || "selection",
    bis_dataflow: String(picked.bis_dataflow || id).trim(),
    name: String(picked.name || picked.bis_readable_title || sourceRow?.name || id).trim(),
  };

  return { kind: "series", row, dataflowId: id };
}
