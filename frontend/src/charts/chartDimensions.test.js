import { resolveChartSize, resolveChartMargin, resolveResponsiveHeight, CHART_SIZE_PRESETS } from "./chartDimensions";
import { CHART_SIZE_VARIANTS } from "./chartTypes";

describe("chartDimensions", () => {
  test("all size variants exist", () => {
    for (const size of Object.values(CHART_SIZE_VARIANTS)) {
      expect(CHART_SIZE_PRESETS[size]).toBeDefined();
      expect(CHART_SIZE_PRESETS[size].plotMinHeight).toBeGreaterThan(0);
    }
  });

  test("compact/standard/analytical/fullscreen have distinct heights", () => {
    expect(CHART_SIZE_PRESETS.compact.containerHeight).toBeLessThan(
      CHART_SIZE_PRESETS.standard.containerHeight
    );
    expect(CHART_SIZE_PRESETS.standard.containerHeight).toBe(280);
    expect(CHART_SIZE_PRESETS.analytical.containerHeight).toBe(420);
    expect(CHART_SIZE_PRESETS.fullscreen.containerHeight).toBe("100%");
  });

  test("resolveChartMargin uses multiSeries margin when needed", () => {
    const spec = resolveChartSize(CHART_SIZE_VARIANTS.STANDARD);
    const single = resolveChartMargin(spec, { multiSeries: false });
    const multi = resolveChartMargin(spec, { multiSeries: true });
    expect(multi.bottom).toBeGreaterThanOrEqual(single.bottom);
  });

  test("responsive height safe on mobile width", () => {
    const spec = resolveChartSize(CHART_SIZE_VARIANTS.STANDARD);
    const mobile = resolveResponsiveHeight(spec, 375);
    expect(mobile).toBeGreaterThanOrEqual(spec.plotMinHeight);
    expect(mobile).toBeLessThanOrEqual(spec.containerHeight);
  });

  test("responsive height safe on notebook width", () => {
    const spec = resolveChartSize(CHART_SIZE_VARIANTS.ANALYTICAL);
    const notebook = resolveResponsiveHeight(spec, 1024);
    expect(notebook).toBe(spec.containerHeight);
  });
});
