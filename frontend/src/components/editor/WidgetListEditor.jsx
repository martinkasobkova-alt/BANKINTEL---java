import React, { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  Trash2,
  ArrowUp,
  ArrowDown,
  Eye,
  EyeOff,
  ChevronDown,
  ChevronRight,
  Maximize2,
  Minimize2,
  Database,
  BarChart3,
  Sparkles,
  RefreshCw,
  ListTree,
  Calculator,
  FileText,
} from "lucide-react";
import { toast } from "sonner";
import api, { formatApiError } from "@/lib/api";
import { WIDGET_TYPES, createEmptyWidget, isTextWidgetType } from "@/lib/widgetCatalog";

function defaultConfigForWidgetType(type) {
  if (type === "arad_view") return { view: "chart", chart_type: "line" };
  if (type === "rss_monitoring") return { ...(createEmptyWidget("rss_monitoring").config || {}) };
  return {};
}
import AradView from "@/components/widgets/AradView";
import WidgetRenderer from "@/components/widgets/WidgetRenderer";
import AdConfigEditor from "@/components/editor/AdConfigEditor";
import RssMonitoringConfigEditor from "@/components/editor/RssMonitoringConfigEditor";
import { DataLoadRowTight } from "@/components/ui/DataLoadIndicator";
import { LoadingInline, LoadingSpinner } from "@/components/ui/loading";
import LocalizedTextFields from "@/components/cms/LocalizedTextFields";
import { useTranslation } from "react-i18next";
import { stripAiCaptionNoise } from "@/lib/widgetCaption";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import { MAX_ARAD_CHART_COMPARE } from "@/components/widgets/AradChartCompareModal";
import AradCompareIndicatorPanel from "@/components/arad/AradCompareIndicatorPanel";

/**
 * Shared, reusable widget-list editor used by:
 *   - HomepageEditorPage (homepage widgets)
 *   - SectionEditorPage  (per-section widgets)
 *
 * Props:
 *   - widgets:   current list
 *   - onChange:  (nextWidgets) => void
 *   - readonly?: optional; hides add/remove controls
 */

const WIDTHS = [
  { value: "eighth", label: "1/8" },
  { value: "sixth", label: "1/6" },
  { value: "quarter", label: "1/4" },
  { value: "third", label: "1/3" },
  { value: "half", label: "1/2" },
  { value: "two-thirds", label: "2/3" },
  { value: "three-quarters", label: "3/4" },
  { value: "full", label: "Plná šířka" },
];

const BAR_ORIENTATIONS = [
  { value: "vertical", label: "Svislé sloupce" },
  { value: "horizontal", label: "Vodorovné pruhy" },
];

const PIE_VARIANTS = [
  { value: "donut", label: "Kolečko" },
  { value: "full", label: "Plný koláč" },
];

/** Stejné klíče jako `clear_widget_layout_pins` v backendu — jinak DnD mřížka přebije šířku i po uložení ze Správy dat. */
const LAYOUT_PIN_KEYS = [
  "grid_column_start",
  "grid_column_end",
  "grid_row_start",
  "grid_row_end",
  "colSpan",
  "rowSpan",
  "col_span",
  "row_span",
];

function stripLayoutPinsFromConfig(config) {
  const c = { ...(config || {}) };
  for (const k of LAYOUT_PIN_KEYS) delete c[k];
  return c;
}

const COLLAPSE_STORAGE_KEY = "widget-editor.expanded";

function loadExpandedSet() {
  try {
    const raw = sessionStorage.getItem(COLLAPSE_STORAGE_KEY);
    if (!raw) return new Set();
    return new Set(JSON.parse(raw));
  } catch {
    return new Set();
  }
}
function persistExpandedSet(set) {
  try {
    sessionStorage.setItem(COLLAPSE_STORAGE_KEY, JSON.stringify([...set]));
  } catch {}
}

/** Automatický výběr os X/Y pro graf (DATE + AMOUNT u World Bank, období + hodnota u ČSÚ, …). */
function guessChartXYFields(fieldList) {
  if (!fieldList?.length) return null;
  const byLower = Object.fromEntries(fieldList.map((f) => [f.toLowerCase(), f]));
  const xKeys = [
    "date",
    "year",
    "time_period",
    "time",
    "period",
    "x",
    "rok",
    "roky",
    "období",
    "obs_date",
    "iso_date",
  ];
  const yKeys = ["amount", "value", "obs_value", "y", "hodnota", "numeric"];
  let x = null;
  for (const k of xKeys) {
    if (byLower[k]) {
      x = byLower[k];
      break;
    }
  }
  if (!x) {
    x = fieldList.find((f) => /date|year|time|period|rok|období/i.test(f)) || null;
  }
  let y = null;
  for (const k of yKeys) {
    const f = byLower[k];
    if (f && f !== x) {
      y = f;
      break;
    }
  }
  if (!y) {
    y =
      fieldList.find((f) => f !== x && /amount|value|price|hodnota|gdp|index|rate|obs/i.test(f)) ||
      null;
  }
  if (!y && x) {
    y = fieldList.find((f) => f !== x) || null;
  }
  if (x && y) return { x, y };
  return null;
}

/** Shodně s backendem: čas na X, hodnota na Y — opraví prohozené AMOUNT/DATE v rozbalovačích. */
function chartAxisSemantic(name) {
  const n = (name || "").toLowerCase();
  if (/date|year|time|period|obdob|datum|freq|quarter|month|day|obs_date|iso_date/i.test(n)) {
    return "time";
  }
  if (/amount|value|gdp|index|rate|hodnot|price|obs_value|balance|quantity/i.test(n)) {
    return "value";
  }
  return "unknown";
}

function maybeSwapChartAxesByName(xField, yField) {
  const sx = chartAxisSemantic(xField);
  const sy = chartAxisSemantic(yField);
  if (sx === "value" && sy === "time") return { x: yField, y: xField };
  return { x: xField, y: yField };
}

