import {
  rebaseData360CountryNodePaths,
  resolveData360BrowseLazyAction,
} from "./catalogData360Browse";

describe("resolveData360BrowseLazyAction", () => {
  const countryRow = {
    kind: "cat",
    depth: 2,
    path: "World Bank > C > Cameroon",
    data360_country: "CMR",
    data360_country_name: "Cameroon",
    data360_country_lazy: true,
  };

  it("loads country at Miller column 2", () => {
    expect(resolveData360BrowseLazyAction(countryRow, 2)).toEqual({
      code: "CMR",
      country: "Cameroon",
    });
  });

  it("ignores letter group rows", () => {
    expect(
      resolveData360BrowseLazyAction(
        { kind: "cat", depth: 1, path: "World Bank > C" },
        1,
      ),
    ).toBeNull();
  });
});

describe("rebaseData360CountryNodePaths", () => {
  it("prefixes letter bucket into hierarchical paths", () => {
    const apiNode = {
      path: "World Bank > Cameroon",
      children: [
        {
          path: "World Bank > Cameroon > Planet",
          children: [],
          sets: [],
        },
      ],
      sets: [],
    };
    const rebased = rebaseData360CountryNodePaths(
      apiNode,
      "World Bank > C > Cameroon",
    );
    expect(rebased.path).toBe("World Bank > C > Cameroon");
    expect(rebased.children[0].path).toBe("World Bank > C > Cameroon > Planet");
  });
});
