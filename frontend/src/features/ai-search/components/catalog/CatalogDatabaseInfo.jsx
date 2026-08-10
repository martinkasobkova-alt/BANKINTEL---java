import React from "react";
import { Info } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { getCatalogDatabaseProfile } from "@/lib/catalogDatabaseProfiles";

/**
 * Ikona ℹ — po kliknutí popover s popisem databáze (oblasti dat, geografie, využití).
 *
 * @param {{ catalogId: string, label?: string, size?: "xs" | "sm", className?: string }} props
 */
export default function CatalogDatabaseInfo({ catalogId, label, size = "xs", className = "" }) {
  const profile = getCatalogDatabaseProfile(catalogId);
  if (!profile) return null;

  const btnSize = size === "sm" ? "h-7 w-7" : "h-5 w-5";
  const iconSize = size === "sm" ? "h-3.5 w-3.5" : "h-3 w-3";
  const title = label || catalogId;

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          className={`inline-flex shrink-0 items-center justify-center rounded-full border border-sky-200/80 bg-sky-50/90 text-sky-800 shadow-sm hover:bg-sky-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-500/35 ${btnSize} ${className}`}
          aria-label={`Informace o databázi ${title}`}
          data-testid={`catalog-info-${catalogId}`}
          onClick={(e) => e.stopPropagation()}
        >
          <Info className={iconSize} aria-hidden />
        </button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        side="bottom"
        className="w-[min(24rem,calc(100vw-2rem))] max-h-[min(72vh,32rem)] overflow-y-auto p-4 text-[12px] text-slate-700 leading-relaxed"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="space-y-3">
          <div>
            <div className="text-[10px] font-semibold uppercase tracking-wide text-sky-800/80">Databáze</div>
            <div className="font-semibold text-slate-900 text-sm mt-0.5">{title}</div>
            <p className="text-sky-900/90 font-medium mt-1 leading-snug">{profile.tagline}</p>
          </div>

          <p className="text-slate-700 leading-relaxed">{profile.summary}</p>

          <div className="rounded-lg border border-slate-200/80 bg-slate-50/80 px-3 py-2 space-y-1.5">
            <div>
              <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">Geografie</span>
              <p className="text-slate-800 mt-0.5">{profile.geography}</p>
            </div>
            {profile.frequency ? (
              <div>
                <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">Frekvence</span>
                <p className="text-slate-800 mt-0.5">{profile.frequency}</p>
              </div>
            ) : null}
          </div>

          <div>
            <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-500 mb-1.5">
              Hlavní oblasti dat
            </div>
            <ul className="space-y-1">
              {profile.areas.map((area) => (
                <li key={area} className="flex gap-2 text-slate-800">
                  <span className="text-sky-600 shrink-0 mt-0.5">•</span>
                  <span>{area}</span>
                </li>
              ))}
            </ul>
          </div>

          <div className="border-t border-slate-200/80 pt-2.5">
            <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-500 mb-1">
              Typické využití
            </div>
            <p className="text-slate-700">{profile.typicalUse}</p>
          </div>

          {profile.examples?.length ? (
            <div className="border-t border-slate-200/80 pt-2.5">
              <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-500 mb-1">
                Příklady ukazatelů
              </div>
              <p className="text-[11px] text-slate-600 font-mono leading-relaxed">{profile.examples.join(" · ")}</p>
            </div>
          ) : null}
        </div>
      </PopoverContent>
    </Popover>
  );
}
