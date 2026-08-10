import fs from "fs";
import path from "path";
import { geographyIso2, geographyMatchesRegion, MAP_PROJECTIONS } from "@/lib/chartGeoMapData";
import {
  computeFeaturesViewBox,
  computeSideLegendMapLayout,
  featureToSvgPath,
  filterFeaturesByRegion,
  loadWorldAtlasTopology,
  shouldUseGeoMapSideLegend,
  topologyToCountryFeatures,
} from "@/lib/geoMapRenderer";

describe("geoMapRenderer", () => {
  it("switches geo map legend to side layout in narrow plots", () => {
    expect(shouldUseGeoMapSideLegend({ plotWidth: 480 })).toBe(true);
    expect(shouldUseGeoMapSideLegend({ plotWidth: 720 })).toBe(false);
    expect(shouldUseGeoMapSideLegend({ veryNarrowWidget: true, plotWidth: 900 })).toBe(true);
    expect(shouldUseGeoMapSideLegend({ plotWidth: 0 })).toBe(false);
  });

  it("computes compact side-legend map width without excess gutter", () => {
    const frame = computeSideLegendMapLayout({
      viewBox: "0 0 800 420",
      plotWidth: 480,
      plotHeight: 260,
      legendReserve: 54,
      gap: 8,
    });
    expect(frame).not.toBeNull();
    expect(frame.mapWidth + 54 + 8).toBeLessThanOrEqual(480);
    expect(frame.mapWidth).toBeGreaterThan(200);
  });

  it("loads bundled world atlas topology", async () => {
    const topology = await loadWorldAtlasTopology();
    const all = topologyToCountryFeatures(topology);
    expect(topology?.type).toBe("Topology");
    expect(all.length).toBeGreaterThan(100);
  });

  it("resolves ISO for world-atlas country names and renders europe paths", async () => {
    const atlasPath = path.join(__dirname, "../../public/world-atlas-countries-110m.json");
    const topology = JSON.parse(fs.readFileSync(atlasPath, "utf8"));
    const all = topologyToCountryFeatures(topology);
    expect(all.length).toBeGreaterThan(100);

    const unresolved = all.filter((f) => !geographyIso2(f));
    const europe = filterFeaturesByRegion(all, "europe");
    expect(europe.length).toBeGreaterThan(30);
    expect(unresolved.length).toBeLessThan(20);

    const proj = MAP_PROJECTIONS.europe;
    const paths = europe.filter((f) => featureToSvgPath(f, proj, "europe").length > 10);
    expect(paths.length).toBe(europe.length);

    const frame = computeFeaturesViewBox(europe, proj, { region: "europe" });
    expect(frame.viewBox).toMatch(/^-?\d/);
    expect(frame.aspect).toBeGreaterThan(0.45);
    expect(frame.aspect).toBeLessThan(1.05);
    const vbParts = frame.viewBox.split(/\s+/).map(Number);
    expect(vbParts[2]).toBeLessThan(1200);
    expect(vbParts[2] / vbParts[3]).toBeLessThan(2.2);
    const germany = all.find((f) => f.properties?.name === "Germany");
    expect(geographyIso2(germany)).toBe("DE");
    expect(geographyMatchesRegion(germany, "europe")).toBe(true);
  });
});
