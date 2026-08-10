import React from "react";
import { Calendar, User } from "lucide-react";

/**
 * Designový hlavičkový blok článku — titulek ve stylu aplikace (font-serif / --title).
 */
export default function ArticlePageHero({
  title,
  summary,
  coverImageUrl,
  publishedAt,
  authorName,
  dateLabel,
  categoryName,
}) {
  const hasMeta = Boolean(dateLabel || authorName || categoryName);

  return (
    <header className="soft-card overflow-hidden">
      {coverImageUrl ? (
        <div className="relative w-full overflow-hidden border-b border-[hsl(var(--border)/0.55)] bg-slate-100">
          <img
            src={coverImageUrl}
            alt=""
            className="w-full max-h-[min(420px,52vh)] object-cover"
            referrerPolicy="no-referrer"
          />
        </div>
      ) : null}
      <div className="px-5 py-6 sm:px-7 sm:py-7">
        {categoryName ? (
          <p
            className="mb-2 text-[11px] font-semibold uppercase tracking-[0.14em]"
            style={{ color: "hsl(var(--primary-deep))" }}
          >
            {categoryName}
          </p>
        ) : null}
        <h1
          className="font-serif text-[28px] sm:text-[34px] md:text-[40px] leading-[1.08] text-content-wrap"
          data-testid="article-page-title"
        >
          {title}
        </h1>
        {hasMeta ? (
          <div
            className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs sm:text-sm"
            style={{ color: "hsl(var(--muted-foreground))" }}
          >
            {dateLabel ? (
              <span className="inline-flex items-center gap-1.5">
                <Calendar className="h-3.5 w-3.5 shrink-0 opacity-80" aria-hidden />
                <time dateTime={publishedAt || undefined}>{dateLabel}</time>
              </span>
            ) : null}
            {authorName ? (
              <span className="inline-flex items-center gap-1.5">
                <User className="h-3.5 w-3.5 shrink-0 opacity-80" aria-hidden />
                {authorName}
              </span>
            ) : null}
          </div>
        ) : null}
        {summary ? (
          <p
            className="mt-5 text-base sm:text-lg font-medium leading-relaxed text-slate-700 border-t border-[hsl(var(--border)/0.55)] pt-5 text-content-wrap"
          >
            {summary}
          </p>
        ) : null}
      </div>
    </header>
  );
}
