import http from "node:http";

const listenPort = 5190;
const frontendOrigin = new URL("http://127.0.0.1:5175");
const backendOrigin = new URL("http://127.0.0.1:8081");

const state = {
  scenario: "success",
  startedAt: new Date().toISOString(),
  requests: [],
  events: [],
  streamRequests: 0,
  streamRequestIds: [],
  postDiscoveryRequests: 0,
  selectedSource: null,
  finalObservability: null,
  finalObservabilityHistory: [],
};

function resetEvidence(scenario) {
  state.scenario = scenario || "success";
  state.startedAt = new Date().toISOString();
  state.requests = [];
  state.events = [];
  state.streamRequests = 0;
  state.streamRequestIds = [];
  state.postDiscoveryRequests = 0;
  state.selectedSource = null;
  state.finalObservability = null;
  state.finalObservabilityHistory = [];
}

function record(kind, details = {}) {
  state.events.push({ at: new Date().toISOString(), kind, ...details });
}

function writeJson(res, status, body) {
  const data = Buffer.from(JSON.stringify(body, null, 2));
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": data.length,
    "cache-control": "no-store",
  });
  res.end(data);
}

function proxyRequest(req, res, targetOrigin) {
  const target = new URL(req.url, targetOrigin);
  const upstream = http.request(
    target,
    {
      method: req.method,
      headers: { ...req.headers, host: target.host },
    },
    (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
      upstreamRes.pipe(res);
    },
  );
  upstream.on("error", (error) => {
    if (!res.headersSent) writeJson(res, 502, { error: error.message });
    else res.destroy(error);
  });
  req.pipe(upstream);
}

function parsePacket(data) {
  try {
    return JSON.parse(data);
  } catch {
    return null;
  }
}

function serializePacket(packet) {
  return `data:${JSON.stringify(packet)}\n\n`;
}

function emptyFinalPacket(packet) {
  const payload = { ...(packet.payload || {}) };
  payload.ok = true;
  payload.partial = false;
  for (const key of Object.keys(payload)) {
    if (key === "recommended_chart_set" || key === "indicator_sections" || key.endsWith("_indicators")) {
      payload[key] = [];
    }
  }
  payload.discovery_fallback_reason = "catalog_discovery_empty";
  payload.empty_result = true;
  payload.clarification_message = "Pro tento dotaz nebyly nalezeny použitelné řady. Upřesněte téma nebo geografii.";
  payload.source_terminal_statuses = Object.fromEntries(
    Object.keys(payload.source_terminal_statuses || {}).map((source) => [source, "empty"]),
  );
  return { ...packet, payload };
}

function emptyIndicatorPacket(packet) {
  const payload = { ...(packet.payload || {}) };
  for (const key of Object.keys(payload)) {
    if (key === "recommended_chart_set" || key === "indicator_sections" || key.endsWith("_indicators")) {
      payload[key] = [];
    }
  }
  return { ...packet, payload };
}

function finalPacketWithInjectedSourceStatus(packet, selectedSource, status, reason) {
  const payload = { ...(packet.payload || {}) };
  payload.source_terminal_statuses = {
    ...(payload.source_terminal_statuses || {}),
    [selectedSource]: status,
  };
  payload.discovery_fallback_reason = payload.discovery_fallback_reason || reason;
  return { ...packet, payload };
}

function recordFinalObservability(packet) {
  const payload = packet?.payload || {};
  const resultRows = collectResultRows(payload);
  state.finalObservability = {
    request_id: payload.request_id || packet.request_id || null,
    discovery_run_id: payload.discovery_run_id || packet.discovery_run_id || null,
    full_discovery_run_count: payload.full_discovery_run_count ?? null,
    cache_hit: payload.cache_hit ?? null,
    fallback_reason: payload.discovery_fallback_reason ?? null,
    serving_time_ms: payload.serving_time_ms ?? null,
    cached_compute_time_ms: payload.cached_compute_time_ms ?? null,
    source_terminal_statuses: payload.source_terminal_statuses || null,
    terminal_status: payload.terminal_status || null,
    result_row_count: resultRows.length,
    top1_signature: resultRows[0]?.signature || null,
    result_signatures: resultRows.map((row) => row.signature),
  };
  state.finalObservabilityHistory.push(state.finalObservability);
}

