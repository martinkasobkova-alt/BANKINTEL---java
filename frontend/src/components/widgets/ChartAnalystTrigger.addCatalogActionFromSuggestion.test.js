import { describe, expect, it } from "vitest";
import { addCatalogActionFromSuggestion } from "./ChartAnalystTrigger.jsx";

// Zivy nalez: AI nad dashboardem pridala widget "Gross domestic product, volume, country
// specific (A) - Nemecko" (OECD4), tvrdila ze ho overila pres /catalog/preview, a widget skoncil
// s "Pro tuto radu nebyla nalezena zadna data". Priciny: addCatalogActionFromSuggestion pri
// prevodu navrhu na chart_action zahazovala query_params (rok, mereni, frekvence, ref_area...) -
// overovaci preview volani tak melo volnejsi telo (jen set_id) nez skutecny widget, u
// vicerozmernych zdroju (OECD4/Eurostat/World Bank) to nechalo projit jinou/vychozi dimenzi.
describe("addCatalogActionFromSuggestion", () => {
  it("preserves query_params from the suggestion onto the chart action (OECD4-style multi-dimension source)", () => {
    const suggestion = {
      source: "oecd4",
      set_id: "economic_outlook_118/DEU/GDP/_/A",
      title: "Gross domestic product, volume, country specific (A) · Německo",
      query_params: {
        provider: "oecd",
        oecd_api_mode: "oecd4_offline",
        oecd4_key: "economic_outlook_118",
        oecd4_measure: "GDP",
        ref_area: "DEU",
        freq: "A",
        agency: "OECD.ECO.MAD",
        dataflow: "DSD_EO@DF_EO",
        version: "1.4",
      },
    };

    const action = addCatalogActionFromSuggestion(suggestion, "HDP Německa");

    expect(action).toBeTruthy();
    expect(action.query_params).toEqual(suggestion.query_params);
  });

  it("leaves query_params undefined when the suggestion has none (single-dimension sources unaffected)", () => {
    const suggestion = { source: "fred", set_id: "FPCPITOTLZGCZE", title: "Inflation, consumer prices" };

    const action = addCatalogActionFromSuggestion(suggestion, "inflace");

    expect(action).toBeTruthy();
    expect(action.query_params).toBeUndefined();
  });
});
