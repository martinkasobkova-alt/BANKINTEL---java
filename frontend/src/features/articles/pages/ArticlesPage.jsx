import React, { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Newspaper } from "lucide-react";
import AppShell from "@/components/layout/AppShell";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import { formatLocaleDate } from "@/i18n/formatLocale";
import { getArticleCoverImageUrl } from "@/lib/articleCover";

export default function ArticlesPage() {
  const { t, i18n } = useTranslation();
  const { canEditContent } = useAuth();
  const [rows, setRows] = useState([]);
  const [categories, setCategories] = useState([]);
  const [activeCategoryId, setActiveCategoryId] = useState("");
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");

  const loadCategories = useCallback(async () => {
    try {
      const { data } = await api.get("/articles/categories");
      setCategories(Array.isArray(data) ? data : []);
    } catch {
      setCategories([]);
    }
  }, []);

  const loadArticles = useCallback(async () => {
    setLoading(true);
    setErr("");
    try {
      const params = { limit: 80 };
      if (activeCategoryId) params.category_id = activeCategoryId;
      const { data } = await api.get("/articles", { params });
      setRows(Array.isArray(data) ? data : []);
    } catch (e) {
      setErr(formatApiErrorFromAxios(e));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [activeCategoryId]);

  useEffect(() => {
    loadCategories();
  }, [loadCategories]);

  useEffect(() => {
    loadArticles();
  }, [loadArticles]);

  return (
    <AppShell
      title={t("pages.articles.title")}
      subtitle={t("pages.articles.subtitle")}
      actions={
        canEditContent ? (
          <Link
            to="/admin/articles"
            className="flex items-center gap-1.5 px-3 h-8 text-xs border border-border rounded-sm hover:bg-muted/60"
          >
            {t("pages.articles.manage")}
          </Link>
        ) : null
      }
    >
      <div className="max-w-3xl space-y-5">
        {categories.length > 0 ? (
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => setActiveCategoryId("")}
              className={`rounded-full px-3 py-1.5 text-xs font-medium border transition ${
                !activeCategoryId
                  ? "border-[hsl(var(--primary)/0.45)] bg-[hsl(var(--primary-soft)/0.35)] text-[hsl(var(--primary-deep))]"
                  : "border-border text-slate-600 hover:bg-muted/50"
              }`}
            >
              Vše
            </button>
            {categories.map((cat) => (
              <button
                key={cat.id}
                type="button"
                onClick={() => setActiveCategoryId(cat.id)}
                className={`rounded-full px-3 py-1.5 text-xs font-medium border transition ${
                  activeCategoryId === cat.id
                    ? "border-[hsl(var(--primary)/0.45)] bg-[hsl(var(--primary-soft)/0.35)] text-[hsl(var(--primary-deep))]"
                    : "border-border text-slate-600 hover:bg-muted/50"
                }`}
              >
                {cat.name}
              </button>
            ))}
          </div>
        ) : null}

        {err ? <div className="chip-rose rounded-md p-3 text-sm">{err}</div> : null}

        {loading ? (
          <div className="text-sm text-slate-600">{t("pages.articles.loading")}</div>
        ) : rows.length === 0 ? (
          <div className="soft-card p-6 text-sm text-slate-600">{t("pages.articles.empty")}</div>
        ) : (
          <div className="space-y-3">
            {rows.map((a) => {
              const coverUrl = getArticleCoverImageUrl(a);
              return (
                <Link
                  key={a.id}
                  to={`/zpravy/${a.slug || a.id}`}
                  className="soft-card block overflow-hidden hover:border-[hsl(var(--primary)/0.45)] transition"
                >
                  {coverUrl ? (
                    <div className="relative h-44 w-full overflow-hidden border-b border-slate-100 bg-slate-100 sm:h-52">
                      <img
                        src={coverUrl}
                        alt=""
                        className="h-full w-full object-cover"
                        loading="lazy"
                        referrerPolicy="no-referrer"
                      />
                    </div>
                  ) : null}
                  <div className="flex items-start justify-between gap-3 p-4">
                    <div className="min-w-0 flex-1">
                      {a.category_name ? (
                        <div className="text-[10px] font-semibold uppercase tracking-wide text-[hsl(var(--primary-deep))] mb-1">
                          {a.category_name}
                        </div>
                      ) : null}
                      <div className="font-semibold text-slate-900">{a.title}</div>
                      {a.published_at ? (
                        <div className="text-xs text-slate-500 mt-1">
                          {formatLocaleDate(a.published_at, i18n.language)}
                        </div>
                      ) : null}
                      {a.summary ? (
                        <div className="text-sm text-slate-600 mt-2 line-clamp-2">{a.summary}</div>
                      ) : null}
                      {!a.published && canEditContent ? (
                        <span className="inline-block mt-2 text-[10px] uppercase tracking-wide px-2 py-0.5 rounded-full bg-amber-100 text-amber-900">
                          {t("pages.articles.draft")}
                        </span>
                      ) : null}
                    </div>
                    {!coverUrl ? (
                      <Newspaper className="h-4 w-4 text-slate-400 shrink-0 mt-1" />
                    ) : null}
                  </div>
                </Link>
              );
            })}
          </div>
        )}
      </div>
    </AppShell>
  );
}
