import { isEcbCuratedRowPreviewEligible } from "./ecbCatalogSetId";

describe("isEcbCuratedRowPreviewEligible", () => {
  it("accepts curated ECB alias rows with country from preview verification", () => {
    expect(
      isEcbCuratedRowPreviewEligible({
        set_id: "ecb_mir_mortgage_rate_new_business",
        preview_request_payload: { country: "SE" },
      }),
    ).toBe(true);
  });

  it("does not accept curated ECB alias rows without country context", () => {
    expect(
      isEcbCuratedRowPreviewEligible({
        set_id: "ecb_mir_mortgage_rate_new_business",
      }),
    ).toBe(false);
  });
});
