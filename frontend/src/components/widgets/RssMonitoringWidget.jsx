import React from "react";
import { ExternalLink } from "lucide-react";
import { fmtDateTime } from "@/lib/format";
import { resolveWidgetPanel } from "@/lib/widgetPanel";
import { buildChartTheme } from "@/lib/chartTheme";

function stripHtml(s) {
  if (!s) return "";
  return String(s)
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export default function RssMonitoringWidget({ title, data, config, compact }) {
  const surface = resolveWidgetPanel(config);
  const theme = buildChartTheme(config?.chart_color);
  const err = data?.error;
  const items = Array.isArray(data?.items) ? data.items : [];

  if (err) {
    return (
      <div
        className={`${surface.className} h-full flex flex-col gap-2 p-4 text-sm text-red-800`}
        style={surface.style}
      >
        {title ? <div className="font-semibold text-foreground">{title}</div> : null}
        <p>{err}</p>
      </div>
    );
  }

  return (
    <div
      className={`${surface.className} h-full flex flex-col gap-3 p-4 overflow-hidden`}
      style={{
        ...surface.style,
        borderColor: theme.border,
        backgroundImage: surface.style?.backgroundColor ? undefined : theme.bodyBg,
      }}
    >
      {title ? (
        <div
          className="font-serif text-base font-semibold shrink-0 border-l-4 pl-2"
          style={{ color: theme.accent, borderColor: theme.accent }}
        >
          {title}
        </div>
      ) : null}
      <ul className={`space-y-3 min-h-0 overflow-y-auto ${compact ? "text-xs" : "text-sm"}`}>
        {items.length === 0 ? (
          <li className="text-muted-foreground">Žádné položky. Zkuste synchronizovat zdroj nebo upravit filtry.</li>
        ) : (
          items.map((it) => {
            const isTranslated = !!(it.title_cs || it.summary_cs);
            const displayTitle = it.title_cs || it.title || "Bez názvu";
            const displaySummary = it.summary_cs || it.summary;
            return (
              <li
                key={it.id || `${it.feed_id}-${it.link}-${it.published_at}`}
                className="border-b border-[hsl(var(--border)/0.5)] pb-3 last:border-0"
              >
                <div className="flex items-start justify-between gap-2">
                  <a
                    href={it.link || "#"}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="font-medium hover:underline flex items-start gap-1"
                    style={{ color: theme.accent }}
                  >
                    <span className="line-clamp-2">{displayTitle}</span>
                    <ExternalLink className="h-3.5 w-3.5 shrink-0 mt-0.5 opacity-70" aria-hidden />
                  </a>
                </div>
                <div className="text-[11px] text-muted-foreground mt-1 flex flex-wrap gap-x-2 gap-y-0.5">
                  <span>{it.source_name || "—"}</span>
                  {it.published_at ? <span>{fmtDateTime(it.published_at)}</span> : null}
                  {it.category ? (
                    <span
                      className="rounded px-1.5 py-0 text-foreground/90"
                      style={{ background: theme.accentSoft }}
                    >
                      {it.category}
                    </span>
                  ) : null}
                  {isTranslated ? (
                    <span
                      className="rounded px-1.5 py-0 text-foreground/70 border border-[hsl(var(--border)/0.6)]"
                      title={`Přeloženo z originálu: ${it.title || ""}`}
                    >
                      překlad
                    </span>
                  ) : null}
                </div>
                {displaySummary ? (
                  <p className="text-foreground/85 mt-1 line-clamp-3">{stripHtml(displaySummary)}</p>
                ) : null}
              </li>
            );
          })
        )}
      </ul>
    </div>
  );
}
