import {
  formatSnapshotDate,
  hasWidgetRenderableData,
  mergeResolvedWidget,
  snapshotDataFromWidget,
  widgetInitialFromListRow,
  widgetsNeedingLiveResolve,
  widgetPatchAffectsDataCache,
  mergeWidgetLayoutPatch,
  widgetNeedsBackgroundRefresh,
} from "@/lib/widgetSnapshot";

describe("widgetSnapshot", () => {
  test("widgetInitialFromListRow uses snapshot without loading", () => {
    const w = {
      id: "w1",
      type: "chart",
      data_snapshot: { data: { rows: [{ period: "2020", value: 1 }] } },
      last_fetched_at: "2026-01-15T10:00:00.000Z",
      expires_at: "2027-01-15T10:00:00.000Z",
    };
    const initial = widgetInitialFromListRow(w);
    expect(initial._loading).toBe(false);
    expect(initial.data.rows).toHaveLength(1);
    expect(initial.from_snapshot).toBe(true);
    expect(initial._refreshing).toBe(false);
  });

  test("stale snapshot starts background refresh state", () => {
    const w = {
      id: "w2",
      type: "chart",
      data_snapshot: { data: { rows: [{ period: "2020", value: 1 }] } },
      last_fetched_at: "2026-01-01T00:00:00.000Z",
      expires_at: "2026-01-02T00:00:00.000Z",
    };
    const initial = widgetInitialFromListRow(w);
    expect(initial.is_stale).toBe(true);
    expect(initial._refreshing).toBe(true);
    expect(widgetNeedsBackgroundRefresh(w)).toBe(true);
  });

  test("widget without snapshot starts loading", () => {
    const initial = widgetInitialFromListRow({ id: "w2", type: "chart" });
    expect(initial._loading).toBe(true);
    expect(initial.data).toBeNull();
  });

  test("mergeResolvedWidget preserves data on refresh error meta", () => {
    const base = {
      id: "w1",
      data: { rows: [{ period: "2020", value: 1 }] },
      from_snapshot: true,
      last_fetched_at: "2026-01-01T00:00:00.000Z",
    };
    const merged = mergeResolvedWidget(base, {
      data: { rows: [{ period: "2020", value: 1 }] },
      from_snapshot: true,
      refresh_error: "Aktualizace selhala.",
      is_stale: true,
    });
    expect(merged.refresh_error).toBe("Aktualizace selhala.");
    expect(merged._loading).toBe(false);
  });

  test("widgetsNeedingLiveResolve skips snapshot widgets", () => {
    const list = [
      { id: "a", data_snapshot: { data: { rows: [{ period: "2020", value: 1 }] } } },
      { id: "b", type: "chart" },
    ];
    expect(widgetsNeedingLiveResolve(list).map((w) => w.id)).toEqual(["b"]);
  });

  test("hasWidgetRenderableData detects rows", () => {
    expect(hasWidgetRenderableData({ data: { rows: [{ period: "x", value: 1 }] } })).toBe(true);
    expect(hasWidgetRenderableData({ data: null })).toBe(false);
  });

  test("formatSnapshotDate cs-CZ", () => {
    const s = formatSnapshotDate("2026-03-09T12:00:00.000Z");
    expect(s).toMatch(/\d{1,2}\.\s?\d{1,2}\.\s?2026/);
  });

  test("snapshotDataFromWidget ignores error snapshot", () => {
    expect(snapshotDataFromWidget({ data_snapshot: { data: { error: "x" } } })).toBeNull();
  });

  test("widgetPatchAffectsDataCache ignores layout and display patches", () => {
    expect(widgetPatchAffectsDataCache({ width: "half" })).toBe(false);
    expect(widgetPatchAffectsDataCache({ rowSpan: 3 })).toBe(false);
    expect(widgetPatchAffectsDataCache({ title: "Nový název" })).toBe(false);
    expect(widgetPatchAffectsDataCache({ config: { chart_color: "#fff", chart_type: "area" } })).toBe(false);
    expect(widgetPatchAffectsDataCache({ config: { lock_source_data: true } })).toBe(false);
    expect(widgetPatchAffectsDataCache({ config: { grid_column_start: 2, grid_row_start: 1 } })).toBe(false);
  });

  test("widgetPatchAffectsDataCache detects data config patches", () => {
    expect(widgetPatchAffectsDataCache({ config: { date_from: "2020-01-01" } })).toBe(true);
    expect(widgetPatchAffectsDataCache({ config: { chart_compare_with: [{ id: 1 }] } })).toBe(true);
    expect(widgetPatchAffectsDataCache({ config: { chart_data_mode: "latest" } })).toBe(true);
    expect(widgetPatchAffectsDataCache({ config: { selected_dimensions: { geo: "DE" } } })).toBe(true);
    expect(widgetPatchAffectsDataCache({ config: { dimension_filters: { freq: "A" } } })).toBe(true);
  });

  test("mergeWidgetLayoutPatch preserves snapshot data", () => {
    const widget = {
      id: "w1",
      data: { rows: [{ period: "2020", value: 1 }] },
      data_snapshot: { data: { rows: [{ period: "2020", value: 1 }] } },
      cache_key: "abc",
      from_snapshot: true,
    };
    const merged = mergeWidgetLayoutPatch(widget, { width: "half", cache_key: "abc" }, { width: "half" });
    expect(merged.data.rows).toHaveLength(1);
    expect(merged.cache_key).toBe("abc");
    expect(merged.from_snapshot).toBe(true);
    expect(merged._loading).toBe(false);
  });
});
