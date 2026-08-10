import {
  rebaseBisSeriesNodePaths,
  resolveBisBrowseLazyAction,
} from "./catalogBisBrowse";

describe("catalogBisBrowse", () => {
  it("resolveBisBrowseLazyAction detects dataflow rows", () => {
    expect(
      resolveBisBrowseLazyAction({
        item_kind: "dataflow",
        kind: "set",
        bis_dataflow: "CPMI_CASHLESS",
        path: "BIS > C > CPMI cashless payments",
      }),
    ).toEqual({
      kind: "dataflow",
      id: "CPMI_CASHLESS",
      path: "BIS > C > CPMI cashless payments",
    });
  });

  it("resolveBisBrowseLazyAction detects lazy country rows", () => {
    expect(
      resolveBisBrowseLazyAction(
        {
          kind: "cat",
          bis_lazy_country: true,
          ref_area: "CZ",
          path: "BIS > C > CPMI cashless payments > CZ",
        },
        3,
        "CPMI_CASHLESS",
      ),
    ).toEqual({
      kind: "country",
      code: "CZ",
      flowId: "CPMI_CASHLESS",
      path: "BIS > C > CPMI cashless payments > CZ",
    });
  });

  it("rebaseBisSeriesNodePaths rewrites API paths under catalog path", () => {
    const rebased = rebaseBisSeriesNodePaths(
      {
        name: "CPMI cashless payments",
        path: "BIS::CPMI_CASHLESS",
        children: [
          {
            name: "Czech Republic",
            path: "BIS::CPMI_CASHLESS > CZ",
            children: [],
            sets: [],
          },
        ],
        sets: [],
      },
      "BIS > C > CPMI cashless payments",
    );
    expect(rebased.path).toBe("BIS > C > CPMI cashless payments");
    expect(rebased.children[0].path).toBe("BIS > C > CPMI cashless payments > CZ");
  });
});