function collectResultRows(payload) {
  const rows = [];
  const seen = new Set();
  const visit = (value, key = "") => {
    if (Array.isArray(value)) {
      if (key === "recommended_chart_set" || key === "indicator_sections" || key.endsWith("_indicators")) {
        for (const item of value) addRow(item);
      }
      for (const item of value) visit(item, key);
      return;
    }
    if (!value || typeof value !== "object") return;
    for (const [childKey, childValue] of Object.entries(value)) visit(childValue, childKey);
  };
  const addRow = (item) => {
    if (!item || typeof item !== "object") return;
    const source = String(item.canonical_source_id || item.catalog || item.source || "").trim();
    const id = String(item.set_id || item.series_id || item.id || item.code || "").trim();
    const title = String(item.title || item.name || item.label || "").trim();
    if (!source && !id && !title) return;
    const signature = `${source}|${id}|${title}`;
    if (seen.has(signature)) return;
    seen.add(signature);
    rows.push({ signature });
  };
  visit(payload);
  return rows;
}

function transformPacket(packet, res, selectedSource) {
  const event = String(packet?.event || "");
  const source = String(packet?.source || "");

  if (state.scenario === "zero" && event === "indicators_update") {
    return emptyIndicatorPacket(packet);
  }

  if (event === "search_finished") {
    let finalPacket = packet;
    if (state.scenario === "zero") {
      finalPacket = emptyFinalPacket(packet);
    } else if (selectedSource && state.scenario === "http500") {
      finalPacket = finalPacketWithInjectedSourceStatus(packet, selectedSource, "error", "http_500");
    } else if (selectedSource && state.scenario === "timeout") {
      finalPacket = finalPacketWithInjectedSourceStatus(packet, selectedSource, "timeout", "upstream_timeout");
    } else if (selectedSource && state.scenario === "malformed") {
      finalPacket = finalPacketWithInjectedSourceStatus(packet, selectedSource, "error", "malformed_payload");
    }
    recordFinalObservability(finalPacket);
    return finalPacket;
  }

  if (selectedSource && source === selectedSource && event === "source_finished") {
    if (state.scenario === "http500") {
      return { ...packet, event: "source_error", status: "error", candidates: 0, reason: "http_500" };
    }
    if (state.scenario === "timeout") {
      return { ...packet, event: "source_timeout", status: "timeout", candidates: 0, reason: "upstream_timeout" };
    }
    if (state.scenario === "malformed") {
      res.write("data:{not-valid-json\n\n");
      record("malformed_payload_injected", { source: selectedSource });
      return { ...packet, event: "source_error", status: "error", candidates: 0, reason: "malformed_payload" };
    }
  }
  return packet;
}