export default function WidgetListEditor({ widgets, onChange, expandWidgetId }) {
  const [sources, setSources] = useState([]);
  const [computed, setComputed] = useState([]);
  // Set of widget IDs that are currently expanded. Default = all collapsed
  // so 10+ widgets stay manageable.
  const [expanded, setExpanded] = useState(() => loadExpandedSet());
  const expandedOnceRef = useRef(new Set());

  useEffect(() => {
    if (!expandWidgetId) return;
    if (expandedOnceRef.current.has(expandWidgetId)) return;
    if (!widgets.some((w) => w.id === expandWidgetId)) return;
    expandedOnceRef.current.add(expandWidgetId);
    setExpanded((prev) => new Set([...prev, expandWidgetId]));
  }, [expandWidgetId, widgets]);

  useEffect(() => {
    (async () => {
      try {
        const [{ data: s }, { data: cm }] = await Promise.all([
          api.get("/sources").catch(() => ({ data: [] })),
          api.get("/computed").catch(() => ({ data: [] })),
        ]);
        setSources(s || []);
        setComputed(cm || []);
      } catch {
        setSources([]);
        setComputed([]);
      }
    })();
  }, []);

  useEffect(() => {
    persistExpandedSet(expanded);
  }, [expanded]);

  const aradSources = useMemo(() => sources.filter((s) => s.source_type === "arad"), [sources]);
  const eurostatSources = useMemo(
    () => sources.filter((s) => s.source_type === "eurostat"),
    [sources]
  );
  const csuSources = useMemo(
    () => sources.filter((s) => s.source_type === "csu"),
    [sources]
  );
  const ecbSources = useMemo(
    () => sources.filter((s) => s.source_type === "ecb"),
    [sources]
  );
  const fredSources = useMemo(
    () => sources.filter((s) => s.source_type === "fred"),
    [sources]
  );
  const alphavantageSources = useMemo(
    () => sources.filter((s) => s.source_type === "alphavantage"),
    [sources]
  );
  const wbSources = useMemo(
    () => sources.filter((s) => s.source_type === "worldbank"),
    [sources]
  );
  const data360Sources = useMemo(
    () => sources.filter((s) => s.source_type === "world_bank_data360"),
    [sources]
  );
  const bisSources = useMemo(() => sources.filter((s) => s.source_type === "bis"), [sources]);
  const imfSources = useMemo(() => sources.filter((s) => s.source_type === "imf"), [sources]);
  const oecdSources = useMemo(() => sources.filter((s) => s.source_type === "oecd"), [sources]);
  // "Vlastní data" widget covers everything that's neither ARAD, Eurostat,
  // nor ČSÚ (uploaded files, generic Custom API). Each public catalog has
  // its own dedicated widget so the dropdown stays focused.
  const datasetSources = useMemo(
    () => sources.filter(
      (s) => s.source_type !== "arad"
        && s.source_type !== "eurostat"
        && s.source_type !== "csu"
        && s.source_type !== "ecb"
        && s.source_type !== "fred"
        && s.source_type !== "alphavantage"
        && s.source_type !== "worldbank"
        && s.source_type !== "world_bank_data360"
        && s.source_type !== "bis"
        && s.source_type !== "imf"
        && s.source_type !== "oecd"
    ),
    [sources]
  );

  const isOpen = (id) => expanded.has(id);
  const toggleOpen = (id) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const expandAll = () => setExpanded(new Set(widgets.map((w) => w.id)));
  const collapseAll = () => setExpanded(new Set());

  const setWidget = (idx, patch) =>
    onChange(widgets.map((w, i) => (i === idx ? { ...w, ...patch } : w)));
  const setWidgetConfig = (idx, patch) =>
    onChange(widgets.map((w, i) => (i === idx ? { ...w, config: { ...w.config, ...patch } } : w)));
  const removeWidget = (idx) => {
    const removed = widgets[idx];
    onChange(widgets.filter((_, i) => i !== idx));
    if (removed) {
      setExpanded((prev) => {
        const next = new Set(prev);
        next.delete(removed.id);
        return next;
      });
    }
  };
  const move = (idx, dir) => {
    const arr = [...widgets];
    const j = idx + dir;
    if (j < 0 || j >= arr.length) return;
    [arr[idx], arr[j]] = [arr[j], arr[idx]];
    onChange(arr);
  };

  const allOpen = widgets.length > 0 && widgets.every((w) => expanded.has(w.id));

  return (
    <>
      <div className="mb-4 flex items-center justify-between gap-2 flex-wrap rounded-2xl border border-slate-200/80 bg-white/90 px-4 py-3 shadow-sm ring-1 ring-slate-100">
        <div className="flex items-center gap-2">
          <span className="inline-flex h-8 w-1 rounded-full bg-[hsl(var(--primary))] opacity-80" aria-hidden />
          <div>
            <div className="text-sm font-semibold text-slate-800">Widgety na stránce</div>
            <div className="text-xs text-slate-500">{widgets.length} {widgets.length === 1 ? "blok" : "bloků"} — rozbalte a nastavte zdroje</div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {widgets.length > 1 && (
            <button
              type="button"
              onClick={allOpen ? collapseAll : expandAll}
              className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-slate-50/80 px-3 h-9 text-xs font-medium text-slate-700 shadow-sm transition hover:border-slate-300 hover:bg-white"
              data-testid="widget-toggle-all-btn"
              title={allOpen ? "Sbalit všechny widgety" : "Rozbalit všechny widgety"}
            >
              {allOpen ? <Minimize2 className="h-3.5 w-3.5" /> : <Maximize2 className="h-3.5 w-3.5" />}
              {allOpen ? "Sbalit vše" : "Rozbalit vše"}
            </button>
          )}
          {/* „Přidat widget" už je dostupný přes plovoucí „+" v pravém dolním rohu
              (AdminQuickAddWidget) — duplicitní tlačítko v hlavičce by mátlo. */}
        </div>
      </div>

      <div className="space-y-3">
        {widgets.map((w, idx) => {
          const open = isOpen(w.id);
          const typeLabel = WIDGET_TYPES.find((t) => t.value === w.type)?.label || w.type;
          const widthLabel = WIDTHS.find((t) => t.value === (w.width || "full"))?.label || "Plná šířka";
          const showAppearanceControls = w.type !== "ad";
          const showChartColor = !isTextWidgetType(w.type) && w.type !== "ad";
          const showCaption = !isTextWidgetType(w.type) && w.type !== "ad";
          return (
            <div
              key={w.id}
              className={`group overflow-hidden rounded-2xl border border-slate-200/90 bg-white shadow-sm transition-all duration-200 ${
                open ? "shadow-md ring-1 ring-slate-200/80" : "hover:border-slate-300 hover:shadow-md"
              }`}
              data-testid="widget-card"
            >
              {/* HEADER — always visible. Click anywhere on it to toggle. */}
              <div
                className={`flex cursor-pointer select-none items-center gap-2 px-3 py-2.5 transition-colors md:px-4 ${
                  open
                    ? "bg-gradient-to-r from-slate-50/95 to-white border-b border-slate-100"
                    : "hover:bg-slate-50/80"
                }`}
                onClick={() => toggleOpen(w.id)}
                data-testid="widget-header"
              >
                <button
                  type="button"
                  onClick={(e) => { e.stopPropagation(); toggleOpen(w.id); }}
                  className="text-slate-500 hover:text-slate-800 p-0.5"
                  aria-label={open ? "Sbalit widget" : "Rozbalit widget"}
                >
                  {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                </button>

                <span className="text-[9px] font-mono text-slate-400 w-4 shrink-0">#{idx + 1}</span>

                <div className="min-w-0 flex-1 flex items-center gap-1.5">
                  <div className="text-xs font-medium text-slate-800 truncate">
                    {w.title?.trim() || <span className="text-slate-400 italic">Bez titulku</span>}
                  </div>
                  <span className="max-w-[min(40vw,200px)] shrink-0 truncate rounded-lg bg-slate-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-slate-600">
                    {typeLabel.split("·")[0].trim()}
                  </span>
                  <span className="text-[9px] font-mono text-slate-400 shrink-0">{widthLabel}</span>
                </div>

                <div className="flex shrink-0 items-center gap-0.5 rounded-xl border border-transparent p-0.5 hover:border-slate-200 hover:bg-white/80" onClick={(e) => e.stopPropagation()}>
                  <button
                    type="button"
                    onClick={() => move(idx, -1)}
                    disabled={idx === 0}
                    className="rounded-lg p-1.5 text-slate-500 transition hover:bg-slate-100 hover:text-slate-900 disabled:opacity-25"
                    title="Nahoru"
                  >
                    <ArrowUp className="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    onClick={() => move(idx, +1)}
                    disabled={idx === widgets.length - 1}
                    className="rounded-lg p-1.5 text-slate-500 transition hover:bg-slate-100 hover:text-slate-900 disabled:opacity-25"
                    title="Dolů"
                  >
                    <ArrowDown className="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    onClick={() => removeWidget(idx)}
                    className="rounded-lg p-1.5 text-red-600 transition hover:bg-red-50 hover:text-red-800"
                    title="Odstranit widget"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>

              {/* BODY — only when expanded */}
              {open && (
                <div className="border-t border-slate-100 bg-gradient-to-b from-white to-slate-50/40 px-3 pb-4 pt-3 md:px-4">
                  <div className="grid grid-cols-1 gap-3 md:grid-cols-12 md:gap-4">
                    <div className="md:col-span-3">
                      <Field label="Typ widgetu">
                        <select
                          className="input"
                          value={w.type}
                          onChange={(e) => {
                            const t = e.target.value;
                            setWidget(idx, { type: t, config: defaultConfigForWidgetType(t) });
                          }}
                        >
                          {WIDGET_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                        </select>
                      </Field>
                    </div>

                    {w.type !== "ad" ? (
                      <div className="md:col-span-5">
                        <LocalizedTextFields
                          labelCs="Titulek (zobrazí se nad widgetem)"
                          valueCs={w.title || ""}
                          valueEn={w.title_en || ""}
                          onChangeCs={(v) => setWidget(idx, { title: v })}
                          onChangeEn={(v) => setWidget(idx, { title_en: v })}
                          placeholderCs="Volitelně"
                        />
                      </div>
                    ) : (
                      <div className="md:col-span-5">
                        <Field label="Titulek">
                          <div className="input flex items-center text-xs text-slate-500 italic bg-slate-50/60">
                            Inzerce nemá titulek — celý prostor je pro reklamu.
                          </div>
                        </Field>
                      </div>
                    )}

                    <div className="md:col-span-2">
                      <Field label="Šířka">
                        <select
                          className="input"
                          value={w.width || "full"}
                          onChange={(e) =>
                            setWidget(idx, {
                              width: e.target.value,
                              config: stripLayoutPinsFromConfig(w.config),
                            })
                          }
                        >
                          {WIDTHS.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                        </select>
                      </Field>
                    </div>

                    <div className="md:col-span-2">
                      <Field label="Výška (řádky)">
                        <select
                          className="input"
                          value={w.rowSpan != null ? String(w.rowSpan) : ""}
                          onChange={(e) => {
                            const v = e.target.value;
                            setWidget(idx, { rowSpan: v === "" ? null : Number(v) });
                          }}
                        >
                          <option value="">Výchozí</option>
                          <option value="1">1 řádek (~200 px)</option>
                          <option value="2">2 řádky (~400 px)</option>
                          <option value="3">3 řádky (~600 px)</option>
                          <option value="4">4 řádky (~800 px)</option>
                          <option value="5">5 řádků (~1 000 px)</option>
                        </select>
                      </Field>
                    </div>

                    <div className="md:col-span-12">
                      <WidgetConfig
                        w={w}
                        aradSources={aradSources}
                        datasetSources={datasetSources}
                        eurostatSources={eurostatSources}
                        csuSources={csuSources}
                        ecbSources={ecbSources}
                        fredSources={fredSources}
                        alphavantageSources={alphavantageSources}
                        wbSources={wbSources}
                        data360Sources={data360Sources}
                        bisSources={bisSources}
                        imfSources={imfSources}
                        oecdSources={oecdSources}
                        computed={computed}
                        setConfig={(p) => setWidgetConfig(idx, p)}
                      />
                    </div>
                    {showAppearanceControls ? (
                      <details className="md:col-span-12 rounded-xl border border-slate-200/80 bg-white/80 px-3 py-2">
                        <summary className="cursor-pointer text-xs font-semibold text-slate-700">
                          Vzhled a popisek
                        </summary>
                        <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-12">
                          <div className="md:col-span-5">
                            <Field label="Pozadí panelu (koláž)">
                              <select
                                className="input"
                                value={w.config?.panel_style || "default"}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  setWidgetConfig(idx, {
                                    panel_style: v,
                                    ...(v !== "custom" ? { panel_color: undefined } : {}),
                                  });
                                }}
                              >
                                <option value="default">Výchozí (karta jako dosud)</option>
                                <option value="white">Čistá bílá</option>
                                <option value="muted">Světle šedé</option>
                                <option value="slate">Šedomodré</option>
                                <option value="mint">Jemně mentolové</option>
                                <option value="cream">Teplý krémový</option>
                                <option value="none">Bez výplně (jen rámeček)</option>
                                <option value="custom">Vlastní barva (hex)</option>
                              </select>
                            </Field>
                          </div>
                          {w.config?.panel_style === "custom" && (
                            <div className="md:col-span-3">
                              <Field label="Barva (#RRGGBB)">
                                <input
                                  className="input"
                                  placeholder="#F1F5F9"
                                  value={w.config?.panel_color || ""}
                                  onChange={(e) => setWidgetConfig(idx, { panel_color: e.target.value })}
                                />
                              </Field>
                            </div>
                          )}
                          {showChartColor ? (
                            <div className="md:col-span-4">
                              <Field label="Barva grafu (primární)">
                                <div className="flex items-center gap-2">
                                  <input
                                    type="color"
                                    className="h-9 w-12 rounded border border-border/60 cursor-pointer"
                                    value={w.config?.chart_color || "#5FB8A4"}
                                    onChange={(e) =>
                                      setWidgetConfig(idx, { chart_color: e.target.value })
                                    }
                                  />
                                  <input
                                    className="input flex-1"
                                    placeholder="#5FB8A4 (nechat prázdné = výchozí)"
                                    value={w.config?.chart_color || ""}
                                    onChange={(e) => {
                                      const v = e.target.value.trim();
                                      if (!v || /^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$/.test(v))
                                        setWidgetConfig(idx, { chart_color: v || undefined });
                                    }}
                                  />
                                </div>
                              </Field>
                            </div>
                          ) : null}
                          {showCaption ? (
                            <div className="md:col-span-12">
                              <CaptionField w={w} cfg={w.config || {}} setConfig={(p) => setWidgetConfig(idx, p)} />
                            </div>
                          ) : null}
                        </div>
                      </details>
                    ) : null}
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>

    </>
  );
}

function WidgetConfig({
  w,
  aradSources,
  datasetSources,
  eurostatSources,
  csuSources,
  ecbSources,
  fredSources,
  alphavantageSources,
  wbSources,
  data360Sources,
  bisSources,
  imfSources,
  oecdSources,
  computed,
  setConfig,
}) {
  const cfg = w.config || {};
  if (w.type === "arad_view") {
    return <AradViewConfig cfg={cfg} sources={aradSources} setConfig={setConfig} />;
  }
  if (w.type === "computed_view") {
    return <ComputedViewConfig cfg={cfg} computed={computed} setConfig={setConfig} />;
  }
  if (w.type === "eurostat_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={eurostatSources}
          setConfig={setConfig}
          emptyHint='Zatím žádný Eurostat zdroj. Přidej ho v sekci „Datové zdroje" → tlačítko „Katalog Eurostat".'
          sourceLabel="Eurostat dataset"
          defaultViewChart
          widgetType="eurostat_view"
        />
      </div>
    );
  }
  if (w.type === "csu_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={csuSources}
          setConfig={setConfig}
          emptyHint='Zatím žádný ČSÚ zdroj. Přidej ho v sekci „Datové zdroje" → tlačítko „Katalog ČSÚ".'
          sourceLabel="ČSÚ výběr (DataStat)"
          defaultViewChart
          widgetType="csu_view"
        />
      </div>
    );
  }
  if (w.type === "ecb_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={ecbSources}
          setConfig={setConfig}
          emptyHint='Zatím žádný ECB zdroj. Přidej ho v sekci „Datové zdroje" → tlačítko „Katalog ECB".'
          sourceLabel="ECB časová řada (Data Portal)"
          defaultViewChart
          widgetType="ecb_view"
        />
      </div>
    );
  }
  if (w.type === "fred_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={fredSources}
          setConfig={setConfig}
          emptyHint='Zatím žádný FRED zdroj. Přidej ho v sekci „Datové zdroje" → tlačítko „Katalog FRED".'
          sourceLabel="FRED indikátor (St. Louis Fed)"
          defaultViewChart
          widgetType="fred_view"
        />
      </div>
    );
  }
  if (w.type === "alphavantage_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={alphavantageSources}
          setConfig={setConfig}
          emptyHint='Zatím žádný Alpha Vantage zdroj. Přidej ho v „Datové zdroje“ → Nový zdroj → typ „Alpha Vantage“ (symbol + volitelně TIME_SERIES_*). Na serveru musí být ALPHAVANTAGE_API_KEY.'
          sourceLabel="Alpha Vantage (symbol)"
          defaultViewChart
          widgetType="alphavantage_view"
        />
      </div>
    );
  }
  if (w.type === "worldbank_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={wbSources}
          setConfig={setConfig}
          emptyHint='Zatím žádný World Bank zdroj. Přidej ho v sekci „Datové zdroje" → tlačítko „Katalog World Bank".'
          sourceLabel="World Bank indikátor (WDI)"
          defaultViewChart
          widgetType="worldbank_view"
        />
      </div>
    );
  }
  if (w.type === "world_bank_data360_view") {
    return (
      <div className="space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-emerald-100/90 bg-emerald-50/70 px-3 py-2 text-xs text-emerald-950">
          <span className="leading-snug">
            Data360 používá vyhledávání (searchv2) v katalogu; uložený zdroj má stejná pole jako při přidání z katalogu{" "}
            (DATABASE_ID, INDICATOR, filtry).
          </span>
          <Link
            to="/data360/catalog"
            className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-emerald-200/80 bg-white/90 px-2.5 py-1 text-[11px] font-semibold text-emerald-900 shadow-sm hover:bg-emerald-50"
          >
            Vybrat z Data360 katalogu
          </Link>
        </div>
        <DatasetViewConfig
          cfg={cfg}
          sources={data360Sources}
          setConfig={setConfig}
          emptyHint='Zatím žádný World Bank zdroj. Přidej ho v „Datové zdroje“ → katalog World Bank, nebo zde zvolte existující zdroj.'
          sourceLabel="World Bank · indikátor"
          defaultViewChart
          widgetType="world_bank_data360_view"
        />
      </div>
    );
  }
  if (w.type === "bis_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={bisSources}
          setConfig={setConfig}
          emptyHint='Zatím žádný BIS zdroj. Přidej ho přes „Datové zdroje" → „Katalogy dat" → BIS.'
          sourceLabel="BIS řada (SDMX)"
          defaultViewChart
          widgetType="bis_view"
        />
      </div>
    );
  }
  if (w.type === "imf_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={imfSources}
          setConfig={setConfig}
          emptyHint='Zatím žádný IMF zdroj. Přidej ho přes „Katalogy dat" → IMF.'
          sourceLabel="IMF řada (CompactData)"
          defaultViewChart
          widgetType="imf_view"
        />
      </div>
    );
  }
  if (w.type === "oecd_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={oecdSources}
          setConfig={setConfig}
          emptyHint='Zatím žádný OECD zdroj. Přidej ho přes „Katalogy dat" → OECD.'
          sourceLabel="OECD řada (CSV)"
          defaultViewChart
          widgetType="oecd_view"
        />
      </div>
    );
  }
  if (w.type === "dataset_view") {
    return (
      <div className="space-y-2">
        <DatasetViewConfig
          cfg={cfg}
          sources={datasetSources}
          setConfig={setConfig}
          widgetType="dataset_view"
        />
      </div>
    );
  }
  if (isTextWidgetType(w.type)) {
    return <RichTextConfig cfg={cfg} setConfig={setConfig} />;
  }
  if (w.type === "rss_monitoring") {
    return <RssMonitoringConfigEditor cfg={cfg} setConfig={setConfig} />;
  }
  if (w.type === "ad") {
    return <AdWidgetConfig cfg={cfg} setConfig={setConfig} />;
  }
  return null;
}

