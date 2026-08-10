import React from "react";

/**
 * Full-width browse režim — column explorer přes hlavní plochu (notebook / tablet).
 */
export default function CatalogBrowseView({ children, className = "" }) {
  return (
    <section
      className={`catalog-browse-view flex flex-col w-full min-w-0 min-h-0 flex-1 ${className}`.trim()}
      data-testid="catalog-browse-view"
      aria-label="Procházení katalogu"
    >
      {children}
    </section>
  );
}
