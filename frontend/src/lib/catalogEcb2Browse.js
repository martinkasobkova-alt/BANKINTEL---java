import { normalizeEcb2BrowseBucket } from "@/lib/ecbSeriesDisplayTitle";

/**
 * Určí, kterou ECB 2 lazy větev načíst po výběru složky ve sloupcovém exploreru.
 * Preferuje metadata (ecb_*_lazy) před depth — Miller sloupce nemusí odpovídat depth po patchi.
 */
export function resolveEcb2BrowseLazyAction(row, columnIndex = null) {
  const code = String(row?.ecb_country || "").trim().toUpperCase();
  if (!code) return null;

  const flow = String(row?.ecb_flow || "").trim().toUpperCase();
  const letter = normalizeEcb2BrowseBucket(row?.ecb_letter);
  const depth = Number(row?.depth ?? -1);
  const col =
    columnIndex != null && Number.isFinite(Number(columnIndex))
      ? Number(columnIndex)
      : null;

  const path = String(row?.path || "").trim();

  if (letter && flow && (row?.ecb_letter_lazy || depth === 3 || col === 3)) {
    return { kind: "letter", code, flow, letter, path };
  }

  if (
    flow &&
    (row?.ecb_flow_lazy || row?.ecb_flow_letter_lazy || depth === 2 || col === 2)
  ) {
    return { kind: "flow", code, flow, path };
  }

  if (row?.ecb_country_lazy || depth === 1 || col === 1) {
    return { kind: "country", code, path };
  }

  return null;
}

/** Miller sloupec země má načtené dataset složky (BSI, …)? */
export function ecb2CountryBranchHasFlowChildren(allRows, countryPath) {
  const path = String(countryPath || "").trim();
  if (!path) return false;
  return (Array.isArray(allRows) ? allRows : []).some(
    (r) =>
      r?.kind === "cat" &&
      String(r?.parentPath || "").trim() === path &&
      String(r?.ecb_flow || "").trim(),
  );
}
