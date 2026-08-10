import {
  buildActiveRelatedSegmentLabels,
  buildExploreSectorHierarchy,
  buildRelatedSegmentList,
  buildUnifiedRelatedSegmentItems,
  applyRelatedSegmentOrder,
  sortAndRenumberRelatedSegmentItems,
  sortExploreSegmentLabels,
  swapPrimaryWithRelated,
} from "./exploreSectorHierarchy";
import { mapRelationshipRowToLinkedSector } from "./exploreRelatedPresets";

describe("exploreSectorHierarchy", () => {
  test("sortExploreSegmentLabels sorts Czech labels alphabetically", () => {
    expect(
      sortExploreSegmentLabels([
        "Zdravotnictví a farmacie",
        "Automobilový průmysl",
        "Bankovnictví a finance",
        "Energetika",
      ])
    ).toEqual([
      "Automobilový průmysl",
      "Bankovnictví a finance",
      "Energetika",
      "Zdravotnictví a farmacie",
    ]);
  });

  test("buildRelatedSegmentList deduplicates and excludes primary", () => {
    expect(
      buildRelatedSegmentList({
        primarySector: "Automobilový průmysl",
        supplementarySegments: ["Kovy, hutnictví a těžba", "Energetika"],
        relatedSegmentsText: "energetika, doprava",
      })
    ).toEqual(["Kovy, hutnictví a těžba", "Energetika", "doprava"]);
  });

  test("swapPrimaryWithRelated promotes related and demotes old primary", () => {
    const next = swapPrimaryWithRelated({
      primarySector: "Automobilový průmysl",
      relatedSector: "Kovy, hutnictví a těžba",
      supplementarySegments: ["Energetika", "Doprava a logistika"],
      relatedSegmentsText: "chemie",
    });

    expect(next).toEqual({
      primarySector: "Kovy, hutnictví a těžba",
      supplementarySegmentSelections: [
        "Automobilový průmysl",
        "Energetika",
        "Doprava a logistika",
        "chemie",
      ],
      relatedSegmentsText: "",
      relatedSegments: [
        "Automobilový průmysl",
        "Energetika",
        "Doprava a logistika",
        "chemie",
      ],
    });
  });

  test("swapPrimaryWithRelated returns null for invalid swap", () => {
    expect(
      swapPrimaryWithRelated({
        primarySector: "Automobilový průmysl",
        relatedSector: "Automobilový průmysl",
        supplementarySegments: ["Energetika"],
      })
    ).toBeNull();
    expect(
      swapPrimaryWithRelated({
        primarySector: "",
        relatedSector: "Energetika",
      })
    ).toBeNull();
  });

  test("buildExploreSectorHierarchy exposes combined payload values", () => {
    expect(
      buildExploreSectorHierarchy({
        primarySector: "Automobilový průmysl",
        supplementarySegments: ["Energetika"],
        relatedSegmentsText: "doprava",
      })
    ).toEqual({
      primarySector: "Automobilový průmysl",
      relatedSegments: ["Energetika", "doprava"],
      combinedRelatedSegments: "Energetika, doprava",
    });
  });

  test("buildActiveRelatedSegmentLabels merges predefined, manual and topics with exclusions", () => {
    expect(
      buildActiveRelatedSegmentLabels({
        primarySector: "Automobilový průmysl",
        relationshipRows: [
          { sector_id: "energy", sector_name_cs: "Energetika", relationship_type: "supplier" },
          { sector_id: "retail_consumer", sector_name_cs: "Maloobchod a spotřeba domácností" },
        ],
        excludedRelatedKeys: ["energy"],
        supplementarySegments: ["Doprava a logistika"],
        relatedSegmentsText: "komodity",
      })
    ).toEqual(["Maloobchod a spotřeba domácností", "Doprava a logistika", "komodity"]);
  });

  test("buildUnifiedRelatedSegmentItems preserves source metadata", () => {
    const items = buildUnifiedRelatedSegmentItems({
      primarySector: "Automobilový průmysl",
      relationshipRows: [
        { sector_id: "energy", sector_name_cs: "Energetika", weight: 0.8, rank: 1 },
      ],
      supplementarySegments: ["Doprava a logistika"],
      relatedSegmentsText: "komodity",
    });
    expect(items.map((item) => item.source)).toEqual(["predefined", "manual", "topic"]);
    expect(items.map((item) => item.rank)).toEqual([1, 2, 3]);
  });

  // Regression (ETAPA 4, 2026-08-03): rows shaped exactly like the raw
  // /explore/related-suggestions API response (related_segment_id/related_segment_name_cs, no
  // sector_id/sector_name_cs) must NOT silently vanish from unifiedRelatedItems. Passed
  // unmapped, they used to be treated as unlabeled/unkeyed and dropped entirely, which flipped
  // combinedRelatedSegments to "" every time an API response replaced the local fallback rows —
  // the mechanism behind the duplicate-request loop fixed in ExplorePage.jsx.
  test("buildUnifiedRelatedSegmentItems drops raw API-shaped rows without mapRelationshipRowToLinkedSector", () => {
    const items = buildUnifiedRelatedSegmentItems({
      primarySector: "Bankovnictví a finance",
      relationshipRows: [
        { related_segment_id: "construction_real_estate", related_segment_name_cs: "Stavebnictví a nemovitosti" },
      ],
    });
    expect(items).toEqual([]);
  });

  test("buildUnifiedRelatedSegmentItems keeps API-shaped rows once normalized via mapRelationshipRowToLinkedSector", () => {
    const apiRows = [
      { related_segment_id: "construction_real_estate", related_segment_name_cs: "Stavebnictví a nemovitosti", weight: 0.75, rank: 1 },
      { related_segment_id: "manufacturing_general", related_segment_name_cs: "Zpracovatelský průmysl", weight: 0.6, rank: 2 },
    ];
    const items = buildUnifiedRelatedSegmentItems({
      primarySector: "Bankovnictví a finance",
      relationshipRows: apiRows.map(mapRelationshipRowToLinkedSector).filter(Boolean),
    });
    expect(items.map((item) => item.label)).toEqual(["Stavebnictví a nemovitosti", "Zpracovatelský průmysl"]);
  });

  test("sortAndRenumberRelatedSegmentItems promotes ranks after removals", () => {
    const items = sortAndRenumberRelatedSegmentItems([
      { key: "a", label: "A", source: "predefined", weight: 0.75, rank: 1, priority_tier: "core" },
      { key: "c", label: "C", source: "predefined", weight: 0.55, rank: 5, priority_tier: "secondary" },
      { key: "d", label: "D", source: "predefined", weight: 0.55, rank: 6, priority_tier: "secondary" },
    ]);
    expect(items.map((item) => item.rank)).toEqual([1, 2, 3]);
    expect(items.map((item) => item.weight)).toEqual([0.8, 0.65, 0.55]);
    expect(items[1].priority_tier).toBe("core");
    expect(items[2].priority_tier).toBe("secondary");
  });

  test("applyRelatedSegmentOrder keeps custom drag order", () => {
    const items = sortAndRenumberRelatedSegmentItems(
      applyRelatedSegmentOrder(
        [
          { key: "a", label: "A", source: "predefined" },
          { key: "b", label: "B", source: "predefined" },
          { key: "c", label: "C", source: "predefined" },
        ],
        ["c", "a", "b"]
      )
    );
    expect(items.map((item) => item.label)).toEqual(["C", "A", "B"]);
    expect(items.map((item) => item.rank)).toEqual([1, 2, 3]);
    expect(items[0].weight).toBe(0.8);
  });
});
