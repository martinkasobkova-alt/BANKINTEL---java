import { applySourceStatusEvent, sourceStatusIssues } from "@/lib/exploreSourceProgress";

describe("applySourceStatusEvent", () => {
  it("adds a running row on source_started", () => {
    const rows = applySourceStatusEvent([], { source: "arad", event: "source_started" });
    expect(rows).toEqual([{ source: "arad", status: "running", candidates: null }]);
  });

  it("does not duplicate or reset an already-started source on a repeated source_started", () => {
    const first = applySourceStatusEvent([], { source: "arad", event: "source_started" });
    const withFinish = applySourceStatusEvent(first, {
      source: "arad",
      event: "source_finished",
      status: "ok",
      candidates: 5,
    });
    const repeatedStart = applySourceStatusEvent(withFinish, { source: "arad", event: "source_started" });
    expect(repeatedStart).toEqual(withFinish);
  });

  it("reports a real ok status with the candidate count on source_finished", () => {
    const rows = applySourceStatusEvent(
      [{ source: "fred", status: "running", candidates: null }],
      { source: "fred", event: "source_finished", status: "ok", candidates: 12 }
    );
    expect(rows).toEqual([{ source: "fred", status: "ok", candidates: 12 }]);
  });

  it("reports empty (not an error) when a source finishes with zero candidates", () => {
    const rows = applySourceStatusEvent(
      [{ source: "csu", status: "running", candidates: null }],
      { source: "csu", event: "source_finished", status: "empty", candidates: 0 }
    );
    expect(rows[0].status).toBe("empty");
    expect(rows[0].candidates).toBe(0);
  });

  it("reports timeout for source_timeout events", () => {
    const rows = applySourceStatusEvent(
      [{ source: "imf", status: "running", candidates: null }],
      { source: "imf", event: "source_timeout" }
    );
    expect(rows).toEqual([{ source: "imf", status: "timeout", candidates: 0 }]);
  });

  it("reports error for source_error events", () => {
    const rows = applySourceStatusEvent(
      [{ source: "oecd4", status: "running", candidates: null }],
      { source: "oecd4", event: "source_error" }
    );
    expect(rows).toEqual([{ source: "oecd4", status: "error", candidates: 0 }]);
  });

  it("preserves insertion order across multiple sources as events arrive out of order", () => {
    let rows = [];
    rows = applySourceStatusEvent(rows, { source: "arad", event: "source_started" });
    rows = applySourceStatusEvent(rows, { source: "csu", event: "source_started" });
    rows = applySourceStatusEvent(rows, { source: "fred", event: "source_started" });
    // csu finishes first, but must keep its original (second) position, not jump to the front.
    rows = applySourceStatusEvent(rows, { source: "csu", event: "source_finished", status: "ok", candidates: 3 });
    expect(rows.map((r) => r.source)).toEqual(["arad", "csu", "fred"]);
  });

  it("ignores messages without a source and unknown event names", () => {
    const rows = [{ source: "arad", status: "running", candidates: null }];
    expect(applySourceStatusEvent(rows, { event: "source_started" })).toBe(rows);
    expect(applySourceStatusEvent(rows, { source: "arad", event: "something_else" })).toBe(rows);
  });

  it("keeps failure reasons and reports only real source failures", () => {
    let rows = [];
    rows = applySourceStatusEvent(rows, { source: "arad", event: "source_finished", status: "empty" });
    rows = applySourceStatusEvent(rows, {
      source: "ecb2",
      event: "source_error",
      reason: "http_500",
    });
    rows = applySourceStatusEvent(rows, {
      source: "fred",
      event: "source_timeout",
      reason: "deadline_exceeded",
    });
    rows = applySourceStatusEvent(rows, {
      source: "csu",
      event: "source_skipped",
      reason: "cz_only_source",
    });

    expect(sourceStatusIssues(rows)).toEqual([
      { source: "ecb2", status: "error", candidates: 0, reason: "http_500" },
      { source: "fred", status: "timeout", candidates: 0, reason: "deadline_exceeded" },
    ]);
    expect(rows.find((row) => row.source === "csu")).toEqual({
      source: "csu",
      status: "skipped",
      candidates: 0,
      reason: "cz_only_source",
    });
  });
});
