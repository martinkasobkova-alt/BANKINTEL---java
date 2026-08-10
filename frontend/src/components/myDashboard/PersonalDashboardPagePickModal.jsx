import React, { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { RefreshCw } from "lucide-react";

/** Nad fullscreen náhledem grafu (z-[250]) a detailem řady (z-[86]). */
const PAGE_PICK_MODAL_Z = "z-[270]";

/**
 * Výběr stránky osobního dashboardu (více dashboardů u předplatitele).
 */
export default function PersonalDashboardPagePickModal({
  open = false,
  pages = [],
  selectedId = "",
  onSelectedIdChange,
  onConfirm,
  onCancel,
  loading = false,
  title = "Kam přidat graf?",
  description = "Vyberte stránku osobního dashboardu.",
  confirmLabel = "Přidat",
}) {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!open) return undefined;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKeyDown = (event) => {
      if (event.key === "Escape" && !loading) onCancel?.();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      document.body.style.overflow = prev;
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [open, loading, onCancel]);

  if (!open || !mounted || typeof document === "undefined") return null;

  const list = Array.isArray(pages) ? pages : [];

  return createPortal(
    <div
      className={`fixed inset-0 ${PAGE_PICK_MODAL_Z} flex items-center justify-center p-4 bg-black/45 backdrop-blur-[1px]`}
      role="dialog"
      aria-modal="true"
      aria-labelledby="pick-dash-page-title"
      onClick={(event) => {
        if (event.target === event.currentTarget && !loading) onCancel?.();
      }}
    >
      <div className="bg-card rounded-2xl shadow-xl max-w-md w-full p-5 space-y-4 border border-border">
        <div id="pick-dash-page-title" className="text-sm font-medium text-foreground">
          {title}
        </div>
        <p className="text-xs text-muted-foreground">{description}</p>
        <div className="space-y-2 max-h-56 overflow-y-auto">
          {list.map((p) => (
            <label key={p.id} className="flex items-center gap-2 text-sm cursor-pointer">
              <input
                type="radio"
                name="pick-personal-dash-page"
                checked={selectedId === p.id}
                onChange={() => onSelectedIdChange?.(p.id)}
              />
              <span>
                {p.title}
                {p.is_default ? " ★" : ""}
              </span>
            </label>
          ))}
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <button
            type="button"
            className="h-9 px-3 text-xs rounded-lg border border-border/80 bg-card hover:bg-muted/50"
            onClick={onCancel}
            disabled={loading}
          >
            Zrušit
          </button>
          <button
            type="button"
            className="btn-mint h-9 px-4 text-xs inline-flex items-center gap-1.5 disabled:opacity-50"
            onClick={onConfirm}
            disabled={loading || !selectedId}
          >
            {loading ? <RefreshCw className="h-3.5 w-3.5 animate-spin" /> : null}
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