function proxyExploreStream(req, res) {
  state.streamRequests += 1;
  const streamRequestNumber = state.streamRequests;
  const target = new URL(req.url, backendOrigin);
  const requestId = String(target.searchParams.get("request_id") || "").trim() || null;
  state.streamRequestIds.push(requestId);
  record("stream_request", { number: streamRequestNumber, request_id: requestId, url: target.pathname + target.search });

  const upstream = http.request(
    target,
    {
      method: req.method,
      headers: {
        ...req.headers,
        host: target.host,
        accept: "text/event-stream",
        "accept-encoding": "identity",
      },
    },
    (upstreamRes) => {
      const headers = {
        ...upstreamRes.headers,
        "cache-control": "no-cache, no-transform",
        "content-type": "text/event-stream;charset=UTF-8",
        "x-accel-buffering": "no",
      };
      delete headers["content-length"];
      delete headers["content-encoding"];
      res.writeHead(upstreamRes.statusCode || 502, headers);

      let buffer = "";
      let interrupted = false;
      upstreamRes.setEncoding("utf8");
      upstreamRes.on("data", (chunk) => {
        if (interrupted) return;
        buffer += chunk.replaceAll("\r\n", "\n");
        let boundary;
        while ((boundary = buffer.indexOf("\n\n")) >= 0) {
          const frame = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          const dataLines = frame
            .split("\n")
            .filter((line) => line.startsWith("data:"))
            .map((line) => line.slice(5));
          if (!dataLines.length) {
            res.write(`${frame}\n\n`);
            continue;
          }
          const packet = parsePacket(dataLines.join("\n"));
          if (!packet) {
            res.write(`${frame}\n\n`);
            continue;
          }
          const event = String(packet.event || "");
          const source = String(packet.source || "");
          if (!state.selectedSource && event === "source_started" && source) {
            state.selectedSource = source;
          }
          record("sse_event", {
            event,
            source: source || null,
            request_id: packet.request_id || null,
            discovery_run_id: packet.discovery_run_id || null,
          });

          if (state.scenario === "interrupt" && event === "source_started") {
            interrupted = true;
            record("stream_interrupted", { source: source || null });
            upstream.destroy();
            res.end();
            return;
          }

          const transformed = transformPacket(packet, res, state.selectedSource);
          if (transformed !== packet) {
            record("sse_event_transformed", {
              source: source || null,
              from: event,
              to: transformed.event,
              reason: transformed.reason || transformed?.payload?.discovery_fallback_reason || null,
            });
          }
          res.write(serializePacket(transformed));
        }
      });
      upstreamRes.on("end", () => {
        if (interrupted) return;
        if (buffer) res.write(buffer);
        record("stream_complete", { number: streamRequestNumber });
        res.end();
      });
    },
  );
  upstream.on("error", (error) => {
    record("upstream_error", { message: error.message });
    if (!res.headersSent) writeJson(res, 502, { error: error.message });
    else res.end();
  });
  res.on("close", () => {
    if (!res.writableEnded && !upstream.destroyed) {
      record("downstream_closed", { number: streamRequestNumber, request_id: requestId });
      upstream.destroy();
    }
  });
  req.pipe(upstream);
}

const server = http.createServer((req, res) => {
  const requestUrl = new URL(req.url, `http://${req.headers.host || `127.0.0.1:${listenPort}`}`);
  state.requests.push({ at: new Date().toISOString(), method: req.method, path: requestUrl.pathname });

  if (requestUrl.pathname === "/__fault") {
    resetEvidence(requestUrl.searchParams.get("scenario") || "success");
    writeJson(res, 200, { ok: true, scenario: state.scenario });
    return;
  }
  if (requestUrl.pathname === "/__evidence") {
    const requestIdCounts = new Map();
    for (const requestId of state.streamRequestIds.filter(Boolean)) {
      requestIdCounts.set(requestId, (requestIdCounts.get(requestId) || 0) + 1);
    }
    writeJson(res, 200, {
      ...state,
      reconnectRequests: Array.from(requestIdCounts.values()).reduce(
        (count, occurrences) => count + Math.max(0, occurrences - 1),
        0,
      ),
    });
    return;
  }
  if (requestUrl.pathname === "/api/explore/sector/stream") {
    proxyExploreStream(req, res);
    return;
  }
  if (requestUrl.pathname === "/api/explore/sector" && req.method === "POST") {
    state.postDiscoveryRequests += 1;
    record("post_discovery_request");
  }
  proxyRequest(req, res, frontendOrigin);
});

server.listen(listenPort, "127.0.0.1", () => {
  console.log(`Manager Explorer fault proxy listening on http://127.0.0.1:${listenPort}`);
});
