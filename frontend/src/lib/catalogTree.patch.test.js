import {
  defaultOpenPathsFromTree,
  flattenCatalogCategories,
  formatBrowseCategoryCount,
  patchBrowseRowsForLazyCountry,
} from "./catalogTree";

describe("formatBrowseCategoryCount", () => {
  it("hides zero before expand, shows after", () => {
    expect(formatBrowseCategoryCount({ count: 0 }, false)).toBeNull();
    expect(formatBrowseCategoryCount({ count: 0 }, true)).toBe(0);
    expect(formatBrowseCategoryCount({ count: 12 }, false)).toBe(12);
  });
});

describe("defaultOpenPathsFromTree", () => {
  it("keeps lazy BIS country folders collapsed initially", () => {
    const tree = [
      {
        path: "BIS::WS_TEST",
        name: "Test flow",
        children: [
          {
            path: "BIS::WS_TEST > AT",
            name: "Austria",
            bis_lazy_country: true,
            ref_area: "AT",
            children: [],
            sets: [],
          },
        ],
        sets: [],
      },
    ];
    const paths = defaultOpenPathsFromTree(tree, { catalogMode: "countries_lazy" });
    expect(paths.has("BIS::WS_TEST")).toBe(true);
    expect(paths.has("BIS::WS_TEST > AT")).toBe(false);
  });
});

