import { useEffect, useState } from "react";

/**
 * Aktualizuje se při změně dopadu média i při změně size okna na stejném breakpointu.
 * Default: @param query CSS media podmínky, např. "(max-width: 767px)" (Tailwind `max-md`).
 */
export function useMediaQuery(query, defaultMatches = false) {
  const [matches, setMatches] = useState(defaultMatches);

  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return undefined;
    const mq = window.matchMedia(query);
    const apply = () => setMatches(mq.matches);
    apply();
    mq.addEventListener("change", apply);
    return () => mq.removeEventListener("change", apply);
  }, [query]);

  return matches;
}

/** Tailwind breakpoint `md`: min-width 768px — vrací true pro šířky ≤767px („mobil/tablet užší než desktop“). */
export function useIsMobileDashboard() {
  return useMediaQuery("(max-width: 767px)", false);
}
