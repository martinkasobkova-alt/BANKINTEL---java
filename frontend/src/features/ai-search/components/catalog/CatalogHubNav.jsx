import React from "react";
import { Link, useLocation } from "react-router-dom";
import { Database, LayoutGrid } from "lucide-react";

/** Přepínač mezi průřezovým hledáním v DB a kurátorovaným prohlížením podle země/tématu. */
export default function CatalogHubNav() {
  const { pathname } = useLocation();
  const isTopics = pathname.startsWith("/search/catalog/topics");
  const isSearch = !isTopics;

  const tabClass = (active) =>
    `inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
      active
        ? "bg-card text-foreground shadow-sm border border-border/80"
        : "text-muted-foreground hover:text-foreground hover:bg-muted/40"
    }`;

  return (
    <nav
      className="flex flex-wrap gap-1 rounded-xl border border-border/80 bg-muted/30 p-1"
      aria-label="Režim katalogu"
    >
      <Link to="/search/catalog" className={tabClass(isSearch)}>
        <Database className="h-4 w-4 shrink-0" />
        Vyhledávání v databázích
      </Link>
      <Link to="/search/catalog/topics" className={tabClass(isTopics)}>
        <LayoutGrid className="h-4 w-4 shrink-0" />
        Podle země a témat
      </Link>
    </nav>
  );
}
