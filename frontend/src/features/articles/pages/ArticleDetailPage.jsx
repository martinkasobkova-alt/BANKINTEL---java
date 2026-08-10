import React, { useEffect, useState } from "react";

import { Link, useParams } from "react-router-dom";

import { useTranslation } from "react-i18next";

import { ArrowLeft } from "lucide-react";

import AppShell from "@/components/layout/AppShell";

import ArticleBodyView from "@/components/articles/ArticleBodyView";

import ArticlePageHero from "@/components/articles/ArticlePageHero";

import api, { formatApiErrorFromAxios } from "@/lib/api";

import { formatLocaleDate } from "@/i18n/formatLocale";

import { getArticleCoverImageUrl } from "@/lib/articleCover";



export default function ArticleDetailPage() {

  const { t, i18n } = useTranslation();

  const { articleId } = useParams();

  const [article, setArticle] = useState(null);

  const [loading, setLoading] = useState(true);

  const [err, setErr] = useState("");



  useEffect(() => {

    if (!articleId) return;

    let cancelled = false;

    (async () => {

      setLoading(true);

      setErr("");

      try {

        const { data } = await api.get(`/articles/${encodeURIComponent(articleId)}`);

        if (!cancelled) setArticle(data);

      } catch (e) {

        if (!cancelled) {

          setArticle(null);

          setErr(formatApiErrorFromAxios(e));

        }

      } finally {

        if (!cancelled) setLoading(false);

      }

    })();

    return () => {

      cancelled = true;

    };

  }, [articleId]);



  const dateLabel = article?.published_at

    ? formatLocaleDate(article.published_at, i18n.language)

    : "";



  return (

    <AppShell

      title={t("pages.articles.title")}

      subtitle={t("pages.articleDetail.subtitle")}

      actions={

        <Link

          to="/zpravy"

          className="flex items-center gap-1.5 px-2.5 h-8 text-xs border border-border rounded-sm hover:bg-muted/60"

        >

          <ArrowLeft className="h-3.5 w-3.5" /> {t("pages.articleDetail.backToList")}

        </Link>

      }

    >

      <article className="max-w-3xl space-y-5">

        {loading ? (

          <p className="text-sm text-slate-600">{t("common.loading")}</p>

        ) : err || !article ? (

          <div className="chip-rose rounded-md p-3 text-sm">{err || t("pages.articleDetail.notFound")}</div>

        ) : (

          <>

            <ArticlePageHero

              title={article.title}

              summary={article.summary}

              coverImageUrl={getArticleCoverImageUrl(article)}

              publishedAt={article.published_at}

              authorName={article.author_name}

              dateLabel={dateLabel}

              categoryName={article.category_name}

            />

            <div className="soft-card px-5 py-6 sm:px-7 sm:py-7">

              <ArticleBodyView body={article.body} className="prose prose-slate max-w-none" />

            </div>

          </>

        )}

      </article>

    </AppShell>

  );

}


