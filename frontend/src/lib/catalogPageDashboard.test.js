import { addCatalogPreviewToPersonalDashboard, buildCatalogChartActionsProps } from "@/lib/catalogPageDashboard";

describe("catalogPageDashboard", () => {
  test("buildCatalogChartActionsProps disables add when not subscriber", () => {
    const props = buildCatalogChartActionsProps({
      feature: { isSubscriber: false, canPersonalDashboard: false, canSaveWidget: false },
      previewData: { rows: [{ period: "2020", value: 1 }, { period: "2021", value: 2 }] },
      previewError: "",
      previewLoading: false,
      onAddToDashboard: () => {},
    });
    expect(props.canAddToDashboard).toBe(false);
  });

  test("addCatalogPreviewToPersonalDashboard creates widget and snapshot", async () => {
    const calls = [];
    const api = {
      get: jest.fn(async () => ({ data: [{ id: "p1", is_default: true }] })),
      post: jest.fn(async (url) => {
        calls.push(url);
        if (url.includes("/widgets")) return { data: { id: "w1" } };
        return { data: {} };
      }),
    };
    const ok = await addCatalogPreviewToPersonalDashboard({
      api,
      nav: jest.fn(),
      def: { id: "fred" },
      previewData: {
        rows: [{ x: "2020", y: 1 }, { x: "2021", y: 2 }],
        fields: ["x", "y"],
        metadata: { filters_applied: { geo: "US" } },
      },
      row: { set_id: "DGS10", name: "10Y Treasury" },
      feature: { isSubscriber: true, canPersonalDashboard: true, canSaveWidget: true },
    });
    expect(ok).toBe(true);
    expect(calls.some((u) => u.includes("/widgets"))).toBe(true);
    expect(calls.some((u) => u.includes("render-widget"))).toBe(true);
  });

  test("addCatalogPreviewToPersonalDashboard opens page pick when multiple pages", async () => {
    const setPagePick = jest.fn();
    const api = {
      get: jest.fn(async () => ({
        data: [
          { id: "p1", title: "A", is_default: true },
          { id: "p2", title: "B" },
        ],
      })),
      post: jest.fn(),
    };
    const ok = await addCatalogPreviewToPersonalDashboard({
      api,
      nav: jest.fn(),
      def: { id: "fred" },
      previewData: {
        rows: [{ x: "2020", y: 1 }, { x: "2021", y: 2 }],
        fields: ["x", "y"],
      },
      row: { set_id: "DGS10", name: "10Y Treasury" },
      feature: { isSubscriber: true, canPersonalDashboard: true, canSaveWidget: true },
      setPagePick,
    });
    expect(ok).toBe(false);
    expect(setPagePick).toHaveBeenCalledWith(
      expect.objectContaining({ selectedId: "p1", pages: expect.any(Array), built: expect.any(Object) })
    );
    expect(api.post).not.toHaveBeenCalled();
  });
});
