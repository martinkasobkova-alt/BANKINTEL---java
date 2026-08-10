import { Globe } from "lucide-react";

/**
 * Classic-search web-research fallback (backend: CatalogDeepSearchService / SearchV2Service,
 * triggered only when the catalog deep search returns status="no_valid_result"). A distinct,
 * visually separated section - never mixed into catalog verified/possible hits - so the user
 * always sees what came from the internal catalog vs. what was found on the web. Mirrors Manager
 * Explorer's ExploreWebSourcesSection, restyled to the classic-search look (soft-card, like the
 * separate "Akcie" section). Findings use the shared web-findings shape from WebResearchService.
 */
const WEB_SOURCE_TIER_BADGE = {
  official: {
    label: "Oficiální zdroj",
    className: "bg-emerald-100 text-emerald-800 canvas-dark:bg-emerald-900/40 canvas-dark:text-emerald-300",
  },
  press: {
    label: "Tisk",
    className: "bg-amber-100 text-amber-800 canvas-dark:bg-amber-900/40 canvas-dark:text-amber-300",
  },
};

export default function CatalogWebFallbackSection({ deepData }) {
  const webSources = Array.isArray(deepData?.web_sources) ? deepData.web_sources : [];
  const status = deepData?.web_research_status;

  if (!webSources.length) {
    // Only show an explicit "nothing found on the web either" note when the fallback actually ran.
    // For "not_attempted" (catalog found data, so the web was never queried) render nothing.
    if (status === "empty" || status === "failed") {
      return (
        <section className="soft-card mb-3 p-3" data-testid="catalog-web-fallback-results">
          <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-sky-700 canvas-dark:text-sky-400">
            <Globe className="h-3.5 w-3.5" aria-hidden />
            Nalezeno na webu (mimo interní katalog)
          </h3>
          <p className="mt-2 text-[13px] text-muted-foreground">
            Katalog k tomuto dotazu nenašel vlastní data a hledání na webu nenašlo nic dostatečně
            doloženého.
          </p>
        </section>
      );
    }
    return null;
  }

  return (
    <section className="soft-card mb-3 p-3" data-testid="catalog-web-fallback-results">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-sky-700 canvas-dark:text-sky-400">
          <Globe className="h-3.5 w-3.5" aria-hidden />
          Nalezeno na webu (mimo interní katalog)
        </h3>
        <span className="text-[11px] text-muted-foreground">
          {webSources.length} {webSources.length === 1 ? "zjištění" : "zjištění"} · web
        </span>
      </div>
      <p className="mt-1 text-[11px] text-muted-foreground">
        Katalog k tomuto dotazu nenašel vlastní data — následující zjištění pochází z webu a je
        oddělené od ověřených katalogových výsledků výše.
      </p>
      <div className="mt-2 space-y-2">
        {webSources.slice(0, 8).map((item, idx) => {
          const badge = WEB_SOURCE_TIER_BADGE[item?.source_tier];
          const sourceUrls = Array.isArray(item?.source_urls) ? item.source_urls : [];
          return (
            <div
              key={`${item?.url || "web-source"}-${idx}`}
              className="rounded-lg border bg-card px-3 py-2.5 space-y-1 shadow-sm"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="text-[13px] font-semibold text-foreground">{item?.title}</div>
                {badge ? (
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium ${badge.className}`}>
                    {badge.label}
                  </span>
                ) : null}
              </div>
              {item?.value_text ? (
                <div className="text-[13px] text-foreground/90">
                  {item.value_text}
                  {item?.period ? ` (${item.period})` : ""}
                </div>
              ) : null}
              {item?.summary_cz ? <div className="text-[12px] text-muted-foreground">{item.summary_cz}</div> : null}
              {sourceUrls.length ? (
                <details className="mt-1 rounded-lg border bg-muted/30 px-2 py-1.5">
                  <summary className="cursor-pointer text-[10px] font-semibold uppercase tracking-wide text-sky-700 canvas-dark:text-sky-400">
                    Zdroje ({sourceUrls.length})
                  </summary>
                  <div className="mt-1 space-y-1">
                    {sourceUrls.slice(0, 4).map((url, srcIdx) => (
                      <a
                        key={`${url}-${srcIdx}`}
                        href={String(url)}
                        target="_blank"
                        rel="noreferrer noopener"
                        className="block truncate text-[11px] font-medium text-sky-700 canvas-dark:text-sky-400 underline decoration-sky-300 underline-offset-2 hover:text-sky-900 canvas-dark:hover:text-sky-200"
                        title={String(url)}
                      >
                        {url}
                      </a>
                    ))}
                  </div>
                </details>
              ) : null}
            </div>
          );
        })}
      </div>
    </section>
  );
}
