import React, { useState } from "react";
import SharedChartMessagePreview from "@/components/chat/SharedChartMessagePreview";
import ArchiveInlineChartPanel from "@/components/archive/ArchiveInlineChartPanel";
import { parseArticleBody, renderArticleInline, videoEmbedUrl } from "@/lib/articleBodyFormat";
import { sharedChartToPreviewLink } from "@/lib/sharedChartLink";

function Heading({ level, text }) {
  const cls =
    level === 1
      ? "text-2xl font-bold text-slate-900 mt-6 mb-3"
      : level === 2
        ? "text-xl font-semibold text-slate-900 mt-5 mb-2"
        : "text-lg font-semibold text-slate-800 mt-4 mb-2";
  const Tag = level === 1 ? "h1" : level === 2 ? "h2" : "h3";
  return (
    <Tag className={cls}>
      <span dangerouslySetInnerHTML={{ __html: renderArticleInline(text) }} />
    </Tag>
  );
}

function VideoEmbed({ url }) {
  const embed = videoEmbedUrl(url);
  if (!embed) {
    return (
      <a href={url} target="_blank" rel="noreferrer" className="text-sm text-indigo-700 underline">
        Otevřít video
      </a>
    );
  }
  if (embed.match(/\.(mp4|webm|ogg)/i)) {
    return (
      <video controls className="w-full max-w-full rounded-lg border border-slate-200" src={embed} />
    );
  }
  return (
    <div className="relative w-full overflow-hidden rounded-lg border border-slate-200 bg-black aspect-video">
      <iframe
        src={embed}
        title="Video"
        className="absolute inset-0 h-full w-full"
        allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
        allowFullScreen
      />
    </div>
  );
}

/**
 * Renders article body with headings, formatting, media, tables and embedded charts.
 */
export default function ArticleBodyView({ body, className = "" }) {
  const [expandedChartLink, setExpandedChartLink] = useState(null);
  const blocks = parseArticleBody(body);

  if (!blocks.length) return null;

  return (
    <>
      <div className={`article-body-view space-y-4 text-sm leading-relaxed text-slate-800 ${className}`}>
        {blocks.map((block, i) => {
          switch (block.type) {
            case "heading":
              return <Heading key={i} level={block.level} text={block.text} />;
            case "list":
              return (
                <ul key={i} className="list-disc pl-5 space-y-1">
                  {block.items.map((item, j) => (
                    <li key={j}>
                      <span dangerouslySetInnerHTML={{ __html: renderArticleInline(item) }} />
                    </li>
                  ))}
                </ul>
              );
            case "table":
              return (
                <div key={i} className="overflow-x-auto rounded-lg border border-slate-200">
                  <table className="w-full min-w-[240px] text-left text-sm">
                    <tbody>
                      {block.rows.map((row, ri) => (
                        <tr key={ri} className={ri === 0 ? "bg-slate-50 font-semibold" : "border-t border-slate-100"}>
                          {row.map((cell, ci) => (
                            <td key={ci} className="px-3 py-2 align-top">
                              <span dangerouslySetInnerHTML={{ __html: renderArticleInline(cell) }} />
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              );
            case "image":
              return (
                <img
                  key={i}
                  src={block.url}
                  alt={block.alt || ""}
                  className="max-w-full rounded-lg border border-slate-200"
                />
              );
            case "video":
              return (
                <div key={i} className="my-2">
                  <VideoEmbed url={block.url} />
                </div>
              );
            case "chart":
              return (
                <div key={i} className="my-3">
                  <SharedChartMessagePreview
                    sharedChart={block.chart}
                    onExpand={(link) => setExpandedChartLink(link || sharedChartToPreviewLink(block.chart))}
                  />
                </div>
              );
            case "paragraph":
            default:
              const html = renderArticleInline(block.text).replace(/\n/g, "<br/>");
              return (
                <p
                  key={i}
                  className="text-slate-800"
                  dangerouslySetInnerHTML={{ __html: html }}
                />
              );
          }
        })}
      </div>
      {expandedChartLink ? (
        <ArchiveInlineChartPanel
          link={expandedChartLink}
          onClose={() => setExpandedChartLink(null)}
        />
      ) : null}
    </>
  );
}
