import React, { useCallback, useMemo, useState } from "react";
import { toast } from "sonner";
import { ChartAnalystPanel } from "@/components/widgets/ChartAnalystTrigger";
import {
  buildDashboardChartContract,
  parseDashboardWidgetReferenceNumbers,
} from "@/lib/dashboardChartContracts";
import { addDashboardWidgetsFromChatActions } from "@/lib/dashboardAiAddWidget";

const DASHBOARD_SUGGESTIONS = [
  "Co grafy říkají?",
  "Najdi nejsilnější vztah mezi řadami",
  "V jakém období byl největší rozdíl mezi řadami?",
  "Spočítej Sharpe ratio pro první řadu",
  "Připrav analytickou kostku",
  "Přidej mi na dashboard graf HDP Německa",
];

function ScopeButton({ active, disabled, children, onClick }) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`h-7 rounded-lg border px-2 text-[11px] font-semibold transition disabled:opacity-45 ${
        active
          ? "border-sky-700 bg-sky-700 text-white"
          : "border-sky-200 bg-white text-sky-800 hover:bg-sky-50"
      }`}
    >
      {children}
    </button>
  );
}

function widgetKey(widget) {
  return String(widget?.id || widget?.widget_id || widget?.title || "");
}

function decorateWidgetForContract(widget, index) {
  if (!widget || !Number.isFinite(index)) return widget;
  return {
    ...widget,
    __dashboard_graph_index: index,
  };
}

export default function DashboardAiChat({
  widgets = [],
  currentWidget = null,
  pageId = "",
  pageTitle = "Dashboard",
  defaultScope = "dashboard",
  onClose,
  /** Zavolá se po úspěšném přidání grafu přes chat, aby rodič mohl znovu načíst widgety stránky. */
  onWidgetsChanged,
}) {
  const [scope, setScope] = useState(defaultScope === "current" && currentWidget ? "current" : "dashboard");
  const widgetIndexByKey = useMemo(() => {
    const map = new Map();
    widgets.forEach((widget, idx) => {
      const key = widgetKey(widget);
      if (key) map.set(key, idx + 1);
    });
    return map;
  }, [widgets]);

  const decorateWidgets = useMemo(
    () => (items) =>
      (Array.isArray(items) ? items : []).map((widget, localIdx) => {
        const index = widgetIndexByKey.get(widgetKey(widget)) || localIdx + 1;
        return decorateWidgetForContract(widget, index);
      }),
    [widgetIndexByKey],
  );

  const scopedWidgets = useMemo(() => {
    if (scope === "current" && currentWidget) return decorateWidgets([currentWidget]);
    return decorateWidgets(widgets);
  }, [currentWidget, decorateWidgets, scope, widgets]);

  const contract = useMemo(
    () =>
      buildDashboardChartContract(scopedWidgets, {
        pageId,
        pageTitle,
        scope,
        title: scope === "current" && currentWidget ? currentWidget.title || pageTitle : pageTitle,
      }),
    [currentWidget, pageId, pageTitle, scope, scopedWidgets],
  );
  const resolveChartContractForQuestion = useMemo(
    () => (question, fallbackContract) => {
      const refs = parseDashboardWidgetReferenceNumbers(question, widgets.length);
      if (!refs.length) return fallbackContract;
      const selected = refs.map((n) => widgets[n - 1]).filter(Boolean);
      if (!selected.length) return fallbackContract;
      return buildDashboardChartContract(decorateWidgets(selected), {
        pageId,
        pageTitle,
        scope: "referenced_graphs",
        title: `Vybrané grafy ${refs.join(", ")}`,
      });
    },
    [decorateWidgets, pageId, pageTitle, widgets],
  );

  // Stabilni klic konverzace vazany jen na stranku (+ konkretni widget u scope="current") - NE na
  // aktualni sadu widgetu/pocet radek, ktera se u dashboardu bezne meni (pridani/odebrani grafu).
  // Bez tohohle se pri kazde zmene poctu widgetu spocital jiny hash z chartContract a konverzace
  // vypadala jako "zresetovana", i kdyz sessionStorage pod puvodnim klicem porad existovala.
  const conversationKey =
    scope === "current" && currentWidget
      ? `dashboard:${pageId}:widget:${widgetKey(currentWidget)}`
      : `dashboard:${pageId}:all`;

  // "AI nad grafem" umi navrhnout pridani rady ("add_catalog_series" chart_actions), ale tam se
  // rada jen prida do JEDNOHO existujiciho grafu. Na dashboardu misto toho vytvarime rovnou NOVY
  // nezavisly widget na aktualni strance - viz dashboardAiAddWidget.js.
  const handleApplyActions = useCallback(
    async (actions) => {
      const added = await addDashboardWidgetsFromChatActions(actions, pageId);
      if (added) {
        toast.success("Graf byl přidán do vašeho dashboardu.");
        if (typeof onWidgetsChanged === "function") onWidgetsChanged();
      }
      return added;
    },
    [pageId, onWidgetsChanged],
  );

  const hasReadableData = Array.isArray(contract?.data) && contract.data.length > 0;
  const readableSeries = Number(contract?.metadata?.readable_series_count || contract?.series?.length || 0);
  const scopeLabel = scope === "current" ? "aktuální graf" : "celý dashboard";
  const headerExtra = (
    <div className="flex flex-wrap items-center justify-end gap-1.5">
      <ScopeButton
        active={scope === "current"}
        disabled={!currentWidget}
        onClick={() => setScope("current")}
      >
        Aktuální graf
      </ScopeButton>
      <ScopeButton active={scope === "dashboard"} onClick={() => setScope("dashboard")}>
        Celý dashboard
      </ScopeButton>
    </div>
  );

  return (
    <ChartAnalystPanel
      key={`${scope}:${currentWidget?.id || "all"}`}
      chartContract={contract}
      conversationKey={conversationKey}
      allowEmptyData
      embedded
      onClose={onClose}
      onApplyActions={handleApplyActions}
      heading="AI nad dashboardem"
      subheading={`Ptejte se na ${scopeLabel}, vztahy mezi řadami, analytickou kostku, nebo požádejte o přidání nového grafu`}
      emptyText={
        hasReadableData
          ? `AI pracuje s ${readableSeries} řadami z režimu ${scopeLabel}. Odkazy typu „graf 4 a 5“ automaticky vezmou tyto grafy z dashboardu. Umí i přidat nový graf na dashboard („přidej mi graf HDP Německa“).`
          : "V tomto rozsahu zatím nejsou čitelná numerická data z grafových widgetů. Můžete ale požádat o přidání nového grafu na dashboard."
      }
      suggestions={DASHBOARD_SUGGESTIONS}
      headerExtra={headerExtra}
      resolveChartContractForQuestion={resolveChartContractForQuestion}
    />
  );
}
