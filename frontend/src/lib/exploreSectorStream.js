import { API_ROOT_URL, EXPLORE_LONG_REQUEST_TIMEOUT_MS } from "@/lib/api";
import { parseExploreManagerPayload } from "@/lib/exploreManagerPayload";

export function shouldFallbackToExploreSectorPost(streamResult) {
  return streamResult?.usedSse === false && !streamResult?.payload;
}

function createRequestId() {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/** Krátké okno na makro seed ze streamu — neblokuje první curated plán. */
const MACRO_SEED_WAIT_MS = 3500;

export function explorePayloadHasCuratedRows(body) {
  if (!body || typeof body !== "object") return false;
  const parsed = parseExploreManagerPayload(body);
  return (parsed.allRows || []).length > 0;
}

/**
 * SSE stream pro /explore/sector/stream — průběžné řady + finální odpověď.
 * Po prvním kurátorovaném plánu (preset_ready) vrátí výsledek bez čekání na deep search.
 */
export function runExploreSectorStream({
  params,
  signal,
  timeoutMs = EXPLORE_LONG_REQUEST_TIMEOUT_MS,
  onPreset,
  onPartial,
  onSourceStatus,
  onQuickPreview,
  allowEarlyComplete = true,
  acceptEvent = () => true,
}) {
  if (typeof EventSource === "undefined") {
    return Promise.resolve({ payload: null, usedSse: false });
  }

  const qs = new URLSearchParams(params instanceof URLSearchParams ? params : params || {});
  if (!qs.has("request_id")) qs.set("request_id", createRequestId());
  const url = `${API_ROOT_URL.replace(/\/$/, "")}/explore/sector/stream?${qs.toString()}`;

  return new Promise((resolve) => {
    const es = new EventSource(url);
    let payload = null;
    let settled = false;
    let macroWaitTimer = null;
    let earlyCompleteScheduled = false;
    let watchdog = null;

    const close = () => {
      try {
        es.close();
      } catch  {
        /* noop */
      }
    };

    const done = (result) => {
      if (settled) return;
      settled = true;
      if (macroWaitTimer != null) {
        window.clearTimeout(macroWaitTimer);
        macroWaitTimer = null;
      }
      if (watchdog != null) {
        window.clearTimeout(watchdog);
        watchdog = null;
      }
      resolve(result ?? { payload, usedSse: true });
    };

    const finishEarly = (reason = "curated_plan_ready") => {
      if (!payload || settled) return;
      if (watchdog != null) window.clearTimeout(watchdog);
      if (signal) signal.removeEventListener("abort", onAbort);
      close();
      done({
        payload: {
          ...payload,
          partial: true,
          macro_enrichment_pending: reason === "curated_plan_ready",
          macro_enrichment_note:
            reason === "curated_plan_ready"
              ? "Makro kontext se doplňuje na pozadí — první nástřel řad je z JSON kurátorovaného plánu."
              : undefined,
        },
        usedSse: true,
        earlyComplete: true,
      });
    };

    const scheduleEarlyComplete = () => {
      if (!allowEarlyComplete || earlyCompleteScheduled || settled) return;
      earlyCompleteScheduled = true;
      macroWaitTimer = window.setTimeout(() => finishEarly("curated_plan_ready"), MACRO_SEED_WAIT_MS);
    };

    const onAbort = () => {
      if (settled) return;
      close();
      const aborted = Boolean(signal?.aborted);
      done({
        payload,
        usedSse: true,
        aborted,
        timedOut: !aborted,
        disconnected: !aborted,
      });
    };

    const shouldProcess = () => {
      if (settled) return false;
      if (signal?.aborted) return false;
      if (!acceptEvent()) return false;
      return true;
    };

    if (signal) {
      if (signal.aborted) {
        done({ payload: null, usedSse: true, aborted: true });
        return;
      }
      signal.addEventListener("abort", onAbort, { once: true });
    }

    watchdog = timeoutMs > 0 ? window.setTimeout(onAbort, timeoutMs) : null;

    const absorbPayload = (body, { fromMacroSeed = false } = {}) => {
      if (!body || typeof body !== "object") return;
      payload = body;
      if (fromMacroSeed && earlyCompleteScheduled && !settled) {
        finishEarly("macro_seed_ready");
      }
    };

    es.onmessage = (ev) => {
      if (!shouldProcess()) return;
      try {
        const msg = JSON.parse(ev.data || "{}");
        const event = String(msg.event || "");
        if (event === "preset_ready" && msg.payload) {
          if (typeof onPreset === "function" && acceptEvent()) onPreset(msg.payload);
          if (explorePayloadHasCuratedRows(msg.payload)) {
            absorbPayload(msg.payload);
            scheduleEarlyComplete();
          }
        }
        if (event === "indicators_update" && msg.payload) {
          if (typeof onPartial === "function" && acceptEvent()) onPartial(msg.payload);
          if (explorePayloadHasCuratedRows(msg.payload)) {
            const hasMacro = Array.isArray(msg.payload.macro_indicators) && msg.payload.macro_indicators.length > 0;
            absorbPayload(msg.payload, { fromMacroSeed: hasMacro });
            if (!earlyCompleteScheduled && explorePayloadHasCuratedRows(msg.payload)) {
              scheduleEarlyComplete();
            }
          }
        }
        if (event === "quick_data_preview" && msg.payload) {
          if (typeof onQuickPreview === "function" && acceptEvent()) onQuickPreview(msg.payload);
        }
        if (
          ["source_started", "source_finished", "source_timeout", "source_error"].includes(event) &&
          typeof onSourceStatus === "function"
        ) {
          onSourceStatus(msg);
        }
        if (event === "search_finished" && msg.payload) {
          payload = msg.payload;
          if (watchdog != null) window.clearTimeout(watchdog);
          if (signal) signal.removeEventListener("abort", onAbort);
          close();
          done({ payload, usedSse: true });
        }
        if (event === "timeout") {
          if (payload && explorePayloadHasCuratedRows(payload)) {
            finishEarly("deep_search_timeout");
            return;
          }
          if (watchdog != null) window.clearTimeout(watchdog);
          if (signal) signal.removeEventListener("abort", onAbort);
          close();
          done({
            payload: {
              ...(msg.payload || {}),
              ...(payload && typeof payload === "object" ? payload : {}),
              macro_enrichment_note: "Makro kontext nebyl plně doplněn — deep search vypršel v časovém limitu.",
            },
            usedSse: true,
            timedOut: true,
          });
        }
      } catch {
        if (typeof onSourceStatus === "function" && acceptEvent()) {
          onSourceStatus({
            event: "source_error",
            source: "stream",
            status: "error",
            reason: "malformed_payload",
          });
        }
      }
    };

    es.onerror = () => {
      if (watchdog != null) window.clearTimeout(watchdog);
      if (signal) signal.removeEventListener("abort", onAbort);
      close();
      if (settled) return;
      if (signal?.aborted) {
        done({ payload, usedSse: true, aborted: true });
        return;
      }
      if (payload && explorePayloadHasCuratedRows(payload)) {
        done({ payload: { ...payload, partial: true }, usedSse: true, earlyComplete: true, disconnected: true });
        return;
      }
      // The stream endpoint only ever produces text/event-stream (see ExploreController), so a
      // plain fetch() with Accept:application/json here always gets HTTP 406 - there is no JSON
      // representation for it to fall back to. Signal the interrupted stream to the caller;
      // restarting discovery through POST would duplicate the in-flight server-side run.
      done({ payload, usedSse: true, streamError: true, disconnected: true });
    };
  });
}
