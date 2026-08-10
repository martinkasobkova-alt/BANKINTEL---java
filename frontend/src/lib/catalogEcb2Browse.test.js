import { ecb2CountryBranchHasFlowChildren, resolveEcb2BrowseLazyAction } from "./catalogEcb2Browse";

describe("resolveEcb2BrowseLazyAction", () => {
  const countryRow = {
    kind: "cat",
    depth: 1,
    ecb_country: "U2",
    ecb_country_lazy: true,
    path: "ECB · ověřené řady > U2",
  };

  it("loads country from lazy flag or Miller column 1", () => {
    expect(resolveEcb2BrowseLazyAction(countryRow)).toEqual({
      kind: "country",
      code: "U2",
      path: "ECB · ověřené řady > U2",
    });
    expect(resolveEcb2BrowseLazyAction({ ...countryRow, depth: 0 }, 1)).toEqual({
      kind: "country",
      code: "U2",
      path: "ECB · ověřené řady > U2",
    });
  });

  it("loads flow folder at column 2", () => {
    const flowRow = {
      kind: "cat",
      depth: 2,
      ecb_country: "U2",
      ecb_flow: "BSI",
      ecb_flow_lazy: true,
    };
    expect(resolveEcb2BrowseLazyAction({ ...flowRow, path: "ECB · ověřené řady > U2 > BSI" }, 2)).toEqual({
      kind: "flow",
      code: "U2",
      flow: "BSI",
      path: "ECB · ověřené řady > U2 > BSI",
    });
  });

  it("loads letter folder at column 3", () => {
    const letterRow = {
      kind: "cat",
      depth: 3,
      ecb_country: "U2",
      ecb_flow: "BSI",
      ecb_letter: "loans",
      ecb_letter_lazy: true,
    };
    expect(
      resolveEcb2BrowseLazyAction(
        { ...letterRow, path: "ECB · ověřené řady > U2 > BSI > loans" },
        3,
      ),
    ).toEqual({
      kind: "letter",
      code: "U2",
      flow: "BSI",
      letter: "loans",
      path: "ECB · ověřené řady > U2 > BSI > loans",
    });
  });

  it("ignores root row without country code", () => {
    expect(
      resolveEcb2BrowseLazyAction({ kind: "cat", depth: 0, path: "ECB · ověřené řady" }, 0),
    ).toBeNull();
  });
});

describe("ecb2CountryBranchHasFlowChildren", () => {
  it("detects flow folders under a country path", () => {
    const countryPath = "ECB · ověřené řady > BG";
    expect(
      ecb2CountryBranchHasFlowChildren(
        [
          { kind: "cat", path: countryPath, parentPath: "ECB · ověřené řady" },
          {
            kind: "cat",
            path: `${countryPath} > BSI`,
            parentPath: countryPath,
            ecb_flow: "BSI",
          },
        ],
        countryPath,
      ),
    ).toBe(true);
  });
});
