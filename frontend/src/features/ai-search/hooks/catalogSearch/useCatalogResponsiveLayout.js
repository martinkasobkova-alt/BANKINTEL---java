import { useEffect, useState } from "react";

/** Breakpoint logika pro katalogové vyhledávání (browse/detail workflow). */
export const CATALOG_LAYOUT_BREAKPOINTS = {
  mobile: 1024,
  laptop: 1280,
  wide: 1536,
};

/** Breakpoint logika pro katalogové vyhledávání (spec §6–7 + browse/detail režim). */
export function useCatalogResponsiveLayout({ hasActiveSearch }) {
  const [width, setWidth] = useState(() =>
    typeof window !== "undefined" ? window.innerWidth : 1440,
  );

  useEffect(() => {
    const onResize = () => setWidth(window.innerWidth);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  const isMobile = width < CATALOG_LAYOUT_BREAKPOINTS.mobile;
  const isTablet = width >= CATALOG_LAYOUT_BREAKPOINTS.mobile && width < CATALOG_LAYOUT_BREAKPOINTS.laptop;
  const isLaptop =
    width >= CATALOG_LAYOUT_BREAKPOINTS.laptop && width < CATALOG_LAYOUT_BREAKPOINTS.wide;
  const isWideDesktop = width >= CATALOG_LAYOUT_BREAKPOINTS.wide;

  /** Pod 1536 px: browse + fullscreen detail (bez split explorer/detail). */
  const browseDetailWorkflow = !isWideDesktop;

  let layoutVariant = "A";
  if (isMobile) {
    layoutVariant = "mobile";
  } else if (isWideDesktop) {
    layoutVariant = hasActiveSearch ? "C" : "A";
  } else if (isTablet || (isLaptop && hasActiveSearch)) {
    layoutVariant = "C";
  } else if (isLaptop) {
    layoutVariant = "B";
  }

  const explorerDefaultOpen =
    isWideDesktop && !hasActiveSearch ? true : !isMobile && !isTablet && !(isLaptop && hasActiveSearch);

  const maxExplorerColumns = isWideDesktop ? 6 : isLaptop ? 5 : 4;
  const explorerWidthClass =
    layoutVariant === "A"
      ? "catalog-layout-explorer-wide"
      : layoutVariant === "B"
        ? "catalog-layout-explorer-medium"
        : "catalog-layout-explorer-collapsed";

  return {
    width,
    layoutVariant,
    isMobile,
    isTablet,
    isLaptop,
    isWideDesktop,
    browseDetailWorkflow,
    explorerDefaultOpen,
    maxExplorerColumns,
    explorerWidthClass,
    detailPresentation: isWideDesktop ? "overlay" : "fullscreen",
  };
}