function DatasetViewConfig({
  cfg,
  sources,
  setConfig,
  emptyHint = 'Zatím žádný vlastní zdroj. Přidej ho v sekci „Datové zdroje" (Excel / PDF / API).',
  sourceLabel = "Datový zdroj (vlastní soubor / dataset)",
  defaultViewChart = false,
  widgetType = "dataset_view",
}) {
  const [fields, setFields] = useState([]);
  const [loadingFields, setLoadingFields] = useState(false);
  const [sourcePreviewMeta, setSourcePreviewMeta] = useState(null);
  const [loadingPreviewMeta, setLoadingPreviewMeta] = useState(false);
  const sourceId = cfg.source_id || "";
  const view = cfg.view || (defaultViewChart ? "chart" : "table");
  const selectedSource = useMemo(() => sources.find((s) => s.id === sourceId) || null, [sources, sourceId]);
  const isCsuChart =
    view === "chart" &&
    (widgetType === "csu_view" || String(selectedSource?.source_type || "").toLowerCase() === "csu");
  const previewIndicators = Array.isArray(sourcePreviewMeta?.indicators) ? sourcePreviewMeta.indicators : [];
  const previewExtraDims = Array.isArray(sourcePreviewMeta?.extra_dimensions) ? sourcePreviewMeta.extra_dimensions : [];
  const previewGroupField = String(sourcePreviewMeta?.group_field || cfg.series_field || "").trim();
  const regionDim = previewExtraDims.find((d) => /kraj|region|uzemi.kraj/i.test(String(d.field || "").replace(/[^a-zA-Z]/g, "")));
  const defaultSplitDim = regionDim || previewExtraDims[0] || null;
  const chartSeriesDim = String(cfg.chart_series_dim || "").trim();
  const isIndicatorSeriesMode = Boolean(previewGroupField && chartSeriesDim === previewGroupField);
  const isDimensionSeriesMode = Boolean(chartSeriesDim && !isIndicatorSeriesMode);
  const dimensionFilters = cfg.dimension_filters && typeof cfg.dimension_filters === "object" ? cfg.dimension_filters : {};

  // Persist default chart mode so /homepage/preview always sends view:"chart" for ČSÚ/ECB/…
  // (otherwise cfg.view can be missing while the select shows „Graf“ from defaultViewChart).
  useEffect(() => {
    if (!defaultViewChart || cfg.view != null) return;
    setConfig({ view: "chart" });
  }, [defaultViewChart, cfg.view, setConfig]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!sourceId) {
        setFields([]);
        return;
      }
      const src = sources.find((s) => s.id === sourceId);
      if (!src) {
        setFields([]);
        return;
      }
      const dsName = src.dataset_name || src.name;
      setLoadingFields(true);
      try {
        const { data: list } = await api.get("/datasets");
        const ds =
          (list || []).find((d) => d.source_id === sourceId) ||
          (list || []).find((d) => d.name === dsName) ||
          (list || []).find((d) => d.name === src.name);
        if (!cancelled) setFields(ds?.fields || []);
      } catch {
        if (!cancelled) setFields([]);
      }
      if (!cancelled) setLoadingFields(false);
    })();
    return () => { cancelled = true; };
  }, [sourceId, sources]);

  useEffect(() => {
    let cancelled = false;
    if (!sourceId || !isCsuChart) {
      setSourcePreviewMeta(null);
      setLoadingPreviewMeta(false);
      return () => { cancelled = true; };
    }
    setLoadingPreviewMeta(true);
    api
      .get(`/sources/${sourceId}/preview`, { params: { limit: 200 } })
      .then(({ data }) => {
        if (!cancelled) setSourcePreviewMeta(data || null);
      })
      .catch(() => {
        if (!cancelled) setSourcePreviewMeta(null);
      })
      .finally(() => {
        if (!cancelled) setLoadingPreviewMeta(false);
      });
    return () => { cancelled = true; };
  }, [sourceId, isCsuChart]);

  useEffect(() => {
    if (view !== "chart" || !fields.length || loadingFields) return;
    const guess = guessChartXYFields(fields);
    if (!guess) return;
    const needX = !String(cfg.x_field || "").trim();
    const needY = !String(cfg.y_field || "").trim();
    if (!needX && !needY) return;
    const patch = {};
    if (needX) patch.x_field = guess.x;
    if (needY) patch.y_field = guess.y;
    if (Object.keys(patch).length === 0) return;
    setConfig(patch);
  }, [view, fields, loadingFields, sourceId, cfg.x_field, cfg.y_field, setConfig]);

  useEffect(() => {
    if (view !== "chart" || !fields.length || loadingFields) return;
    const xf = String(cfg.x_field || "").trim();
    const yf = String(cfg.y_field || "").trim();
    if (!xf || !yf) return;
    const { x, y } = maybeSwapChartAxesByName(xf, yf);
    if (x !== xf || y !== yf) setConfig({ x_field: x, y_field: y });
  }, [view, fields, loadingFields, cfg.x_field, cfg.y_field, setConfig]);

  useEffect(() => {
    if (!isCsuChart || !previewGroupField || !previewIndicators.length) return;
    if (String(cfg.series_field || "").trim() && String(cfg.series_value || "").trim()) return;
    setConfig({
      series_field: previewGroupField,
      series_value: String(previewIndicators[0].id || ""),
      agg: cfg.agg || "avg",
      limit: 0,
    });
  }, [isCsuChart, previewGroupField, previewIndicators, cfg.series_field, cfg.series_value, cfg.agg, setConfig]);

  const patchDimensionFilter = (field, value) => {
    const next = { ...dimensionFilters };
    if (value) next[field] = value;
    else delete next[field];
    setConfig({ dimension_filters: next });
  };

  return (
    <div className="space-y-3">
      <div className="rounded-2xl border border-sky-100 bg-gradient-to-br from-sky-50/60 via-white to-indigo-50/20 p-3 shadow-sm ring-1 ring-sky-100/50">
        <div className="grid grid-cols-1 gap-2 md:grid-cols-12">
          <div className="md:col-span-7">
            <Field label={sourceLabel}>
              <select
                className="input"
                value={sourceId}
                onChange={(e) => setConfig({
                  source_id: e.target.value,
                  x_field: "",
                  y_field: "",
                  series_field: "",
                  series_value: "",
                  chart_series_dim: "",
                  dimension_filters: {},
                })}
              >
                <option value="">— vyberte —</option>
                {sources.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name} {s.source_type ? `· ${s.source_type}` : ""}
                  </option>
                ))}
              </select>
              {sources.length === 0 && (
                <div className="mt-2 rounded-lg border border-amber-100 bg-amber-50/80 px-3 py-2 text-xs text-amber-950">
                  {emptyHint}
                </div>
              )}
            </Field>
          </div>
          <div className="md:col-span-3">
            <Field label="Zobrazení">
              <select className="input" value={view} onChange={(e) => setConfig({ view: e.target.value })}>
                <option value="table">Tabulka</option>
                <option value="chart">Graf</option>
              </select>
            </Field>
          </div>
          <div className="md:col-span-2">
            <Field label="Limit">
              <input
                className="input"
                type="number"
                min={0}
                max={5000}
                value={cfg.limit ?? 0}
                onChange={(e) => setConfig({ limit: Number(e.target.value) })}
                title="0 = vše"
              />
            </Field>
          </div>
        </div>
      </div>

      {view === "chart" && (
        <div className="rounded-2xl border border-violet-100 bg-gradient-to-br from-violet-50/40 via-white to-slate-50/30 p-3 shadow-sm ring-1 ring-violet-100/40">
          <div className="mb-2 flex items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-violet-500/15 text-violet-800">
                <BarChart3 className="h-3.5 w-3.5" strokeWidth={2.25} />
              </span>
              <div>
                <div className="text-sm font-semibold text-slate-800">Graf</div>
                <div className="text-[11px] text-slate-500">Základní volby jsou tady, technické nastavení je níže.</div>
              </div>
            </div>
            {loadingFields ? <LoadingInline label="Sloupce…" size="sm" muted /> : null}
          </div>

          {isCsuChart && sourceId ? (
            <div className="mb-3 rounded-xl border border-blue-200/70 bg-blue-50/70 p-2.5 text-xs text-slate-700">
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-blue-900/80">
                  ČSÚ dimenze
                </div>
                {loadingPreviewMeta ? <LoadingInline label="Načítám dimenze…" size="sm" muted /> : null}
              </div>

              <div className="grid grid-cols-1 gap-2 md:grid-cols-12">
                <div className="md:col-span-4">
                  <Field label="Ukazatel / řada">
                    <select
                      className="input"
                      value={String(cfg.series_value || "")}
                      onChange={(e) => setConfig({
                        series_field: previewGroupField,
                        series_value: e.target.value,
                        agg: cfg.agg || "avg",
                        limit: chartSeriesDim ? 0 : (cfg.limit ?? 0),
                      })}
                      disabled={!previewGroupField || previewIndicators.length === 0 || isIndicatorSeriesMode}
                    >
                      <option value="">
                        {isIndicatorSeriesMode ? "— všechny ukazatele —" : "— vyberte ukazatel —"}
                      </option>
                      {previewIndicators.map((ind) => (
                        <option key={ind.id} value={ind.id}>
                          {ind.name || ind.id}{ind.count ? ` (${ind.count})` : ""}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>

                <div className="md:col-span-5">
                  <Field label="Série">
                    <div className="mt-1 flex flex-wrap gap-1">
                      <button
                        type="button"
                        onClick={() => setConfig({
                          chart_series_dim: "",
                          series_field: previewGroupField || cfg.series_field || "",
                          series_value: cfg.series_value || String(previewIndicators[0]?.id || ""),
                          agg: cfg.agg || "avg",
                        })}
                        className={`flex-1 min-w-[7rem] h-8 rounded-md border px-2 text-xs font-mono transition-colors ${
                          !chartSeriesDim
                            ? "border-blue-700 bg-blue-600 text-white"
                            : "border-border/70 bg-white text-slate-700 hover:bg-slate-50"
                        }`}
                      >
                        Jeden
                      </button>
                      <button
                        type="button"
                        disabled={!previewGroupField}
                        onClick={() => {
                          const nextFilters = { ...dimensionFilters };
                          delete nextFilters[previewGroupField];
                          setConfig({
                            chart_series_dim: previewGroupField,
                            series_field: "",
                            series_value: "",
                            dimension_filters: nextFilters,
                            agg: "avg",
                            limit: 0,
                          });
                        }}
                        className={`flex-1 min-w-[8rem] h-8 rounded-md border px-2 text-xs font-mono transition-colors disabled:opacity-50 ${
                          isIndicatorSeriesMode
                            ? "border-blue-700 bg-blue-600 text-white"
                            : "border-border/70 bg-white text-slate-700 hover:bg-slate-50"
                        }`}
                      >
                        Všechny ukazatele
                      </button>
                      <button
                        type="button"
                        disabled={!defaultSplitDim?.field}
                        onClick={() => {
                          const nextDim = defaultSplitDim?.field || "";
                          const nextFilters = { ...dimensionFilters };
                          delete nextFilters[nextDim];
                          setConfig({
                            chart_series_dim: nextDim,
                            series_field: previewGroupField || cfg.series_field || "",
                            series_value: cfg.series_value || String(previewIndicators[0]?.id || ""),
                            dimension_filters: nextFilters,
                            agg: "avg",
                            limit: 0,
                          });
                        }}
                        className={`flex-1 min-w-[8rem] h-8 rounded-md border px-2 text-xs font-mono transition-colors disabled:opacity-50 ${
                          isDimensionSeriesMode
                            ? "border-blue-700 bg-blue-600 text-white"
                            : "border-border/70 bg-white text-slate-700 hover:bg-slate-50"
                        }`}
                      >
                        Podle dimenze
                      </button>
                    </div>
                  </Field>
                </div>

                <div className="md:col-span-3">
                  <Field label="Rozdělit podle">
                    <select
                      className="input"
                      value={chartSeriesDim}
                      onChange={(e) => {
                        const nextDim = e.target.value;
                        const nextFilters = { ...dimensionFilters };
                        if (nextDim) delete nextFilters[nextDim];
                        const splitByIndicators = nextDim && nextDim === previewGroupField;
                        setConfig({
                          chart_series_dim: nextDim,
                          series_field: splitByIndicators ? "" : (previewGroupField || cfg.series_field || ""),
                          series_value: splitByIndicators ? "" : (cfg.series_value || String(previewIndicators[0]?.id || "")),
                          dimension_filters: nextFilters,
                          agg: nextDim ? "avg" : (cfg.agg || "avg"),
                          limit: nextDim ? 0 : (cfg.limit ?? 0),
                        });
                      }}
                    >
                      <option value="">— bez rozdělení —</option>
                      {previewGroupField ? (
                        <option value={previewGroupField}>
                          {previewGroupField} / všechny ukazatele{previewIndicators.length ? ` (${previewIndicators.length})` : ""}
                        </option>
                      ) : null}
                      {previewExtraDims.map((dim) => (
                        <option key={dim.field} value={dim.field}>
                          {dim.field}{dim.values?.length ? ` (${dim.values.length})` : ""}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>
              </div>
            </div>
          ) : null}

          <div className="grid grid-cols-1 gap-2 md:grid-cols-12">
            <div className="md:col-span-3">
              <Field label="Typ grafu">
                <select
                  className="input"
                  value={cfg.chart_type || "line"}
                  onChange={(e) => setConfig({ chart_type: e.target.value })}
                >
                  <option value="line">Čára</option>
                  <option value="bar">Sloupce</option>
                  <option value="area">Plocha</option>
                  <option value="pie">Koláč</option>
                </select>
              </Field>
            </div>
          <ChartVariantFields cfg={cfg} setConfig={setConfig} />
            <div className="md:col-span-3">
              <Field label="Agregace">
                <select className="input" value={cfg.agg || "sum"} onChange={(e) => setConfig({ agg: e.target.value })}>
                  <option value="sum">Σ součet</option>
                  <option value="avg">⌀ průměr</option>
                  <option value="last">poslední</option>
                  <option value="max">max</option>
                  <option value="min">min</option>
                  <option value="count">počet</option>
                </select>
              </Field>
            </div>
            <div className="md:col-span-3">
              <Field label="Období od">
                <input
                  className="input"
                  value={cfg.date_from || ""}
                  onChange={(e) => setConfig({ date_from: e.target.value })}
                  placeholder="2010"
                />
              </Field>
            </div>
            <div className="md:col-span-3">
              <Field label="Období do">
                <input
                  className="input"
                  value={cfg.date_to || ""}
                  onChange={(e) => setConfig({ date_to: e.target.value })}
                  placeholder="2024"
                />
              </Field>
            </div>
          </div>

          <details className="mt-3 rounded-xl border border-slate-200/80 bg-white/80 px-3 py-2">
            <summary className="cursor-pointer text-xs font-semibold text-slate-700">Pokročilé osy</summary>
            <div className="mt-3 grid grid-cols-1 gap-2 md:grid-cols-2">
              <Field label="Sloupec X (osa, např. datum / období)">
                <select
                  className="input"
                  value={cfg.x_field || ""}
                  onChange={(e) => setConfig({ x_field: e.target.value })}
                  disabled={fields.length === 0}
                >
                  <option value="">— vyberte —</option>
                  {fields.map((f) => (<option key={f} value={f}>{f}</option>))}
                </select>
              </Field>
              <Field label="Sloupec Y (číselná hodnota)">
                <select
                  className="input"
                  value={cfg.y_field || ""}
                  onChange={(e) => setConfig({ y_field: e.target.value })}
                  disabled={fields.length === 0}
                >
                  <option value="">— vyberte —</option>
                  {fields.map((f) => (<option key={f} value={f}>{f}</option>))}
                </select>
              </Field>
            </div>
          </details>

          {isCsuChart && previewExtraDims.length > 0 ? (
            <details className="mt-2 rounded-xl border border-slate-200/80 bg-white/80 px-3 py-2">
              <summary className="cursor-pointer text-xs font-semibold text-slate-700">
                Filtry dimenzí
              </summary>
              <div className="mt-3 grid grid-cols-1 gap-2 md:grid-cols-2">
                {previewExtraDims
                  .filter((dim) => String(dim.field || "") !== chartSeriesDim)
                  .filter((dim) => String(dim.field || "") !== previewGroupField)
                  .map((dim) => (
                    <label key={dim.field} className="block text-[11px] text-slate-600">
                      {dim.field}
                      <select
                        className="mt-0.5 w-full rounded-lg border bg-white px-2 py-1.5 text-xs"
                        value={dimensionFilters[dim.field] || ""}
                        onChange={(e) => patchDimensionFilter(dim.field, e.target.value)}
                      >
                        <option value="">— všechny ({dim.values?.length || 0}) —</option>
                        {(dim.values || []).map((v) => (
                          <option key={v} value={v}>{v}</option>
                        ))}
                      </select>
                    </label>
                  ))}
              </div>
            </details>
          ) : null}
        </div>
      )}

      {view === "table" && sourceId && (
        <div className="rounded-xl border border-slate-100 bg-slate-50/50 px-3 py-2 text-xs text-slate-600">
          Tabulka zobrazí řádky z datasetu podle limitu výše.
        </div>
      )}

      {sourceId && fields.length === 0 && !loadingFields && (
        <div className="rounded-xl border border-amber-200/80 bg-amber-50 px-4 py-3 text-sm text-amber-950 shadow-sm">
          Zdroj zatím nemá synchronizovaná data. V „Datové zdroje“ spusťte u zdroje synchronizaci.
        </div>
      )}
      {sourceId && (
        <div className="mt-1 rounded-2xl border border-slate-200/80 bg-white p-3 shadow-inner">
          <PreviewToggle storageKey={`dataset-preview-${widgetType}-${sourceId}-${view}`} defaultOpen={view === "chart"}>
            <DatasetViewPreview cfg={cfg} widgetType={widgetType} />
          </PreviewToggle>
        </div>
      )}
    </div>
  );
}

function DatasetViewPreview({ cfg, widgetType }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");
  const dimensionFiltersKey = JSON.stringify(cfg.dimension_filters || {});
  const chartCompareKey = JSON.stringify(cfg.chart_compare_with || []);

  useEffect(() => {
    if (!cfg.source_id) return;
    let cancelled = false;
    setLoading(true);
    setErr("");
    api
      .post("/homepage/preview", { type: widgetType, config: cfg })
      .then(({ data }) => {
        if (!cancelled) setData(data);
      })
      .catch((e) => {
        if (!cancelled) setErr(formatApiError(e.response?.data?.detail) || e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [
    widgetType,
    cfg.source_id,
    cfg.view,
    cfg.x_field,
    cfg.y_field,
    cfg.agg,
    cfg.limit,
    cfg.chart_type,
    cfg.series_field,
    cfg.series_value,
    cfg.chart_series_dim,
    dimensionFiltersKey,
    chartCompareKey,
    cfg.date_from,
    cfg.date_to,
  ]);

  if (loading) return <DataLoadRowTight />;
  if (err) return <div className="text-xs text-rose-700 font-mono py-2">Chyba: {err}</div>;
  if (!data) return null;
  if (data.data?.error) {
    return <div className="text-xs text-rose-700 font-mono py-2">{data.data.error}</div>;
  }
  return (
    <div className="border border-border/60 rounded-md p-3 bg-white">
      <WidgetRenderer w={data} />
    </div>
  );
}

function CaptionField({ w, cfg, setConfig }) {
  const { t } = useTranslation();
  const { isAdmin } = useAuth();
  const widgetType = w?.type;
  const showAi = Boolean(
    widgetType && !isTextWidgetType(widgetType) && widgetType !== "ad" && widgetType !== "rss_monitoring"
  );
  const captionValue = stripAiCaptionNoise(cfg.caption || "");
  const captionEnValue = stripAiCaptionNoise(cfg.caption_en || "");
  const [aiLoading, setAiLoading] = useState(false);
  const [aiText, setAiText] = useState("");
  const [aiReason, setAiReason] = useState("");
  const [aiSummary, setAiSummary] = useState("");
  const [aiEnabled, setAiEnabled] = useState(null);
  const [reloading, setReloading] = useState(false);
  const [aiPrompt, setAiPrompt] = useState("");
  const [aiPromptOpen, setAiPromptOpen] = useState(false);

  const refreshStatus = useCallbackSafe(() => {
    if (!showAi || !isAdmin) return;
    api
      .get("/homepage/ai-commentary-status")
      .then(({ data }) => setAiEnabled(Boolean(data?.enabled)))
      .catch(() => setAiEnabled(null));
  }, [showAi, isAdmin]);

  useEffect(() => {
    refreshStatus();
  }, [refreshStatus]);

  useEffect(() => {
    if ((cfg.caption || "") !== captionValue) {
      setConfig({ caption: captionValue });
    }
  }, [cfg.caption, captionValue, setConfig]);

  const runAiPreview = () => {
    if (!widgetType) return;
    setAiLoading(true);
    setAiReason("");
    setAiText("");
    setAiSummary("");
    api
      .post("/homepage/ai-commentary", {
        type: widgetType,
        title: w?.title || "",
        width: w?.width || "full",
        config: cfg,
        prompt: aiPrompt.trim(),
      })
      .then(({ data }) => {
        setAiEnabled(Boolean(data?.enabled));
        const text = stripAiCaptionNoise((data && data.text) || "");
        setAiText(text);
        if (text) {
          setConfig({ caption: text });
          toast.success("AI popisek byl vložen do pole.");
        }
        setAiReason((data && data.reason) || "");
        setAiSummary((data && data.summary) || "");
      })
      .catch((e) => {
        setAiReason(formatApiError(e.response?.data?.detail) || e.message);
      })
      .finally(() => setAiLoading(false));
  };

  const reloadEnv = () => {
    setReloading(true);
    api
      .post("/homepage/ai-commentary-reload")
      .then(({ data }) => {
        setAiEnabled(Boolean(data?.enabled));
        toast.success(data?.enabled ? ".env načten, AI aktivní." : ".env načten, ale AI stále neaktivní.");
      })
      .catch((e) => toast.error(formatApiError(e.response?.data?.detail) || e.message))
      .finally(() => setReloading(false));
  };

  return (
    <div>
      <LocalizedTextFields
        labelCs={t("cms.captionCs")}
        labelEn={t("cms.captionEn")}
        valueCs={captionValue}
        valueEn={captionEnValue}
        onChangeCs={(v) => setConfig({ caption: v })}
        onChangeEn={(v) => setConfig({ caption_en: v })}
        multiline
        rows={2}
        placeholderCs="Např. „Čtvrtletní údaje za všechny banky, zdroj: ČNB ARAD"
      />
      {showAi && isAdmin && (
        <div className="mt-2 space-y-1.5">
          {aiEnabled === false && (
            <div className="flex flex-wrap items-center justify-between gap-2 text-[10px] text-amber-900 bg-amber-50 border border-amber-200/90 rounded-md px-2 py-1.5 leading-snug">
              <span>
                AI shrnutí je vypnuté: chybí <code className="font-mono">OPENAI_API_KEY</code> v{" "}
                <code className="font-mono">backend/.env</code> (nebo{" "}
                <code className="font-mono">OPENAI_COMMENTARY=0</code>).
              </span>
              <button
                type="button"
                onClick={reloadEnv}
                disabled={reloading}
                aria-busy={reloading ? "true" : undefined}
                className="inline-flex items-center gap-1 h-6 px-2 rounded-md border border-amber-300 bg-white hover:bg-amber-100/60 disabled:opacity-50"
              >
                {reloading ? <LoadingSpinner suppressAria size="xs" aria-label="" /> : <RefreshCw className="h-3 w-3" />}
                {reloading ? "Načítám…" : "Načíst .env"}
              </button>
            </div>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={runAiPreview}
              disabled={aiLoading}
              aria-busy={aiLoading ? "true" : undefined}
              className="inline-flex items-center gap-1.5 h-8 px-3 text-xs rounded-md border border-border/70 bg-white hover:bg-slate-50 disabled:opacity-50"
            >
              {aiLoading ? <LoadingSpinner suppressAria size="xs" aria-label="" /> : <Sparkles className="h-3.5 w-3.5 shrink-0" strokeWidth={1.5} />}
              {aiLoading ? "Generuji…" : "Vygenerovat AI popisek"}
            </button>
            <button
              type="button"
              onClick={() => setAiPromptOpen((v) => !v)}
              className={`inline-flex items-center gap-1 h-8 px-2.5 text-[11px] rounded-md border ${
                aiPromptOpen || aiPrompt
                  ? "border-[hsl(var(--primary))] bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]"
                  : "border-border/60 bg-white text-slate-600 hover:bg-slate-50"
              }`}
              title="Vlastní instrukce pro AI – např. zaměř se na meziroční změnu."
            >
              Vlastní prompt{aiPrompt ? " ●" : ""}
            </button>
          </div>
          {aiPromptOpen && (
            <div className="rounded-md border border-border/60 bg-slate-50/70 p-2">
              <textarea
                value={aiPrompt}
                onChange={(e) => setAiPrompt(e.target.value)}
                className="w-full min-h-[60px] text-xs border border-border/60 rounded-md px-2 py-2 bg-white resize-y"
                placeholder={
                  'Vlastní instrukce pro AI – např. „Zaměř se na meziroční změnu", „Vysvětli v kontextu inflace", „Přidej krátké srovnání s eurozónou"…'
                }
              />
              <div className="mt-1 flex items-center justify-between gap-2">
                <p className="text-[10px] leading-snug text-slate-500">
                  AI dál drží fakta z dat (čísla nepřepočítává), ale přizpůsobí zaměření a tón. Max ~600 znaků.
                </p>
                {aiPrompt && (
                  <button
                    type="button"
                    onClick={() => setAiPrompt("")}
                    className="text-[10px] uppercase tracking-wider text-slate-500 hover:text-slate-800"
                  >
                    Vyčistit
                  </button>
                )}
              </div>
            </div>
          )}
          {aiReason && !aiText ? (
            <p className="text-[11px] text-rose-600 leading-snug whitespace-pre-wrap">{aiReason}</p>
          ) : null}
          {aiSummary ? (
            <details className="text-[10px] text-slate-500">
              <summary className="cursor-pointer select-none">Podklad pro AI (souhrn dat)</summary>
              <p className="mt-1 whitespace-pre-wrap font-mono leading-snug">{aiSummary}</p>
            </details>
          ) : null}
          <p className="text-[10px] text-slate-500 leading-snug">
            Veřejná stránka ukazuje stejné shrnutí automaticky pod grafem (dokud je na serveru klíč). Ruční popisek výše můžete mít i vedle toho.
          </p>
        </div>
      )}
    </div>
  );
}

function useCallbackSafe(fn, deps) {
  return React.useCallback(fn, deps); // eslint-disable-line react-hooks/exhaustive-deps
}

function RichTextConfig({ cfg, setConfig }) {
  const { t } = useTranslation();
  const taRef = useRef(null);
  const imageInputRef = useRef(null);
  const medallionInputRef = useRef(null);
  const [uploadingMedia, setUploadingMedia] = useState(null);
  const wrap = (before, after = before) => {
    const ta = taRef.current;
    if (!ta) return;
    const start = ta.selectionStart;
    const end = ta.selectionEnd;
    const value = ta.value;
    const sel = value.slice(start, end) || "text";
    const next = value.slice(0, start) + before + sel + after + value.slice(end);
    setConfig({ content: next });
    requestAnimationFrame(() => {
      ta.focus();
      ta.setSelectionRange(start + before.length, start + before.length + sel.length);
    });
  };
  const insert = (snippet) => {
    const ta = taRef.current;
    const value = ta?.value ?? cfg.content ?? "";
    const start = ta ? ta.selectionStart : value.length;
    const next = value.slice(0, start) + snippet + value.slice(start);
    setConfig({ content: next });
    requestAnimationFrame(() => {
      if (!taRef.current) return;
      taRef.current.focus();
      taRef.current.setSelectionRange(start + snippet.length, start + snippet.length);
    });
  };
  const uploadMedia = async (file, kind) => {
    if (!file) return;
    const formData = new FormData();
    formData.append("file", file);
    setUploadingMedia(kind);
    try {
      const { data } = await api.post("/media/upload", formData, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      if (!data?.url) throw new Error("Cloudinary nevrátil URL obrázku.");
      const alt = kind === "medallion" ? "medailon" : file.name?.replace(/\.[^.]+$/, "") || "Obrázek";
      insert(`\n\n![${alt}](${data.url})\n\n`);
      toast.success("Obrázek byl nahrán.");
    } catch (e) {
      toast.error(formatApiError(e.response?.data?.detail) || e.message || "Obrázek se nepodařilo nahrát.");
    } finally {
      setUploadingMedia(null);
    }
  };
  const insertImage = () => {
    imageInputRef.current?.click();
  };
  /** Kulatý medailonek (hlava komentátora) — alt „medailon“; v prvním odstavci samostatně = velký nahoře. */
  const insertMedallion = () => {
    medallionInputRef.current?.click();
  };
  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-rose-100 bg-gradient-to-br from-rose-50/50 via-white to-pink-50/20 p-4 shadow-sm ring-1 ring-rose-100/40 md:p-5">
        <div className="mb-4 flex items-center gap-2 border-b border-rose-100/80 pb-3">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-rose-500/15 text-rose-800">
            <FileText className="h-4 w-4" strokeWidth={2.25} />
          </span>
          <div>
            <div className="text-sm font-semibold text-slate-800">Komentář / text</div>
            <div className="text-xs text-slate-600">Markdown s podporou tučného textu, obrázků a medailonku</div>
          </div>
        </div>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-12 md:gap-4">
      <div className="md:col-span-6">
        <LocalizedTextFields
          labelCs={t("cms.headingCs")}
          labelEn={t("cms.headingEn")}
          valueCs={cfg.heading || ""}
          valueEn={cfg.heading_en || ""}
          onChangeCs={(v) => setConfig({ heading: v })}
          onChangeEn={(v) => setConfig({ heading_en: v })}
          placeholderCs="Např. O sekci"
        />
      </div>
      <div className="md:col-span-6">
        <LocalizedTextFields
          labelCs={t("cms.subheadingCs")}
          labelEn={t("cms.subheadingEn")}
          valueCs={cfg.subheading || ""}
          valueEn={cfg.subheading_en || ""}
          onChangeCs={(v) => setConfig({ subheading: v })}
          onChangeEn={(v) => setConfig({ subheading_en: v })}
          placeholderCs="Krátký dodatek pod titulek"
        />
      </div>
      <div className="md:col-span-12">
        <label className="text-[9px] uppercase tracking-[0.06em] text-slate-500 font-medium">Obsah (text / obrázky)</label>
        <div className="mt-1 flex items-center gap-1 flex-wrap p-1 border border-border border-b-0 rounded-t-sm bg-slate-50/60">
          <button type="button" onClick={() => wrap("**")} title="Tučně (Ctrl+B)" className="h-6 w-7 text-[11px] font-bold border border-border rounded-sm bg-white hover:bg-slate-100">B</button>
          <button type="button" onClick={() => wrap("*")} title="Kurzíva (Ctrl+I)" className="h-6 w-7 text-[11px] italic border border-border rounded-sm bg-white hover:bg-slate-100">I</button>
          <span className="mx-1 h-5 w-px bg-border" />
          <button type="button" onClick={insertImage} disabled={!!uploadingMedia} className="h-6 px-1.5 text-[10px] border border-border rounded-sm bg-white hover:bg-slate-100 disabled:opacity-60">
            {uploadingMedia === "image" ? "Nahrávám…" : "🖼 Obrázek"}
          </button>
          <button
            type="button"
            onClick={insertMedallion}
            disabled={!!uploadingMedia}
            title="Kulatá fotka komentátora (nahoře jako první odstavec, nebo malá v textu)"
            className="h-6 px-1.5 text-[10px] border border-border rounded-sm bg-white hover:bg-slate-100 disabled:opacity-60"
          >
            {uploadingMedia === "medallion" ? "Nahrávám…" : "◯ Medailonek"}
          </button>
          <input
            ref={imageInputRef}
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml"
            className="hidden"
            onChange={(e) => {
              uploadMedia(e.target.files?.[0], "image");
              e.target.value = "";
            }}
          />
          <input
            ref={medallionInputRef}
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml"
            className="hidden"
            onChange={(e) => {
              uploadMedia(e.target.files?.[0], "medallion");
              e.target.value = "";
            }}
          />
          <button type="button" onClick={() => insert("\n\n")} title="Nový odstavec" className="h-6 px-1.5 text-[10px] border border-border rounded-sm bg-white hover:bg-slate-100">↵ Odstavec</button>
          <span className="ml-auto text-[10px] text-slate-500 font-mono">Zarovnání doleva</span>
        </div>
        <textarea
          ref={taRef}
          className="input"
          style={{ height: "auto", minHeight: 160, borderTop: 0, borderTopLeftRadius: 0, borderTopRightRadius: 0 }}
          value={cfg.content || ""}
          onChange={(e) => setConfig({ content: e.target.value })}
          onKeyDown={(e) => {
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "b") {
              e.preventDefault();
              wrap("**");
            } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "i") {
              e.preventDefault();
              wrap("*");
            }
          }}
          placeholder={"Zde napište komentář…\n\n**Tučně** i *kurzívou*.\n\n![Popis](https://example.com/obrazek.png)\n\nNebo medailonek: ![medailon](https://example.com/tvár.png) jako první řádek = velký kruh nahoře."}
        />
        <div className="text-[10px] text-slate-400 font-mono mt-1">
          Podporováno: **tučně**, *kurzíva*, ![alt](url), prázdný řádek = nový odstavec. Medailonek: tlačítko ◯ nebo{" "}
          <span className="whitespace-nowrap">![medailon](url)</span> — první odstavec jen tento řádek = velký kruh nahoře; jinde v textu = malý kulatý výřez.
        </div>
        <div className="mt-3">
          <label className="text-[9px] uppercase tracking-[0.06em] text-slate-500 font-medium">{t("cms.contentEn")}</label>
          <textarea
            className="input mt-1"
            style={{ minHeight: 120 }}
            value={cfg.content_en || ""}
            onChange={(e) => setConfig({ content_en: e.target.value })}
            placeholder={cfg.content || ""}
          />
        </div>
      </div>
        </div>
      </div>
    </div>
  );
}

function AdWidgetConfig({ cfg, setConfig }) {
  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-violet-100 bg-gradient-to-br from-violet-50/50 via-white to-purple-50/20 p-4 shadow-sm ring-1 ring-violet-100/40 md:p-5">
        <div className="mb-4 flex items-center gap-2 border-b border-violet-100/80 pb-3">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-violet-500/15 text-violet-800">
            <FileText className="h-4 w-4" strokeWidth={2.25} />
          </span>
          <div>
            <div className="text-sm font-semibold text-slate-800">Inzerce / reklama</div>
            <div className="text-xs text-slate-600">
              Reklamní prostor bez titulku — obrázek, text nebo HTML snippet (např. AdSense).
            </div>
          </div>
        </div>
        <AdConfigEditor cfg={cfg} onPatch={(p) => setConfig(p)} />
      </div>
    </div>
  );
}

function ComputedViewConfig({ cfg, computed, setConfig }) {
  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-amber-100 bg-gradient-to-br from-amber-50/60 via-white to-orange-50/20 p-4 shadow-sm ring-1 ring-amber-100/50 md:p-5">
        <div className="mb-4 flex items-center gap-2 border-b border-amber-100/80 pb-3">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-amber-500/15 text-amber-800">
            <Calculator className="h-4 w-4" strokeWidth={2.25} />
          </span>
          <div>
            <div className="text-sm font-semibold text-slate-800">Vlastní výpočet</div>
            <div className="text-xs text-slate-600">Vyberte připravený vzorec ze sekce „Vlastní výpočty"</div>
          </div>
        </div>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-12 md:gap-4">
          <div className="md:col-span-6">
            <Field label="Vlastní výpočet">
              <select className="input" value={cfg.computed_id || ""} onChange={(e) => setConfig({ computed_id: e.target.value })}>
                <option value="">— vyberte —</option>
                {computed.map((c) => (
                  <option key={c.id} value={c.id}>{c.name} ({c.operation})</option>
                ))}
              </select>
              {computed.length === 0 && (
                <div className="mt-2 rounded-lg border border-amber-200/80 bg-amber-50 px-3 py-2 text-xs text-amber-950">
                  Zatím žádný výpočet. Vytvořte ho v sekci „Vlastní výpočty".
                </div>
              )}
            </Field>
          </div>
          <div className="md:col-span-2">
            <Field label="Zobrazení">
              <select className="input" value={cfg.view || "chart"} onChange={(e) => setConfig({ view: e.target.value })}>
                <option value="chart">Graf</option>
                <option value="table">Tabulka</option>
              </select>
            </Field>
          </div>
          <div className="md:col-span-2">
            <Field label="Typ grafu (výchozí)">
              <select className="input" value={cfg.chart_type || "line"} onChange={(e) => setConfig({ chart_type: e.target.value })}>
                <option value="line">Čára</option>
                <option value="bar">Sloupec</option>
                <option value="area">Plocha</option>
                <option value="pie">Koláč</option>
              </select>
            </Field>
          </div>
          {(cfg.view || "table") === "chart" ? (
            <div className="md:col-span-2">
              <Field label="První karta v grafu">
                <select
                  className="input"
                  value={String(cfg.default_data_view || "chart").toLowerCase() === "table" ? "table" : "chart"}
                  onChange={(e) => setConfig({ default_data_view: e.target.value })}
                >
                  <option value="chart">Graf</option>
                  <option value="table">Tabulka</option>
                </select>
              </Field>
            </div>
          ) : null}
          <ChartVariantFields cfg={cfg} setConfig={setConfig} />
          <div className="md:col-span-2">
            <Field label="Limit (0 = vše)">
              <input className="input" type="number" min={0} max={5000} value={cfg.limit ?? 0} onChange={(e) => setConfig({ limit: Number(e.target.value) })} />
            </Field>
          </div>
        </div>
      </div>

      {cfg.computed_id && (
        <div className="mt-1 rounded-2xl border border-slate-200/80 bg-white p-3 shadow-inner">
          <PreviewToggle storageKey={`computed-preview-${cfg.computed_id}`}>
            <ComputedViewPreview cfg={cfg} />
          </PreviewToggle>
        </div>
      )}
    </div>
  );
}

function ComputedViewPreview({ cfg }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  useEffect(() => {
    if (!cfg.computed_id) return;
    let cancelled = false;
    setLoading(true);
    setErr("");
    api
      .post("/homepage/preview", { type: "computed_view", config: cfg })
      .then(({ data }) => {
        if (!cancelled) setData(data);
      })
      .catch((e) => {
        if (!cancelled) setErr(formatApiError(e.response?.data?.detail) || e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [cfg.computed_id, cfg.view, cfg.chart_type, cfg.default_data_view, cfg.limit, cfg.caption]);

  if (loading) return <DataLoadRowTight />;
  if (err) return <div className="text-xs text-rose-700 font-mono py-2">Chyba: {err}</div>;
  if (!data) return null;
  if (data.data?.error) {
    return <div className="text-xs text-rose-700 font-mono py-2">{data.data.error}</div>;
  }
  return (
    <div className="border border-border/60 rounded-md p-3 bg-white">
      <WidgetRenderer w={data} />
    </div>
  );
}

/** Doplňkové ARAD řady do jednoho grafu (`chart_compare_with` → backend `multi_series`). */
function AradCompareWithConfig({ cfg, sources, setConfig }) {
  const feCompare = useFeatureAccess("composite_charts");
  const [mergedAradIndicators, setMergedAradIndicators] = useState([]);
  const [mergedLoading, setMergedLoading] = useState(false);
  const list = Array.isArray(cfg.chart_compare_with) ? cfg.chart_compare_with : [];

  useEffect(() => {
    if (!sources?.length) {
      setMergedAradIndicators([]);
      setMergedLoading(false);
      return;
    }
    let cancelled = false;
    (async () => {
      setMergedLoading(true);
      try {
        const packs = await Promise.all(
          sources.map((s) =>
            api
              .get(`/sources/${s.id}/arad/indicators`)
              .then((r) => ({
                id: s.id,
                name: (s.name || s.id || "").trim(),
                rows: Array.isArray(r.data) ? r.data : [],
              }))
              .catch(() => ({
                id: s.id,
                name: (s.name || s.id || "").trim(),
                rows: [],
              }))
          )
        );
        if (cancelled) return;
        const out = [];
        const seen = new Set();
        for (const { id: sid, name: sname, rows: packRows } of packs) {
          for (const row of packRows) {
            const iid = String(row.indicator_id || "").trim();
            if (!iid) continue;
            const k = `${sid}\0${iid}`;
            if (seen.has(k)) continue;
            seen.add(k);
            out.push({
              ...row,
              source_id: sid,
              indicator_id: iid,
              _aradSourceLabel: sname,
            });
          }
        }
        out.sort((a, b) => {
          const na = (a.name || "").toLocaleLowerCase("cs");
          const nb = (b.name || "").toLocaleLowerCase("cs");
          if (na !== nb) return na.localeCompare(nb, "cs");
          return String(a.indicator_id || "").localeCompare(String(b.indicator_id || ""), "cs");
        });
        setMergedAradIndicators(out);
      } finally {
        if (!cancelled) setMergedLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [sources]);

  const updateAt = (idx, patch) => {
    const next = list.map((row, j) => (j === idx ? { ...row, ...patch } : row));
    setConfig({ chart_compare_with: next });
  };
  const addRow = () => {
    if (list.length >= MAX_ARAD_CHART_COMPARE) return;
    const defaultSid = String(cfg.source_id || "").trim();
    setConfig({
      chart_compare_with: [
        ...list,
        { source_id: defaultSid, indicator_id: "", chart_type: "line", y_axis: "left" },
      ],
    });
  };
  const removeAt = (idx) => {
    const next = list.filter((_, j) => j !== idx);
    setConfig({ chart_compare_with: next });
  };

  if (!cfg.source_id || !cfg.indicator_id) return null;

  return (
    <div className="rounded-2xl border border-violet-100 bg-gradient-to-br from-violet-50/40 via-white to-slate-50/30 p-4 shadow-sm ring-1 ring-violet-100/50 md:p-5">
      <div className="mb-3 flex items-center gap-2 border-b border-violet-100/80 pb-3">
        <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-violet-500/15 text-violet-800">
          <BarChart3 className="h-4 w-4" strokeWidth={2.25} />
        </span>
        <div>
          <div className="text-sm font-semibold text-slate-800">Další ukazatele (tabulka i graf)</div>
          <div className="text-xs text-slate-600">
            Až {MAX_ARAD_CHART_COMPARE} dalších řad se stejnou časovou osou — v tabulce jako další sloupce, v grafu jako
            další řady (sloučení podle období z databáze). Výběr můžete zúžit zdrojem v řádku; prázdný zdroj = stejný
            ARAD zdroj jako hlavní řada.
          </div>
        </div>
      </div>
      {feCompare.ready && !feCompare.allowed ? (
        <p className="text-[11px] text-amber-800 bg-amber-50/90 border border-amber-200/80 rounded-lg px-2.5 py-2 mb-3">
          {feCompare.message || "Kombinace více řad je dostupná s předplatným."}
        </p>
      ) : null}
      <div className="space-y-3">
        {list.map((row, idx) => (
          <AradCompareRow
            key={`cmp-${idx}-${row.source_id || "all"}-${row.indicator_id || "x"}`}
            row={row}
            sources={sources}
            mergedIndicators={mergedAradIndicators}
            mergedLoading={mergedLoading}
            onChange={(patch) => updateAt(idx, patch)}
            onRemove={() => removeAt(idx)}
          />
        ))}
      </div>
      {list.length < MAX_ARAD_CHART_COMPARE ? (
        <button
          type="button"
          onClick={addRow}
          className="mt-3 h-9 w-full text-xs font-medium rounded-lg border border-violet-200 bg-white text-violet-900 hover:bg-violet-50/80"
        >
          + Přidat řadu pro porovnání
        </button>
      ) : null}
    </div>
  );
}

function AradCompareRow({ row, sources, mergedIndicators, mergedLoading, onChange, onRemove }) {
  const sourceId = row.source_id || "";

  return (
    <div className="rounded-lg border border-border/70 bg-white p-3 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11px] font-semibold text-slate-700">Další řada</span>
        <button
          type="button"
          onClick={onRemove}
          className="text-[11px] text-rose-600 hover:underline shrink-0"
        >
          Odebrat
        </button>
      </div>
      <Field label="ARAD zdroj (volitelné zúžení)">
        <select
          className="input"
          value={sourceId}
          onChange={(e) => onChange({ source_id: e.target.value, indicator_id: "" })}
        >
          <option value="">Všechny napojené ARAD zdroje</option>
          {sources.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Indikátor">
        <AradCompareIndicatorPanel
          row={row}
          mergedIndicators={mergedIndicators}
          mergedLoading={mergedLoading}
          onChange={onChange}
          inputClassName="input"
          listMaxHeight={320}
          treeMaxHeight={220}
        />
      </Field>
      <div className="grid grid-cols-2 gap-2">
        <Field label="Typ v grafu">
          <select
            className="input text-xs"
            value={["line", "bar", "area"].includes(String(row.chart_type || "").toLowerCase()) ? String(row.chart_type).toLowerCase() : "line"}
            onChange={(e) => onChange({ chart_type: e.target.value })}
          >
            <option value="line">Čára</option>
            <option value="bar">Sloupec</option>
            <option value="area">Plocha</option>
          </select>
        </Field>
        <Field label="Osa Y">
          <select
            className="input text-xs"
            value={String(row.y_axis || "left").toLowerCase() === "right" ? "right" : "left"}
            onChange={(e) => onChange({ y_axis: e.target.value })}
          >
            <option value="left">Vlevo</option>
            <option value="right">Vpravo</option>
          </select>
        </Field>
      </div>
    </div>
  );
}

function AradViewConfig({ cfg, sources, setConfig }) {
  const feCompare = useFeatureAccess("composite_charts");
  const [indicators, setIndicators] = useState([]);
  const [refreshing, setRefreshing] = useState(false);
  const [filter, setFilter] = useState("");
  const sourceId = cfg.source_id || "";

  useEffect(() => {
    if (cfg.view != null) return;
    setConfig({ view: "chart", chart_type: cfg.chart_type || "line" });
  }, [cfg.view, cfg.chart_type, setConfig]);

  useEffect(() => {
    if (!sourceId) { setIndicators([]); return; }
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get(`/sources/${sourceId}/arad/indicators`);
        if (cancelled) return;
        if (data && data.length > 0) {
          setIndicators(data);
          return;
        }
        setRefreshing(true);
        await api.post(`/sources/${sourceId}/arad/refresh-indicators`);
        const { data: fresh } = await api.get(`/sources/${sourceId}/arad/indicators`);
        if (!cancelled) setIndicators(fresh || []);
      } catch  {
        if (!cancelled) setIndicators([]);
      }
      if (!cancelled) setRefreshing(false);
    })();
    return () => { cancelled = true; };
  }, [sourceId]);

  const refresh = async () => {
    if (!sourceId) return;
    setRefreshing(true);
    try {
      await api.post(`/sources/${sourceId}/arad/refresh-indicators`);
      const { data } = await api.get(`/sources/${sourceId}/arad/indicators`);
      setIndicators(data || []);
      toast.success(`Načteno ${data?.length || 0} indikátorů`);
    } catch (e) {
      toast.error(formatApiError(e.response?.data?.detail) || e.message);
    }
    setRefreshing(false);
  };

  const filtered = indicators.filter((i) => {
    if (!filter) return true;
    const q = filter.toLowerCase();
    return (i.indicator_id || "").toLowerCase().includes(q) || (i.name || "").toLowerCase().includes(q);
  });

  const selectedMainIndicatorLabel = useMemo(() => {
    const id = String(cfg.indicator_id || "").trim();
    if (!id) return "";
    const hit = indicators.find((i) => String(i.indicator_id || "").trim() === id);
    const n = (hit?.name || "").trim();
    const unit = (hit?.unit || "").trim();
    if (n) return unit ? `${n} (${unit})` : n;
    return id;
  }, [cfg.indicator_id, indicators]);

  const bulkAddFilteredToCompare = () => {
    if (!feCompare.ready || !feCompare.allowed) {
      toast.error(feCompare.message || "Kombinace více řad vyžaduje oprávnění nebo předplatné.");
      return;
    }
    const main = String(cfg.indicator_id || "").trim();
    const sid = String(sourceId || "").trim();
    if (!sid || !main) return;
    const existing = Array.isArray(cfg.chart_compare_with) ? cfg.chart_compare_with : [];
    const slots = MAX_ARAD_CHART_COMPARE - existing.length;
    if (slots <= 0) {
      toast.message(`Už je vyplněno maximum (${MAX_ARAD_CHART_COMPARE}) dalších řad.`);
      return;
    }
    const used = new Set([main, ...existing.map((r) => String(r.indicator_id || "").trim()).filter(Boolean)]);
    const ct = ["line", "bar", "area"].includes(String(cfg.chart_type || "").toLowerCase())
      ? String(cfg.chart_type).toLowerCase()
      : "line";
    const additions = [];
    for (const i of filtered) {
      const iid = String(i.indicator_id || "").trim();
      if (!iid || used.has(iid)) continue;
      used.add(iid);
      additions.push({
        source_id: sid,
        indicator_id: iid,
        chart_type: ct,
        y_axis: "left",
      });
      if (additions.length >= slots) break;
    }
    if (!additions.length) {
      toast.message("V aktuálním filtru nejsou žádné další ukazatele k přidání (hlavní řada se přeskakuje).");
      return;
    }
    setConfig({ chart_compare_with: [...existing, ...additions] });
    toast.success(
      additions.length === 1
        ? "Přidána 1 další řada — zkontrolujte náhled tabulky nebo grafu."
        : `Přidáno ${additions.length} dalších řad — zkontrolujte náhled tabulky nebo grafu.`
    );
  };

  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-sky-100 bg-gradient-to-br from-sky-50/60 via-white to-indigo-50/20 p-4 shadow-sm ring-1 ring-sky-100/50 md:p-5">
        <div className="mb-4 flex items-center gap-2 border-b border-sky-100/80 pb-3">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-sky-500/15 text-sky-700">
            <Database className="h-4 w-4" strokeWidth={2.25} />
          </span>
          <div>
            <div className="text-sm font-semibold text-slate-800">Datový zdroj (ARAD)</div>
            <div className="text-xs text-slate-600">
              Vyberte synchronizovaný ARAD zdroj a hlavní indikátor; další ukazatele (sada sloupců v tabulce nebo řad v
              grafu) doplníte níže nebo tlačítkem pod seznamem.
            </div>
          </div>
        </div>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-12 md:gap-4">
          <div className="md:col-span-4">
            <Field label="ARAD zdroj">
              <select className="input" value={sourceId} onChange={(e) => setConfig({ source_id: e.target.value, indicator_id: "" })}>
                <option value="">— vyberte —</option>
                {sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </Field>
          </div>
          <div className="md:col-span-2">
            <Field label="Zobrazení">
              <select className="input" value={cfg.view || "chart"} onChange={(e) => setConfig({ view: e.target.value })}>
                <option value="table">Tabulka</option>
                <option value="chart">Graf</option>
              </select>
            </Field>
          </div>
          <div className="md:col-span-2">
            <Field label="Typ grafu (výchozí)">
              <select className="input" value={cfg.chart_type || "line"} onChange={(e) => setConfig({ chart_type: e.target.value })}>
                <option value="line">Čára</option>
                <option value="bar">Sloupec</option>
                <option value="area">Plocha</option>
                <option value="pie">Koláč</option>
              </select>
            </Field>
          </div>
          {(cfg.view || "table") === "chart" ? (
            <div className="md:col-span-2">
              <Field label="První karta v grafu">
                <select
                  className="input"
                  value={String(cfg.default_data_view || "chart").toLowerCase() === "table" ? "table" : "chart"}
                  onChange={(e) => setConfig({ default_data_view: e.target.value })}
                >
                  <option value="chart">Graf</option>
                  <option value="table">Tabulka</option>
                </select>
              </Field>
            </div>
          ) : null}
          <ChartVariantFields cfg={cfg} setConfig={setConfig} />
          <div className="md:col-span-2">
            <Field label="Limit (0 = vše)">
              <input className="input" type="number" min={0} max={5000} value={cfg.limit ?? 0} onChange={(e) => setConfig({ limit: Number(e.target.value) })} placeholder="0 = celá historie" />
            </Field>
          </div>
          <div className="md:col-span-2 flex items-end">
            <button
              type="button"
              onClick={refresh}
              disabled={!sourceId || refreshing}
              aria-busy={refreshing ? "true" : undefined}
              className="h-9 px-3 text-xs border border-border rounded-sm bg-white hover:bg-slate-100 disabled:opacity-40 w-full inline-flex items-center justify-center gap-1.5"
            >
              {refreshing ? <LoadingSpinner suppressAria size="xs" aria-label="" /> : null}
              {refreshing ? "Načítám…" : `Znovu načíst ARAD (${indicators.length})`}
            </button>
          </div>
        </div>
        {(cfg.view || "table") === "chart" ? (
          <div className="mt-3 max-w-xs">
            <Field label="Hlavní řada — osa Y (složený graf)">
              <select
                className="input"
                value={String(cfg.primary_y_axis || "left").toLowerCase() === "right" ? "right" : "left"}
                onChange={(e) => setConfig({ primary_y_axis: e.target.value })}
              >
                <option value="left">Vlevo</option>
                <option value="right">Vpravo</option>
              </select>
            </Field>
          </div>
        ) : null}
      </div>

      <div className="rounded-2xl border border-emerald-100 bg-gradient-to-br from-emerald-50/50 via-white to-teal-50/20 p-4 shadow-sm ring-1 ring-emerald-100/40 md:p-5">
        <div className="mb-3 flex items-center gap-2 border-b border-emerald-100/80 pb-3">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-emerald-500/15 text-emerald-800">
            <ListTree className="h-4 w-4" strokeWidth={2.25} />
          </span>
          <div>
            <div className="text-sm font-semibold text-slate-800">Výběr indikátoru</div>
            <div className="text-xs text-slate-600">Prohledávej dostupné časové řady v katalogu</div>
          </div>
        </div>
        <Field label="Indikátor (klikněte na řádek pro výběr)">
          <input className="input mb-2" placeholder="Filtr podle názvu nebo ID…" value={filter} onChange={(e) => setFilter(e.target.value)} />
          <div
            className="border border-border rounded-sm bg-white overflow-y-auto"
            style={{ maxHeight: 260, minHeight: 120 }}
          >
            {filtered.length === 0 && (
              <div className="px-3 py-4 text-[12px] text-slate-400 font-mono text-center">
                — prázdné — klikněte „Znovu načíst ARAD…"
              </div>
            )}
            {filtered.map((i) => {
              const isSelected = i.indicator_id === cfg.indicator_id;
              return (
                <button
                  type="button"
                  key={i.indicator_id}
                  onClick={() => setConfig({ indicator_id: i.indicator_id })}
                  title={i.name || i.indicator_id}
                  className={`w-full text-left px-2 py-1.5 border-b border-border/40 text-[11px] leading-snug font-mono flex gap-2 items-start transition-colors ${
                    isSelected
                      ? "row-selected font-medium"
                      : "hover:bg-[hsl(var(--primary-soft)/0.6)] text-slate-800"
                  }`}
                >
                  <span className={`shrink-0 w-8 text-center tabular-nums ${isSelected ? "opacity-60" : "text-slate-400"}`}>
                    [{i.frequency_code || "?"}]
                  </span>
                  <span className="shrink-0 w-24 tabular-nums">{i.indicator_id}</span>
                  <span className="flex-1 break-words" style={{ wordBreak: "break-word" }}>
                    {i.name || "(bez názvu)"}
                    {i.unit ? <span className={`ml-1 ${isSelected ? "opacity-60" : "text-slate-400"}`}> ({i.unit})</span> : null}
                  </span>
                </button>
              );
            })}
          </div>
          {cfg.indicator_id && (
            <div className="text-[11px] text-[hsl(218_60%_32%)] mt-1">
              ✓ Vybráno: <span className="font-medium">{selectedMainIndicatorLabel}</span>
            </div>
          )}
          {sourceId && cfg.indicator_id && filtered.length > 0 ? (
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={bulkAddFilteredToCompare}
                disabled={
                  !feCompare.ready ||
                  !feCompare.allowed ||
                  (Array.isArray(cfg.chart_compare_with) ? cfg.chart_compare_with.length : 0) >=
                    MAX_ARAD_CHART_COMPARE
                }
                className="text-[11px] font-medium px-2.5 py-1.5 rounded-md border border-emerald-200 bg-white text-emerald-900 hover:bg-emerald-50/90 disabled:opacity-50 disabled:cursor-not-allowed"
                title="Přidá všechny řády z aktuálního filtru (kromě hlavní) až do limitu dalších řad."
              >
                Přidat vyfiltrované řady (max {MAX_ARAD_CHART_COMPARE - (Array.isArray(cfg.chart_compare_with) ? cfg.chart_compare_with.length : 0)})
              </button>
              <span className="text-[10px] text-slate-500 leading-snug max-w-md">
                Jedním klikem doplníte např. více položek výkazu — zúžte nejdřív filtr nahoře, pak přidejte řady do
                společné tabulky nebo grafu.
              </span>
            </div>
          ) : null}
        </Field>
      </div>

      {cfg.source_id && cfg.indicator_id ? (
        <AradCompareWithConfig cfg={cfg} sources={sources} setConfig={setConfig} />
      ) : null}

      {cfg.source_id && cfg.indicator_id && (
        <div className="mt-1 rounded-2xl border border-slate-200/80 bg-white p-3 shadow-inner">
          <PreviewToggle
            storageKey={`arad-preview-${cfg.source_id}-${cfg.indicator_id}-${cfg.view || "table"}`}
            defaultOpen={(cfg.view || "table") === "chart"}
          >
            <AradPreview cfg={cfg} />
          </PreviewToggle>
        </div>
      )}
    </div>
  );
}

/**
 * Collapsible "Živý náhled" wrapper. Choice is remembered per widget config
 * (per storageKey) via sessionStorage so navigating around doesn't reset it.
 */
function PreviewToggle({ storageKey, children, label = "Živý náhled", defaultOpen = false }) {
  const [open, setOpen] = useState(() => {
    try {
      const stored = sessionStorage.getItem(storageKey);
      if (stored != null) return stored === "1";
      return defaultOpen;
    } catch {
      return defaultOpen;
    }
  });
  const toggle = () => {
    setOpen((v) => {
      const next = !v;
      try { sessionStorage.setItem(storageKey, next ? "1" : "0"); } catch {}
      return next;
    });
  };
  return (
    <div>
      <button
        type="button"
        onClick={toggle}
        className="flex items-center gap-1.5 text-[10px] uppercase tracking-[0.08em] text-slate-600 hover:text-[hsl(var(--primary))] font-medium mb-1.5 transition-colors"
        title={open ? "Skrýt živý náhled" : "Zobrazit živý náhled"}
      >
        {open ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
        <span>{label}</span>
        <span className="text-slate-400 font-mono normal-case tracking-normal">
          ({open ? "skrýt" : "zobrazit"})
        </span>
      </button>
      {open && <div>{children}</div>}
    </div>
  );
}

function AradPreview({ cfg }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  useEffect(() => {
    if (!cfg.source_id || !cfg.indicator_id) return;
    let cancelled = false;
    setLoading(true);
    setErr("");
    api
      .post("/homepage/preview", { type: "arad_view", config: cfg })
      .then(({ data }) => {
        if (!cancelled) setData(data);
      })
      .catch((e) => {
        if (!cancelled) setErr(formatApiError(e.response?.data?.detail) || e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [
    cfg.source_id,
    cfg.indicator_id,
    cfg.view,
    cfg.limit,
    cfg.caption,
    cfg.chart_type,
    cfg.default_data_view,
    cfg.chart_color,
    cfg.chart_frequency,
    cfg.primary_y_axis,
    JSON.stringify(cfg.chart_compare_with || []),
  ]);

  if (loading) return <DataLoadRowTight />;
  if (err) return <div className="text-xs text-rose-700 font-mono py-2">Chyba: {err}</div>;
  if (!data) return null;
  if (data.data?.error) {
    return <div className="text-xs text-rose-700 font-mono py-2">{data.data.error}</div>;
  }
  return (
    <AradView
      userTitle={data.title}
      data={data.data}
      caption={cfg.caption}
      widget={{ id: "preview", type: "arad_view", config: cfg }}
      defaultChartType={cfg.chart_type || "line"}
      defaultChartFrequency={cfg.chart_frequency}
    />
  );
}

function ChartVariantFields({ cfg, setConfig }) {
  const chartType = String(cfg.chart_type || "line").toLowerCase();
  if (chartType !== "bar" && chartType !== "pie") return null;
  return (
    <>
      {chartType === "bar" ? (
        <div className="md:col-span-2">
          <Field label="Varianta sloupců">
            <select
              className="input"
              value={cfg.chart_bar_orientation || "vertical"}
              onChange={(e) => setConfig({ chart_bar_orientation: e.target.value })}
            >
              {BAR_ORIENTATIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>{opt.label}</option>
              ))}
            </select>
          </Field>
        </div>
      ) : null}
      {chartType === "pie" ? (
        <div className="md:col-span-2">
          <Field label="Varianta koláče">
            <select
              className="input"
              value={cfg.chart_pie_variant || "donut"}
              onChange={(e) => setConfig({ chart_pie_variant: e.target.value })}
            >
              {PIE_VARIANTS.map((opt) => (
                <option key={opt.value} value={opt.value}>{opt.label}</option>
              ))}
            </select>
          </Field>
        </div>
      ) : null}
    </>
  );
}

function Field({ label, children }) {
  return (
    <div>
      <label className="mb-1.5 block text-xs font-semibold text-slate-700 tracking-wide">{label}</label>
      <div>{children}</div>
    </div>
  );
}
