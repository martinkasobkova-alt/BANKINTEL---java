import React from "react";
import { Link } from "react-router-dom";
import { FolderTree } from "lucide-react";
import { buildCatalogHubHref } from "@/lib/catalogBackNav";

const baseBtn =
  "catalog-back-to-hub-btn inline-flex shrink-0 items-center justify-center gap-1.5 rounded-xl border border-[hsl(var(--border)/0.75)] bg-white/82 px-2 shadow-sm transition hover:bg-[hsl(var(--primary-soft))] text-slate-800 h-9 min-h-[2.25rem] sm:gap-2 sm:px-3";

/**
 * Vedle „Zpět na zdroje“ — návrat na rozcestník /search/catalog (?catalog=…).
 * @param {{ catalogId: string, className?: string }} props
 */
export default function CatalogBackToHubButton({ catalogId, className = "" }) {
  const id = String(catalogId || "").trim().toLowerCase();
  return (
    <Link
      to={buildCatalogHubHref(id)}
      className={`${baseBtn} ${className}`}
      title="Zpět do katalogu"
      aria-label="Zpět do katalogu"
      data-testid={`catalog-back-to-hub-${id}`}
    >
      <FolderTree className="h-4 w-4 shrink-0 text-slate-600" aria-hidden />
      <span className="hidden sm:inline text-sm">Zpět do katalogu</span>
      <span className="inline sm:hidden text-[11px] font-semibold leading-none max-[380px]:hidden">Katalog</span>
    </Link>
  );
}