describe("patchBrowseRowsForLazyCountry", () => {
  it("replaces only the target country branch without dropping other countries", () => {
    const root = {
      path: "ECB",
      name: "ECB",
      children: [
        { path: "ECB > CZ", name: "Česko", ecb_country: "CZ", children: [], sets: [] },
        { path: "ECB > DE", name: "Německo", ecb_country: "DE", children: [], sets: [] },
      ],
      sets: [],
    };
    const base = flattenCatalogCategories([root]);

    const czNode = {
      path: "ECB > CZ",
      name: "Česko (CZ)",
      ecb_country: "CZ",
      children: [
        {
          path: "ECB > CZ > Inflace",
          name: "Inflace",
          children: [],
          sets: [{ set_id: "ecb:CZ:inflace_celkova", name: "Inflace celkem", kind: "selection" }],
        },
      ],
      sets: [],
    };
    const czRow = base.find((r) => r.path === "ECB > CZ");
    const patched = patchBrowseRowsForLazyCountry(base, czNode, czRow);

    expect(patched.some((r) => r.path === "ECB > DE")).toBe(true);
    expect(patched.some((r) => r.path === "ECB > CZ > Inflace")).toBe(true);
    expect(patched.some((r) => r.set_id === "ecb:CZ:inflace_celkova")).toBe(true);
    const czAfter = patched.find((r) => r.path === "ECB > CZ");
    expect(czAfter?.count).toBe(1);

    const czIdx = patched.findIndex((r) => r.path === "ECB > CZ");
    const inflIdx = patched.findIndex((r) => r.path === "ECB > CZ > Inflace");
    const deIdx = patched.findIndex((r) => r.path === "ECB > DE");
    expect(czIdx).toBeGreaterThanOrEqual(0);
    expect(inflIdx).toBeGreaterThan(czIdx);
    expect(deIdx).toBeGreaterThan(inflIdx);
  });

  it("keeps IMF country row at same depth after lazy load (regression)", () => {
    const rootPath = "IMF · země a ukazatele";
    const countryPath = `${rootPath} > ALB`;
    const root = {
      path: rootPath,
      name: rootPath,
      children: [
        {
          path: countryPath,
          name: "Albania (ALB)",
          imf_country: "ALB",
          children: [],
          sets: [],
        },
      ],
      sets: [],
    };
    const base = flattenCatalogCategories([root]);
    const lazy = base.find((r) => r.imf_country === "ALB");
    expect(lazy?.depth).toBe(1);

    const countryNode = {
      path: countryPath,
      name: "Albania (ALB)",
      imf_country: "ALB",
      children: [
        {
          path: `${countryPath} > WEO`,
          name: "Světový ekonomický výhled",
          children: [],
          sets: [{ set_id: "IMF|x", name: "GDP", kind: "selection" }],
        },
      ],
      sets: [],
    };
    const patched = patchBrowseRowsForLazyCountry(base, countryNode, lazy);
    const countryAfter = patched.find((r) => r.path === countryPath && r.kind === "cat");
    expect(countryAfter?.depth).toBe(1);
    expect(countryAfter?.parentPath).toBe(rootPath);
    expect(patched.some((r) => r.path === countryPath && r.kind === "cat")).toBe(true);
  });

  it("keeps lazy country display name when API returns code-only label", () => {
    const rootPath = "IMF · země a ukazatele";
    const countryPath = `${rootPath} > U142`;
    const root = {
      path: rootPath,
      name: rootPath,
      children: [
        {
          path: countryPath,
          name: "Asia (U142)",
          imf_country: "U142",
          children: [],
          sets: [],
        },
      ],
      sets: [],
    };
    const base = flattenCatalogCategories([root]);
    const lazy = base.find((r) => r.imf_country === "U142");
    const countryNode = {
      path: countryPath,
      name: "U142 (U142)",
      imf_country: "U142",
      children: [{ path: `${countryPath} > WEO`, name: "WEO", children: [], sets: [] }],
      sets: [],
    };
    const patched = patchBrowseRowsForLazyCountry(base, countryNode, lazy);
    const countryAfter = patched.find((r) => r.path === countryPath && r.kind === "cat");
    expect(countryAfter?.name).toBe("Asia (U142)");
  });

  it("data360 lazy country patch keeps theme folders under letter bucket path", () => {
    const rootPath = "World Bank";
    const letterPath = `${rootPath} > C`;
    const countryPath = `${letterPath} > Cameroon`;
    const root = {
      path: rootPath,
      name: rootPath,
      children: [
        {
          path: letterPath,
          name: "C (21)",
          children: [
            {
              path: countryPath,
              name: "Cameroon",
              data360_country: "CMR",
              data360_country_name: "Cameroon",
              data360_country_lazy: true,
              children: [],
              sets: [],
            },
          ],
          sets: [],
        },
      ],
      sets: [],
    };
    const base = flattenCatalogCategories([root]);
    const lazy = base.find((r) => r.data360_country === "CMR");
    expect(lazy?.depth).toBe(2);

    const countryNode = {
      path: "World Bank > Cameroon",
      name: "Cameroon",
      data360_country: "CMR",
      data360_country_name: "Cameroon",
      children: [
        {
          path: "World Bank > Cameroon > Planet",
          name: "Planet (2)",
          children: [],
          sets: [
            {
              set_id: "WB_WDI|WB_WDI_SP_POP_TOTL",
              name: "Population",
              kind: "selection",
            },
          ],
        },
      ],
      sets: [],
    };
    const rebased = {
      ...countryNode,
      path: countryPath,
      children: [
        {
          ...countryNode.children[0],
          path: `${countryPath} > Planet`,
        },
      ],
    };
    const patched = patchBrowseRowsForLazyCountry(base, rebased, lazy);
    const theme = patched.find((r) => r.path === `${countryPath} > Planet`);
    expect(theme?.parentPath).toBe(countryPath);
    expect(patched.some((r) => r.set_id === "WB_WDI|WB_WDI_SP_POP_TOTL")).toBe(true);
  });

  it("ecb2 lazy country patch exposes flow folders for Miller column explorer", () => {
    const rootPath = "ECB · ověřené řady";
    const countryPath = `${rootPath} > U2`;
    const root = {
      path: rootPath,
      name: rootPath,
      children: [
        {
          path: countryPath,
          name: "Eurozóna (EA20) (U2)",
          ecb_country: "U2",
          ecb_country_lazy: true,
          children: [],
          sets: [],
        },
      ],
      sets: [],
    };
    const base = flattenCatalogCategories([root]);
    const lazy = base.find((r) => r.ecb_country === "U2");
    expect(lazy?.depth).toBe(1);

    const countryNode = {
      path: countryPath,
      name: "Eurozóna (EA20) (U2)",
      ecb_country: "U2",
      children: [
        {
          path: `${countryPath} > BSI`,
          name: "BSI · bilance bank (100)",
          ecb_country: "U2",
          ecb_flow: "BSI",
          ecb_flow_lazy: true,
          children: [],
          sets: [],
        },
      ],
      sets: [],
    };
    const patched = patchBrowseRowsForLazyCountry(base, countryNode, lazy);
    const flow = patched.find((r) => r.ecb_flow === "BSI" && r.kind === "cat");
    expect(flow?.depth).toBe(2);
    expect(flow?.parentPath).toBe(countryPath);
    const countryAfter = patched.find((r) => r.path === countryPath && r.kind === "cat");
    expect(countryAfter?.count).toBeGreaterThan(0);
  });

  it("uses imf_browse_count for lazy country row count before expansion", () => {
    const root = {
      path: "IMF",
      name: "IMF",
      children: [
        {
          path: "IMF > ALB",
          name: "Albania (ALB)",
          imf_country: "ALB",
          imf_browse_count: 46,
          children: [],
          sets: [],
        },
      ],
      sets: [],
    };
    const flat = flattenCatalogCategories([root]);
    const alb = flat.find((r) => r.imf_country === "ALB");
    expect(alb?.count).toBe(46);
  });
});
