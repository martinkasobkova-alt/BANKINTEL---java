import api from "@/lib/api";
import {
  hasWidgetRenderableData,
  mergeResolvedWidget,
  snapshotDataFromWidget,
  widgetInitialFromListRow,
} from "@/lib/widgetSnapshot";
import { refreshStaleWidgetsInBackground } from "@/lib/progressiveWidgetResolve";

/**
 * Načtení dat pro osobní widgety (server ověřuje id + user_id).
 * Widgety se snapshotem se vykreslí hned z GET — live resolve jen pro chybějící data.
 */
export function loadPersonalWidgetsProgressively(rawWidgets, onResolved, options = {}) {
  const { isStale, afterInitialBatch } = options;
  const widgets = Array.isArray(rawWidgets) ? rawWidgets : [];
  const tasks = widgets.filter((w) => w?.id);
  if (tasks.length === 0) {
    if (afterInitialBatch && !isStale?.()) {
      afterInitialBatch(tasks);
    }
    return;
  }

  const applyResolved = (w, resolved) => {
    onResolved(w.id, mergeResolvedWidget(w, resolved));
  };

  const finishBatch = () => {
    if (!isStale?.() && afterInitialBatch) {
      afterInitialBatch(tasks);
    }
    refreshStaleWidgetsInBackground(tasks, onResolved, {
      isStale,
      refreshOne: (w) => refreshPersonalWidget(w.id),
    });
  };

  const needsLive = tasks.filter((w) => {
    const initial = widgetInitialFromListRow(w);
    return !hasWidgetRenderableData(initial) && !snapshotDataFromWidget(w);
  });

  if (needsLive.length === 0) {
    finishBatch();
    return;
  }

  const ids = needsLive.map((w) => w.id);
  const byId = new Map(tasks.map((w) => [w.id, w]));

  const runLegacyParallel = () => {
    let remaining = needsLive.length;
    const tick = () => {
      remaining -= 1;
      if (remaining <= 0) finishBatch();
    };
    needsLive.forEach((w) => {
      api
        .post("/me/dashboard/render-widget", { id: w.id })
        .then(({ data: resolved }) => {
          if (isStale?.()) return;
          applyResolved(w, resolved);
        })
        .catch((e) => {
          if (isStale?.()) return;
          const d = e?.response?.data?.detail;
          let msg = "Nepodařilo se načíst data widgetu.";
          if (typeof d === "string") msg = d;
          else if (Array.isArray(d) && d.length && typeof d[0]?.msg === "string") msg = d[0].msg;
          onResolved(w.id, {
            ...w,
            data: { error: msg },
            _loading: false,
            _refreshing: false,
          });
        })
        .finally(tick);
    });
  };

  api
    .post("/me/dashboard/render-widgets", { ids })
    .then(({ data }) => {
      if (isStale?.()) return;
      const list = Array.isArray(data?.widgets) ? data.widgets : [];
      const resolvedById = new Map(list.map((x) => [x.id, x]));
      for (const wid of ids) {
        if (isStale?.()) return;
        const w = byId.get(wid);
        const resolved = resolvedById.get(wid);
        if (!w) continue;
        if (!resolved) {
          onResolved(wid, {
            ...w,
            data: { error: "Widget se nepodařilo načíst." },
            _loading: false,
            _refreshing: false,
          });
        } else {
          applyResolved(w, resolved);
        }
      }
      finishBatch();
    })
    .catch(() => {
      runLegacyParallel();
    });
}

export function refreshPersonalWidget(widgetId) {
  return api.post("/me/dashboard/render-widget", { id: widgetId, force_refresh: true });
}
