import { bisPreviewGroupKey } from "./bisCatalogFreq";

describe("bisPreviewGroupKey", () => {
  it("uses unique set_id per series, not shared country bucket", () => {
    const base = {
      bis_dataflow: "WS_LBS_D_PUB",
      ref_area: "AT",
      territory: "Austria",
    };
    const a = bisPreviewGroupKey({
      ...base,
      set_id: "BIS|WS_LBS_D_PUB|Q.AT.XW.S1.S1.N.L.LE.F3.T._Z.EUR._T.M.V.N.C01",
    });
    const b = bisPreviewGroupKey({
      ...base,
      set_id: "BIS|WS_LBS_D_PUB|Q.AT.XW.S1.S1.N.L.LE.F3.T._Z.USD._T.M.V.N.C02",
    });
    expect(a).not.toBe(b);
    expect(a).toContain("BIS|WS_LBS_D_PUB|");
    expect(b).toContain("BIS|WS_LBS_D_PUB|");
  });
});
