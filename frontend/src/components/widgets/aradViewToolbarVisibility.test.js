import {
  resolveChartCompareToolbarVisible,
  resolveChartTransformToolbarVisible,
  resolveChartActionsInSidePanel,
} from "./aradViewToolbarVisibility";

describe("resolveChartCompareToolbarVisible", () => {
  test("hidden in catalog live preview", () => {
    expect(
      resolveChartCompareToolbarVisible({ catalogLivePreview: true, canEditChartCompare: true })
    ).toBe(false);
  });

  test("visible when user can edit compare", () => {
    expect(resolveChartCompareToolbarVisible({ canEditChartCompare: true })).toBe(true);
  });

  test("visible for single-series read-only (help)", () => {
    expect(resolveChartCompareToolbarVisible({ isMultiSeries: false })).toBe(true);
  });
});

describe("resolveChartTransformToolbarVisible", () => {
  test("visible when multiple transforms and dated series", () => {
    expect(
      resolveChartTransformToolbarVisible({
        allowedTransformCount: 3,
        hasDates: true,
        latestDataMode: false,
      })
    ).toBe(true);
  });

  test("hidden in latest mode", () => {
    expect(
      resolveChartTransformToolbarVisible({
        allowedTransformCount: 3,
        hasDates: true,
        latestDataMode: true,
      })
    ).toBe(false);
  });
});

describe("resolveChartActionsInSidePanel", () => {
  test("true when desktop options panel is active", () => {
    expect(
      resolveChartActionsInSidePanel({
        controlsInOptionsPanel: true,
        showInteractiveControls: true,
      })
    ).toBe(true);
  });

  test("false in fullscreen expand", () => {
    expect(
      resolveChartActionsInSidePanel({
        controlsInOptionsPanel: true,
        fsExpand: true,
      })
    ).toBe(false);
  });
});
