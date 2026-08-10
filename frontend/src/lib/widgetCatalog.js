/**
 * Shared widget type list + factory for empty widgets.
 * Used by admin editor (WidgetListEditor) and inline “+” on public pages.
 */
export const WIDGET_TYPES = [
  { value: "arad_view", label: "ARAD · indikátor (ČNB)" },
  { value: "eurostat_view", label: "Eurostat · dataset (EU)" },
  { value: "csu_view", label: "ČSÚ · výběr (DataStat)" },
  { value: "ecb_view", label: "ECB · časová řada (Data Portal)" },
  { value: "fred_view", label: "FRED · indikátor (St. Louis Fed)" },
  { value: "alphavantage_view", label: "ALPHA VANTAGE — akcie / indexy (OHLCV)" },
  { value: "worldbank_view", label: "World Bank · indikátor" },
  {
    value: "world_bank_data360_view",
    label: "World Bank · indikátor",
  },
  { value: "bis_view", label: "BIS · statistika (Basilejská banka)" },
  { value: "imf_view", label: "IMF · řada (CompactData)" },
  { value: "oecd_view", label: "OECD · řada (stats.oecd.org)" },
  { value: "computed_view", label: "Vlastní výpočet (poměr / součet / rozdíl)" },
  { value: "dataset_view", label: "Vlastní data · soubor (Excel / PDF / vlastní API)" },
  { value: "markdown", label: "Textové pole · text / popisek / obrázek" },
  { value: "rss_monitoring", label: "RSS monitoring · novinky (feed)" },
  { value: "ad", label: "Inzerce · reklamní prostor (bez titulku)" },
];

export function createEmptyWidget(type = "arad_view") {
  let config = {};
  if (type === "arad_view") {
    config = {
      view: "chart",
      chart_type: "line",
    };
  }
  if (type === "rss_monitoring") {
    config = {
      selected_feed_ids: [],
      categories: [],
      q: "",
      item_limit: 15,
      days: null,
    };
  }
  return {
    id: `tmp-${Math.random().toString(36).slice(2, 9)}`,
    type,
    title: "",
    width: "full",
    config,
  };
}
