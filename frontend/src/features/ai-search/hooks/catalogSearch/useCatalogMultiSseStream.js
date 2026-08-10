import { CROSS_SEARCH_TIMEOUT_MS } from "@/lib/catalogCrossSearch";

export function multiSearchPayloadFromFinal(msg, query) {
  if (!msg || typeof msg !== "object") {
    return {
      query: String(query || "").trim(),
      results: [],
      themes_triggered: [],
      source_summaries: [],
      message_cs: "",
      elapsed_ms: 0,
    };
  }
  const summaries = Array.isArray(msg.source_summaries)
    ? msg.source_summaries
    : msg.source_statuses && typeof msg.source_statuses === "object"
      ? Object.values(msg.source_statuses)
      : [];
  return {
    query: String(msg.query || query || "").trim(),
    results: Array.isArray(msg.hits) ? msg.hits : [],
    themes_triggered: Array.isArray(msg.themes) ? msg.themes : [],
    source_summaries: summaries,
    message_cs: String(msg.message_cs || "").trim(),
    elapsed_ms: Number(msg.elapsed_ms) || 0,
  };
}

export async function runCatalogMultiSearchSseStream({
  url,
  signal,
  timeoutMs = CROSS_SEARCH_TIMEOUT_MS,
  isRequestCurrent,
  onLane,
  onFinal,
}) {
  if (typeof EventSource === "undefined") {
    return {
      payload: null,
      laneResults: {},
      finalHits: [],
      awaitingFinal: false,
      telemetry: { sse_started: false, fallback_post_skipped_reason: "eventsource_unavailable" },
    };
  }

  let payload = null;
  let finalHits = [];
  const laneResults = {};
  let awaitingFinal = true;
  const telemetry = {
    sse_started: true,
    sse_first_event_received: false,
    sse_final_received: false,
    sse_lane_events_received: 0,
    sse_closed: false,
  };

  await new Promise((resolve) => {
    const es = new EventSource(url);
    let settled = false;
    let closing = false;
    const close = () => {
      try {
        es.close();
      } catch  {
        /* noop */
      }
    };
    const done = () => {
      if (settled) return;
      settled = true;
      awaitingFinal = false;
      resolve();
    };
    const onAbort = () => {
      closing = true;
      close();
      done();
    };
    signal.addEventListener("abort", onAbort, { once: true });
    const watchdog = window.setTimeout(onAbort, timeoutMs);

    const handleLane = (rawData) => {
      if (!isRequestCurrent()) return;
      try {
        const msg = JSON.parse(rawData || "{}");
        telemetry.sse_first_event_received = true;
        telemetry.sse_lane_events_received += 1;
        const source = String(msg.source || "").toLowerCase();
        const hits = Array.isArray(msg.hits) ? msg.hits : [];
        if (source) {
          laneResults[source] = hits;
        }
        if (typeof onLane === "function") {
          onLane({
            ...msg,
            event: "lane",
            source,
            hits,
            count: Number(msg.count ?? hits.length) || 0,
            themes: Array.isArray(msg.themes) ? msg.themes : [],
          });
        }
      } catch  {
        /* noop */
      }
    };

    const handleFinal = (rawData) => {
      if (!isRequestCurrent()) return;
      try {
        const msg = JSON.parse(rawData || "{}");
        telemetry.sse_first_event_received = true;
        telemetry.sse_final_received = true;
        finalHits = Array.isArray(msg.hits) ? msg.hits : [];
        awaitingFinal = false;
        if (typeof onFinal === "function") {
          onFinal({ ...msg, event: "final" });
        }
      } catch  {
        /* noop */
      }
    };

    es.addEventListener("lane", (ev) => handleLane(ev.data));
    es.addEventListener("final", (ev) => handleFinal(ev.data));

    es.onmessage = (ev) => {
      if (!isRequestCurrent()) return;
      try {
        const msg = JSON.parse(ev.data || "{}");
        telemetry.sse_first_event_received = true;
        if (msg.event === "search_finished" && msg.payload) {
          payload = msg.payload;
          telemetry.sse_final_received = true;
          finalHits = Array.isArray(msg.payload?.results) ? msg.payload.results : finalHits;
          awaitingFinal = false;
          window.clearTimeout(watchdog);
          signal.removeEventListener("abort", onAbort);
          closing = true;
          close();
          done();
        }
      } catch  {
        /* noop */
      }
    };

    es.onerror = () => {
      telemetry.sse_closed = true;
      window.clearTimeout(watchdog);
      signal.removeEventListener("abort", onAbort);
      if (!closing) close();
      done();
    };
  });

  telemetry.sse_closed = true;
  return { payload, laneResults, finalHits, awaitingFinal, telemetry };
}
