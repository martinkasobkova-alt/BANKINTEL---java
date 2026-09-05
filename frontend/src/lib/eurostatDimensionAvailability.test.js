import { beforeEach, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock("@/lib/api", () => ({
  __esModule: true,
  default: { post: apiMocks.post },
}));

import {
  buildAvailabilityBannerLines,
  fetchEurostatDimensionOptions,
  formatTimeFilterUsed,
  incompleteAvailabilityNotice,
  shouldShowIncompleteAvailabilityWarning,
} from "./eurostatDimensionAvailability";

beforeEach(() => {
  apiMocks.post.mockReset();
});

// Zive zjisteno: fetchEurostatDimensionOptions/fetchEurostatCascadeState nemely zadny test,
// jen jejich banner-building pomocniky nize. Tenhle test kryje presne cestu/parametry, co
// SourcePreview.jsx nove pouziva k oznaceni hodnot bez dat v rychlem ODVETVI menu.
describe("fetchEurostatDimensionOptions", () => {
  it("posts selected_dimensions + target_dimension to the dimension-availability endpoint", async () => {
    apiMocks.post.mockResolvedValueOnce({
      data: { options: [{ code: "G45" }], invalid_removed: ["T"], complete: true },
    });

    const data = await fetchEurostatDimensionOptions({
      datasetId: "naio_10_pyp1620",
      selectedDimensions: { geo: "CZ" },
      targetDimension: "ind_use",
      userQuery: "stavebnictvi",
    });

    expect(apiMocks.post).toHaveBeenCalledWith(
      "/eurostat/datasets/naio_10_pyp1620/dimension-availability",
      {
        selected_dimensions: { geo: "CZ" },
        target_dimension: "ind_use",
        user_query: "stavebnictvi",
      },
    );
    expect(data.invalid_removed).toEqual(["T"]);
  });

  it("returns null without calling the API when datasetId or targetDimension is missing", async () => {
    expect(await fetchEurostatDimensionOptions({ datasetId: "", targetDimension: "ind_use" })).toBeNull();
    expect(await fetchEurostatDimensionOptions({ datasetId: "naio_10_pyp1620", targetDimension: "" })).toBeNull();
    expect(apiMocks.post).not.toHaveBeenCalled();
  });
});

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
