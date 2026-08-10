/**
 * Tests for parseChartPeriod — annual, quarterly, monthly, daily ISO formats.
 */

import { parseChartPeriod } from "./chartPeriodParse";

describe("parseChartPeriod", () => {
  test("annual YYYY", () => {
    expect(parseChartPeriod("2008")?.getFullYear()).toBe(2008);
    expect(parseChartPeriod("2009")?.getMonth()).toBe(0);
  });

  test("quarterly YYYY-Qn", () => {
    const dt = parseChartPeriod("2020-Q1");
    expect(dt?.getFullYear()).toBe(2020);
    expect(dt?.getMonth()).toBe(0);
  });

  test("monthly YYYY-MM", () => {
    expect(parseChartPeriod("2020-01")?.getMonth()).toBe(0);
  });

  test("daily YYYY-MM-DD", () => {
    expect(parseChartPeriod("2020-01-31")?.getDate()).toBe(31);
  });
});
