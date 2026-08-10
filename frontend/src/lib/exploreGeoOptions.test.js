import { groupExploreCountryOptions } from "./exploreGeoOptions";

describe("groupExploreCountryOptions", () => {
  test("groups countries by continent and sorts alphabetically", () => {
    const groups = groupExploreCountryOptions([
      { code: "DE", label_cs: "Německo", continent_id: "europe" },
      { code: "CZ", label_cs: "Česko", continent_id: "europe" },
      { code: "JP", label_cs: "Japonsko", continent_id: "asia" },
      { code: "US", label_cs: "Spojené státy", continent_id: "north_america" },
    ]);
    expect(groups.map((g) => g.label_cs)).toEqual(["Asie", "Evropa", "Severní Amerika"]);
    const europe = groups.find((g) => g.id === "europe");
    expect(europe.countries.map((c) => c.label_cs)).toEqual(["Česko", "Německo"]);
  });
});
