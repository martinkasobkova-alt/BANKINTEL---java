import { afterEach, describe, expect, it, vi } from "vitest";

import { runCatalogDeepSearchSseStream } from "./useCatalogSseStream";

class FakeEventSource {
  static instances = [];

  constructor(url) {
    this.url = url;
    this.listeners = new Map();
    this.closed = false;
    FakeEventSource.instances.push(this);
  }

  addEventListener(name, handler) {
    this.listeners.set(name, handler);
  }

  emit(name, payload) {
    this.listeners.get(name)?.({ data: JSON.stringify(payload) });
  }

  close() {
    this.closed = true;
  }
}

describe("runCatalogDeepSearchSseStream", () => {
  afterEach(() => {
    FakeEventSource.instances = [];
    vi.unstubAllGlobals();
  });

  it("treats a named final event as the terminal payload", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    vi.stubGlobal("window", { setTimeout, clearTimeout });
    const controller = new AbortController();
    const onFinal = vi.fn();
    const run = runCatalogDeepSearchSseStream({
      url: "/api/catalog/deep-search/stream?q=test",
      signal: controller.signal,
      timeoutMs: 5_000,
      isRequestCurrent: () => true,
      onFinal,
    });

    const stream = FakeEventSource.instances[0];
    const finalPayload = {
      ok: true,
      verified: [{ source: "ecb2", set_id: "CBD/A.SK.11.A.21110" }],
    };
    stream.emit("final", finalPayload);

    await expect(run).resolves.toMatchObject({
      payload: finalPayload,
      telemetry: { sse_final_received: true },
    });
    expect(onFinal).toHaveBeenCalledWith({ ...finalPayload, event: "final" });
    expect(stream.closed).toBe(true);
  });
});
