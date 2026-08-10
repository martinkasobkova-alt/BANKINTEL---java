import React, { useMemo, useRef, useState } from "react";
import {
  Copy,
  Download,
  FileSpreadsheet,
  FileImage,
  Maximize2,
  Table2,
} from "lucide-react";
import {
  resolveToolbarCapabilities,
  TOOLBAR_ACTIONS,
  isToolbarActionAllowed,
} from "./chartToolbarCapabilities";
import {
  controlButtonClass,
  exportButtonClass,
  getActiveControlStyle,
  getChartTheme,
  toolbarGroupLabelClass,
} from "./chartDashboardStyle";

const TRANSFORM_LABELS = {
  [TOOLBAR_ACTIONS.RAW]: "Absolutní",
  [TOOLBAR_ACTIONS.INDEX_100]: "Index 100",
  [TOOLBAR_ACTIONS.YOY]: "YoY",
  [TOOLBAR_ACTIONS.MOM]: "MoM",
  [TOOLBAR_ACTIONS.QOQ]: "QoQ",
  [TOOLBAR_ACTIONS.ROLLING_AVERAGE]: "Klouz. prům.",
  [TOOLBAR_ACTIONS.ROLLING_MEDIAN]: "Klouz. med.",
  [TOOLBAR_ACTIONS.SPREAD]: "Spread",
  [TOOLBAR_ACTIONS.RATIO]: "Poměr",
};

/**
 * Dashboard-style toolbar — segmented pills (chip-mint) jako AradView.
 */
export default function ChartToolbar({
  contract,
  chartType,
  activeTransform = TOOLBAR_ACTIONS.RAW,
  onTransform,
  onExport,
  onToggleTable,
  showTable = false,
  onFullscreen,
  compact = false,
}) {
  const [exportOpen, setExportOpen] = useState(false);
  const exportRef = useRef(null);
  const chartTheme = useMemo(() => getChartTheme(null), []);
  const activeStyle = useMemo(() => getActiveControlStyle(chartTheme), [chartTheme]);

  const capabilities = useMemo(
    () => resolveToolbarCapabilities(contract, { chartType }),
    [contract, chartType]
  );

  return (
    <div className="chart-toolbar flex flex-wrap items-end justify-between gap-3" data-testid="chart-toolbar">
      {capabilities.transform.length > 0 && onTransform ? (
        <div className="flex-1 min-w-0">
          <div className={toolbarGroupLabelClass()}>Transformace</div>
          <div className="flex flex-wrap gap-1.5">
            {capabilities.transform.map((action) => {
              const active = activeTransform === action;
              return (
                <button
                  key={action}
                  type="button"
                  className={controlButtonClass(active, { compact })}
                  style={active ? activeStyle : undefined}
                  onClick={() => onTransform(action)}
                >
                  {TRANSFORM_LABELS[action] || action}
                </button>
              );
            })}
          </div>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-1.5 shrink-0">
        {isToolbarActionAllowed(capabilities, TOOLBAR_ACTIONS.DATA_TABLE) && onToggleTable ? (
          <button
            type="button"
            className={controlButtonClass(showTable, { compact: true })}
            style={showTable ? activeStyle : undefined}
            onClick={onToggleTable}
            title="Tabulka dat"
          >
            <Table2 className="w-3.5 h-3.5 inline mr-1" />
            Tabulka
          </button>
        ) : null}
        {isToolbarActionAllowed(capabilities, TOOLBAR_ACTIONS.FULLSCREEN) && onFullscreen ? (
          <button
            type="button"
            className={exportButtonClass()}
            onClick={onFullscreen}
            title="Celá obrazovka"
          >
            <Maximize2 className="w-3.5 h-3.5" />
          </button>
        ) : null}

        <div className="relative" ref={exportRef}>
          <button
            type="button"
            className={exportButtonClass()}
            onClick={() => setExportOpen((v) => !v)}
            title="Export"
          >
            <Download className="w-3.5 h-3.5" />
            Export
          </button>
          {exportOpen ? (
            <div className="absolute right-0 mt-1 z-30 min-w-[180px] bg-white border border-border/60 rounded-lg shadow-lg overflow-hidden">
              {[
                { id: "csv", label: "CSV (long)", icon: FileSpreadsheet },
                { id: "csv_wide", label: "CSV (wide)", icon: FileSpreadsheet },
                { id: "xlsx", label: "Excel (XLSX)", icon: FileSpreadsheet },
                { id: "clipboard", label: "Kopírovat do Excelu", icon: Copy },
                { id: "png", label: "Obrázek PNG", icon: FileImage },
              ].map(({ id, label, icon: Icon }) => (
                <button
                  key={id}
                  type="button"
                  className="w-full flex items-center gap-2 px-3 h-9 text-[12px] text-slate-700 hover:bg-slate-50 text-left"
                  onClick={() => {
                    onExport?.(id);
                    setExportOpen(false);
                  }}
                >
                  <Icon className="h-3.5 w-3.5 text-slate-500" />
                  {label}
                </button>
              ))}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

export { resolveToolbarCapabilities, TOOLBAR_ACTIONS, isToolbarActionAllowed };
