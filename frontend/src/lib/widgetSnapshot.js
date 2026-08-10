/** Helpers for dashboard widget data snapshots (backend `data_snapshot` fields). */

export function snapshotDataFromWidget(w) {
  const snap = w?.data_snapshot;
  if (!snap || typeof snap !== "object") return null;
  const data = snap.data;
  if (data == null) return null;
  if (typeof data === "object" && data.error) return null;
  return data;
}

export function hasWidgetRenderableData(w) {
  if (!w) return false;
  const data = w.data ?? snapshotDataFromWidget(w);
  if (data == null) return false;
  if (typeof data === "object" && data.error) return false;
  if (Array.isArray(data)) return data.length > 0;
  if (typeof data === "object") {
    if (Array.isArray(data.rows) && data.rows.length > 0) return true;
    if (data.content != null && String(data.content).trim()) return true;
    if (data.value != null) return true;
    if (data.view === "chart" && Array.isArray(data.records)) return true;
    if (Array.isArray(data.items)) return true;
  }
  return typeof data === "number" || typeof data === "string";
}

export function widgetInitialFromListRow(w) {
  const data = snapshotDataFromWidget(w);
  const hasSnapshot = data != null;
  const stale = hasSnapshot ? isWidgetSnapshotStale(w) : false;
  return {
    ...w,
    width: w.width || "full",
    data: hasSnapshot ? data : w.data ?? null,
    _loading: !hasSnapshot && !hasWidgetRenderableData({ ...w, data }),
    from_snapshot: hasSnapshot,
    last_fetched_at: w.last_fetched_at ?? null,
    is_stale: stale,
    _refreshing: stale,
  };
}

export function isWidgetSnapshotStale(w) {
  if (!w?.expires_at) return Boolean(w?.is_stale);
  const exp = Date.parse(String(w.expires_at));
  if (Number.isNaN(exp)) return Boolean(w?.is_stale);
  return Date.now() >= exp;
}

/** Snapshot s daty, u kterého vypršela platnost — na pozadí stáhnout čerstvá data. */
export function widgetNeedsBackgroundRefresh(w) {
  if (!w?.id) return false;
  if (!snapshotDataFromWidget(w) && !hasWidgetRenderableData(w)) return false;
  return isWidgetSnapshotStale(w);
}

export function mergeResolvedWidget(base, resolved) {
  const fromSnapshot = Boolean(resolved?.from_snapshot);
  return {
    ...base,
    type: resolved.type ?? base.type,
    engine_type: resolved.engine_type ?? base.engine_type,
    title: resolved.title ?? base.title,
    width: resolved.width ?? base.width ?? "full",
    rowSpan: resolved.rowSpan !== undefined ? resolved.rowSpan : base.rowSpan,
    config: resolved.config ?? base.config,
    data: resolved.data ?? base.data,
    from_snapshot: fromSnapshot,
    last_fetched_at: resolved.last_fetched_at ?? base.last_fetched_at ?? null,
    is_stale: Boolean(resolved.is_stale),
    snapshot_status: resolved.snapshot_status ?? base.snapshot_status,
    refresh_error: resolved.refresh_error ?? null,
    _loading: false,
    _refreshing: false,
  };
}

export function formatSnapshotDate(iso) {
  if (!iso) return null;
  const d = new Date(String(iso));
  if (Number.isNaN(d.getTime())) return null;
  return d.toLocaleDateString("cs-CZ", { day: "2-digit", month: "2-digit", year: "numeric" });
}

/** Config keys that affect only display/layout — must not trigger live resolve. */
export const WIDGET_DISPLAY_ONLY_CONFIG_KEYS = new Set([
  "caption",
  "caption_en",
  "panel_style",
  "chart_color",
  "chart_colors",
  "chart_type",
  "height",
  "fullscreen",
  "grid_column_start",
  "grid_column_end",
  "grid_row_start",
  "grid_row_end",
  "bar_orientation",
  "show_legend",
  "show_grid",
  "y_axis_label",
  "x_axis_label",
  "default_data_view",
  "chart_sort_order",
  "chart_label_overrides",
  "chart_bar_labels",
  "chart_avg_line",
  "chart_median_line",
  "chart_trend_line",
  "chart_series_mode",
  "hide_chart_controls",
  "mini_chart",
  "chart_title_emphasis",
  "lock_source_data",
  "heading",
  "subheading",
  "content",
]);

const LAYOUT_PATCH_TOP_KEYS = new Set(["width", "rowSpan", "order"]);
const META_PATCH_TOP_KEYS = new Set(["title", "description", "section_page_id"]);

/** True pokud PATCH mění datovou konfiguraci a vyžaduje live resolve. */
export function widgetPatchAffectsDataCache(patch) {
  if (!patch || typeof patch !== "object") return false;
  for (const key of Object.keys(patch)) {
    if (key === "config") continue;
    if (!LAYOUT_PATCH_TOP_KEYS.has(key) && !META_PATCH_TOP_KEYS.has(key)) return true;
  }
  const cfg = patch.config;
  if (!cfg || typeof cfg !== "object") return false;
  return Object.keys(cfg).some((k) => !WIDGET_DISPLAY_ONLY_CONFIG_KEYS.has(k));
}

/** Sloučí layout-only PATCH do widgetu bez ztráty dat / snapshotu. */
export function mergeWidgetLayoutPatch(widget, saved, patch = {}) {
  const rowSpanFromPatch =
    patch.rowSpan !== undefined ? patch.rowSpan : saved?.rowSpan !== undefined ? saved.rowSpan : widget.rowSpan;
  return {
    ...widget,
    ...saved,
    engine_type: saved?.engine_type ?? widget.engine_type,
    width: saved?.width ?? patch.width ?? widget.width,
    rowSpan: rowSpanFromPatch,
    title: saved?.title ?? patch.title ?? widget.title,
    config: saved?.config
      ? { ...(widget.config || {}), ...saved.config, ...(patch.config || {}) }
      : patch.config
        ? { ...(widget.config || {}), ...patch.config }
        : widget.config,
    data: widget.data,
    data_snapshot: saved?.data_snapshot ?? widget.data_snapshot,
    last_fetched_at: saved?.last_fetched_at ?? widget.last_fetched_at,
    expires_at: saved?.expires_at ?? widget.expires_at,
    cache_key: saved?.cache_key ?? widget.cache_key,
    snapshot_status: saved?.snapshot_status ?? widget.snapshot_status,
    from_snapshot: widget.from_snapshot,
    is_stale: widget.is_stale,
    refresh_error: null,
    _loading: false,
    _refreshing: false,
  };
}

export function applyLocalWidgetReorder(widgets, ids, widgetLayout = {}) {
  const byId = new Map((Array.isArray(widgets) ? widgets : []).map((w) => [w.id, w]));
  return ids
    .map((id) => {
      const w = byId.get(id);
      if (!w) return null;
      const layout = widgetLayout?.[id];
      if (!layout || typeof layout !== "object") return w;
      return { ...w, config: { ...(w.config || {}), ...layout } };
    })
    .filter(Boolean);
}

export function widgetsNeedingLiveResolve(widgets) {
  return (Array.isArray(widgets) ? widgets : []).filter(
    (w) => w?.id && !hasWidgetRenderableData(w) && !snapshotDataFromWidget(w)
  );
}
