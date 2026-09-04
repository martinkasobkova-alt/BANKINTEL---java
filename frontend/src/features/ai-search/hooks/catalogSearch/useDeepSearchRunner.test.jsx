import React, { useEffect, useMemo } from "react";
import { act } from "react";
import { createRoot } from "react-dom/client";
import { vi } from "vitest";
import { useDeepSearchRunner } from "./useDeepSearchRunner";
import { AI_SEARCH_SCOPE_EXTENDED } from "@/hooks/catalogSearch/useCatalogSearchState";
import api from "@/lib/api";

const apiMocks = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock("@/lib/api", () => ({
  __esModule: true,
  default: {
    post: apiMocks.post,
  },
  API_ROOT_URL: "http://localhost/api",
  API_FAILURE_CORS_OR_NETWORK: "API_FAILURE_CORS_OR_NETWORK",
  catalogDeepSearchRowDedupeKey: (row) => `${String(row?.catalog_id || "")}|${String(row?.set_id || row?.series_id || "")}`,
  formatApiErrorFromAxios: (e) => String(e?.message || "api-error"),
  normalizeApiFailure: (e) => ({ isCanceled: false, message: String(e?.message || "api-error"), details: "" }),
}));

function flushMicrotasks() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function waitForAssert(assertFn, timeoutMs = 4000) {
  const start = Date.now();
  // eslint-disable-next-line no-constant-condition
  while (true) {
    try {
      assertFn();
      return;
    } catch (err) {
      if (Date.now() - start > timeoutMs) throw err;
      await act(async () => {
        await flushMicrotasks();
      });
    }
  }
}

function makeEventSourceMock(script) {
  const instances = [];
  class MockEventSource {
    constructor(url) {
      this.url = url;
      this.closed = false;
      this.onmessage = null;
      this.onerror = null;
      this.listeners = new Map();
      instances.push(this);
      script(this);
    }

    addEventListener(type, listener) {
      const listeners = this.listeners.get(type) || new Set();
      listeners.add(listener);
      this.listeners.set(type, listeners);
    }

    removeEventListener(type, listener) {
      this.listeners.get(type)?.delete(listener);
    }

    close() {
      this.closed = true;
    }

    emit(event, payload = {}) {
      const message = { data: JSON.stringify({ event, ...payload }) };
      if (event === "lane" || event === "final") {
        this.listeners.get(event)?.forEach((listener) => listener(message));
        return;
      }
      this.onmessage?.(message);
    }

    fail() {
      this.onerror?.(new Event("error"));
    }
  }
  return { MockEventSource, instances };
}

