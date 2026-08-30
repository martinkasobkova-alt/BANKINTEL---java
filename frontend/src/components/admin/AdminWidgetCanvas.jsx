import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  GripVertical,
  Palette,
  LayoutGrid,
  Check,
  FileText,
  Trash2,
  X,
  Sparkles,
  Code2,
} from "lucide-react";
import { toast } from "sonner";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import WidgetRenderer from "@/components/widgets/WidgetRenderer";
import { ALL_FREQS, FREQ_RANK, resolveDefaultTargetFreq } from "@/components/widgets/aradViewChartFreq";
import { resolveNativeFrequencyCode } from "@/lib/chartFrequencyInfer";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import { stripAiCaptionNoise } from "@/lib/widgetCaption";
import { isTextWidgetType } from "@/lib/widgetCatalog";
import { LoadingSpinner } from "@/components/ui/loading";
import { AdImageFramingBlock } from "@/components/editor/AdImageFramingBlock";
import WidgetEmbedSnippetModal from "@/components/myDashboard/WidgetEmbedSnippetModal";
import { getAdImageSlides } from "@/lib/adConfig";
import {
  getWidgetGridPlacement,
  getEffectiveLayoutMap,
  resolveDashboardDropZone,
  computeLayoutAfterDashboardDrop,
  readWidgetGridRect,
  sortWidgetsByGridLayout,
  getDashboardMobileSpan,
} from "@/lib/widgetGridPlacement";

/**
 * Mřížka widgetů s admin ovládáním přímo nad kartou.
 *
 * Props:
 *  - widgets: vykreslené widgety (pole ve formátu z /render endpointů)
 *  - isAdmin: zapne/vypne drag&drop + panel nastavení
 *  - onReorder(ids: string[], widgetLayout?: Record<string, object>) => Promise<void>
 *  - onPatch(widgetId: string, patch: {width?, config?}) => Promise<void>
 *  - onDelete?(widgetId: string) => Promise<void>
 *  - aradMultiSeriesHelpContext: nápověda „Více řad“ v AradView — personal_dashboard vs veřejná stránka
 */
