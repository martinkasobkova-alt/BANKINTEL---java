import React, { useMemo } from "react";
import { FeatureAccessContext } from "@/contexts/FeatureAccessContext";

const MOBILE_EMBED_FEATURES = [
  "chart_type",
  "chart_period",
  "chart_time_range",
  "chart_table_toggle",
  "view_toggle",
  "chart_avg_line",
  "chart_median_line",
  "chart_trend_line",
  "chart_compare",
  "chart_export",
  "chart_zoom",
  "composite_charts",
  "ad_free_dashboard",
];

/** V mobilním embedu povolit stejné ovládání grafu jako předplatitel na webu. */
export function MobileEmbedFeatureAccessProvider({ children }) {
  const value = useMemo(() => {
    const effective = {};
    for (const key of MOBILE_EMBED_FEATURES) {
      effective[key] = { access_level: "full", allowed: true };
    }
    return {
      effective,
      inFlight: false,
      accessMapReady: true,
      error: null,
      refetch: async () => {},
    };
  }, []);

  return (
    <FeatureAccessContext.Provider value={value}>{children}</FeatureAccessContext.Provider>
  );
}
