import { describe, expect, it } from "vitest";
import { buildPeriodAnnotationLayout, compactPeriodAnnotationLabel } from "./periodAnnotationLayout";

describe("period annotation layout", () => {
  it("keeps a complete timeline up to the chart annotation limit", () => {
    const annotations = Array.from({ length: 20 }, (_, index) => ({
      from: String(2000 + index),
      to: String(2001 + index),
      label: `Období ${index + 1}`,
    }));

    expect(buildPeriodAnnotationLayout(annotations).entries).toHaveLength(20);
  });

  it("drops annotations without a usable period", () => {
    expect(buildPeriodAnnotationLayout([{ label: "Bez období" }]).entries).toEqual([]);
  });

  it("shortens only labels that would dominate a band", () => {
    expect(compactPeriodAnnotationLabel("Krize")).toBe("Krize");
    expect(compactPeriodAnnotationLabel("Velmi dlouhý popisek události, který se do grafu nevejde").endsWith("…")).toBe(true);
  });
});
