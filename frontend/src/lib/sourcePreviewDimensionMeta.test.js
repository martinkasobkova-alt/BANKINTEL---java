import { applyLiveAvailabilityToDimensionOptions } from "./sourcePreviewDimensionMeta";

describe("applyLiveAvailabilityToDimensionOptions", () => {
  const options = [
    { value: "T", label: "Households" },
    { value: "G45", label: "Wholesale" },
  ];

  it("marks everything hasData:true when no availability was fetched yet", () => {
    const out = applyLiveAvailabilityToDimensionOptions(options, null);
    expect(out.map((o) => o.hasData)).toEqual([true, true]);
  });

  it("marks a code present in invalidCodes as hasData:false", () => {
    const out = applyLiveAvailabilityToDimensionOptions(options, { invalidCodes: new Set(["T"]) });
    expect(out.find((o) => o.value === "T").hasData).toBe(false);
    expect(out.find((o) => o.value === "G45").hasData).toBe(true);
  });

  it("treats an empty invalidCodes set the same as no availability (favor showing over blocking)", () => {
    const out = applyLiveAvailabilityToDimensionOptions(options, { invalidCodes: new Set() });
    expect(out.map((o) => o.hasData)).toEqual([true, true]);
  });

  it("does not mutate the original option objects", () => {
    applyLiveAvailabilityToDimensionOptions(options, { invalidCodes: new Set(["T"]) });
    expect(options[0].hasData).toBeUndefined();
  });

  it("never marks the currently selected value as hasData:false, even if the probe flagged it", () => {
    // Živě zjištěno na naio_10_pyp1620: probe kontroluje jen nejnovější období (lastTimePeriod=1)
    // a kombinace s bohatou historií (2011-2023) může vyjít "bez dat", pokud poslední rok ještě
    // není zveřejněný - appka přitom PRÁVĚ TEĎ reálná data pro tuhle hodnotu zobrazuje, což je
    // silnější důkaz než jeden úzký probe.
    const out = applyLiveAvailabilityToDimensionOptions(
      options,
      { invalidCodes: new Set(["T", "G45"]) },
      "T",
    );
    expect(out.find((o) => o.value === "T").hasData).toBe(true);
    expect(out.find((o) => o.value === "G45").hasData).toBe(false);
  });
});
