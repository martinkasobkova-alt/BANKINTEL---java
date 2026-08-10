/**
 * Real per-source discovery progress, built from SSE source_started/source_finished/
 * source_timeout/source_skipped/source_error events - replaces the old fake animation driven by
 * hardcoded source/category name lists with no connection to what the backend is actually doing
 * (see MANAGER_EXPLORER_AUDIT_V2.md section 4.1).
 */

/**
 * Applies one onSourceStatus SSE message to the previous ordered list of per-source rows,
 * returning a new list. Insertion order is preserved (sources keep their position once
 * "source_started" arrives), so the UI list doesn't jump around as later events arrive.
 */
export function applySourceStatusEvent(prevRows, msg) {
  const source = String(msg?.source || "").trim();
  if (!source) return prevRows;
  const event = String(msg?.event || "");
  const next = new Map((prevRows || []).map((row) => [row.source, row]));

  if (event === "source_started") {
    if (!next.has(source)) next.set(source, { source, status: "running", candidates: null });
  } else if (event === "source_finished") {
    next.set(source, {
      source,
      status: String(msg.status || "ok"),
      candidates: Number(msg.candidates || 0),
    });
  } else if (event === "source_timeout") {
    next.set(source, {
      source,
      status: "timeout",
      candidates: 0,
      ...(msg?.reason ? { reason: String(msg.reason) } : {}),
    });
  } else if (event === "source_skipped") {
    // Intentionally not dispatched (e.g. CZ-only ARAD/ČSÚ on an Austria query) — not a failure.
    next.set(source, {
      source,
      status: "skipped",
      candidates: 0,
      ...(msg?.reason ? { reason: String(msg.reason) } : {}),
    });
  } else if (event === "source_error") {
    next.set(source, {
      source,
      status: "error",
      candidates: 0,
      ...(msg?.reason ? { reason: String(msg.reason) } : {}),
    });
  } else {
    return prevRows;
  }
  return Array.from(next.values());
}

export function sourceStatusIssues(rows) {
  return (Array.isArray(rows) ? rows : []).filter(
    (row) => row?.status === "timeout" || row?.status === "error",
  );
}