export default function AdminWidgetCanvas({
  widgets = [],
  isAdmin = false,
  onReorder,
  onPatch,
  onDelete,
  defaultChartType = "line",
  defaultChartFrequency,
  aradMultiSeriesHelpContext = "public_site",
  dashboardSharePageId = null,
  personalDashboardPages = null,
  currentDashboardPageId = null,
  pageAllowEmbed = false,
  pageShareToken = "",
  onWidgetRefresh = null,
}) {
  const { canEditContent } = useAuth();
  const allowWidgetConfigPatch =
    typeof onPatch === "function" && (isAdmin || canEditContent);

  const [localOrder, setLocalOrder] = useState(null);
  const [draggingId, setDraggingId] = useState(null);
  /** @type {React.MutableRefObject<{ targetId: string, zone: string } | null>} */
  const dropHintRef = useRef(null);
  const [dropHint, setDropHint] = useState(null);
  const savingRef = useRef(false);
  const [deleteCandidate, setDeleteCandidate] = useState(null);
  const [deleteBusy, setDeleteBusy] = useState(false);

  // Při HTML5 drag&drop není jiná cesta, jak „jet“ stránkou: auto-scroll u okrajů
  // a kolečko myši posouvá hlavní obsah (`#app-main-scroll-pane` v `AppShell`).
  useEffect(() => {
    if (!isAdmin || !draggingId) return;
    const scrollRoot = () =>
      document.getElementById("app-main-scroll-pane") || document.getElementById("app-main-scroll");
    const EDGE = 80;
    const maxStep = 22;
    let raf = null;
    let lastY = 0;
    const tick = (clientY) => {
      const el = scrollRoot();
      if (el) {
        const r = el.getBoundingClientRect();
        if (clientY < r.top + EDGE) {
          const t = (r.top + EDGE - clientY) / EDGE;
          el.scrollTop -= Math.max(3, Math.round(maxStep * t));
        } else if (clientY > r.bottom - EDGE) {
          const t = (clientY - (r.bottom - EDGE)) / EDGE;
          el.scrollTop += Math.max(3, Math.round(maxStep * t));
        }
        return;
      }
      if (clientY < EDGE) {
        window.scrollBy(0, -maxStep);
      } else if (clientY > window.innerHeight - EDGE) {
        window.scrollBy(0, maxStep);
      }
    };
    const scheduleTick = (clientY) => {
      if (clientY == null || Number.isNaN(clientY)) return;
      lastY = clientY;
      if (raf != null) return;
      raf = requestAnimationFrame(() => {
        raf = null;
        tick(lastY);
      });
    };
    const onDragOver = (e) => {
      e.preventDefault();
      scheduleTick(e.clientY);
    };
    const onDrag = (e) => {
      scheduleTick(e.clientY);
    };
    const onWheel = (e) => {
      const el = scrollRoot();
      if (!el) return;
      el.scrollTop += e.deltaY;
      e.preventDefault();
    };
    window.addEventListener("dragover", onDragOver);
    document.addEventListener("drag", onDrag, true);
    document.addEventListener("wheel", onWheel, { capture: true, passive: false });
    return () => {
      if (raf != null) cancelAnimationFrame(raf);
      window.removeEventListener("dragover", onDragOver);
      document.removeEventListener("drag", onDrag, true);
      document.removeEventListener("wheel", onWheel, { capture: true });
    };
  }, [isAdmin, draggingId]);

  const orderedWidgets = useMemo(() => {
    if (!localOrder) return widgets;
    const byId = new Map(widgets.map((w) => [w.id, w]));
    const out = [];
    for (const id of localOrder) {
      const w = byId.get(id);
      if (w) {
        out.push(w);
        byId.delete(id);
      }
    }
    for (const w of byId.values()) out.push(w);
    return out;
  }, [widgets, localOrder]);

  /** Jakmile je v configu alespoň jedna uložená mřížka, řadíme podle Y/X (explicitní souřadnice). */
  const displayWidgets = useMemo(() => {
    if (!orderedWidgets.some((w) => readWidgetGridRect(w) != null)) {
      return orderedWidgets;
    }
    return sortWidgetsByGridLayout(orderedWidgets);
  }, [orderedWidgets]);

  /** Stejná logika jako u řazení — buňka musí použít vypočtený obdélník (včetně nového colSpan po změně šířky), ne jen uložené grid_* v configu. */
  const layoutMap = useMemo(
    () => getEffectiveLayoutMap(displayWidgets),
    [displayWidgets]
  );

  const commitReorder = useCallback(
    async (ids, widgetLayout) => {
      if (!onReorder || savingRef.current) return;
      savingRef.current = true;
      try {
        await onReorder(ids, widgetLayout);
        setLocalOrder(null);
      } catch (e) {
        toast.error(
          e?.response?.data?.detail || "Nepodařilo se uložit pořadí widgetů."
        );
        setLocalOrder(null);
      } finally {
        savingRef.current = false;
      }
    },
    [onReorder]
  );

  const getWidgetById = useCallback(
    (id) => orderedWidgets.find((x) => x.id === id),
    [orderedWidgets]
  );

  const computeDropHint = useCallback(
    (e, targetW, dragId) => {
      const dragW = orderedWidgets.find((x) => x.id === dragId);
      const rect = e.currentTarget.getBoundingClientRect();
      const relX =
        rect.width > 0 ? (e.clientX - rect.left) / rect.width : 0.5;
      const relY =
        rect.height > 0 ? (e.clientY - rect.top) / rect.height : 0.5;
      const zone = resolveDashboardDropZone(dragW, targetW, relX, relY);
      return { targetId: targetW.id, zone };
    },
    [orderedWidgets]
  );

  const handleCellDragOver = useCallback(
    (e, targetW) => {
      if (!isAdmin || !draggingId) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
      const hint = computeDropHint(e, targetW, draggingId);
      dropHintRef.current = hint;
      setDropHint(hint);
    },
    [isAdmin, draggingId, computeDropHint]
  );

  const handleCellDragLeave = useCallback(
    (e, targetW) => {
      if (!isAdmin) return;
      const rel = e.relatedTarget;
      if (rel instanceof Node && e.currentTarget.contains(rel)) return;
      if (dropHintRef.current?.targetId === targetW.id) {
        setDropHint(null);
        dropHintRef.current = null;
      }
    },
    [isAdmin]
  );

  const handleCellDrop = useCallback(
    (e, targetW) => {
      if (!isAdmin) return;
      e.preventDefault();
      let zone =
        dropHintRef.current?.targetId === targetW.id
          ? dropHintRef.current.zone
          : null;
      if (!zone && draggingId) {
        zone = computeDropHint(e, targetW, draggingId).zone;
      }
      setDropHint(null);
      dropHintRef.current = null;
      const currentDraggingId = draggingId;
      setDraggingId(null);
      if (!currentDraggingId || currentDraggingId === targetW.id || !zone) return;
      const prevIds = orderedWidgets.map((x) => x.id);
      const { nextIds, widgetLayout } = computeLayoutAfterDashboardDrop(
        orderedWidgets,
        currentDraggingId,
        targetW.id,
        zone,
        getWidgetById
      );
      const sameOrder =
        prevIds.length === nextIds.length && prevIds.every((id, i) => id === nextIds[i]);
      const emptyLayout =
        widgetLayout == null || Object.keys(widgetLayout).length === 0;
      if (sameOrder && emptyLayout) return;
      setLocalOrder(nextIds);
      commitReorder(nextIds, widgetLayout);
    },
    [
      isAdmin,
      draggingId,
      orderedWidgets,
      computeDropHint,
      getWidgetById,
      commitReorder,
    ]
  );

  /** Drop přes koš vpravo dole — smaže widget (po potvrzení). */
  const handleTrashDrop = useCallback(
    (e) => {
      e.preventDefault();
      const id = e.dataTransfer?.getData("text/plain") || draggingId;
      setDraggingId(null);
      setDropHint(null);
      dropHintRef.current = null;
      if (!id || typeof onDelete !== "function") return;
      const target = orderedWidgets.find((x) => x.id === id);
      setDeleteCandidate({
        id,
        title: target?.title?.trim() || "",
      });
    },
    [draggingId, onDelete, orderedWidgets]
  );

  const confirmDeleteCandidate = useCallback(async () => {
    if (!deleteCandidate?.id || typeof onDelete !== "function" || deleteBusy) return;
    setDeleteBusy(true);
    try {
      await onDelete(deleteCandidate.id);
      setDeleteCandidate(null);
    } catch (err) {
      toast.error(err?.response?.data?.detail || "Nepodařilo se widget odstranit.");
    } finally {
      setDeleteBusy(false);
    }
  }, [deleteCandidate, deleteBusy, onDelete]);

  return (
    <div className="widget-canvas-grid w-full">
      {displayWidgets.map((w) => {
        const isDragging = draggingId === w.id;
        const showDropZone =
          isAdmin &&
          draggingId &&
          draggingId !== w.id &&
          dropHint?.targetId === w.id;
        const { colSpan, rowSpan } = getWidgetGridPlacement(w);
        const gridRect = layoutMap.get(w.id) ?? readWidgetGridRect(w);
        return (
          <div
            id={w.id ? `widget-${w.id}` : undefined}
            key={`${w.id}::${w.width || "full"}::${gridRect ? `${gridRect.colStart}-${gridRect.colEnd}-${gridRect.rowStart}-${gridRect.rowEnd}` : "flow"}`}
            data-has-grid={gridRect ? "1" : undefined}
            data-mobile-span={getDashboardMobileSpan(w)}
            className={`widget-canvas-cell relative h-full min-h-0 flex flex-col transition-opacity ${
              isDragging ? "opacity-40" : ""
            }`}
            style={
              gridRect
                ? {
                    "--grid-cs": gridRect.colStart,
                    "--grid-ce": gridRect.colEnd,
                    "--grid-rs": gridRect.rowStart,
                    "--grid-re": gridRect.rowEnd,
                    "--widget-cs": String(colSpan),
                    "--widget-rs": String(rowSpan),
                  }
                : {
                    "--widget-cs": String(colSpan),
                    "--widget-rs": String(rowSpan),
                  }
            }
            onDragOver={
              isAdmin ? (e) => handleCellDragOver(e, w) : undefined
            }
            onDragLeave={
              isAdmin ? (e) => handleCellDragLeave(e, w) : undefined
            }
            onDrop={isAdmin ? (e) => handleCellDrop(e, w) : undefined}
          >
            <div className="flex min-h-0 min-w-0 flex-1 flex-col">
              <WidgetRenderer
                w={w}
                defaultChartType={defaultChartType}
                defaultChartFrequency={defaultChartFrequency}
                aradMultiSeriesHelpContext={aradMultiSeriesHelpContext}
                onWidgetConfigPatch={allowWidgetConfigPatch ? onPatch : null}
                dashboardSharePageId={dashboardSharePageId}
                onWidgetRefresh={onWidgetRefresh}
              />
            </div>
            {showDropZone && (
              <DashboardDropZoneOverlay zone={dropHint.zone} />
            )}
            {isAdmin && (
              <AdminOverlay
                widget={w}
                defaultChartType={defaultChartType}
                defaultChartFrequency={defaultChartFrequency}
                onPatch={onPatch}
                personalDashboardPages={personalDashboardPages}
                currentDashboardPageId={currentDashboardPageId}
                pageAllowEmbed={pageAllowEmbed}
                pageShareToken={pageShareToken}
                onRequestDelete={
                  typeof onDelete === "function"
                    ? () => setDeleteCandidate({ id: w.id, title: w.title })
                    : null
                }
                onDragStart={() => setDraggingId(w.id)}
                onDragEnd={() => {
                  setDraggingId(null);
                  setDropHint(null);
                  dropHintRef.current = null;
                }}
              />
            )}
          </div>
        );
      })}
      {isAdmin && typeof onDelete === "function" && (
        <TrashDropZone visible={Boolean(draggingId)} onDrop={handleTrashDrop} />
      )}
      <AlertDialog
        open={Boolean(deleteCandidate)}
        onOpenChange={(open) => {
          if (!open && !deleteBusy) setDeleteCandidate(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Odstranit widget?</AlertDialogTitle>
            <AlertDialogDescription>
              {deleteCandidate?.title
                ? `Opravdu chcete odstranit widget „${deleteCandidate.title}“?`
                : "Opravdu chcete odstranit tento widget?"}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteBusy}>Zrušit</AlertDialogCancel>
            <AlertDialogAction
              onClick={(e) => {
                e.preventDefault();
                confirmDeleteCandidate();
              }}
              disabled={deleteBusy}
              className="bg-red-600 hover:bg-red-700 text-white"
            >
              {deleteBusy ? "Mažu..." : "Smazat"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/** Plovoucí koš vpravo dole — admin tam přetáhne widget pro smazání. */
function TrashDropZone({ visible, onDrop }) {
  const [over, setOver] = useState(false);
  return (
    <div
      onDragOver={(e) => {
        e.preventDefault();
        if (e.dataTransfer) e.dataTransfer.dropEffect = "move";
        if (!over) setOver(true);
      }}
      onDragEnter={(e) => {
        e.preventDefault();
        setOver(true);
      }}
      onDragLeave={(e) => {
        // Pouze když opouštíme samotný kontejner (ne child) — kontrolujeme `relatedTarget`.
        if (!e.currentTarget.contains(e.relatedTarget)) setOver(false);
      }}
      onDrop={(e) => {
        setOver(false);
        onDrop?.(e);
      }}
      className={`fixed z-[40] bottom-44 right-5 md:bottom-28 transition-all duration-150 select-none pointer-events-${visible ? "auto" : "none"} ${
        visible ? "opacity-100 scale-100" : "opacity-0 scale-90"
      }`}
      aria-hidden={!visible}
      role="button"
      title="Pustit sem pro smazání widgetu"
    >
      <div
        className={`flex h-16 w-16 items-center justify-center rounded-full border-2 shadow-lg backdrop-blur transition-colors ${
          over
            ? "bg-red-600 border-red-700 text-white scale-110"
            : "bg-white/95 border-red-300 text-red-600 hover:bg-red-50"
        }`}
      >
        <Trash2 className={`${over ? "h-8 w-8" : "h-7 w-7"} transition-all`} strokeWidth={2} />
      </div>
      {over && (
        <div className="absolute -top-7 left-1/2 -translate-x-1/2 whitespace-nowrap text-[11px] font-semibold text-red-700 bg-white/95 px-2 py-0.5 rounded-md shadow border border-red-200">
          Pustit pro smazání
        </div>
      )}
    </div>
  );
}

/** Zóna dropu: střed = prohodit pozice, okraje = modré nápovědy (nad/pod/vlevo/vpravo). */
function DashboardDropZoneOverlay({ zone }) {
  const base =
    "pointer-events-none absolute inset-0 z-[40] overflow-hidden rounded-[inherit]";
  const tint =
    "absolute bg-[hsl(202_90%_52%_/_0.22)] border-[hsl(202_90%_48%)]";
  if (zone === "swap") {
    return (
      <div className={base} aria-hidden>
        <div className="absolute inset-[14%] flex items-center justify-center rounded-xl border-2 border-dashed border-amber-600 bg-amber-400/20 shadow-inner">
          <span className="rounded-md bg-white/90 px-2 py-1 text-[10px] font-bold uppercase tracking-wide text-amber-900 shadow-sm">
            Prohodit
          </span>
        </div>
      </div>
    );
  }
  if (zone === "above") {
    return (
      <div className={base} aria-hidden>
        <div
          className={`${tint} left-0 right-0 top-0 h-1/2 border-b-2 border-dashed rounded-t-[inherit]`}
        />
      </div>
    );
  }
  if (zone === "below") {
    return (
      <div className={base} aria-hidden>
        <div
          className={`${tint} left-0 right-0 bottom-0 h-1/2 border-t-2 border-dashed rounded-b-[inherit]`}
        />
      </div>
    );
  }
  if (zone === "before") {
    return (
      <div className={base} aria-hidden>
        <div
          className={`${tint} top-0 bottom-0 left-0 w-1/2 border-r-2 border-dashed rounded-l-[inherit]`}
        />
      </div>
    );
  }
  return (
    <div className={base} aria-hidden>
      <div
        className={`${tint} top-0 bottom-0 right-0 w-1/2 border-l-2 border-dashed rounded-r-[inherit]`}
      />
    </div>
  );
}

const WIDTH_OPTIONS = [
  { value: "full", label: "Plná šířka" },
  { value: "three-quarters", label: "3 / 4" },
  { value: "two-thirds", label: "2 / 3" },
  { value: "half", label: "1 / 2" },
  { value: "third", label: "1 / 3" },
  { value: "quarter", label: "1 / 4" },
  { value: "sixth", label: "1 / 6" },
  { value: "eighth", label: "1 / 8" },
];

/** Výška widgetu — počet řádků (grid-auto-rows: 200px → 1 = 200px, 2 = 400px…) */
const HEIGHT_OPTIONS = [
  { value: "",  label: "Výchozí výška" },
  { value: "1", label: "1 řádek  (~200 px)" },
  { value: "2", label: "2 řádky  (~400 px)" },
  { value: "3", label: "3 řádky  (~600 px)  — vertikální" },
  { value: "4", label: "4 řádky  (~800 px)  — vertikální" },
  { value: "5", label: "5 řádků  (~1 000 px) — extra vysoký" },
];

// Paleta barev grafu sladěná s `CHART_PALETTE` ve `WidgetRenderer.jsx`.
// První položka = žádné override (vrátí se ke globálnímu primárnímu odstínu).
const CHART_COLOR_PRESETS = [
  { value: "", label: "Výchozí", swatch: "linear-gradient(135deg, hsl(202 90% 52%), hsl(218 65% 22%))" },
  { value: "#1f8cdb", label: "Modrá (primární)", swatch: "hsl(202 90% 52%)" },
  { value: "#12366a", label: "Tmavě modrá", swatch: "hsl(218 65% 22%)" },
  { value: "#2e7ec8", label: "Ocelová modrá", swatch: "hsl(208 75% 48%)" },
  { value: "#5fb8a4", label: "Mentolová", swatch: "hsl(170 35% 55%)" },
  { value: "#e89da7", label: "Růžová", swatch: "hsl(344 70% 70%)" },
  { value: "#f4b860", label: "Krémová", swatch: "hsl(36 90% 68%)" },
  { value: "#8b8cc8", label: "Fialová", swatch: "hsl(240 55% 70%)" },
  { value: "#e08060", label: "Terracotta", swatch: "hsl(18 75% 62%)" },
  { value: "#6b7280", label: "Šedá", swatch: "#6b7280" },
];

const PANEL_STYLE_OPTIONS = [
  { value: "default", label: "Výchozí karta" },
  { value: "white", label: "Čistá bílá" },
  { value: "muted", label: "Světle šedá" },
  { value: "slate", label: "Šedomodrá" },
  { value: "mint", label: "Mentolová" },
  { value: "cream", label: "Teplá krémová" },
  { value: "none", label: "Bez výplně" },
];

const CHART_TYPE_IDS = new Set([
  "line",
  "bar",
  "area",
  "pie",
  "dot",
  "geo_map",
  "pictogram",
  "icon_chart",
]);

const CHART_TYPE_SELECT_OPTIONS = [
  { value: "line", label: "Čára" },
  { value: "bar", label: "Sloupce" },
  { value: "area", label: "Plocha" },
  { value: "pie", label: "Kruhový" },
  { value: "dot", label: "Body (dot)" },
  { value: "geo_map", label: "Mapa zemí" },
  { value: "pictogram", label: "Pictogram" },
  { value: "icon_chart", label: "Ikony / emotikony" },
];

const CHART_SORT_OPTIONS = [
  { value: "source", label: "Abecedně / podle zdroje" },
  { value: "asc", label: "Od nejmenší hodnoty" },
  { value: "desc", label: "Od největší hodnoty" },
];

function ellipsizeLabel(value, max = 18) {
  const s = String(value ?? "").trim();
  if (!s || s.length <= max) return s;
  return `${s.slice(0, Math.max(1, max - 1)).trimEnd()}…`;
}

const AGGREGATION_OPTIONS = [
  { value: "__auto__", label: "Automaticky" },
  { value: "sum", label: "Součet" },
  { value: "avg", label: "Průměr" },
  { value: "last", label: "Poslední hodnota" },
  { value: "max", label: "Maximum" },
  { value: "min", label: "Minimum" },
  { value: "count", label: "Počet" },
];

const AGGREGATION_IDS = new Set(AGGREGATION_OPTIONS.map((o) => o.value).filter((v) => v !== "__auto__"));

const UPLOAD_AGG_OPTIONS = [
  { id: "sum", label: "Součet" },
  { id: "avg", label: "Průměr" },
  { id: "last", label: "Poslední" },
  { id: "max", label: "Maximum" },
  { id: "min", label: "Minimum" },
  { id: "count", label: "Počet" },
];

const UPLOAD_Y_OPS = [
  { id: "+", label: "+ (součet sloupců)" },
  { id: "-", label: "− (rozdíl)" },
  { id: "*", label: "× (součin)" },
  { id: "/", label: "÷ (podíl)" },
];

const UPLOAD_FREQUENCY_OPTIONS = [
  { id: "__auto__", label: "Původní / automaticky" },
  { id: "M", label: "Měsíčně" },
  { id: "Q", label: "Čtvrtletně" },
  { id: "S", label: "Pololetně" },
  { id: "Y", label: "Ročně" },
];

function isEmbedChartWidget(w) {
  return (
    w?.type === "chart" ||
    w?.type === "external_catalog_chart" ||
    w?.type === "computed_chart" ||
    w?.type === "uploaded_data_chart" ||
    w?.type === "user_upload_chart" ||
    w?.engine_type === "external_catalog_chart"
  );
}

function AdminOverlay({
  widget,
  onPatch,
  onDragStart,
  onDragEnd,
  onRequestDelete = null,
  defaultChartType = "line",
  defaultChartFrequency,
  personalDashboardPages = null,
  currentDashboardPageId = null,
  pageAllowEmbed = false,
  pageShareToken = "",
}) {
  const { isAdmin: isSiteAdmin } = useAuth();
  const [open, setOpen] = useState(false);
  const [embedOpen, setEmbedOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [uploadingMedia, setUploadingMedia] = useState(null);
  const [uploadColumns, setUploadColumns] = useState([]);
  const [uploadPreviewLoading, setUploadPreviewLoading] = useState(false);
  const [uploadPreviewError, setUploadPreviewError] = useState("");
  const [aiCaptionLoading, setAiCaptionLoading] = useState(false);
  const [aiCaptionReason, setAiCaptionReason] = useState("");
  const [aiPrompt, setAiPrompt] = useState("");
  const [aiPromptOpen, setAiPromptOpen] = useState(false);
  const contentRef = useRef(null);
  const imageInputRef = useRef(null);
  const medallionInputRef = useRef(null);
  const [draft, setDraft] = useState({
    heading: "",
    subheading: "",
    content: "",
    caption: "",
  });
  const [titleDraft, setTitleDraft] = useState(widget?.title || "");
  const chartColor = (widget?.config?.chart_color || "").toLowerCase();
  const panelStyle = widget?.config?.panel_style || "default";
  const width = widget?.width || "full";
  const rowSpanRaw = widget?.rowSpan ?? widget?.config?.rowSpan ?? widget?.config?.row_span;
  const rowSpanVal = rowSpanRaw != null ? String(rowSpanRaw) : "";
  const dashboardPages = Array.isArray(personalDashboardPages) ? personalDashboardPages : [];
  const showDashboardPagePicker =
    dashboardPages.length > 1 && Boolean(currentDashboardPageId) && typeof onPatch === "function";
  const widgetDashboardPageId = String(widget?.page_id || currentDashboardPageId || "").trim();
  const effectiveWidgetType = widget?.engine_type || widget?.type;
  const isAdWidget = widget?.type === "ad";
  const adCfg = widget?.config || {};
  const adKind = String(adCfg.kind ?? adCfg.ad_kind ?? "image").toLowerCase();
  const isAdImage = isAdWidget && adKind === "image";
  const adFramingPreviewUrl = useMemo(() => {
    if (!isAdImage) return "";
    const slides = getAdImageSlides(widget?.config || {});
    return String(slides[0]?.image_url || "").trim();
  }, [isAdImage, widget?.config]);
  const isTextWidget = isTextWidgetType(widget?.type);
  const isRssWidget = widget?.type === "rss_monitoring";
  const isUserUploadChart = widget?.type === "user_upload_chart";
  const canShowEmbed =
    pageAllowEmbed &&
    Boolean(String(pageShareToken || "").trim()) &&
    isEmbedChartWidget(widget);
  // CSU/dataset chart — widget uložený z PersonalCatalogChartForm (interní nebo externí katalog)
  const isCsuDatasetChart = ["chart", "external_catalog_chart"].includes(widget?.type) ||
    ["csu_view", "dataset_view", "eurostat_view", "ecb_view", "fred_view",
     "alphavantage_view", "worldbank_view", "bis_view", "imf_view", "oecd_view"].includes(widget?.engine_type);
  // Dostupná pole z dat widgetu (tabulka ukáže správná data → fields jsou spolehlivá)
  const dataFields = useMemo(() => {
    const d = widget?.data;
    if (!d) return [];
    // Z tabulkových dat (fields pole)
    if (Array.isArray(d.fields) && d.fields.length) return d.fields.map(String);
    // Z rows klíčů
    if (Array.isArray(d.rows) && d.rows.length) return Object.keys(d.rows[0] || {}).filter(k => k !== "x" && k !== "y");
    return [];
  }, [widget?.data]);
  const dataGroupField = widget?.data?.group_field || widget?.config?.series_field || "";
  const csuCfg = widget?.config || {};
  const patchCsuConfig = async (next) => patch({ config: next });
  const uploadConfig = widget?.config || {};
  const uploadChartMode = String(uploadConfig?.chart_mode || "single").toLowerCase();
  const uploadYMode = String(uploadConfig?.y_mode || "single").toLowerCase();
  const uploadXField = String(uploadConfig?.x_field || "");
  const uploadYField = String(uploadConfig?.y_field || "");
  const uploadYFieldB = String(uploadConfig?.y_field_b || "");
  const uploadYOp = String(uploadConfig?.y_op || "+");
  const uploadAgg = String(uploadConfig?.agg || "sum");
  const rawUploadChartType = String(uploadConfig?.chart_type || "").trim().toLowerCase();
  const uploadChartType = CHART_TYPE_IDS.has(rawUploadChartType) ? rawUploadChartType : "line";
  const uploadUnit = String(uploadConfig?.unit || uploadConfig?.chart_unit || "").trim();
  const rawUploadChartSortOrder = String(uploadConfig?.chart_sort_order || "").trim().toLowerCase();
  const uploadChartSortOrder = CHART_SORT_OPTIONS.some((opt) => opt.value === rawUploadChartSortOrder)
    ? rawUploadChartSortOrder
    : "source";
  const uploadChartFrequency = String(uploadConfig?.chart_frequency || "").trim().toUpperCase();
  const uploadYFields = Array.isArray(uploadConfig?.y_fields)
    ? uploadConfig.y_fields.map((v) => String(v)).filter(Boolean)
    : [];
  const supportsChartOptions = [
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
    "external_catalog_chart",
    "dataset_chart",
    "formula_chart",
  ].includes(effectiveWidgetType);
  const wideChartTile =
    width === "full" || width === "three-quarters" || width === "two-thirds";
  const miniChartMode = Boolean(widget?.config?.mini_chart);
  const chartTitleEmphasis = Boolean(widget?.config?.chart_title_emphasis);
  const latestDataMode = String(widget?.config?.chart_data_mode || widget?.data?.chart_data_mode || "").toLowerCase() === "latest";
  const barLabelsEnabled =
    widget?.config?.chart_bar_labels === true ||
    (latestDataMode && widget?.config?.chart_bar_labels !== false);
  const avgLineEnabled = widget?.config?.chart_avg_line === true;
  const medianLineEnabled = widget?.config?.chart_median_line === true;
  const trendLineEnabled = widget?.config?.chart_trend_line === true;
  const rawChartSortOrder = String(widget?.config?.chart_sort_order || widget?.data?.chart_sort_order || "source").toLowerCase();
  const chartSortOrder = CHART_SORT_OPTIONS.some((opt) => opt.value === rawChartSortOrder)
    ? rawChartSortOrder
    : "source";
  const chartLabelOverrides = useMemo(() => {
    const raw = widget?.config?.chart_label_overrides;
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return {};
    const out = {};
    for (const [key, value] of Object.entries(raw)) {
      const k = String(key || "").trim();
      const v = String(value || "").trim();
      if (k && v) out[k] = v;
    }
    return out;
  }, [widget?.config?.chart_label_overrides]);
  const latestChartLabelRows = useMemo(() => {
    if (!latestDataMode || !Array.isArray(widget?.data?.rows)) return [];
    const seen = new Set();
    const out = [];
    for (const row of widget.data.rows) {
      const label = String(row?.x ?? row?.period ?? "").trim();
      if (!label || seen.has(label)) continue;
      seen.add(label);
      out.push(label);
      if (out.length >= 40) break;
    }
    return out;
  }, [latestDataMode, widget?.data?.rows]);
  const rawChartType = (widget?.config?.chart_type || "").trim().toLowerCase();
  const chartTypeSelectValue = CHART_TYPE_IDS.has(rawChartType)
    ? rawChartType
    : String(defaultChartType || "line").toLowerCase();
  const rawDefaultDataView = String(widget?.config?.default_data_view || "").toLowerCase();
  const defaultDataViewValue = rawDefaultDataView === "table" ? "table" : "chart";
  const supportsAggregationOptions =
    supportsChartOptions && !isUserUploadChart && !["computed_view", "formula_chart"].includes(effectiveWidgetType);
  const rawAgg = String(widget?.config?.agg || "").trim().toLowerCase();
  const aggSelectValue = AGGREGATION_IDS.has(rawAgg) ? rawAgg : "__auto__";
  const originalIndicatorName = useMemo(() => {
    const fromData = [
      widget?.data?.selected_indicator_label,
      widget?.data?.main_indicator_label,
      widget?.data?.indicator_name,
      widget?.data?.indicator_title,
    ];
    for (const v of fromData) {
      const s = String(v || "").trim();
      if (s) return s;
    }
    const fromConfig = [
      widget?.config?.selected_indicator_name,
      widget?.config?.selected_indicator_label,
      widget?.config?.indicator_name,
      widget?.config?.selected_indicator,
      widget?.config?.indicator_id,
      widget?.config?.set_id,
    ];
    for (const v of fromConfig) {
      const s = String(v || "").trim();
      if (s) return s;
    }
    return "";
  }, [
    widget?.data?.selected_indicator_label,
    widget?.data?.main_indicator_label,
    widget?.data?.indicator_name,
    widget?.data?.indicator_title,
    widget?.config?.selected_indicator_name,
    widget?.config?.selected_indicator_label,
    widget?.config?.indicator_name,
    widget?.config?.selected_indicator,
    widget?.config?.indicator_id,
    widget?.config?.set_id,
  ]);
  const supportsFrequencyOptions = [
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
    "external_catalog_chart",
  ].includes(effectiveWidgetType);
  const supportsSeriesModeOptions =
    supportsChartOptions && !isUserUploadChart && !["computed_view", "formula_chart"].includes(effectiveWidgetType);
  const rawSeriesMode = String(widget?.config?.chart_series_mode || "").trim().toLowerCase();
  const hasConfiguredComposite =
    (Array.isArray(widget?.config?.chart_compare_with) && widget.config.chart_compare_with.length > 0) ||
    Boolean(String(widget?.config?.chart_series_dim || "").trim()) ||
    Boolean(widget?.data?.multi_series);
  const seriesModeValue =
    rawSeriesMode === "single"
      ? "single"
      : rawSeriesMode === "multi" || hasConfiguredComposite
        ? "multi"
        : "single";
  const nativeChartFreq = resolveNativeFrequencyCode({
    explicitFrequency: widget?.data?.frequency,
    rows: widget?.data?.rows || [],
    isMultiSeries: Boolean(widget?.data?.multi_series),
  });
  const rawChartFreq = (widget?.config?.chart_frequency || "").trim().toUpperCase();
  const nativeRank = FREQ_RANK[nativeChartFreq] ?? 99;
  const hasExplicitFreq =
    Boolean(rawChartFreq) &&
    FREQ_RANK[rawChartFreq] !== undefined &&
    FREQ_RANK[rawChartFreq] >= nativeRank;
  const effectiveChartFreq = resolveDefaultTargetFreq(
    widget?.config?.chart_frequency,
    defaultChartFrequency,
    nativeChartFreq
  );
  const freqSelectValue = hasExplicitFreq ? rawChartFreq : "__auto__";
  const uploadAllColumns = useMemo(() => {
    const out = [];
    const seen = new Set();
    const push = (v) => {
      const s = String(v || "").trim();
      if (!s || seen.has(s)) return;
      seen.add(s);
      out.push(s);
    };
    uploadColumns.forEach(push);
    push(uploadXField);
    push(uploadYField);
    push(uploadYFieldB);
    uploadYFields.forEach(push);
    return out;
  }, [uploadColumns, uploadXField, uploadYField, uploadYFieldB, uploadYFields]);

  useEffect(() => {
    setDraft({
      heading: widget?.config?.heading || "",
      subheading: widget?.config?.subheading || "",
      content: widget?.config?.content || "",
      caption: stripAiCaptionNoise(widget?.config?.caption || ""),
    });
    setTitleDraft(widget?.title || "");
  }, [widget?.id, widget?.config, widget?.title]);

  useEffect(() => {
    if (!open || !isUserUploadChart) {
      setUploadColumns([]);
      setUploadPreviewError("");
      setUploadPreviewLoading(false);
      return;
    }
    const uploadId = String(uploadConfig?.user_upload_id || uploadConfig?.upload_id || "").trim();
    if (!uploadId) {
      setUploadColumns([]);
      setUploadPreviewError("Widget nemá nastavený user_upload_id.");
      return;
    }
    let cancelled = false;
    setUploadPreviewLoading(true);
    setUploadPreviewError("");
    (async () => {
      try {
        const { data } = await api.get(`/me/uploads/${uploadId}/preview`);
        if (cancelled) return;
        setUploadColumns(Array.isArray(data?.columns) ? data.columns.map((c) => String(c)) : []);
      } catch (e) {
        if (cancelled) return;
        setUploadColumns([]);
        setUploadPreviewError(formatApiErrorFromAxios(e) || "Nepodařilo se načíst sloupce uploadu.");
      } finally {
        if (!cancelled) setUploadPreviewLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [open, isUserUploadChart, uploadConfig?.user_upload_id]);

  const patch = async (payload) => {
    if (!onPatch) return false;
    setSaving(true);
    try {
      const ok = await onPatch(widget.id, payload);
      if (ok === false) {
        toast.error("Nepodařilo se uložit změnu widgetu.");
        return false;
      }
      return true;
    } catch (e) {
      toast.error(
        e?.response?.data?.detail || "Nepodařilo se uložit změnu widgetu."
      );
      return false;
    } finally {
      setSaving(false);
    }
  };

  const onWidthChange = (e) => patch({ width: e.target.value });
  const onRowsChange  = (e) => {
    const v = e.target.value;
    patch({ rowSpan: v === "" ? null : Number(v) });
  };
  const onPanelStyleChange = (e) =>
    patch({ config: { panel_style: e.target.value } });
  const onColorPick = (hex) => patch({ config: { chart_color: hex } });
  const onHideControlsToggle = (next) => patch({ config: { hide_chart_controls: next } });
  const onMiniChartToggle = (next) => patch({ config: { mini_chart: next } });
  const onChartTitleEmphasisToggle = (next) => patch({ config: { chart_title_emphasis: next } });
  const onBarLabelsToggle = (next) => patch({ config: { chart_bar_labels: next } });
  const onAvgLineToggle = (next) => patch({ config: { chart_avg_line: next } });
  const onMedianLineToggle = (next) => patch({ config: { chart_median_line: next } });
  const onTrendLineToggle = (next) => patch({ config: { chart_trend_line: next } });
  const onChartTypeChange = (e) => patch({ config: { chart_type: e.target.value } });
  const onDefaultDataViewChange = (e) => patch({ config: { default_data_view: e.target.value } });
  const onChartSortOrderChange = (e) => patch({ config: { chart_sort_order: e.target.value } });
  const onChartLabelOverrideChange = (sourceLabel, nextLabel) => {
    const source = String(sourceLabel || "").trim();
    if (!source) return;
    const next = { ...chartLabelOverrides };
    const label = String(nextLabel || "").trim();
    if (!label || label === source) delete next[source];
    else next[source] = label;
    patch({ config: { chart_label_overrides: next } });
  };
  const onChartFrequencyChange = (e) => {
    const v = e.target.value;
    if (v === "__auto__") patch({ config: { chart_frequency: null } });
    else patch({ config: { chart_frequency: v } });
  };
  const onAggregationChange = (e) => {
    const v = e.target.value;
    if (v === "__auto__") patch({ config: { agg: null } });
    else patch({ config: { agg: v } });
  };
  const onSeriesModeChange = (e) => patch({ config: { chart_series_mode: e.target.value } });
  const patchUploadConfig = async (next) => {
    await patch({ config: next });
  };
  const insertContentSnippet = (snippet) => {
    const ta = contentRef.current;
    const value = draft.content || "";
    const start = ta ? ta.selectionStart : value.length;
    const next = value.slice(0, start) + snippet + value.slice(start);
    setDraft((d) => ({ ...d, content: next }));
    requestAnimationFrame(() => {
      if (!contentRef.current) return;
      contentRef.current.focus();
      contentRef.current.setSelectionRange(
        start + snippet.length,
        start + snippet.length
      );
    });
  };
  const uploadContentMedia = async (file, kind) => {
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
      insertContentSnippet(`\n\n![${alt}](${data.url})\n\n`);
      toast.success("Obrázek byl nahrán.");
    } catch (e) {
      toast.error(
        e?.response?.data?.detail || e.message || "Obrázek se nepodařilo nahrát."
      );
    } finally {
      setUploadingMedia(null);
    }
  };
  const generateAiCaption = async () => {
    if (!widget?.type || isTextWidget) return;
    setAiCaptionLoading(true);
    setAiCaptionReason("");
    try {
      const { data } = await api.post("/homepage/ai-commentary", {
        id: widget.id,
        type: widget.type,
        title: widget.title || "",
        width: widget.width || "full",
        config: widget.config || {},
        prompt: aiPrompt.trim(),
      });
      const text = stripAiCaptionNoise((data && data.text) || "");
      if (text) {
        setDraft((d) => ({ ...d, caption: text }));
        toast.success("AI popisek byl vložen do pole.");
      } else {
        setAiCaptionReason((data && data.reason) || "AI nevrátila žádný popisek.");
      }
    } catch (e) {
      setAiCaptionReason(
        e?.response?.data?.detail || e.message || "AI popisek se nepodařilo vygenerovat."
      );
    } finally {
      setAiCaptionLoading(false);
    }
  };
  const saveText = async () => {
    const ok = await patch({
      config: isTextWidget
        ? {
            heading: draft.heading,
            subheading: draft.subheading,
            content: draft.content,
          }
        : { caption: draft.caption },
    });
    if (ok) {
      toast.success(isTextWidget ? "Text byl uložen." : "Popisek byl uložen.");
      setOpen(false);
    }
  };

  return (
    <>
      <WidgetEmbedSnippetModal
        open={embedOpen}
        onClose={() => setEmbedOpen(false)}
        shareToken={pageShareToken}
        widgetId={widget?.id}
        widgetTitle={widget?.title}
      />
    <div
      className={`absolute -top-3 right-2 ${open ? "z-[90]" : "z-30"} flex flex-nowrap items-center justify-end gap-1.5 shrink-0`}
    >
      <button
        type="button"
        draggable
        onDragStart={(e) => {
          e.dataTransfer.setData("text/plain", widget.id);
          e.dataTransfer.effectAllowed = "move";
          onDragStart?.();
        }}
        onDragEnd={onDragEnd}
        title="Přesunout widget: puste do středu jiného = prohodit pozice, k okraji = vložit vedle / nad / pod"
        className={`${open ? "hidden" : "group flex"} h-7 w-7 shrink-0 items-center justify-center rounded-md border border-border/60 bg-white/90 text-slate-500 shadow-sm backdrop-blur hover:bg-white hover:text-slate-800 cursor-grab active:cursor-grabbing`}
      >
        <GripVertical className="h-3.5 w-3.5" strokeWidth={1.75} />
      </button>

      <div className="relative flex flex-row flex-nowrap items-center gap-1.5 shrink-0">
        {canShowEmbed && !open ? (
          <button
            type="button"
            onClick={() => setEmbedOpen(true)}
            title="Kód pro vložení grafu do článku"
            className="flex h-7 shrink-0 items-center gap-1 px-2 rounded-md border text-[10px] uppercase tracking-wider font-semibold shadow-sm backdrop-blur bg-white/90 text-slate-600 border-border/60 hover:bg-white"
          >
            <Code2 className="h-3 w-3" strokeWidth={2} />
            <span>Embed</span>
          </button>
        ) : null}
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          title="Upravit vzhled widgetu"
          className={`${open ? "hidden" : "flex"} h-7 shrink-0 items-center gap-1 px-2 rounded-md border text-[10px] uppercase tracking-wider font-semibold shadow-sm backdrop-blur ${
            open
              ? "bg-[hsl(var(--primary))] text-white border-transparent"
              : "bg-white/90 text-slate-600 border-border/60 hover:bg-white"
          }`}
        >
          {saving ? (
            <LoadingSpinner suppressAria size="xs" aria-label="" className="text-slate-600" />
          ) : (
            <LayoutGrid className="h-3 w-3" strokeWidth={2} />
          )}
          <span>Upravit</span>
        </button>

        {onRequestDelete && !open ? (
          <button
            type="button"
            onClick={onRequestDelete}
            title="Odstranit widget"
            aria-label="Odstranit widget"
            className="flex h-7 shrink-0 items-center gap-1 px-2 rounded-md border text-[10px] uppercase tracking-wider font-semibold shadow-sm backdrop-blur bg-white/90 text-rose-600 border-rose-200 hover:bg-rose-50 hover:text-rose-700"
          >
            <Trash2 className="h-3 w-3" strokeWidth={2} />
            <span>Smazat</span>
          </button>
        ) : null}

        {open && (
          <div className="fixed left-3 right-3 top-24 md:top-32 z-[100] md:left-auto md:right-6 md:w-[min(24rem,calc(100vw-18.5rem))] w-auto max-w-none rounded-lg border border-border/70 bg-white/98 p-3 shadow-lg backdrop-blur max-h-[calc(100dvh-6rem)] md:max-h-[calc(100vh-9.5rem)] overflow-y-auto">
            <div className="mb-3 flex items-center justify-between gap-3 border-b border-border/60 pb-2">
              <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-slate-500">
                Upravit widget
              </div>
              <button
                type="button"
                onClick={() => setOpen(false)}
                className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-border/60 bg-white text-slate-500 hover:bg-slate-100 hover:text-slate-800"
                title="Zavřít úpravy"
              >
                <X className="h-3.5 w-3.5" strokeWidth={2} />
              </button>
            </div>
            {showDashboardPagePicker ? (
              <>
                <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-slate-500 mb-1.5">
                  Stránka dashboardu
                </div>
                <select
                  value={widgetDashboardPageId}
                  onChange={(e) => {
                    const nextPageId = String(e.target.value || "").trim();
                    if (!nextPageId || nextPageId === widgetDashboardPageId) return;
                    void patch({ page_id: nextPageId });
                  }}
                  disabled={saving}
                  className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white mb-3"
                  title="Na které stránce osobního dashboardu se widget zobrazí"
                >
                  {dashboardPages.map((page) => (
                    <option key={page.id} value={page.id}>
                      {page.title}
                      {page.is_default ? " ★" : ""}
                    </option>
                  ))}
                </select>
              </>
            ) : null}
            <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-slate-500 mb-1.5">
              Šířka
            </div>
            <select
              value={width}
              onChange={onWidthChange}
              className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white mb-3"
            >
              {WIDTH_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>

            <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-slate-500 mb-1.5">
              Výška (orientace)
            </div>
            <select
              value={rowSpanVal}
              onChange={onRowsChange}
              className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white mb-3"
            >
              {HEIGHT_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>

            {isRssWidget && (
              <>
                <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-slate-500 mb-1.5">
                  Název widgetu
                </div>
                <div className="flex gap-1.5 mb-3">
                  <input
                    value={titleDraft}
                    onChange={(e) => setTitleDraft(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") patch({ title: titleDraft });
                    }}
                    className="flex-1 h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    placeholder="Název widgetu"
                    maxLength={200}
                  />
                  <button
                    type="button"
                    disabled={saving}
                    onClick={() => patch({ title: titleDraft })}
                    className="h-8 px-3 rounded-md text-xs font-semibold bg-[hsl(var(--primary))] text-white disabled:opacity-60 whitespace-nowrap"
                  >
                    Uložit
                  </button>
                </div>
              </>
            )}

            <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-slate-500 mb-1.5">
              Pozadí karty
            </div>
            <select
              value={panelStyle}
              onChange={onPanelStyleChange}
              className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white mb-3"
            >
              {PANEL_STYLE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>

            {isAdImage && (
              <div className="mb-3">
                <AdImageFramingBlock
                  cfg={adCfg}
                  onApply={(p) => patch({ config: { ...(widget?.config || {}), ...p } })}
                  previewImageUrl={adFramingPreviewUrl || undefined}
                  introVariant="compact"
                />
              </div>
            )}

            {!isAdWidget && (
              <>
            <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-slate-500 mb-1.5 flex items-center gap-1">
              <Palette className="h-3 w-3" /> Barva grafu
            </div>
            <div className="grid grid-cols-5 gap-1.5">
              {CHART_COLOR_PRESETS.map((opt) => {
                const active =
                  (opt.value || "") === (chartColor || "") ||
                  (opt.value === "" && !chartColor);
                return (
                  <button
                    key={opt.value || "default"}
                    type="button"
                    title={opt.label}
                    onClick={() => onColorPick(opt.value)}
                    className={`relative h-8 w-full rounded-md border transition-all ${
                      active
                        ? "ring-2 ring-[hsl(var(--primary))] ring-offset-1 border-transparent"
                        : "border-border/60 hover:border-slate-400"
                    }`}
                    style={{ background: opt.swatch }}
                  >
                    {active && (
                      <Check className="absolute inset-0 m-auto h-4 w-4 text-white drop-shadow" />
                    )}
                  </button>
                );
              })}
            </div>
            <div className="text-[10px] text-slate-400 mt-2 leading-snug">
              Kliknutím vybereš odstín. Výchozí = globální barva aplikace.
              Změny se ukládají ihned a vidí je všichni návštěvníci.
            </div>
              </>
            )}

            {supportsChartOptions && (
              <div className="mt-3 rounded-md border border-border/60 bg-slate-50/70 p-2.5 space-y-2">
                <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500">
                  Režim grafu
                </div>
                {!isUserUploadChart && originalIndicatorName && (
                  <div className="rounded-md border border-border/60 bg-white p-2">
                    <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500 mb-0.5">
                      Aktuální indikátor (originální název)
                    </div>
                    <div className="text-[11px] text-slate-700 leading-snug break-words">
                      {originalIndicatorName}
                    </div>
                  </div>
                )}
                <div>
                  <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500 mb-1">
                    Výchozí zobrazení
                  </div>
                  <select
                    value={defaultDataViewValue}
                    onChange={onDefaultDataViewChange}
                    className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                  >
                    <option value="chart">Graf</option>
                    <option value="table">Tabulka</option>
                  </select>
                  <div className="text-[10px] text-slate-500 mt-1 leading-snug">
                    První karta po načtení stránky; návštěvník může přepnout v hlavičce widgetu.
                  </div>
                </div>
                <div>
                  <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500 mb-1">
                    Typ grafu
                  </div>
                  <select
                    value={chartTypeSelectValue}
                    onChange={onChartTypeChange}
                    className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                  >
                    {CHART_TYPE_SELECT_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                  <div className="text-[10px] text-slate-500 mt-1 leading-snug">
                    {CHART_TYPE_IDS.has(rawChartType)
                      ? "Vlastní typ widgetu — ukládá se ihned, graf se hned přerenderuje."
                      : `Dědí výchozí typ ze stránky (${CHART_TYPE_SELECT_OPTIONS.find((o) => o.value === chartTypeSelectValue)?.label || chartTypeSelectValue}). Změnou nastavíte vlastní typ pro tento widget.`}
                  </div>
                </div>
                {supportsFrequencyOptions && (
                  <div>
                    <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500 mb-1">
                      Výchozí frekvence (D / M / Q / R …)
                    </div>
                    <select
                      value={freqSelectValue}
                      onChange={onChartFrequencyChange}
                      className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    >
                      <option value="__auto__">Automaticky (stránka / nativní indikátor)</option>
                      {ALL_FREQS.map((f) => (
                        <option key={f.code} value={f.code}>
                          {f.title} ({f.label})
                        </option>
                      ))}
                    </select>
                    <div className="text-[10px] text-slate-500 mt-1 leading-snug">
                      {hasExplicitFreq
                        ? "Vlastní frekvence widgetu — ukládá se ihned (hrubší než zdroj = agregace)."
                        : `Nyní: ${ALL_FREQS.find((x) => x.code === effectiveChartFreq)?.title || effectiveChartFreq}. Jemnější než zdroj (${nativeChartFreq || "?"}) nelze — ta zůstane u indikátoru.`}
                    </div>
                  </div>
                )}
                {supportsAggregationOptions && (
                  <div>
                    <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500 mb-1">
                      Výchozí agregace
                    </div>
                    <select
                      value={aggSelectValue}
                      onChange={onAggregationChange}
                      className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    >
                      {AGGREGATION_OPTIONS.map((opt) => (
                        <option key={opt.value} value={opt.value}>
                          {opt.label}
                        </option>
                      ))}
                    </select>
                    <div className="text-[10px] text-slate-500 mt-1 leading-snug">
                      {aggSelectValue === "__auto__"
                        ? "Použije se výchozí logika grafu nebo zdroje; u sazeb a indexů typicky průměr."
                        : "Vlastní agregace widgetu — uloží se ihned a použije se při výchozím načtení grafu."}
                    </div>
                  </div>
                )}
                {supportsSeriesModeOptions && (
                  <div>
                    <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500 mb-1">
                      Výchozí řady grafu
                    </div>
                    <select
                      value={seriesModeValue}
                      onChange={onSeriesModeChange}
                      className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    >
                      <option value="single">Samostatný graf</option>
                      <option value="multi">Složený graf / více řad</option>
                    </select>
                    <div className="text-[10px] text-slate-500 mt-1 leading-snug">
                      {seriesModeValue === "multi"
                        ? "Návštěvníkům se načte složený graf, pokud má widget nastavené další řady nebo rozdělení podle dimenze."
                        : "Návštěvníkům se načte jen hlavní řada; uložené další řady zůstanou zachované pro pozdější zapnutí."}
                    </div>
                  </div>
                )}
                {latestDataMode && (
                  <div>
                    <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500 mb-1">
                      Řazení sloupců
                    </div>
                    <select
                      value={chartSortOrder}
                      onChange={onChartSortOrderChange}
                      className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    >
                      {CHART_SORT_OPTIONS.map((opt) => (
                        <option key={opt.value} value={opt.value}>
                          {opt.label}
                        </option>
                      ))}
                    </select>
                    <div className="text-[10px] text-slate-500 mt-1 leading-snug">
                      Platí pro režim posledních údajů, například porovnání zemí od nejvyšší nebo nejnižší hodnoty.
                    </div>
                  </div>
                )}
                {latestDataMode && latestChartLabelRows.length > 0 && (
                  <div>
                    <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500 mb-1">
                      Vlastní názvy položek
                    </div>
                    <div className="max-h-48 overflow-y-auto rounded-md border border-border/60 bg-white p-1.5 space-y-1.5">
                      {latestChartLabelRows.map((label) => (
                        <label key={label} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-1.5 items-center text-[10px] text-slate-600">
                          <span className="truncate" title={label}>{label}</span>
                          <input
                            className="h-7 min-w-0 rounded border border-border/60 px-2 text-[11px] text-slate-800"
                            value={chartLabelOverrides[label] || ""}
                            placeholder={ellipsizeLabel(label, 18)}
                            onChange={(e) => onChartLabelOverrideChange(label, e.target.value)}
                          />
                        </label>
                      ))}
                    </div>
                    <div className="text-[10px] text-slate-500 mt-1 leading-snug">
                      Zadejte krátký název pro dlouhé položky. Prázdné pole vrátí původní název.
                    </div>
                  </div>
                )}
                <label
                  className={`flex items-center justify-between gap-2 text-[11px] text-slate-700 ${wideChartTile ? "opacity-60" : ""}`}
                  title={
                    wideChartTile
                      ? "Na široké nebo celé dlaždici zůstává lišta grafu vždy zapnutá (plná interakce)."
                      : undefined
                  }
                >
                  <span>Skrýt nastavení grafu (frekvence, období, typ)</span>
                  <input
                    type="checkbox"
                    checked={Boolean(widget?.config?.hide_chart_controls)}
                    disabled={wideChartTile}
                    onChange={(e) => onHideControlsToggle(e.target.checked)}
                    className="h-4 w-4 rounded border-border disabled:opacity-60"
                  />
                </label>
                {wideChartTile ? (
                  <div className="text-[10px] text-slate-500 -mt-1 leading-snug">
                    U celé nebo široké dlaždice se ovládací prvky grafu neukládají jako skryté — zůstává plný nástrojový panel.
                  </div>
                ) : null}
                <label className="flex items-center justify-between gap-2 text-[11px] text-slate-700">
                  <span>Mini graf (1/8, 1/6): graf přes celou výšku widgetu</span>
                  <input
                    type="checkbox"
                    checked={miniChartMode}
                    onChange={(e) => onMiniChartToggle(e.target.checked)}
                    className="h-4 w-4 rounded border-border"
                  />
                </label>
                <div className="text-[10px] text-slate-500">
                  Mini režim zjednoduší titulek a skryje jen automatický popisek (jednotka, výpočet); ruční popisek
                  pod grafem zůstane. Plocha grafu vyplní zbytek karty pod hlavičkou.
                </div>
                <label className="flex items-center justify-between gap-2 text-[11px] text-slate-700">
                  <span>Popisky hodnot u sloupců</span>
                  <input
                    type="checkbox"
                    checked={barLabelsEnabled}
                    onChange={(e) => onBarLabelsToggle(e.target.checked)}
                    className="h-4 w-4 rounded border-border"
                  />
                </label>
                <div className="text-[10px] text-slate-500">
                  U průřezových sloupcových grafů (např. poslední údaje podle zemí) jsou popisky zapnuté automaticky;
                  zde je můžete vypnout nebo zapnout i pro jiné sloupcové grafy.
                </div>
                <div className="mt-1 rounded-md border border-border/60 bg-white p-2 space-y-1.5">
                  <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500">
                    Referenční linie
                  </div>
                  <label className="flex items-center justify-between gap-2 text-[11px] text-slate-700">
                    <span>Průměrná linka</span>
                    <input
                      type="checkbox"
                      checked={avgLineEnabled}
                      onChange={(e) => onAvgLineToggle(e.target.checked)}
                      className="h-4 w-4 rounded border-border"
                    />
                  </label>
                  <label className="flex items-center justify-between gap-2 text-[11px] text-slate-700">
                    <span>Mediánová linka</span>
                    <input
                      type="checkbox"
                      checked={medianLineEnabled}
                      onChange={(e) => onMedianLineToggle(e.target.checked)}
                      className="h-4 w-4 rounded border-border"
                    />
                  </label>
                  <label className="flex items-center justify-between gap-2 text-[11px] text-slate-700">
                    <span>Trendová linka</span>
                    <input
                      type="checkbox"
                      checked={trendLineEnabled}
                      onChange={(e) => onTrendLineToggle(e.target.checked)}
                      className="h-4 w-4 rounded border-border"
                    />
                  </label>
                  <div className="text-[10px] text-slate-500 leading-snug">
                    Linky se počítají z právě zobrazeného období (respektují filtr období i zoom osy X).
                  </div>
                </div>
                <label className="flex items-center justify-between gap-2 text-[11px] text-slate-700">
                  <span>Výrazný titulek grafu</span>
                  <input
                    type="checkbox"
                    checked={chartTitleEmphasis}
                    onChange={(e) => onChartTitleEmphasisToggle(e.target.checked)}
                    className="h-4 w-4 rounded border-border"
                  />
                </label>
                <div className="text-[10px] text-slate-500">
                  Větší a tučnější písmo, barevný pás podle zvolené barvy grafu — vhodné pro dlouhé názvy indikátorů na
                  dashboardu.
                </div>
              </div>
            )}

            {isUserUploadChart && (
              <div className="mt-3 rounded-md border border-border/60 bg-slate-50/70 p-2.5 space-y-2">
                <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500">
                  Datová konfigurace (Graf z mých dat)
                </div>
                {uploadPreviewLoading ? (
                  <div className="text-[11px] text-slate-500">Načítám sloupce nahraného souboru…</div>
                ) : uploadPreviewError ? (
                  <div className="text-[11px] text-amber-700">{uploadPreviewError}</div>
                ) : null}

                <label className="block text-[11px] text-slate-700">
                  Režim řad
                  <select
                    value={uploadChartMode}
                    onChange={(e) => patchUploadConfig({ chart_mode: e.target.value })}
                    className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                  >
                    <option value="single">Jeden sloupec Y</option>
                    <option value="multi">Více sloupců — složený graf</option>
                  </select>
                </label>

                <label className="block text-[11px] text-slate-700">
                  Sloupec X
                  {uploadAllColumns.length > 0 ? (
                    <select
                      value={uploadXField}
                      onChange={(e) => patchUploadConfig({ x_field: e.target.value })}
                      className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    >
                      {uploadAllColumns.map((c) => (
                        <option key={`ux-${c}`} value={c}>
                          {c}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <input
                      value={uploadXField}
                      onChange={(e) => patchUploadConfig({ x_field: e.target.value })}
                      className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                      placeholder="např. date"
                    />
                  )}
                </label>

                {uploadChartMode === "multi" ? (
                  <div>
                    <div className="text-[11px] text-slate-700 mb-1">Sloupce Y v grafu (min. 2)</div>
                    <div className="max-h-32 overflow-y-auto rounded-md border border-border/60 bg-white p-2 space-y-1">
                      {uploadAllColumns.filter((c) => c !== uploadXField).map((c) => (
                        <label key={`my-${c}`} className="flex items-center gap-2 text-[11px] text-slate-700">
                          <input
                            type="checkbox"
                            checked={uploadYFields.includes(c)}
                            onChange={() => {
                              const next = uploadYFields.includes(c)
                                ? uploadYFields.filter((v) => v !== c)
                                : [...uploadYFields, c];
                              patchUploadConfig({ y_fields: next, chart_mode: "multi" });
                            }}
                            className="h-3.5 w-3.5 rounded border-border"
                          />
                          <span className="font-mono text-[11px]">{c}</span>
                        </label>
                      ))}
                    </div>
                  </div>
                ) : (
                  <>
                    <label className="block text-[11px] text-slate-700">
                      Režim Y
                      <select
                        value={uploadYMode}
                        onChange={(e) => patchUploadConfig({ y_mode: e.target.value, chart_mode: "single" })}
                        className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                      >
                        <option value="single">Jeden sloupec</option>
                        <option value="binary">Výpočet A/B</option>
                      </select>
                    </label>

                    <label className="block text-[11px] text-slate-700">
                      Sloupec Y (A)
                      {uploadAllColumns.length > 0 ? (
                        <select
                          value={uploadYField}
                          onChange={(e) => patchUploadConfig({ y_field: e.target.value, chart_mode: "single" })}
                          className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                        >
                          {uploadAllColumns.map((c) => (
                            <option key={`uy-${c}`} value={c}>
                              {c}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <input
                          value={uploadYField}
                          onChange={(e) => patchUploadConfig({ y_field: e.target.value, chart_mode: "single" })}
                          className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                          placeholder="např. value"
                        />
                      )}
                    </label>

                    {uploadYMode === "binary" && (
                      <>
                        <label className="block text-[11px] text-slate-700">
                          Operátor
                          <select
                            value={uploadYOp}
                            onChange={(e) => patchUploadConfig({ y_op: e.target.value, y_mode: "binary" })}
                            className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                          >
                            {UPLOAD_Y_OPS.map((o) => (
                              <option key={o.id} value={o.id}>
                                {o.label}
                              </option>
                            ))}
                          </select>
                        </label>
                        <label className="block text-[11px] text-slate-700">
                          Sloupec Y (B)
                          {uploadAllColumns.length > 0 ? (
                            <select
                              value={uploadYFieldB}
                              onChange={(e) => patchUploadConfig({ y_field_b: e.target.value, y_mode: "binary" })}
                              className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                            >
                              {uploadAllColumns.map((c) => (
                                <option key={`uyb-${c}`} value={c}>
                                  {c}
                                </option>
                              ))}
                            </select>
                          ) : (
                            <input
                              value={uploadYFieldB}
                              onChange={(e) => patchUploadConfig({ y_field_b: e.target.value, y_mode: "binary" })}
                              className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                              placeholder="např. qty"
                            />
                          )}
                        </label>
                      </>
                    )}
                  </>
                )}

                <label className="block text-[11px] text-slate-700">
                  Agregace podle X
                  <select
                    value={uploadAgg}
                    onChange={(e) => patchUploadConfig({ agg: e.target.value })}
                    className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                  >
                    {UPLOAD_AGG_OPTIONS.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block text-[11px] text-slate-700">
                  Periodicita osy X
                  <select
                    value={uploadChartFrequency || "__auto__"}
                    onChange={(e) =>
                      patchUploadConfig({
                        chart_frequency: e.target.value === "__auto__" ? null : e.target.value,
                      })
                    }
                    className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                  >
                    {UPLOAD_FREQUENCY_OPTIONS.map((f) => (
                      <option key={f.id} value={f.id}>
                        {f.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block text-[11px] text-slate-700">
                  Výchozí typ grafu
                  <select
                    value={uploadChartType}
                    onChange={(e) =>
                      patchUploadConfig({
                        chart_type: e.target.value,
                      })
                    }
                    className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                  >
                    {(uploadChartMode === "multi"
                      ? CHART_TYPE_SELECT_OPTIONS.filter((opt) => opt.value !== "pie")
                      : CHART_TYPE_SELECT_OPTIONS
                    ).map((opt) => (
                      <option key={`uct-${opt.value}`} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block text-[11px] text-slate-700">
                  Jednotka hodnot (tooltip/osa Y)
                  <input
                    value={uploadUnit}
                    onChange={(e) =>
                      patchUploadConfig({
                        unit: String(e.target.value || "").trim() || null,
                      })
                    }
                    className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    placeholder="např. mld. Kč, mil. Kč, %, ks"
                  />
                </label>
                {uploadChartType === "bar" && (
                  <label className="block text-[11px] text-slate-700">
                    Řazení sloupců
                    <select
                      value={uploadChartSortOrder}
                      onChange={(e) =>
                        patchUploadConfig({
                          chart_sort_order: e.target.value,
                        })
                      }
                      className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    >
                      {CHART_SORT_OPTIONS.map((opt) => (
                        <option key={`ucs-${opt.value}`} value={opt.value}>
                          {opt.label}
                        </option>
                      ))}
                    </select>
                  </label>
                )}
                <div className="text-[10px] text-slate-500">
                  Tip: když graf po změně vrací 0 bodů, zkontrolujte kombinaci X (čas/období) a Y (číselný sloupec).
                </div>
              </div>
            )}

            {isCsuDatasetChart && !isUserUploadChart && (
              <div className="mt-3 rounded-md border border-border/60 bg-slate-50/70 p-2.5 space-y-2">
                <div className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500">
                  Konfigurace grafu (ČSÚ / katalog)
                </div>

                {/* Série: Indikátory vs. Kraje */}
                <label className="block text-[11px] text-slate-700">
                  Zobrazit série jako
                  <div className="mt-1 flex gap-1">
                    <button
                      type="button"
                      onClick={() => patchCsuConfig({ chart_series_dim: null, agg: csuCfg.agg || "avg" })}
                      className={`flex-1 h-7 rounded-md text-[11px] border ${!csuCfg.chart_series_dim ? "bg-blue-600 text-white border-blue-700" : "bg-white text-slate-700 border-border/60 hover:bg-slate-50"}`}
                    >
                      Indikátory
                    </button>
                    {dataFields.some(f => /kraj|uzemi.kraj|region/i.test(f.replace(/[^a-zA-Z]/g, ""))) || csuCfg.chart_series_dim ? (
                      <button
                        type="button"
                        onClick={() => {
                          const krajField = dataFields.find(f => /kraj|uzemi.kraj|region/i.test(f.replace(/[^a-zA-Z]/g, ""))) || csuCfg.chart_series_dim || "ÚZEMÍ-KRAJ";
                          patchCsuConfig({ chart_series_dim: krajField, agg: "avg" });
                        }}
                        className={`flex-1 h-7 rounded-md text-[11px] border ${csuCfg.chart_series_dim ? "bg-blue-600 text-white border-blue-700" : "bg-white text-slate-700 border-border/60 hover:bg-slate-50"}`}
                      >
                        Kraje
                      </button>
                    ) : null}
                  </div>
                  {csuCfg.chart_series_dim && (
                    <div className="mt-1 text-[10px] text-slate-500">
                      Dimenze: <span className="font-mono">{csuCfg.chart_series_dim}</span>
                    </div>
                  )}
                </label>

                {/* Filtr indikátoru (series_value) — jen pokud group_field dostupný */}
                {(dataGroupField || csuCfg.series_field) && csuCfg.chart_series_dim && (
                  <label className="block text-[11px] text-slate-700">
                    Indikátor (série Kraje)
                    <input
                      value={csuCfg.series_value || ""}
                      onChange={(e) => patchCsuConfig({ series_value: e.target.value })}
                      className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                      placeholder={`ID indikátoru, např. ${csuCfg.series_value || "…"}`}
                    />
                    <div className="mt-0.5 text-[10px] text-slate-400">Pole: {csuCfg.series_field || dataGroupField}</div>
                  </label>
                )}

                {/* Agregace */}
                <label className="block text-[11px] text-slate-700">
                  Agregace
                  <select
                    value={csuCfg.agg || "avg"}
                    onChange={(e) => patchCsuConfig({ agg: e.target.value })}
                    className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                  >
                    <option value="avg">Průměr (doporučeno pro ČSÚ indexy)</option>
                    <option value="sum">Součet</option>
                    <option value="last">Poslední hodnota</option>
                    <option value="max">Maximum</option>
                    <option value="min">Minimum</option>
                  </select>
                </label>

                {/* Osa X override */}
                <label className="block text-[11px] text-slate-700">
                  Osa X (časový sloupec)
                  {dataFields.length > 0 ? (
                    <select
                      value={csuCfg.x_field || ""}
                      onChange={(e) => patchCsuConfig({ x_field: e.target.value || null })}
                      className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    >
                      <option value="">— auto detekce —</option>
                      {dataFields.map(f => <option key={f} value={f}>{f}</option>)}
                    </select>
                  ) : (
                    <input
                      value={csuCfg.x_field || ""}
                      onChange={(e) => patchCsuConfig({ x_field: e.target.value || null })}
                      className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                      placeholder="např. Roky, date, CasR"
                    />
                  )}
                </label>

                {/* Osa Y override */}
                <label className="block text-[11px] text-slate-700">
                  Osa Y (číselný sloupec)
                  {dataFields.length > 0 ? (
                    <select
                      value={csuCfg.y_field || ""}
                      onChange={(e) => patchCsuConfig({ y_field: e.target.value || null })}
                      className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    >
                      <option value="">— auto detekce —</option>
                      {dataFields.map(f => <option key={f} value={f}>{f}</option>)}
                    </select>
                  ) : (
                    <input
                      value={csuCfg.y_field || ""}
                      onChange={(e) => patchCsuConfig({ y_field: e.target.value || null })}
                      className="mt-1 w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                      placeholder="např. Hodnota, value, amount"
                    />
                  )}
                </label>

                <div className="text-[10px] text-slate-400">
                  Tip: přepněte widget na Tabulku → uvidíte přesné názvy sloupců → zadejte je sem.
                </div>
              </div>
            )}

            <div className="mt-4 pt-3 border-t border-border/60">
              <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-slate-500 mb-2 flex items-center gap-1">
                <FileText className="h-3 w-3" /> {isTextWidget ? "Textové pole" : "Popisek"}
              </div>

              {isTextWidget ? (
                <div className="space-y-2">
                  <input
                    value={draft.heading}
                    onChange={(e) => setDraft((d) => ({ ...d, heading: e.target.value }))}
                    className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    placeholder="Nadpis textového pole"
                  />
                  <input
                    value={draft.subheading}
                    onChange={(e) => setDraft((d) => ({ ...d, subheading: e.target.value }))}
                    className="w-full h-8 text-xs border border-border/60 rounded-md px-2 bg-white"
                    placeholder="Podnadpis"
                  />
                  <div className="flex flex-wrap items-center gap-1 rounded-t-md border border-border/60 border-b-0 bg-slate-50/80 px-1.5 py-1">
                    <button
                      type="button"
                      onClick={() => imageInputRef.current?.click()}
                      disabled={!!uploadingMedia}
                      className="h-6 rounded border border-border/60 bg-white px-2 text-[10px] font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-60"
                    >
                      {uploadingMedia === "image" ? "Nahrávám…" : "Obrázek"}
                    </button>
                    <button
                      type="button"
                      onClick={() => medallionInputRef.current?.click()}
                      disabled={!!uploadingMedia}
                      className="h-6 rounded border border-border/60 bg-white px-2 text-[10px] font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-60"
                    >
                      {uploadingMedia === "medallion" ? "Nahrávám…" : "Medailonek"}
                    </button>
                    <input
                      ref={imageInputRef}
                      type="file"
                      accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml"
                      className="hidden"
                      onChange={(e) => {
                        uploadContentMedia(e.target.files?.[0], "image");
                        e.target.value = "";
                      }}
                    />
                    <input
                      ref={medallionInputRef}
                      type="file"
                      accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml"
                      className="hidden"
                      onChange={(e) => {
                        uploadContentMedia(e.target.files?.[0], "medallion");
                        e.target.value = "";
                      }}
                    />
                    <span className="text-[10px] text-slate-400">
                      Soubor se uloží do Cloudinary.
                    </span>
                  </div>
                  <textarea
                    ref={contentRef}
                    value={draft.content}
                    onChange={(e) => setDraft((d) => ({ ...d, content: e.target.value }))}
                    className="w-full min-h-[110px] text-xs border border-border/60 rounded-b-md px-2 py-2 bg-white resize-y"
                    placeholder="Text, markdown nebo URL obrázku"
                  />
                </div>
              ) : (
                <div className="space-y-2">
                  {isSiteAdmin ? (
                    <>
                      <div className="flex flex-wrap items-center gap-1.5">
                        <button
                          type="button"
                          onClick={generateAiCaption}
                          disabled={aiCaptionLoading}
                          aria-busy={aiCaptionLoading ? "true" : undefined}
                          className="inline-flex h-8 items-center gap-1.5 rounded-md border border-border/70 bg-white px-3 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
                        >
                          {aiCaptionLoading ? (
                            <LoadingSpinner suppressAria size="xs" aria-label="" />
                          ) : (
                            <Sparkles className="h-3.5 w-3.5" strokeWidth={1.5} />
                          )}
                          {aiCaptionLoading ? "Generuji…" : "Vygenerovat AI popisek"}
                        </button>
                        <button
                          type="button"
                          onClick={() => setAiPromptOpen((v) => !v)}
                          className={`inline-flex h-8 items-center gap-1 rounded-md border px-2 text-[11px] font-medium ${
                            aiPromptOpen || aiPrompt
                              ? "border-[hsl(var(--primary))] bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]"
                              : "border-border/60 bg-white text-slate-600 hover:bg-slate-50"
                          }`}
                          title="Vlastní instrukce pro AI (např. zaměř se na meziroční změnu)"
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
                              'Vlastní instrukce pro AI – např. „Zaměř se na meziroční změnu", „Vysvětli v kontextu inflace", „Přidej porovnání s eurozónou"…'
                            }
                          />
                          <div className="mt-1 flex items-center justify-between gap-2">
                            <p className="text-[10px] leading-snug text-slate-500">
                              AI dál drží fakta z dat, ale přizpůsobí zaměření a tón. Max ~600 znaků.
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
                      {aiCaptionReason ? (
                        <div className="text-[11px] leading-snug text-rose-600">
                          {aiCaptionReason}
                        </div>
                      ) : null}
                    </>
                  ) : null}
                  <textarea
                    value={draft.caption}
                    onChange={(e) => setDraft((d) => ({ ...d, caption: e.target.value }))}
                    className="w-full min-h-[82px] text-xs border border-border/60 rounded-md px-2 py-2 bg-white resize-y"
                    placeholder="Popisek grafu nebo tabulky (volitelné)"
                  />
                </div>
              )}

              <button
                type="button"
                onClick={saveText}
                disabled={saving}
                className="mt-2 w-full h-8 rounded-md text-xs font-semibold bg-[hsl(var(--primary))] text-white disabled:opacity-60"
              >
                {saving ? "Ukládám…" : isTextWidget ? "Uložit text" : "Uložit"}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
    </>
  );
}