function Harness({ onReady, cancelAfterStart = false, aiSearchScope = AI_SEARCH_SCOPE_EXTENDED }) {
  const selected = useMemo(() => new Set(["eurostat", "csu"]), []);
  const sourceOrder = useMemo(() => ["eurostat", "csu"], []);
  const runner = useDeepSearchRunner({
    aiQuery: "HICP Spain",
    selected,
    useAiAssistant: true,
    aiSearchScope,
    deepSourceOrder: sourceOrder,
    deepSourceLabel: (sid) => sid.toUpperCase(),
    chunkTimeoutMs: 1000,
    totalTimeoutMs: 5000,
  });

  useEffect(() => {
    onReady?.(runner);
  }, [onReady, runner]);

  useEffect(() => {
    void runner.runDeepSearch();
    if (cancelAfterStart) {
      setTimeout(() => runner.cancelDeepSearch(), 0);
    }
    // run once for the test harness
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <pre data-testid="state">
      {JSON.stringify({
        deepData: runner.deepData,
        deepError: runner.deepError,
        deepLoading: runner.deepLoading,
      })}
    </pre>
  );
}

describe("useDeepSearchRunner SSE fallback policy", () => {
  let container;
  let root;
  let originalEventSource;

  beforeEach(() => {
    globalThis.IS_REACT_ACT_ENVIRONMENT = true;
    api.post.mockReset();
    originalEventSource = global.EventSource;
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    global.EventSource = originalEventSource;
    window.EventSource = originalEventSource;
    if (root) {
      act(() => {
        root.unmount();
      });
    }
    container?.parentNode?.removeChild(container);
  });

  test("preserves partial rows and does not restart discovery after active SSE closes", async () => {
    const { MockEventSource } = makeEventSourceMock((es) => {
      setTimeout(() => {
        es.emit("source_started", { source: "eurostat", label: "Eurostat" });
        es.emit("source_candidates", {
          source: "eurostat",
          candidates: [{ catalog_id: "eurostat", set_id: "prc_hicp_manr", name: "HICP Spain" }],
        });
        es.fail();
      }, 0);
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    await act(async () => {
      root.render(<Harness />);
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      expect(container.textContent).toContain("HICP Spain");
      expect(container.textContent).toContain("active_stream_not_restarted");
      expect(container.textContent).toContain("Ponechávám průběžné výsledky");
    });
    expect(api.post).not.toHaveBeenCalled();
  });

  test("does not start fallback POST when user cancels SSE", async () => {
    const { MockEventSource, instances } = makeEventSourceMock((es) => {
      setTimeout(() => {
        es.emit("source_started", { source: "eurostat", label: "Eurostat" });
      }, 0);
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    await act(async () => {
      root.render(<Harness cancelAfterStart />);
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      expect(instances[0]?.closed).toBe(true);
    });
    expect(api.post).not.toHaveBeenCalledWith(expect.stringContaining("/catalog/deep-search"), expect.anything(), expect.anything());
  });

  test("starts fallback POST when SSE closes before first event", async () => {
    api.post.mockResolvedValueOnce({
      data: {
        ok: true,
        verified: [{ catalog_id: "eurostat", set_id: "prc_hicp_manr", name: "HICP Spain" }],
        possible: [],
        source_statuses: [],
      },
    });
    const { MockEventSource } = makeEventSourceMock((es) => {
      setTimeout(() => {
        es.fail();
      }, 0);
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    await act(async () => {
      root.render(<Harness />);
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      expect(container.textContent).toContain("HICP Spain");
    });
    expect(api.post).toHaveBeenCalledWith(
      expect.stringContaining("/catalog/deep-search"),
      expect.objectContaining({ q: "HICP Spain", use_ai: true }),
      expect.anything(),
    );
  });

  test("does not abort deep search when aiQuery is re-set to the same trimmed value on submit", async () => {
    let resolvePost;
    const postPromise = new Promise((resolve) => {
      resolvePost = resolve;
    });
    api.post.mockReturnValueOnce(
      postPromise.then(() => ({
        data: {
          ok: true,
          verified: [{ catalog_id: "eurostat", set_id: "prc_hicp_manr", name: "HICP Spain final" }],
          possible: [],
          source_statuses: [],
        },
      })),
    );
    const { MockEventSource } = makeEventSourceMock((es) => {
      setTimeout(() => {
        es.fail();
      }, 0);
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    function SubmitHarness({ onReady }) {
      const selected = useMemo(() => new Set(["eurostat"]), []);
      const [aiQuery, setAiQuery] = React.useState("HICP Spain");
      const runner = useDeepSearchRunner({
        aiQuery,
        selected,
        useAiAssistant: true,
        aiSearchScope: AI_SEARCH_SCOPE_EXTENDED,
        deepSourceOrder: ["eurostat"],
        deepSourceLabel: (sid) => sid.toUpperCase(),
        chunkTimeoutMs: 1000,
        totalTimeoutMs: 5000,
      });
      useEffect(() => {
        onReady?.({ runner, setAiQuery });
      }, [onReady, runner, setAiQuery]);
      return (
        <pre data-testid="state">
          {JSON.stringify({ deepData: runner.deepData, deepLoading: runner.deepLoading })}
        </pre>
      );
    }

    let controls;
    await act(async () => {
      root.render(
        <SubmitHarness
          onReady={(c) => {
            controls = c;
          }}
        />,
      );
      await flushMicrotasks();
    });

    await act(async () => {
      controls.setAiQuery("HICP Spain");
      controls.runner.applySuggestedDeepSearch("HICP Spain");
      await flushMicrotasks();
    });

    await act(async () => {
      resolvePost();
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      expect(container.textContent).toContain("HICP Spain final");
    });
  });

  test("unified search accepts source diagnostics map without crashing", async () => {
    api.post.mockResolvedValueOnce({
      data: {
        ok: true,
        verified: [{ catalog_id: "arad", set_id: "1014", name: "Zisk bank" }],
        possible: [],
        source_statuses: [{ source: "arad", status: "ok", row_count: 1, duration_ms: 120 }],
        sources: {
          arad: { status: "ok", count: 1, duration_ms: 120 },
          csu: { status: "pending", count: 0, duration_ms: 0 },
        },
        source_route: {
          selected_sources: ["arad"],
          fallback_sources: ["csu"],
        },
      },
    });

    function QuickHarness() {
      const selected = useMemo(() => new Set(["arad", "csu"]), []);
      const runner = useDeepSearchRunner({
        aiQuery: "zisk bank",
        selected,
        useAiAssistant: true,
        aiSearchScope: AI_SEARCH_SCOPE_EXTENDED,
        deepSourceOrder: ["arad", "csu"],
        deepSourceLabel: (sid) => sid.toUpperCase(),
        chunkTimeoutMs: 1000,
        totalTimeoutMs: 5000,
      });
      useEffect(() => {
        void runner.runDeepSearch("zisk bank");
        // eslint-disable-next-line react-hooks/exhaustive-deps
      }, []);
      return (
        <pre data-testid="state">
          {JSON.stringify({ deepData: runner.deepData, deepError: runner.deepError, deepLoading: runner.deepLoading })}
        </pre>
      );
    }

    await act(async () => {
      root.render(<QuickHarness />);
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      expect(container.textContent).toContain("Zisk bank");
      expect(container.textContent).not.toContain("forEach is not a function");
      expect(container.textContent).not.toContain("selhalo");
    });
    expect(api.post).toHaveBeenCalledWith(
      "/catalog/deep-search",
      expect.objectContaining({ q: "zisk bank", mode: "multi", use_ai: true }),
      expect.anything(),
    );
  });

  test("unified search exposes every selected source and final source statuses", async () => {
    api.post.mockResolvedValueOnce({
      data: {
        ok: true,
        verified: [],
        possible: [],
        source_statuses: [
          { source: "arad", status: "ok", row_count: 0, duration_ms: 100 },
          { source: "ecb2", status: "ok", row_count: 0, duration_ms: 100 },
          { source: "bis", status: "ok", row_count: 0, duration_ms: 100 },
        ],
        source_route: {
          selected_sources: ["arad", "ecb2", "bis"],
        },
      },
    });

    let capturedRunner;
    function QuickHarness() {
      const selected = useMemo(
        () => new Set(["arad", "bis", "commodities", "csu", "eurostat", "ecb2"]),
        [],
      );
      const runner = useDeepSearchRunner({
        aiQuery: "zisk bank",
        selected,
        useAiAssistant: true,
        aiSearchScope: AI_SEARCH_SCOPE_EXTENDED,
        deepSourceOrder: ["arad", "ecb2", "bis", "commodities"],
        deepSourceLabel: (sid) => sid.toUpperCase(),
        chunkTimeoutMs: 1000,
        totalTimeoutMs: 5000,
      });
      capturedRunner = runner;
      useEffect(() => {
        void runner.runDeepSearch("zisk bank");
        // eslint-disable-next-line react-hooks/exhaustive-deps
      }, []);
      return null;
    }

    await act(async () => {
      root.render(<QuickHarness />);
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      expect(new Set(capturedRunner?.deepActiveSourceIds)).toEqual(
        new Set(["arad", "bis", "commodities", "csu", "eurostat", "ecb2"]),
      );
      const labels = (capturedRunner?.deepSourceStatuses || []).map((s) => s.source);
      expect(labels).toEqual(["arad", "ecb2", "bis"]);
    });
  });

  test("unified search performs only one POST fallback after a source timeout", async () => {
    api.post.mockResolvedValueOnce({
      data: {
        ok: true,
        partial: true,
        verified: [],
        possible: [],
        source_statuses: [
          { source: "arad", status: "ok", row_count: 0, duration_ms: 120 },
          { source: "bis", status: "timeout", row_count: 0, duration_ms: 7000 },
        ],
        sources: {
          arad: { status: "ok", count: 0 },
          bis: { status: "timeout", count: 0 },
        },
        source_route: {
          selected_sources: ["arad", "bis"],
          fallback_sources: ["eurostat"],
        },
        catalog_index_warnings: ["BIS Stats API nestihlo doběhnout v limitu (7 s)."],
      },
    });

    function QuickHarness() {
      const selected = useMemo(() => new Set(["arad", "bis", "eurostat"]), []);
      const runner = useDeepSearchRunner({
        aiQuery: "zisk bank",
        selected,
        useAiAssistant: true,
        aiSearchScope: AI_SEARCH_SCOPE_EXTENDED,
        deepSourceOrder: ["arad", "bis", "eurostat"],
        deepSourceLabel: (sid) => sid.toUpperCase(),
        chunkTimeoutMs: 1000,
        totalTimeoutMs: 5000,
      });
      useEffect(() => {
        void runner.runDeepSearch("zisk bank");
        // eslint-disable-next-line react-hooks/exhaustive-deps
      }, []);
      return <pre data-testid="state">{runner.deepError || "ok"}</pre>;
    }

    await act(async () => {
      root.render(<QuickHarness />);
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      expect(api.post).toHaveBeenCalledTimes(1);
      expect(api.post).toHaveBeenCalledWith(
        "/catalog/deep-search",
        expect.objectContaining({ mode: "multi" }),
        expect.anything(),
      );
    });
  });

  test("records diagnostic when source count has no candidate payload", async () => {
    const { MockEventSource } = makeEventSourceMock((es) => {
      setTimeout(() => {
        es.emit("source_started", { source: "eurostat", label: "Eurostat" });
        es.emit("source_candidates_missing_payload", {
          source: "eurostat",
          row_count: 5000,
          warning: "source_candidates_missing_payload",
        });
        es.fail();
      }, 0);
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    await act(async () => {
      root.render(<Harness />);
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      expect(container.textContent).toContain("Zdroj EUROSTAT hlásí kandidáty");
      expect(container.textContent).toContain("Spusťte hledání znovu");
      expect(container.textContent).toContain("active_stream_not_restarted");
    });
    expect(api.post).not.toHaveBeenCalled();
  });

  test("onNewSearch fires for applySuggestedDeepSearch (nové téma) but not for retry/extend stejného dotazu", async () => {
    const { MockEventSource } = makeEventSourceMock((es) => {
      setTimeout(() => es.fail(), 0);
    });
    global.EventSource = MockEventSource;
    window.EventSource = MockEventSource;

    const onNewSearch = vi.fn();

    function OnNewSearchHarness({ onReady }) {
      const selected = useMemo(() => new Set(["eurostat"]), []);
      const runner = useDeepSearchRunner({
        aiQuery: "HICP Spain",
        selected,
        useAiAssistant: true,
        aiSearchScope: AI_SEARCH_SCOPE_EXTENDED,
        deepSourceOrder: ["eurostat"],
        deepSourceLabel: (sid) => sid.toUpperCase(),
        chunkTimeoutMs: 1000,
        totalTimeoutMs: 5000,
        onNewSearch,
      });
      useEffect(() => {
        onReady?.(runner);
      }, [onReady, runner]);
      return null;
    }

    let runner;
    await act(async () => {
      root.render(
        <OnNewSearchHarness
          onReady={(r) => {
            runner = r;
          }}
        />,
      );
      await flushMicrotasks();
    });

    await act(async () => {
      await runner.runDeepSearch();
      await flushMicrotasks();
    });
    expect(onNewSearch).not.toHaveBeenCalled();

    await act(async () => {
      await runner.runDeepSearchExtended("HICP Spain");
      await flushMicrotasks();
    });
    expect(onNewSearch).not.toHaveBeenCalled();

    await act(async () => {
      await runner.applySuggestedDeepSearch("inflace Nemecko");
      await flushMicrotasks();
    });
    expect(onNewSearch).toHaveBeenCalledTimes(1);
    expect(onNewSearch).toHaveBeenCalledWith("inflace Nemecko");
  });
});
