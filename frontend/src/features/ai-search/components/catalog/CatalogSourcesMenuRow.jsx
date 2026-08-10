import React from "react";
import { Link } from "react-router-dom";
import CatalogDatabaseInfo from "@/components/catalog/CatalogDatabaseInfo";

/**
 * Položka menu „Katalogy dat“ na stránce Zdroje — odkaz + ℹ popover.
 */
export default function CatalogSourcesMenuRow({ to, catalogId, label, icon: Icon, testId }) {
  return (
    <div className="flex items-center gap-0.5 w-full min-w-0 pr-1">
      <Link
        to={to}
        data-testid={testId}
        className="flex flex-1 items-center gap-2 cursor-pointer rounded-sm px-2 py-1.5 text-sm outline-none hover:bg-accent min-w-0"
      >
        {Icon ? <Icon className="h-4 w-4 shrink-0" strokeWidth={1.8} /> : null}
        <span className="truncate">{label}</span>
      </Link>
      <CatalogDatabaseInfo catalogId={catalogId} label={label} className="mr-1" />
    </div>
  );
}
