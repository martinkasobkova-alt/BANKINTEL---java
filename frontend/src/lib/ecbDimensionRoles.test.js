import { buildLocalWizardPlan, classifyEcbDimensionRole, getHumanEcbFieldLabel } from "./ecbDimensionRoles";

describe("ecbDimensionRoles", () => {
  it("classifies REF_AREA as compare", () => {
    expect(classifyEcbDimensionRole("CBD2", { id: "REF_AREA", name: "Area" }).role).toBe("compare");
  });

  it("classifies COUNTRY as compare", () => {
    expect(classifyEcbDimensionRole("AMECO", { id: "COUNTRY", name: "Country" }).role).toBe("compare");
  });

  it("does not classify AME_TRANSFORMATION as metric", () => {
    expect(classifyEcbDimensionRole("AMECO", { id: "AME_TRANSFORMATION", name: "Transformation" }).role).toBe("category");
  });

  it("classifies BANK_SELECTION with Bank N as technical", () => {
    const vals = Array.from({ length: 12 }, (_, i) => ({ id: `B${i + 1}`, name: `Bank ${i + 1}` }));
    expect(classifyEcbDimensionRole("BLS", { id: "BANK_SELECTION", name: "x", values: vals }).role).toBe("technical");
  });

  it("basic plan excludes technical dimensions", () => {
    const dims = [
      { id: "FREQ", name: "F", position: 0, values: [{ id: "Q", name: "Q" }] },
      { id: "REF_AREA", name: "R", position: 1, values: [{ id: "U2", name: "U2" }] },
      { id: "SURVEY_ITEM", name: "S", position: 2, values: [{ id: "X", name: "X" }] },
      {
        id: "BANK_SELECTION",
        name: "B",
        position: 3,
        values: Array.from({ length: 12 }, (_, i) => ({ id: `b${i}`, name: `Bank ${i + 1}` })),
      },
    ];
    const plan = buildLocalWizardPlan("CBD2", dims);
    expect(plan.recommendedBasic.includes("BANK_SELECTION")).toBe(false);
    expect(plan.recommendedBasic).toContain("REF_AREA");
    expect(plan.recommendedBasic).toContain("SURVEY_ITEM");
  });

  it("getHumanEcbFieldLabel avoids raw SDMX ids in basic labels", () => {
    expect(getHumanEcbFieldLabel("compare", "REF_AREA", "Reference area", "CBD2")).toMatch(/Země|oblast/i);
    expect(getHumanEcbFieldLabel("frequency", "FREQ", "Frequency", "CBD2")).toMatch(/Frekvence/i);
    expect(getHumanEcbFieldLabel("technical", "BANK_SELECTION", "x", "BLS")).toMatch(/Technický výběr banky/i);
  });
});
