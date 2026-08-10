import React, { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { ChevronLeft, ChevronRight, MessageSquare, X } from "lucide-react";
import WidgetRenderer from "@/components/widgets/WidgetRenderer";
import DashboardAiChat from "@/components/myDashboard/DashboardAiChat";
import { getPresentableDashboardWidgets } from "@/lib/dashboardChartContracts";

function PresentationButton({ children, onClick, disabled, title }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={title}
      className="inline-flex h-9 items-center justify-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 text-sm font-semibold text-slate-700 shadow-sm transition hover:bg-slate-50 disabled:opacity-45"
    >
      {children}
    </button>
  );
}

export default function DashboardPresentationMode({
  open,
  widgets = [],
  pageId = "",
  pageTitle = "Dashboard",
  defaultChartType = "line",
  defaultChartFrequency,
  onClose,
}) {
  const slides = useMemo(() => getPresentableDashboardWidgets(widgets), [widgets]);
  const [index, setIndex] = useState(0);
  const [chatOpen, setChatOpen] = useState(true);
  const current = slides[index] || null;

  useEffect(() => {
    if (!open) return;
    setIndex((prev) => Math.min(Math.max(prev, 0), Math.max(slides.length - 1, 0)));
  }, [open, slides.length]);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event) => {
      const tag = String(event.target?.tagName || "").toLowerCase();
      if (["input", "textarea", "select"].includes(tag) || event.target?.isContentEditable) return;
      if (event.key === "Escape") {
        event.preventDefault();
        onClose?.();
      } else if (event.key === "ArrowRight" || event.key === "PageDown") {
        event.preventDefault();
        setIndex((prev) => Math.min(prev + 1, Math.max(slides.length - 1, 0)));
      } else if (event.key === "ArrowLeft" || event.key === "PageUp") {
        event.preventDefault();
        setIndex((prev) => Math.max(prev - 1, 0));
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose, open, slides.length]);

  if (!open || typeof document === "undefined") return null;

  const slideWidget = current
    ? {
        ...current,
        width: "full",
        config: {
          ...(current.config || {}),
          mini_chart: false,
        },
      }
    : null;

  return createPortal(
    <div className="fixed inset-0 z-[310] flex flex-col bg-slate-950 text-slate-100" data-testid="dashboard-presentation-mode">
      <header className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-white/10 bg-slate-950/95 px-4 py-3">
        <div className="min-w-0">
          <div className="text-[11px] font-semibold uppercase tracking-wide text-sky-200">Prezentační mód</div>
          <h2 className="truncate text-base font-semibold text-white">{pageTitle}</h2>
        </div>
        <div className="flex items-center gap-2" data-export-ignore="true">
          <span className="rounded-full bg-white/10 px-2.5 py-1 text-xs font-semibold text-slate-100">
            {slides.length ? index + 1 : 0} / {slides.length}
          </span>
          <PresentationButton
            onClick={() => setIndex((prev) => Math.max(prev - 1, 0))}
            disabled={index <= 0}
            title="Předchozí graf"
          >
            <ChevronLeft className="h-4 w-4" />
            Zpět
          </PresentationButton>
          <PresentationButton
            onClick={() => setIndex((prev) => Math.min(prev + 1, Math.max(slides.length - 1, 0)))}
            disabled={index >= slides.length - 1}
            title="Další graf"
          >
            Další
            <ChevronRight className="h-4 w-4" />
          </PresentationButton>
          <button
            type="button"
            onClick={() => setChatOpen((value) => !value)}
            className="inline-flex h-9 items-center justify-center gap-1.5 rounded-lg border border-sky-300/50 bg-sky-500/15 px-3 text-sm font-semibold text-sky-100 transition hover:bg-sky-500/25"
            title={chatOpen ? "Skrýt AI chat" : "Zobrazit AI chat"}
          >
            <MessageSquare className="h-4 w-4" />
            AI chat
          </button>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-white/15 bg-white/10 text-white transition hover:bg-white/20"
            aria-label="Zavřít prezentační mód"
            title="Zavřít"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      </header>

      <main className="min-h-0 flex-1 overflow-hidden bg-slate-100 p-4">
        {slideWidget ? (
          <section className="mx-auto flex h-full max-w-[1600px] flex-col overflow-hidden rounded-lg bg-white text-slate-900 shadow-2xl">
            <div className="min-h-0 flex-1 p-3">
              <WidgetRenderer
                w={slideWidget}
                defaultChartType={defaultChartType}
                defaultChartFrequency={defaultChartFrequency}
                aradMultiSeriesHelpContext="personal_dashboard_presentation"
                readOnlyDashboardView
                kpiSummaryMode="expanded"
              />
            </div>
          </section>
        ) : (
          <div className="flex h-full items-center justify-center rounded-lg border border-dashed border-slate-300 bg-white text-sm text-slate-600">
            Na této stránce zatím nejsou grafové widgety vhodné pro prezentaci.
          </div>
        )}
      </main>

      {chatOpen ? (
        <div className="shrink-0 border-t border-white/10 bg-slate-900 px-4 py-3">
          <div className="mx-auto max-w-[1600px]">
            <DashboardAiChat
              widgets={slides}
              currentWidget={current}
              pageId={pageId}
              pageTitle={pageTitle}
              defaultScope="current"
              onClose={() => setChatOpen(false)}
            />
          </div>
        </div>
      ) : null}
    </div>,
    document.body,
  );
}

