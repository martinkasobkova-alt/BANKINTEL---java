import React, { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { X, RefreshCw, Eye } from "lucide-react";
import api from "@/lib/api";
import { BouncingDots } from "@/components/ui/DataLoadIndicator";
import { LoadingBlock, LoadingSpinner } from "@/components/ui/loading";
import WidgetRenderer, { WIDTH_CLS } from "@/components/widgets/WidgetRenderer";

/**
 * Live, full-screen preview of an unsaved homepage / section configuration.
 *
 * Mounts as a portal-like overlay that completely covers the admin shell
 * (sidebar + header). Inside, the layout intentionally mirrors the public
 * page (same background, same widget grid) so admins see *exactly* what
 * end-users will see — but without persisting the draft.
 */
export default function PreviewModal({ open, onClose, doc }) {
  const [rendered, setRendered] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const widgets = useMemo(() => doc?.widgets || [], [doc]);
  const pageDefaultChartType = doc?.default_chart_type || "line";
  const pageDefaultChartFrequency = doc?.default_chart_frequency || undefined;
  const title = doc?.kind === "section" ? (doc?.name || "Sekce") : (doc?.title || "Přehled");
  const subtitle =
    doc?.kind === "section"
      ? (doc?.subtitle || `URL /s/${doc?.slug || ""}`)
      : (doc?.subtitle || "Vaše vybraná data z veřejných portálů");

  const load = async () => {
    if (!open || widgets.length === 0) {
      setRendered([]);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const results = await Promise.all(
        widgets.map(async (w) => {
          try {
            const { data } = await api.post("/homepage/preview", {
              id: w.id,
              type: w.type,
              title: w.title || "",
              width: w.width || "full",
              config: w.config || {},
            });
            return data;
          } catch (e) {
            return {
              id: w.id,
              type: w.type,
              title: w.title || "",
              width: w.width || "full",
              config: w.config || {},
              data: { error: e.response?.data?.detail || e.message || "Chyba náhledu" },
            };
          }
        })
      );
      setRendered(results);
    } catch (e) {
      setError(e.message || "Chyba načítání náhledu");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, JSON.stringify(widgets)]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e) => {
      if (e.key === "Escape") onClose?.();
    };
    document.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
    };
  }, [open, onClose]);

  if (!open) return null;

  // CRITICAL: render via portal directly into <body>. The AppShell's
  // <main> uses `relative z-10`, which creates a stacking context that
  // would otherwise trap this modal *under* the sidebar (z-20) — even
  // though we set z-[200] on the modal itself.
  return createPortal(
    // True full-viewport overlay. Now sits at the document body level,
    // so nothing from the admin chrome bleeds through.
    <div
      className="fixed inset-0 z-[200] flex flex-col"
      style={{
        background: "linear-gradient(180deg, hsl(205 75% 95%) 0%, hsl(205 70% 92%) 100%)",
      }}
    >
      {/* Faint logo watermark, same as the public page, so the preview
          reads as a real page rather than a popup. */}
      <div
        className="pointer-events-none fixed inset-0 opacity-[0.06]"
        aria-hidden
        style={{
          backgroundImage: "url(/bankovnictvi-logo.png)",
          backgroundRepeat: "no-repeat",
          backgroundPosition: "center center",
          backgroundSize: "60% auto",
        }}
      />

      {/* Sticky preview-mode toolbar */}
      <div
        className="relative z-10 flex items-center justify-between gap-4 px-6 md:px-10 py-3 border-b border-border/40"
        style={{ background: "white" }}
      >
        <div className="flex items-center gap-3 min-w-0">
          <span
            className="flex items-center gap-2 px-3 py-1 rounded-full text-[10px] uppercase tracking-[0.18em] font-semibold"
            style={{
              background: "hsl(var(--primary-soft))",
              color: "hsl(var(--primary-deep))",
            }}
          >
            <Eye className="h-3.5 w-3.5" /> Režim náhledu
          </span>
          <span className="text-[12px] text-slate-500 font-mono truncate">
            Žádné změny zatím nejsou uložené · Esc nebo „Zavřít" pro návrat do editoru
          </span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button
            type="button"
            onClick={load}
            disabled={loading}
            aria-busy={loading ? "true" : undefined}
            className="flex items-center gap-2 px-3 h-9 text-sm rounded-md border border-border/70 hover:bg-[hsl(var(--primary-soft))] disabled:opacity-50"
            title="Znovu načíst náhled"
            data-testid="preview-refresh-btn"
          >
            {loading ? <LoadingSpinner suppressAria size="sm" aria-label="" /> : <RefreshCw className="h-4 w-4" strokeWidth={1.5} />}
            Obnovit
          </button>
          <button
            type="button"
            onClick={onClose}
            className="flex items-center gap-2 px-4 h-9 text-sm rounded-md text-white hover:opacity-90"
            style={{
              background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
              boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
            }}
            title="Zavřít náhled (Esc)"
            data-testid="preview-close-btn"
          >
            <X className="h-4 w-4" /> Zavřít náhled
          </button>
        </div>
      </div>

      {/* Scrollable content — mirrors the public page layout exactly. */}
      <div className="relative flex-1 overflow-y-auto">
        <div className="max-w-[1400px] mx-auto px-6 md:px-10 xl:px-14 py-10 xl:py-14">
          {/* Page title block — same typography as AppShell */}
          <div className="mb-8 xl:mb-12">
            <h1 className="font-serif text-[40px] xl:text-[52px] leading-[1.02]">
              {title}
            </h1>
            {subtitle && (
              <div
                className="text-[11px] mt-3 uppercase tracking-[0.2em] font-semibold"
                style={{ color: "hsl(var(--primary))" }}
              >
                {subtitle}
              </div>
            )}
          </div>

          {/* Widgets grid */}
          {widgets.length === 0 ? (
            <div
              className="border border-dashed border-border/70 rounded-xl p-16 text-center text-sm text-slate-500 font-mono"
              style={{ background: "hsl(205 75% 96%)" }}
            >
              Žádné widgety k zobrazení. Přidej v editoru aspoň jeden widget,
              pak otevři náhled znovu.
            </div>
          ) : loading && rendered.length === 0 ? (
            <div className="w-full max-w-full min-w-0 space-y-4 py-8">
              <LoadingBlock
                label="Načítám náhled…"
                minHeightClass="min-h-[200px]"
                showSkeletonLines
                skeletonRows={5}
              />
              <div className="flex justify-center" aria-hidden>
                <BouncingDots />
              </div>
            </div>
          ) : error ? (
            <div className="p-6 rounded-xl border border-red-200 bg-red-50 text-sm text-red-700">
              {error}
            </div>
          ) : (
            <div className="grid grid-cols-1 xl:grid-cols-24 gap-5 xl:gap-6">
              {rendered.map((w) => (
                <div
                  key={w.id}
                  className={`${WIDTH_CLS[w.width] || WIDTH_CLS.full} min-w-0`}
                >
                  <WidgetRenderer
                    w={w}
                    defaultChartType={pageDefaultChartType}
                    defaultChartFrequency={pageDefaultChartFrequency}
                  />
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}
