import api, { formatApiErrorFromAxios } from "@/lib/api";
import { cleanWidgetCaption } from "@/lib/widgetCaption";
import {
  hasWidgetRenderableData,
  mergeResolvedWidget,
  snapshotDataFromWidget,
  widgetInitialFromListRow,
  widgetNeedsBackgroundRefresh,
} from "@/lib/widgetSnapshot";

/** Typy widgetů, u kterých dává smysl doplnit AI popisek po načtení dat. */
const WIDGET_TYPES_ELIGIBLE_FOR_AI = new Set([
  "arad_view",
  "computed_view",
  "dataset_view",
  "eurostat_view",
  "csu_view",
  "ecb_view",
  "fred_view",
  "alphavantage_view",
  "worldbank_view",
  "world_bank_data360_view",
  "bis_view",
  "imf_view",
  "oecd_view",
  "chart_net_result",
  "chart_dataset_distribution",
  "dataset_chart",
  "formula_chart",
]);

function widgetNeedsLiveResolve(w) {
  if (snapshotDataFromWidget(w)) return false;
  return !hasWidgetRenderableData(w);
}

function staleRefreshErrorMessage(e) {
  return (
    formatApiErrorFromAxios(e) ||
    e?.response?.data?.detail ||
    "Aktualizace na pozadí selhala, zobrazuji poslední uložená data."
  );
}

/**
 * Po zobrazení snapshotu stáhne čerstvá data na pozadí (bez ručního Obnovit).
 */
export function refreshStaleWidgetsInBackground(rawWidgets, onResolved, { isStale, refreshOne } = {}) {
  const stale = (Array.isArray(rawWidgets) ? rawWidgets : []).filter((w) =>
    widgetNeedsBackgroundRefresh(w)
  );
  if (!stale.length || typeof refreshOne !== "function") return;

  stale.forEach((w) => {
    const initial = widgetInitialFromListRow(w);
    onResolved(w.id, { ...initial, _refreshing: true, refresh_error: null });
    refreshOne(w)
      .then(({ data: resolved }) => {
        if (isStale?.()) return;
        onResolved(w.id, {
          ...mergeResolvedWidget(initial, resolved),
          type: resolved.type ?? w.type,
          ai_commentary: resolved.ai_commentary ?? w.ai_commentary,
        });
      })
      .catch((e) => {
        if (isStale?.()) return;
        const msg = staleRefreshErrorMessage(e);
        onResolved(w.id, {
          ...initial,
          _refreshing: false,
          is_stale: true,
          refresh_error: typeof msg === "string" ? msg : "Aktualizace selhala.",
        });
      });
  });
}

/**
 * Po načtení rozvrhu stránky doplní data widgetů bez snapshotu.
 * Widgety s uloženým snapshotem se nevolají — okamžitý render z GET/config.
 */
export function loadWidgetsProgressively(rawWidgets, onResolved, options = {}) {
  const { isStale, afterInitialBatch } = options;
  const widgets = Array.isArray(rawWidgets) ? rawWidgets : [];
  const tasks = widgets.filter((w) => w?.id);
  if (tasks.length === 0) {
    if (afterInitialBatch && !isStale?.()) {
      afterInitialBatch(tasks);
    }
    return;
  }

  const runStaleRefresh = () => {
    refreshStaleWidgetsInBackground(tasks, onResolved, {
      isStale,
      refreshOne: refreshHomepageWidget,
    });
  };

  const needsLive = tasks.filter(widgetNeedsLiveResolve);
  if (needsLive.length === 0) {
    if (afterInitialBatch && !isStale?.()) {
      afterInitialBatch(tasks);
    }
    runStaleRefresh();
    return;
  }

  let remaining = needsLive.length;
  const tick = () => {
    remaining -= 1;
    if (remaining <= 0) {
      if (!isStale?.() && afterInitialBatch) {
        afterInitialBatch(tasks);
      }
      runStaleRefresh();
    }
  };

  needsLive.forEach((w) => {
    api
      .post("/homepage/render-widget", {
        id: w.id,
        type: w.type,
        title: w.title,
        width: w.width,
        rowSpan: w.rowSpan,
        config: w.config,
        skip_ai: true,
      })
      .then(({ data: resolved }) => {
        if (isStale?.()) return;
        onResolved(w.id, {
          ...mergeResolvedWidget(w, resolved),
          type: resolved.type ?? w.type,
          ai_commentary: resolved.ai_commentary ?? w.ai_commentary,
        });
      })
      .catch(() => {
        if (isStale?.()) return;
        onResolved(w.id, {
          id: w.id,
          type: w.type,
          title: w.title,
          width: w.width,
          rowSpan: w.rowSpan,
          config: w.config,
          data: { error: "Nepodařilo se načíst data widgetu." },
          _loading: false,
          _refreshing: false,
        });
      })
      .finally(tick);
  });
}

/**
 * Doplní `ai_commentary` u widgetů bez ručního popisku (stejné chování jako dřív u /render).
 * Volá se až po načtení dat — první kresba zůstane rychlá.
 */
export async function enrichWidgetsWithAiAsync(taskWidgets, { isStale, setPage }) {
  for (const w of taskWidgets) {
    if (isStale?.()) return;
    if (!WIDGET_TYPES_ELIGIBLE_FOR_AI.has(w.type)) continue;
    if (cleanWidgetCaption(w.config?.caption)) continue;
    try {
      const { data } = await refetchOneWidget(w, false);
      if (isStale?.()) return;
      if (!data?.ai_commentary) continue;
      setPage((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          widgets: prev.widgets.map((pw) =>
            pw.id === w.id ? { ...pw, ai_commentary: data.ai_commentary } : pw
          ),
        };
      });
    } catch {
      /* AI je volitelná */
    }
  }
}

/** Jedna karta — po uložení změn v adminu (PATCH), aby se doplnila `data` bez starých race. */
export function refetchOneWidget(widget, skipAi = true, { forceRefresh = false } = {}) {
  return api.post("/homepage/render-widget", {
    id: widget.id,
    type: widget.type,
    title: widget.title,
    width: widget.width,
    rowSpan: widget.rowSpan,
    config: widget.config,
    skip_ai: skipAi,
    force_refresh: forceRefresh,
  });
}

export function refreshHomepageWidget(widget) {
  return refetchOneWidget(widget, true, { forceRefresh: true });
}
