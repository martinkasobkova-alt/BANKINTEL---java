import React, { useEffect, useMemo, useRef, useState } from "react";
import { ChevronDown } from "lucide-react";
import { SECTION_ICONS } from "@/components/layout/Sidebar";

/**
 * Ikony sekcí menu — výběr s vizuálním náhledem (stejný slovník jako SECTION_ICONS v Sidebar).
 */
export default function SectionIconPicker({ value, onChange }) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef(null);
  const safe = SECTION_ICONS[value] ? value : "Folder";

  const iconIds = useMemo(() => Object.keys(SECTION_ICONS).sort((a, b) => a.localeCompare(b)), []);
  const Selected = SECTION_ICONS[safe];

  useEffect(() => {
    function onDoc(e) {
      if (rootRef.current && !rootRef.current.contains(e.target)) setOpen(false);
    }
    if (open) {
      document.addEventListener("mousedown", onDoc);
      return () => document.removeEventListener("mousedown", onDoc);
    }
  }, [open]);

  useEffect(() => {
    function onKey(e) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className="relative" ref={rootRef}>
      <button
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        className="input flex h-auto min-h-[36px] w-full cursor-pointer items-center justify-between gap-2 py-2 text-left hover:bg-slate-50"
        onClick={() => setOpen((o) => !o)}
      >
        <span className="flex min-w-0 items-center gap-2">
          {Selected && <Selected className="h-4 w-4 shrink-0 text-[hsl(var(--primary-deep))]" />}
          <span className="truncate text-sm font-medium">{safe}</span>
        </span>
        <ChevronDown className={`h-4 w-4 shrink-0 opacity-60 transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && (
        <div
          role="listbox"
          aria-label="Výběr ikony"
          className="absolute z-[100] mt-1 max-h-64 min-w-[100%] overflow-y-auto rounded-xl border border-border/80 bg-white p-2 shadow-lg"
        >
          <div className="grid grid-cols-1 gap-px sm:grid-cols-2">
            {iconIds.map((id) => {
              const Ico = SECTION_ICONS[id];
              const selected = id === safe;
              return (
                <button
                  key={id}
                  type="button"
                  role="option"
                  aria-selected={selected}
                  className={`flex items-center gap-2 rounded-lg px-2 py-2 text-left text-[13px] transition-colors ${
                    selected ? "bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]" : "hover:bg-slate-50"
                  }`}
                  onClick={() => {
                    onChange(id);
                    setOpen(false);
                  }}
                >
                  <Ico className="h-4 w-4 shrink-0" />
                  <span className="truncate">{id}</span>
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
