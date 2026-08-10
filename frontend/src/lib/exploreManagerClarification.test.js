import { describe, expect, it } from "vitest";

import {
  buildGeoPayloadFromQueryUnderstanding,
  countryCodesFromQueryUnderstanding,
} from "./exploreManagerClarification";

describe("explore manager query geo", () => {
  it("preserves ISO countries returned in the planner country field", () => {
    const understanding = { country: "CZ, SK", geo_mode: "countries" };

    expect(countryCodesFromQueryUnderstanding(understanding)).toEqual(["CZ", "SK"]);
    expect(buildGeoPayloadFromQueryUnderstanding(understanding)).toEqual({
      country: "CZ, SK",
      geo_mode: "countries",
      continent: null,
    });
  });

  it("accepts a structured country list without losing existing resolved geo", () => {
    const understanding = {
      country: ["AT", "DE"],
      resolved_geo: { countries: ["CZ"] },
    };

    expect(countryCodesFromQueryUnderstanding(understanding)).toEqual(["CZ", "AT", "DE"]);
  });
});
