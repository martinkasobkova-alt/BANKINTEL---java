import { getAradCatalogRescueNotice } from "./aradCatalogRescueNotice";

describe("getAradCatalogRescueNotice", () => {
  test("returns bootstrap warning for arad bootstrap payload", () => {
    const msg = getAradCatalogRescueNotice({
      source: "arad",
      stale: true,
      catalog_rescue: "bootstrap_file",
      categories: [{ name: "A" }],
    });
    expect(msg).toMatch(/omezeném záchranném režimu/i);
  });

  test("returns stale snapshot warning for arad stale payload", () => {
    const msg = getAradCatalogRescueNotice({
      source: "arad",
      stale: true,
      catalog_rescue: "mongo_snapshot",
      categories: [{ name: "A" }],
    });
    expect(msg).toMatch(/poslední uložené verze/i);
  });

  test("returns null when no categories yet", () => {
    const msg = getAradCatalogRescueNotice({
      source: "arad",
      stale: true,
      catalog_rescue: "bootstrap_file",
      categories: [],
    });
    expect(msg).toBeNull();
  });
});
