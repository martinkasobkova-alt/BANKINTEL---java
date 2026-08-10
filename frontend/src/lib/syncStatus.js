/** UI heuristika nad `last_sync_status` — doplnění backend opravy stalých „running“. */

import { fmtDateTime } from "@/lib/format";

export const SYNC_REASON_LABELS_CS = {
  timeout: "Čas vypršel (synchronizace nebo síť)",
  invalid_indicator: "Neplatný nebo neexistující kód indikátoru",
  invalid_dataset_or_key: "Neplatná kombinace databáze a klíče řady",
  empty_response: "API vrátilo hodnoty bez použitelných dat (např. vše NULL)",
  parser_error: "Odpověď upstreamu nelze rozparsovat jako očekávaná data",
  rate_limited: "Upstream dočasně omezuje počet dotazů (rate limit)",
  upstream_error: "Chyba nebo omezení u poskytovatele dat (HTTP / API)",
};

export function humanSyncReason(reasonCode, fallbackFromMessage = "") {
  const k = String(reasonCode || "").trim().toLowerCase();
  return SYNC_REASON_LABELS_CS[k] || (fallbackFromMessage ? String(fallbackFromMessage).trim().slice(0, 280) : "");
}

export const RUNNING_UI_STALE_MS = Number.parseInt(import.meta.env.VITE_SYNC_RUNNING_STALE_UI_MS ?? "", 10) || 10 * 60 * 1000;

function parseIsoMillis(x) {
  if (!x) return NaN;
  const t = Date.parse(x);
  return Number.isFinite(t) ? t : NaN;
}

/** Zobraný stav pro chip: pokud dlouho „running“, zobraz jako stuck/timeout. */
export function effectiveSyncBadgeStatus(row) {
  const st = row?.last_sync_status;
  if (st !== "running") return st;
  const started = parseIsoMillis(row?.last_sync_started_at || row?.last_sync_at);
  if (!Number.isFinite(started)) return "stuck";
  if (Date.now() - started > RUNNING_UI_STALE_MS) return "stuck";
  return "running";
}

export function buildSyncDetailTooltip(row) {
  if (!row) return "";
  const lines = [];
  if (row.last_sync_started_at) lines.push(`Začátek: ${fmtDateTime(row.last_sync_started_at)}`);
  if (row.last_sync_finished_at) lines.push(`Konečný čas: ${fmtDateTime(row.last_sync_finished_at)}`);
  if (typeof row.last_sync_duration_ms === "number") {
    lines.push(`Trvání: ${(row.last_sync_duration_ms / 1000).toFixed(1)} s`);
  }
  if (typeof row.last_sync_records_ingested === "number") {
    lines.push(`Nových řádků ingest: ${row.last_sync_records_ingested}`);
  }
  if (row.last_sync_http_status !== undefined && row.last_sync_http_status !== null && row.last_sync_http_status !== "") {
    lines.push(`HTTP: ${row.last_sync_http_status}`);
  }
  if (row.last_sync_reason_code) {
    const human = humanSyncReason(row.last_sync_reason_code, row.last_sync_message);
    lines.push(`Typ: ${row.last_sync_reason_code}${human ? ` — ${human}` : ""}`);
  }
  const err = row.last_sync_error || row.last_sync_message;
  if (err && String(err).trim()) lines.push(`Poslední zpráva: ${String(err)}`);
  if (row.last_sync_response_preview && String(row.last_sync_response_preview).trim()) {
    lines.push(`Úryvek odpovědi: ${String(row.last_sync_response_preview).slice(0, 400)}`);
  }
  return lines.filter(Boolean).join("\n");
}
