import {
  collectBisPreviewCandidates,
  pickBestBisPreviewRow,
  pickPreferredBisRefArea,
} from "./bisResolveSearchPreviewRow";

const bisDef = { id: "bis", sourceType: "bis", label: "BIS" };

describe("pickPreferredBisRefArea", () => {
  it("prefers CZ when available", () => {
    expect(pickPreferredBisRefArea(["US", "CZ", "DE"])).toBe("CZ");
  });

  it("uses geo hint from search row", () => {
    expect(pickPreferredBisRefArea(["US", "FR"], ["FR"])).toBe("FR");
  });
});

describe("collectBisPreviewCandidates", () => {
  it("returns only previewable leaf sets", () => {
    const rows = collectBisPreviewCandidates(
      {
        categories: [
          {
            name: "Total credit",
            path: "BIS > Total credit",
            children: [],
            sets: [
              {
                set_id: "WS_TC||DATAFLOW",
                name: "dataflow",
                item_kind: "dataflow",
              },
              {
                set_id: "BIS|WS_TC|Q.CZ.W0.S1.S1.N.A",
                name: "Czech Republic",
                item_kind: "selection",
                ref_area: "CZ",
                bis_dataflow: "WS_TC",
              },
            ],
          },
        ],
      },
      bisDef,
    );
    expect(rows).toHaveLength(1);
    expect(rows[0].set_id).toBe("BIS|WS_TC|Q.CZ.W0.S1.S1.N.A");
  });
});

describe("pickBestBisPreviewRow", () => {
  it("prefers CZ series among candidates", () => {
    const picked = pickBestBisPreviewRow(
      [
        { set_id: "BIS|WS_TC|Q.US.X", ref_area: "US" },
        { set_id: "BIS|WS_TC|Q.CZ.X", ref_area: "CZ" },
      ],
      [],
    );
    expect(picked?.set_id).toBe("BIS|WS_TC|Q.CZ.X");
  });
});
