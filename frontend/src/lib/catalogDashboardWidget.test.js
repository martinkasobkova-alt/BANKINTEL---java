import { createExternalCatalogWidgetWithSnapshot } from "@/lib/catalogDashboardWidget";

describe("createExternalCatalogWidgetWithSnapshot", () => {
  test("creates widget then calls render-widget for snapshot", async () => {
    const calls = [];
    const api = {
      post: jest.fn(async (url, _body) => {
        calls.push(url);
        if (url.includes("/widgets")) return { data: { id: "w-new" } };
        return { data: { from_snapshot: false, data: { rows: [] } } };
      }),
    };
    await createExternalCatalogWidgetWithSnapshot(api, "page-1", {
      title: "Graf",
      config: { set_id: "x" },
    });
    expect(calls[0]).toContain("/widgets");
    expect(calls[1]).toBe("/me/dashboard/render-widget");
    expect(api.post).toHaveBeenCalledTimes(2);
  });

  test("returns widget even if render-widget fails", async () => {
    const api = {
      post: jest.fn(async (url) => {
        if (url.includes("/widgets")) return { data: { id: "w2" } };
        throw new Error("fail");
      }),
    };
    const w = await createExternalCatalogWidgetWithSnapshot(api, "p", { title: "T", config: {} });
    expect(w.id).toBe("w2");
  });
});
