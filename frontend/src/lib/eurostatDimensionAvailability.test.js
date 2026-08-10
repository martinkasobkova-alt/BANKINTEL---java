import {
  buildAvailabilityBannerLines,
  formatTimeFilterUsed,
  incompleteAvailabilityNotice,
  shouldShowIncompleteAvailabilityWarning,
} from "./eurostatDimensionAvailability";

describe("eurostatDimensionAvailability UI signals", () => {
  test("Test B — complete=false zobrazí warning", () => {
    expect(
      shouldShowIncompleteAvailabilityWarning({
        complete: false,
        candidate_limit_hit: false,
      }),
    ).toBe(true);
    expect(incompleteAvailabilityNotice({ complete: false })).toContain("neúplný");
  });

  test("Test B — candidate_limit_hit=true zobrazí warning", () => {
    expect(
      shouldShowIncompleteAvailabilityWarning({
        complete: true,
        candidate_limit_hit: true,
      }),
    ).toBe(true);
  });

  test("availability banner — latest_only", () => {
    const lines = buildAvailabilityBannerLines({
      availability_mode: "latest_only",
      time_filter_used: { lastTimePeriod: "1" },
      method_used: "jsonstat_parse",
      complete: true,
      candidate_limit_hit: false,
    });
    expect(lines.some((l) => l.includes("poslední dostupné období"))).toBe(true);
    expect(lines.some((l) => l.includes("posledních 1 období"))).toBe(true);
  });

  test("availability banner — any_time historický", () => {
    const lines = buildAvailabilityBannerLines({
      availability_mode: "any_time",
      time_filter_used: { sinceTimePeriod: "2003", lastTimePeriod: "120" },
      method_used: "jsonstat_parse",
      complete: true,
    });
    expect(lines.some((l) => l.includes("historickém rozsahu"))).toBe(true);
    expect(formatTimeFilterUsed({ sinceTimePeriod: "2003", lastTimePeriod: "120" })).toContain("2003");
  });

  test("availability banner — incomplete a limit", () => {
    const lines = buildAvailabilityBannerLines({
      availability_mode: "latest_only",
      complete: false,
      candidate_limit_hit: true,
      method_used: "probe_fallback",
    });
    expect(lines.some((l) => l.includes("neúplný"))).toBe(true);
    expect(lines.some((l) => l.includes("omezen"))).toBe(true);
  });

  test("kompletní výsledek bez warningu", () => {
    expect(
      shouldShowIncompleteAvailabilityWarning({
        complete: true,
        candidate_limit_hit: false,
      }),
    ).toBe(false);
  });
});
