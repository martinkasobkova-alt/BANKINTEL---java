import React, { useEffect, useRef, useState } from "react";
import { ChevronDown, GitCompare } from "lucide-react";

const ID_KEY_SEPARATOR = "\u0001";

function normalizeIds(input) {
  const list = Array.isArray(input) ? input : input != null ? [input] : [];
  return [...new Set(list.map((v) => String(v || "").trim()).filter(Boolean))];
}

function idsFromKey(key) {
  return key ? key.split(ID_KEY_SEPARATOR).filter(Boolean) : [];
}

function idsEqual(left, right) {
  const a = normalizeIds(left);
  const b = normalizeIds(right);
  if (a.length !== b.length) return false;
  return a.every((id, idx) => id === b[idx]);
}

/**
 * Kompaktní výběr více ukazatelů / dimenzí pro porovnání v grafu.
 */
export default function PreviewGroupCompareDropdown({
  groupFieldLabel = "ukazatel",
  groupField = "",
  indicators = [],
  selectedIds = [],
  selectedIndicator = "",
  onSelectionChange,
  disabled = false,
  compactMobile = false,
  className = "",
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef(null);
  const selectedInput = Array.isArray(selectedIds) && selectedIds.length
    ? selectedIds
    : selectedIndicator
      ? [selectedIndicator]
      : [];
  const selected = normalizeIds(selectedInput);
  const selectedKey = selected.join(ID_KEY_SEPARATOR);
  const [draftSelected, setDraftSelected] = useState(selected);
  const total = indicators.length;
  const compareActive = selected.length > 1;
  const draftChanged = !idsEqual(draftSelected, selected);

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event) => {
      if (rootRef.current && !rootRef.current.contains(event.target)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (!open) setDraftSelected(idsFromKey(selectedKey));
  }, [open, selectedKey]);

  if (!onSelectionChange || total <= 1) return null;

  const toggleId = (id, checked) => {
    const base = draftSelected.length ? draftSelected : selected.length ? selected : normalizeIds(selectedIndicator);
    let next = checked ? [...base, id] : base.filter((x) => x !== id);
    next = normalizeIds(next);
    if (!next.length) return;
    setDraftSelected(next);
  };

  const openDropdown = () => {
    if (!open) setDraftSelected(selected);
    setOpen((wasOpen) => !wasOpen);
  };

  const applyDraft = () => {
    const next = normalizeIds(draftSelected);
    if (!next.length) return;
    if (!idsEqual(next, selected)) onSelectionChange(next);
    setOpen(false);
  };

  return (
    <div ref={rootRef} className={`relative shrink-0 ${className}`.trim()}>
      <button
        type="button"
        disabled={disabled}
        onClick={openDropdown}
        aria-expanded={open}
        aria-haspopup="dialog"
        className={`inline-flex items-center gap-1.5 h-8 px-2.5 text-[11px] rounded-md border transition-colors ${
          compactMobile ? "max-md:h-7 max-md:px-2 max-md:gap-1" : ""
        } ${
          compareActive
            ? "border-sky-300/80 bg-sky-50 text-sky-950 font-medium"
            : "border-border/70 bg-white text-slate-700 hover:bg-slate-50"
        } disabled:opacity-50`}
        title={`Porovnat více položek dimenze ${groupField || groupFieldLabel}`}
        data-testid="preview-group-compare-toggle"
      >
        <GitCompare className="h-3.5 w-3.5 shrink-0" />
        <span className={compactMobile ? "hidden md:inline" : ""}>Porovnání</span>
        <span className={`tabular-nums text-muted-foreground ${compactMobile ? "max-md:text-[10px]" : ""}`}>
          {compareActive ? `${selected.length}/${total}` : compactMobile ? "" : "volitelné"}
        </span>
        {!compareActive && compactMobile ? (
          <span className="md:hidden sr-only">volitelné</span>
        ) : null}
        <ChevronDown className={`h-3.5 w-3.5 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} />
      </button>

      {open ? (
        <div
          className="absolute left-0 top-[calc(100%+0.35rem)] z-40 w-[min(22rem,calc(100vw-2rem))] rounded-xl border border-border/80 bg-card shadow-xl p-3"
          role="dialog"
          aria-label="Porovnání v grafu"
          data-testid="preview-group-compare-panel"
        >
          <div className="text-[11px] font-medium text-foreground">
            Porovnání ({groupFieldLabel.toLowerCase()})
          </div>
          <p className="mt-0.5 text-[10px] text-muted-foreground leading-snug">
            Vyberte položky a potvrďte je tlačítkem Porovnat. Vybráno {draftSelected.length}/{total}.
          </p>
          <div className="mt-2 max-h-48 overflow-y-auto space-y-1 pr-0.5">
            {indicators.map((ind) => {
              const id = String(ind?.id || "").trim();
              if (!id) return null;
              const rawName = String(ind?.name || "").trim();
              const label = rawName && rawName !== id ? rawName : id;
              const count = Number(ind?.count || 0);
              const detailTitle = Number.isFinite(count) && count > 0
                ? `${label} - ${id}, ${count} hodnot`
                : `${label} - ${id}`;
              const checked = draftSelected.includes(id);
              return (
                <label
                  key={id}
                  title={detailTitle}
                  className="flex items-start gap-2 rounded-md border border-border/60 bg-muted/15 px-2 py-1.5 text-[11px] text-foreground cursor-pointer hover:bg-muted/30"
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={disabled}
                    onChange={(e) => toggleId(id, e.target.checked)}
                    className="mt-0.5 h-3.5 w-3.5 rounded border-border shrink-0"
                  />
                  <span className="min-w-0 flex-1">
                    <span className="font-medium line-clamp-2">{label}</span>
                  </span>
                </label>
              );
            })}
          </div>
          <div className="mt-2 flex flex-wrap gap-1.5 border-t border-border/50 pt-2">
            <button
              type="button"
              disabled={disabled}
              onClick={() => {
                const allIds = normalizeIds(indicators.map((ind) => ind?.id));
                if (!allIds.length) return;
                setDraftSelected(allIds);
              }}
              className="h-7 px-2 rounded-md border border-border/70 bg-card text-[10px] hover:bg-muted/50"
            >
              Vybrat vše
            </button>
            <button
              type="button"
              disabled={disabled || !selectedIndicator}
              onClick={() => {
                const one = String(selectedIndicator || "").trim();
                if (!one) return;
                setDraftSelected([one]);
              }}
              className="h-7 px-2 rounded-md border border-border/70 bg-card text-[10px] hover:bg-muted/50"
            >
              Jen aktuální
            </button>
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="h-7 px-2 rounded-md text-[10px] text-muted-foreground hover:text-foreground ml-auto"
            >
              Zavřít
            </button>
            <button
              type="button"
              disabled={disabled || !draftSelected.length || !draftChanged}
              onClick={applyDraft}
              className="h-7 px-2.5 rounded-md border border-sky-600 bg-sky-600 text-[10px] font-semibold text-white hover:bg-sky-700 disabled:cursor-not-allowed disabled:border-border/70 disabled:bg-muted disabled:text-muted-foreground"
            >
              Porovnat
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
