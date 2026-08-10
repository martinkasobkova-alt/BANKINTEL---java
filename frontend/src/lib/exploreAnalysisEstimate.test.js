import {

  estimateExplorePipelineSec,

  estimateSummarizeDurationSec,

  estimateSummarizeFetchTotal,

  formatExploreEtaSec,

  formatPipelineFetchLine,

  parseFetchTotalFromServerHint,

  resolvePipelineFetchTotal,

} from "./exploreAnalysisEstimate";



describe("exploreAnalysisEstimate", () => {

  test("fetch total varies with selection size and related segments", () => {

    expect(estimateSummarizeFetchTotal(30, "countries", 0)).toBe(65);

    expect(estimateSummarizeFetchTotal(630, "countries", 4)).toBe(117);

    expect(estimateSummarizeFetchTotal(2321, "continent", 3)).toBe(199);

    expect(estimateSummarizeFetchTotal(2321, "continent", 0)).toBe(175);

  });



  test("large selection caps sector fetch but not identical for all configs", () => {

    expect(estimateSummarizeFetchTotal(630, "countries", 0)).toBe(85);

    expect(estimateSummarizeFetchTotal(630, "countries", 4)).not.toBe(

      estimateSummarizeFetchTotal(630, "countries", 0),

    );

  });



  test("parseFetchTotalFromServerHint reads server progress", () => {

    expect(parseFetchTotalFromServerHint("Načítám datové řady pro AI (142 zdrojů včetně makro kontextu).")).toBe(

      142,

    );

    expect(parseFetchTotalFromServerHint("Načítám řady pro AI (12/156) · HDP")).toBe(156);

    expect(parseFetchTotalFromServerHint("Řady načteny (50/283) — skládám kontext.")).toBe(283);

  });



  test("formatPipelineFetchLine prefers server fetch total over stale estimate", () => {

    expect(

      formatPipelineFetchLine({

        selectedCount: 283,

        geoMode: "continent",

        relatedSegmentsCount: 3,

        actualFetchTotal: 283,

      }),

    ).toBe("AI stáhne cca 283 datových řad");

    expect(

      formatPipelineFetchLine({

        selectedCount: 283,

        geoMode: "continent",

        relatedSegmentsCount: 3,

        actualFetchTotal: null,

      }),

    ).toBe("283 řad po geo refine → AI stáhne cca 199 datových řad");

    expect(resolvePipelineFetchTotal({ selectedCount: 2321, geoMode: "continent", actualFetchTotal: 283 })).toBe(

      283,

    );

  });



  test("630 rows country geo — odhad ~3 min pipeline (kalibrace E2E)", () => {

    const pipeline = estimateExplorePipelineSec({

      selectedCount: 630,

      geoMode: "countries",

      relatedSegmentsCount: 4,

    });

    expect(pipeline).toBeGreaterThanOrEqual(150);

    expect(pipeline).toBeLessThanOrEqual(255);

    expect(formatExploreEtaSec(pipeline)).toMatch(/~3 min|~4 min/);

  });



  test("summarize alone for large selection ~2 min", () => {

    const sec = estimateSummarizeDurationSec({

      selectedCount: 630,

      geoMode: "countries",

      relatedSegmentsCount: 4,

    });

    expect(sec).toBeGreaterThanOrEqual(120);

    expect(sec).toBeLessThanOrEqual(210);

  });

});


