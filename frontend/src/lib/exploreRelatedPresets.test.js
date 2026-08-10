import { mapRelationshipRowToLinkedSector, localRelatedSegmentRows } from "./exploreRelatedPresets";

describe("mapRelationshipRowToLinkedSector", () => {
  test("normalizes rows shaped like the local relationship table (sector_id/sector_name_cs)", () => {
    const row = {
      sector_id: "energy",
      sector_name_cs: "Energetika",
      weight: 0.8,
      rank: 1,
      relationship_type: "customer",
    };
    expect(mapRelationshipRowToLinkedSector(row)).toMatchObject({
      sector_id: "energy",
      sector_name_cs: "Energetika",
      weight: 0.8,
      rank: 1,
    });
  });

  // Regression: the /explore/related-suggestions and /explore/sector/related-suggestions
  // endpoints return rows shaped as { related_segment_id, related_segment_name_cs, ... } (see
  // ExploreAuxiliaryService), not { sector_id, sector_name_cs } like the local relationship
  // table. ExplorePage.jsx used to store the API rows in state unmapped, so
  // relatedRowKey/relatedRowLabel (exploreSectorHierarchy.js) — which only read
  // sector_id/sector_name_cs — saw every API row as unlabeled and dropped all of them from
  // unifiedRelatedItems. That flipped combinedRelatedSegments between "" and its real value on
  // every refreshRelationshipRelatedRows() run, which (combined with combinedRelatedSegments
  // being part of that callback's own dependency array) produced dozens of duplicate POSTs for a
  // single sector change — live-verified 2026-08-03 with "Bankovnictví a finance".
  test("normalizes rows shaped like the /explore/related-suggestions API response", () => {
    const apiRow = {
      rank: 1,
      related_segment_id: "construction_real_estate",
      related_segment_name_cs: "Stavebnictví a nemovitosti",
      weight: 0.75,
      relationship_type: "customer",
      direction: "context_specific",
      reason_cs: "Hypoteky a nemovitostni trh jsou vyznamne pro bankovni uvery a riziko.",
    };
    const mapped = mapRelationshipRowToLinkedSector(apiRow);
    expect(mapped.sector_id).toBe("construction_real_estate");
    expect(mapped.sector_name_cs).toBe("Stavebnictví a nemovitosti");
    expect(mapped.weight).toBe(0.75);
  });

  test("returns null for a row with neither id/name convention", () => {
    expect(mapRelationshipRowToLinkedSector({ weight: 0.5 })).toBeNull();
  });

  test("returns null for non-object input", () => {
    expect(mapRelationshipRowToLinkedSector(null)).toBeNull();
    expect(mapRelationshipRowToLinkedSector(undefined)).toBeNull();
  });
});

describe("localRelatedSegmentRows", () => {
  test("finds related rows for a known manager segment by exact Czech label", () => {
    const rows = localRelatedSegmentRows("Bankovnictví a finance", { limit: 8 });
    expect(rows.length).toBeGreaterThan(0);
    expect(rows[0]).toHaveProperty("sector_id");
    expect(rows[0]).toHaveProperty("sector_name_cs");
  });

  test("returns an empty array for an unknown segment", () => {
    expect(localRelatedSegmentRows("Neexistujici Segment Xyz", { limit: 8 })).toEqual([]);
  });
});
