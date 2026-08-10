import {
  runExploreSectorStream,
  shouldFallbackToExploreSectorPost,
} from "@/lib/exploreSectorStream";

function makeEventSourceMock(script) {
  const instances = [];
  class MockEventSource {
    constructor(url) {
      this.url = url;
      this.onmessage = null;
      this.onerror = null;
      instances.push(this);
      queueMicrotask(() => script(this));
    }
    close() {
      this.closed = true;
    }
  }
  return { MockEventSource, instances };
}

describe("runExploreSectorStream abort", () => {
  let originalEventSource;

  beforeEach(() => {
    originalEventSource = global.EventSource;
  });

  afterEach(() => {
    global.EventSource = originalEventSource;
    window.EventSource = originalEventSource;
  });

  test("aborts without invoking callbacks after signal abort", async () => {
    const { MockEventSource, instances } = makeEventSourceMock((es) => {
      es.onmessage?.({
        data: JSON.stringify({
          event: "preset_ready",
          payload: { sector_indicators: [{ source: "eurostat", dataset_id: "x" }] },
        }),
      });
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    const ctrl = new AbortController();
    const onPreset = jest.fn();
    const promise = runExploreSectorStream({
      params: { sector: "test" },
      signal: ctrl.signal,
      timeoutMs: 0,
      onPreset,
    });
    ctrl.abort();
    const result = await promise;
    expect(result.aborted).toBe(true);
    expect(onPreset).not.toHaveBeenCalled();
    expect(instances[0]?.closed).toBe(true);
  });

  test("ignores events when acceptEvent returns false", async () => {
    const { MockEventSource } = makeEventSourceMock((es) => {
      es.onmessage?.({
        data: JSON.stringify({
          event: "preset_ready",
          payload: { sector_indicators: [{ source: "eurostat", dataset_id: "x" }] },
        }),
      });
      es.onerror?.();
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    const onPreset = jest.fn();
    const result = await runExploreSectorStream({
      params: { sector: "test" },
      timeoutMs: 5000,
      acceptEvent: () => false,
      onPreset,
    });
    expect(onPreset).not.toHaveBeenCalled();
    expect(result.streamError || result.aborted || !result.payload).toBeTruthy();
  });

  test("on connection error with no curated payload yet, resolves streamError immediately without attempting a JSON fetch fallback", async () => {
    // The stream endpoint only ever produces text/event-stream (ExploreController has no JSON
    // representation for it) - a fetch() fallback to the same URL always 406s. Confirms that
    // path was removed rather than silently reintroduced.
    const fetchSpy = vi.fn();
    const originalFetch = global.fetch;
    global.fetch = fetchSpy;

    const { MockEventSource } = makeEventSourceMock((es) => {
      es.onerror?.();
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    try {
      const result = await runExploreSectorStream({
        params: { sector: "test" },
        timeoutMs: 5000,
      });
      expect(result.streamError).toBe(true);
      expect(result.disconnected).toBe(true);
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      global.fetch = originalFetch;
    }
  });
});

describe("explore sector POST fallback policy", () => {
  test("allows POST only when EventSource is unavailable", () => {
    expect(shouldFallbackToExploreSectorPost({ usedSse: false, payload: null })).toBe(true);
  });

  test("does not restart discovery after an active SSE connection is interrupted", () => {
    expect(
      shouldFallbackToExploreSectorPost({
        usedSse: true,
        payload: null,
        streamError: true,
        disconnected: true,
      })
    ).toBe(false);
  });

  test("isolates a malformed SSE message and continues to the valid final event", async () => {
    const { MockEventSource } = makeEventSourceMock((es) => {
      es.onmessage?.({ data: "{not-json" });
      es.onmessage?.({
        data: JSON.stringify({
          event: "search_finished",
          payload: { ok: true, sector_indicators: [], macro_indicators: [] },
        }),
      });
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;
    const onSourceStatus = vi.fn();

    const result = await runExploreSectorStream({
      params: { sector: "test" },
      timeoutMs: 5000,
      onSourceStatus,
    });

    expect(result.payload?.ok).toBe(true);
    expect(onSourceStatus).toHaveBeenCalledWith(
      expect.objectContaining({ event: "source_error", reason: "malformed_payload" })
    );
  });

  test("adds a request id to the SSE URL for cross-layer correlation", async () => {
    const { MockEventSource, instances } = makeEventSourceMock((es) => {
      es.onerror?.();
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    await runExploreSectorStream({ params: { sector: "test" }, timeoutMs: 5000 });

    const url = new URL(instances[0].url, "http://localhost");
    expect(url.searchParams.get("request_id")).toBeTruthy();
  });
});
