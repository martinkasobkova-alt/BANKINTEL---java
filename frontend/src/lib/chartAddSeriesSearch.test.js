import {
  addSeriesFallbackResult,
  addSeriesSearchQueries,
  buildAddSeriesSelectionContext,
  candidateHasQuerySupport,
  compactCurrencyPairFromText,
  contextualAddSeriesSearchQueries,
  rankAddSeriesSuggestions,
  suggestionMatchesSelectionContext,
} from "./chartAddSeriesSearch";

describe("chartAddSeriesSearch", () => {
  it("expands compact ISO currency pairs for catalog search", () => {
    expect(compactCurrencyPairFromText("pridej tam czkeur")).toEqual({
      base: "CZK",
      quote: "EUR",
      compact: "CZKEUR",
    });
    expect(addSeriesSearchQueries("czkeur")).toEqual([
      "CZK EUR exchange rate",
      "CZK EUR spot exchange rate",
      "EUR CZK exchange rate",
      "CZKEUR exchange rate",
      "czkeur",
    ]);
  });

  it("ranks verified FX/spot series ahead of weak text matches", () => {
    const ranked = rankAddSeriesSuggestions([
      {
        source: "arad",
        set_id: "1145:SMIRNOOBUVMIRS237EUR011311",
        title: "Statistika úrokových sazeb - nové obchody - úvěry, EUR, od 7,5 mil. CZK",
      },
      {
        source: "ecb2",
        set_id: "EXR/A.CZK.EUR.SP00.A",
        title: "Czech koruna · Average · Spot (Euro)",
        full_path: "ECB · ověřené řady > CZK > EXR",
      },
      {
        source: "bis",
        set_id: "BIS|WS_XRU|D.XM",
        title: "Euro area · US dollar exchange rates",
      },
    ], "czkeur");

    expect(ranked[0].set_id).toBe("EXR/A.CZK.EUR.SP00.A");
  });

  it("inherits active chart entities instead of selecting an unrelated geography", () => {
    const context = buildAddSeriesSelectionContext(
      { context_mode: "inherit_chart", context_terms: ["Czechia", "Austria", "Bulgaria"] },
      { series: [{ label: "Czechia" }, { label: "Austria" }, { label: "Bulgaria" }] },
    );
    const ranked = rankAddSeriesSuggestions([
      { source: "bis", set_id: "AR", title: "Argentina · Central bank policy rates" },
      { source: "bis", set_id: "AT", title: "Austria · Central bank policy rates" },
      { source: "bis", set_id: "AU", title: "Australia · Central bank policy rates" },
    ], "interest rates", context);

    expect(ranked[0].set_id).toBe("AT");
    expect(suggestionMatchesSelectionContext(ranked[0], context)).toBe(true);
    expect(suggestionMatchesSelectionContext(ranked[1], context)).toBe(false);
  });

  it("lets an explicit target override entities inherited from the chart", () => {
    const context = buildAddSeriesSelectionContext(
      { context_mode: "explicit", context_terms: ["Argentina"] },
      { series: [{ label: "Czechia" }, { label: "Austria" }] },
    );
    const ranked = rankAddSeriesSuggestions([
      { source: "bis", set_id: "AT", title: "Austria · Central bank policy rates" },
      { source: "bis", set_id: "AR", title: "Argentina · Central bank policy rates" },
    ], "interest rates Argentina", context);

    expect(ranked[0].set_id).toBe("AR");
  });

  it("builds multilingual entity-aware catalog queries", () => {
    const context = buildAddSeriesSelectionContext({
      context_mode: "inherit_chart",
      context_terms: ["Austria", "Bulgaria"],
      catalog_query: "úrokové míry",
      catalog_queries: ["interest rates", "úrokové míry"],
    }, { series: [{ label: "Austria" }, { label: "Bulgaria" }] });

    expect(contextualAddSeriesSearchQueries("úrokové míry", context)).toEqual(expect.arrayContaining([
      "interest rates austria",
      "interest rates bulgaria",
      "úrokové míry austria",
      "úrokové míry bulgaria",
    ]));
  });
  it("blocks auto-add of candidates with no evidence for the requested concept", () => {
    expect(candidateHasQuerySupport(
      { source: "fred", set_id: "EXPINF10YR", title: "10-Year Expected Inflation" },
      ["interest income and costs", "urokove vynosy naklady"],
    )).toBe(false);

    expect(candidateHasQuerySupport(
      { source: "ecb2", set_id: "MIR", title: "Interest rates and bank lending costs" },
      ["interest income and costs", "urokove vynosy naklady"],
    )).toBe(true);
  });

  it("keeps the original add-series request and source in catalog fallback actions", () => {
    const result = addSeriesFallbackResult(
      "Interest income (banking)",
      [],
      "",
      {
        fallbackQuery: "Pridej do grafu urokove vynosy a urokove naklady ceskych bank",
        sourceIds: ["arad"],
      },
    );

    expect(result.chart_actions[0]).toMatchObject({
      type: "open_catalog_search",
      query: "Pridej do grafu urokove vynosy a urokove naklady ceskych bank",
      catalog: "arad",
      source: "arad",
    });
  });
});
