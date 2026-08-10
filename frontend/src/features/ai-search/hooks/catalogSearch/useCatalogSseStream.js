export async function runCatalogDeepSearchSseStream({
  url,
  signal,
  timeoutMs,
  isRequestCurrent,
  onSourceStatus,
  onPartial,
  onLane,
  onFinal,
}) {
  if (typeof EventSource === "undefined") {
    return {
      payload: null,
      laneResults: {},
      telemetry: { fallback_post_started: false, fallback_post_skipped_reason: "eventsource_unavailable" },
    };
  }
  let payload = null;
  const laneResults = {};
  const telemetry = {
    sse_started: true,
    sse_first_event_received: false,
    sse_final_received: false,
    sse_lane_events_received: 0,
    sse_closed: false,
    fallback_post_started: false,
    fallback_post_skipped_reason: "",
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
      resolve();
    };
    const onAbort = () => {
      closing = true;
      close();
      done();
    };
    signal.addEventListener("abort", onAbort, { once: true });
    const watchdog = window.setTimeout(() => {
      onAbort();
    }, timeoutMs);

    const handleLane = (rawData) => {
      if (!isRequestCurrent()) return;
      try {
        const msg = JSON.parse(rawData || "{}");
        telemetry.sse_first_event_received = true;
        telemetry.sse_lane_events_received += 1;
        const source = String(msg.source || "").toLowerCase();
        const rows = Array.isArray(msg.rows) ? msg.rows : [];
        if (source) {
          laneResults[source] = rows;
        }
        if (typeof onLane === "function") {
          onLane({ ...msg, event: "lane", source, rows, count: Number(msg.count ?? rows.length) || 0 });
        }
      } catch  {
        /* noop */
      }
    };

    const applyFinalPayload = (msg) => {
      if (!msg || typeof msg !== "object") return;
      telemetry.sse_final_received = true;
      if (typeof onFinal === "function") {
        onFinal({ ...msg, event: "final" });
      }
    };

    const handleFinal = (rawData) => {
      if (!isRequestCurrent()) return;
      try {
        const msg = JSON.parse(rawData || "{}");
        telemetry.sse_first_event_received = true;
        payload = msg;
        applyFinalPayload(msg);
        window.clearTimeout(watchdog);
        signal.removeEventListener("abort", onAbort);
        closing = true;
        close();
        done();
      } catch  {
        /* noop */
      }
    };

    es.addEventListener("lane", (ev) => {
      handleLane(ev.data);
    });

    es.addEventListener("final", (ev) => {
      handleFinal(ev.data);
    });

    es.onmessage = (ev) => {
      if (!isRequestCurrent()) return;
      try {
        const msg = JSON.parse(ev.data || "{}");
        telemetry.sse_first_event_received = true;
        if (msg.event === "stream_open" && typeof onSourceStatus === "function") {
          onSourceStatus(msg);
        }
        if (
          msg.source &&
          (msg.event === "source_started" ||
            msg.event === "source_finished" ||
            msg.event === "source_timeout" ||
            msg.event === "source_error" ||
            msg.event === "source_terminal")
        ) {
          onSourceStatus(msg);
        }
        if (
          (msg.event === "source_candidates" ||
            msg.event === "source_candidates_missing_payload" ||
            msg.event === "ranking_update" ||
            msg.event === "preview_started" ||
            msg.event === "preview_ready" ||
            msg.event === "deep_ai_update") &&
          typeof onPartial === "function"
        ) {
          onPartial(msg);
        }
        if (msg.event === "search_finished" && msg.payload) {
          payload = msg.payload;
          applyFinalPayload(msg.payload);
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
      if (!closing) {
        close();
      }
      done();
    };
  });
  telemetry.sse_closed = true;
  return { payload, laneResults, telemetry };
}
