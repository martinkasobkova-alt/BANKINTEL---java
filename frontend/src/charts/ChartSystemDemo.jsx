import React, { useCallback, useMemo, useRef, useState } from "react";
import ChartRenderer from "./ChartRenderer";
import ChartContainer from "./ChartContainer";
import ChartToolbar, { TOOLBAR_ACTIONS } from "./ChartToolbar";
import ChartDataTable from "./ChartDataTable";
import { CHART_SIZE_VARIANTS } from "./chartTypes";
import { contractToLongRows, validateChartContract } from "./chartDataContract";
import {
  exportChartCsvLong,
  exportChartCsvWide,
  exportChartXlsxViaApi,
  copyChartDataToClipboard,
  exportChartPng,
  validateExportRows,
  buildChartExportSheets,
} from "./chartExport";
import {
  buildIndustrialProductionContract,
  buildRoeBarComparisonContract,
  buildRelationshipContract,
  buildAreaDemoContract,
  buildLatestInflationContract,
  transformDemoContract,
  computeRelationshipCorrelation,
  DEMO_STATUS,
} from "./chartSystemDemoData";
import { controlButtonClass, exportButtonClass } from "./chartDashboardStyle";
import api from "@/lib/api";
import { toast } from "sonner";

function DemoSection({ title, description, children }) {
  return (
    <section className="chart-system-demo-section">
      <div className="mb-3 px-1">
        <h2 className="text-sm font-bold text-[hsl(218_65%_28%)]">{title}</h2>
        {description ? <p className="text-[12px] text-slate-600 mt-1 leading-relaxed">{description}</p> : null}
      </div>
      {children}
    </section>
  );
}

function InteractiveDemoChart({
  baseContract,
  chartType = "line",
  size = CHART_SIZE_VARIANTS.ANALYTICAL,
  defaultTransform = TOOLBAR_ACTIONS.RAW,
  fixedTransform = null,
  showToolbar = true,
  showTableDefault = false,
}) {
  const exportRef = useRef(null);
  const [activeTransform, setActiveTransform] = useState(defaultTransform);
  const [showTable, setShowTable] = useState(showTableDefault);
  const [fullscreen, setFullscreen] = useState(false);

  const { contract } = useMemo(() => {
    if (fixedTransform) return transformDemoContract(baseContract, fixedTransform);
    if (activeTransform === TOOLBAR_ACTIONS.RAW) return { contract: baseContract, transformLabel: "Raw" };
    return transformDemoContract(baseContract, activeTransform);
  }, [baseContract, activeTransform, fixedTransform]);

  const handleExport = useCallback(
    async (kind) => {
      try {
        const node = exportRef.current?.querySelector("[data-chart-export-root]") || exportRef.current;
        if (kind === "csv") exportChartCsvLong(contract, contract.chart_id);
        else if (kind === "csv_wide") exportChartCsvWide(contract, contract.chart_id);
        else if (kind === "xlsx") await exportChartXlsxViaApi(contract, { api, filename: contract.chart_id });
        else if (kind === "clipboard") await copyChartDataToClipboard(contract);
        else if (kind === "png" && node) await exportChartPng(node, contract.chart_id);
        toast.success(`Export dokončen`);
      } catch (err) {
        toast.error(err.message || "Export selhal");
      }
    },
    [contract]
  );

  const chartBody = (
    <div ref={exportRef} className="min-h-[280px]">
      <ChartContainer
        size={size}
        title={contract.title}
        subtitle={contract.subtitle}
        exportRoot
        toolbar={
          showToolbar ? (
            <ChartToolbar
              contract={contract}
              chartType={chartType}
              activeTransform={fixedTransform || activeTransform}
              onTransform={fixedTransform ? undefined : setActiveTransform}
              onToggleTable={() => setShowTable((v) => !v)}
              showTable={showTable}
              onExport={handleExport}
              onFullscreen={() => setFullscreen(true)}
            />
          ) : null
        }
        table={showTable ? <ChartDataTable contract={contract} size={size} /> : null}
      >
        <ChartRenderer contract={contract} chartType={chartType} size={size} />
      </ChartContainer>
    </div>
  );

  if (fullscreen) {
    return (
      <div className="fixed inset-0 z-[200] bg-white/98 backdrop-blur p-4 flex flex-col">
        <button
          type="button"
          className={`self-end mb-3 ${exportButtonClass()}`}
          onClick={() => setFullscreen(false)}
        >
          Zavřít celou obrazovku
        </button>
        <div className="flex-1 min-h-0">{chartBody}</div>
      </div>
    );
  }

  return chartBody;
}

