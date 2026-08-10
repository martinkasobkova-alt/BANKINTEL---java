import { mergeCatalogDeepSearchPartials } from "@/lib/api";

export function useSearchResultsMerge() {
  const mergeDeepResults = (partials) => {
    const rows = Array.isArray(partials) ? partials : [partials];
    return mergeCatalogDeepSearchPartials(rows.filter(Boolean));
  };
  return { mergeDeepResults };
}

