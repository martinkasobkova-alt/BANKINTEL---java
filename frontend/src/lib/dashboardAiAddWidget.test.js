import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock("@/lib/api", () => ({
  __esModule: true,
  default: { post: apiMocks.post },
}));

import { addDashboardWidgetFromChatAction } from "./dashboardAiAddWidget.js";

beforeEach(() => {
  apiMocks.post.mockReset();
});

// Zivy nalez: "AI nad dashboardem" pridala OECD4 widget bez dat, presto ze AI text tvrdil, ze
// radu overila pres /catalog/preview. Pricina: tahle funkce si pro overeni stavela vlastni "row"
// jen ze set_id/indicator_id a query_params (rok, mereni, ref_area...) z chart_action zahazovala -
// /catalog/preview tak overoval jinou (chudsi/vychozi) dimenzi, nez jakou pak zadal skutecny
// widget. Test overuje, ze query_params z action ted dojde az do /catalog/preview telesa.
describe("addDashboardWidgetFromChatAction", () => {
  it("forwards action.query_params into the /catalog/preview verification body", async () => {
    apiMocks.post.mockResolvedValueOnce({ data: { rows: [] } });
    const action = {
      catalog: "oecd4",
      source: "oecd4",
      set_id: "economic_outlook_118/DEU/GDP/_/A",
      name: "HDP Německa",
      query_params: {
        provider: "oecd",
        oecd4_key: "economic_outlook_118",
        ref_area: "DEU",
        freq: "A",
      },
    };

    const result = await addDashboardWidgetFromChatAction(action, "page-1");

    expect(apiMocks.post).toHaveBeenCalledTimes(1);
    const [url, body] = apiMocks.post.mock.calls[0];
    expect(url).toBe("/catalog/preview");
    expect(body.query_params).toMatchObject(action.query_params);
    // Preview vratil 0 radku - musi to hlasit jako "zadna data", ne tise vytvorit prazdny widget.
    expect(result).toEqual({ ok: false, reason: "no_data" });
  });

  it("still works when the action carries no query_params (single-dimension sources)", async () => {
    apiMocks.post.mockResolvedValueOnce({ data: { rows: [] } });
    const action = { catalog: "fred", set_id: "FPCPITOTLZGCZE", name: "Inflation" };

    await addDashboardWidgetFromChatAction(action, "page-1");

    const [, body] = apiMocks.post.mock.calls[0];
    expect(body.query_params).toBeUndefined();
  });
});