function DevDebugFooter({ contracts }) {
  return (
    <details className="widget-panel-white widget-infographic-light rounded-2xl overflow-hidden mt-8">
      <summary className="cursor-pointer px-5 py-3 text-[12px] font-medium text-slate-600 hover:bg-slate-50/80">
        Dev debug — technické informace (collapsed)
      </summary>
      <div className="px-5 pb-4 space-y-4 border-t border-border/40">
        {contracts.map(({ label, contract, chartType }) => {
          const validation = validateChartContract(contract);
          const exportOk = validateExportRows(contractToLongRows(contract));
          return (
            <div key={label} className="text-[11px] font-mono text-slate-600 space-y-1 pt-2">
              <div className="font-sans font-semibold text-slate-800 text-[12px]">{label}</div>
              <div>chart_type: {chartType || contract.chart_type}</div>
              <div>points: {contract.data?.length} · series: {contract.series?.length}</div>
              <div>value_raw: {validation.ok ? "OK" : validation.errors.join(", ")}</div>
              <div>export: {exportOk.ok ? "OK" : exportOk.errors.join(", ")}</div>
            </div>
          );
        })}
        <table className="w-full text-[11px] font-mono mt-2">
          <tbody>
            {Object.entries(DEMO_STATUS).map(([key, val]) => (
              <tr key={key} className="border-t border-border/30">
                <td className="py-1 pr-4 text-slate-600">{key}</td>
                <td className="py-1 text-slate-700">{val}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

export default function ChartSystemDemo() {
  const timeSeriesContract = useMemo(() => buildIndustrialProductionContract(), []);
  const barContract = useMemo(() => buildRoeBarComparisonContract(), []);
  const latestContract = useMemo(() => buildLatestInflationContract(), []);
  const areaContract = useMemo(() => buildAreaDemoContract(), []);
  const relationshipContract = useMemo(() => buildRelationshipContract(), []);
  const correlationValue = useMemo(() => computeRelationshipCorrelation(relationshipContract), [relationshipContract]);

  const exportPanelRef = useRef(null);
  const [exportPanelStatus, setExportPanelStatus] = useState("");

  const runExportTest = async (kind) => {
    try {
      if (kind === "csv_long") exportChartCsvLong(timeSeriesContract, "demo_export");
      else if (kind === "csv_wide") exportChartCsvWide(timeSeriesContract, "demo_export");
      else if (kind === "xlsx") await exportChartXlsxViaApi(timeSeriesContract, { api, filename: "demo_export" });
      else if (kind === "clipboard") await copyChartDataToClipboard(timeSeriesContract);
      else if (kind === "png" && exportPanelRef.current) await exportChartPng(exportPanelRef.current, "demo_export");
      setExportPanelStatus(`${kind} · ${Object.keys(buildChartExportSheets(timeSeriesContract)).join(", ")}`);
      toast.success("Export test OK");
    } catch (e) {
      setExportPanelStatus(`${kind} — chyba`);
      toast.error(e.message || "Export selhal");
    }
  };

  return (
    <div className="chart-system-demo-page">
      <header className="sticky top-0 z-50 border-b border-border/50 bg-white/90 backdrop-blur-md">
        <div className="max-w-5xl mx-auto px-5 py-4">
          <h1 className="text-xl font-extrabold text-[hsl(218_65%_28%)] tracking-tight">Chart System</h1>
          <p className="text-[13px] text-slate-600 mt-1">
            Vizuální reference dashboardu (AradView) · interní playground
          </p>
        </div>
      </header>

      <main className="max-w-5xl mx-auto px-5 py-8 space-y-10">
        <DemoSection
          title="1. Line chart — časová řada"
          description="CZ · DE · PL · 2020-Q1–2025-Q4 · toolbar · tabulka · export"
        >
          <InteractiveDemoChart
            baseContract={timeSeriesContract}
            chartType="line"
            size={CHART_SIZE_VARIANTS.ANALYTICAL}
          />
        </DemoSection>

        <DemoSection
          title="2. Bar chart — srovnání zemí"
          description="ROE bank · poslední období · baseline Y = 0 · barevné sloupce"
        >
          <InteractiveDemoChart baseContract={barContract} chartType="bar" showToolbar={false} size={CHART_SIZE_VARIANTS.STANDARD} />
        </DemoSection>

        <DemoSection
          title="3. LatestDataMode — inflace HICP"
          description="Stejný režim jako AradView: kategorie na ose X, tooltip Řada:, horizontální scroll >10 položek"
        >
          <InteractiveDemoChart baseContract={latestContract} chartType="bar" showToolbar={false} size={CHART_SIZE_VARIANTS.ANALYTICAL} />
        </DemoSection>

        <DemoSection title="4. Area chart — plošný graf" description="Jedna řada · gradient fill · dashboard styl">
          <InteractiveDemoChart baseContract={areaContract} chartType="area" showToolbar={false} size={CHART_SIZE_VARIANTS.STANDARD} />
        </DemoSection>

        <DemoSection title="5. Dual-axis chart" description="Úroková sazba (%) vs stavební produkce (index)">
          <InteractiveDemoChart baseContract={relationshipContract} chartType="line" showToolbar={false} size={CHART_SIZE_VARIANTS.STANDARD} />
        </DemoSection>

        <DemoSection title="6. Indexed chart — index 100" description="Srovnání dynamiky od báze 2020-Q1">
          <InteractiveDemoChart
            baseContract={timeSeriesContract}
            chartType="line"
            fixedTransform="index_100"
            showToolbar={false}
            size={CHART_SIZE_VARIANTS.STANDARD}
          />
        </DemoSection>

        <div className="grid md:grid-cols-2 gap-8">
          <DemoSection title="7. YoY chart" description="Meziroční změna (%)">
            <InteractiveDemoChart baseContract={timeSeriesContract} chartType="line" fixedTransform="yoy" showToolbar={false} size={CHART_SIZE_VARIANTS.STANDARD} />
          </DemoSection>
          <DemoSection title="8. Rolling average" description="Klouzavý průměr 4Q">
            <InteractiveDemoChart baseContract={timeSeriesContract} chartType="line" fixedTransform="rolling_average" showToolbar={false} size={CHART_SIZE_VARIANTS.STANDARD} />
          </DemoSection>
        </div>

        <div className="grid md:grid-cols-2 gap-8">
          <DemoSection title="9a. Spread" description="Rozdíl řad A − B">
            <InteractiveDemoChart baseContract={relationshipContract} chartType="line" fixedTransform="spread" showToolbar={false} size={CHART_SIZE_VARIANTS.STANDARD} />
          </DemoSection>
          <DemoSection title="9b. Ratio" description="Poměr řad A / B">
            <InteractiveDemoChart baseContract={relationshipContract} chartType="line" fixedTransform="ratio" showToolbar={false} size={CHART_SIZE_VARIANTS.STANDARD} />
          </DemoSection>
        </div>

        <DemoSection
          title="10. Data table + export"
          description="CSV · XLSX · Clipboard · PNG · tabulka pod grafem"
        >
          <InteractiveDemoChart
            baseContract={timeSeriesContract}
            chartType="line"
            showTableDefault
            size={CHART_SIZE_VARIANTS.STANDARD}
          />
        </DemoSection>

        <DemoSection title="Export panel (dev)" description="Samostatný test exportních akcí">
          <div className="flex flex-wrap gap-2 mb-4">
            {[
              ["csv_long", "CSV long"],
              ["csv_wide", "CSV wide"],
              ["xlsx", "Excel"],
              ["clipboard", "Kopírovat"],
              ["png", "PNG"],
            ].map(([key, label]) => (
              <button
                key={key}
                type="button"
                className={controlButtonClass(false)}
                onClick={() => runExportTest(key)}
              >
                {label}
              </button>
            ))}
          </div>
          <div ref={exportPanelRef} data-chart-export-root>
            <ChartContainer size={CHART_SIZE_VARIANTS.STANDARD} title="Export preview" exportRoot={false}>
              <ChartRenderer
                contract={{
                  ...timeSeriesContract,
                  series: [timeSeriesContract.series[0]],
                  data: timeSeriesContract.data.filter((d) => d.series_id === "CZ"),
                }}
                size={CHART_SIZE_VARIANTS.STANDARD}
              />
            </ChartContainer>
          </div>
          {exportPanelStatus ? <p className="mt-2 text-[11px] text-slate-500">{exportPanelStatus}</p> : null}
        </DemoSection>

        <DemoSection
          title="Korelace (analytika)"
          description={`Pearson r = ${correlationValue != null ? correlationValue.toFixed(3) : "—"} · scatter renderer P2`}
        >
          <div className="widget-panel-white widget-infographic-light rounded-2xl px-5 py-8 text-center text-[13px] text-slate-500">
            Scatter view bude doplněn v P2. Koeficient korelace je dostupný v analytické vrstvě.
          </div>
        </DemoSection>

        <DemoSection title="Velikosti grafu" description="compact · standard · analytical">
          <div className="grid md:grid-cols-3 gap-4">
            {[CHART_SIZE_VARIANTS.COMPACT, CHART_SIZE_VARIANTS.STANDARD, CHART_SIZE_VARIANTS.ANALYTICAL].map((sz) => (
              <ChartContainer
                key={sz}
                size={sz}
                title={sz}
                subtitle="CZ — průmyslová výroba"
              >
                <ChartRenderer
                  contract={{
                    ...timeSeriesContract,
                    series: [timeSeriesContract.series[0]],
                    data: timeSeriesContract.data.filter((d) => d.series_id === "CZ"),
                  }}
                  size={sz}
                />
              </ChartContainer>
            ))}
          </div>
        </DemoSection>

        <DevDebugFooter
          contracts={[
            { label: "Line time series", contract: timeSeriesContract, chartType: "line" },
            { label: "Bar ROE", contract: barContract, chartType: "bar" },
            { label: "Latest inflation", contract: latestContract, chartType: "bar" },
            { label: "Area CZ", contract: areaContract, chartType: "area" },
            { label: "Dual-axis", contract: relationshipContract, chartType: "line" },
          ]}
        />
      </main>
    </div>
  );
}
