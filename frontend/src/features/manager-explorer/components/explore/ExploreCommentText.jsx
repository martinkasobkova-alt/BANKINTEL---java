import React, { useMemo } from "react";
import {
  highlightClassForToken,
  tokenizeExploreCommentText,
} from "@/lib/exploreCommentScoreHighlight";

/** Split comment text into bullet lines when it looks like a list. */
export function splitExploreBulletLines(text) {
  const raw = String(text ?? "");
  if (!raw.trim()) return null;
  const lines = raw.split(/\r?\n/);
  const bullets = [];
  for (const line of lines) {
    const trimmed = line.trim();
    const match = trimmed.match(/^([-•*])\s+(.*)$/);
    if (match) {
      bullets.push(match[2]);
    }
  }
  if (bullets.length >= 2) return bullets;
  // Single-line blob with multiple "- " markers
  if (bullets.length === 0 && /(^|\n)\s*[-•*]\s+\S/.test(raw) === false) {
    const inline = raw
      .split(/(?:^|\s)[-•*]\s+/)
      .map((p) => p.trim())
      .filter(Boolean);
    // Only treat as bullets if the text clearly started with a marker
    if (/^\s*[-•*]\s+/.test(raw) && inline.length >= 2) return inline;
  }
  return bullets.length >= 2 ? bullets : null;
}

function HighlightedInline({ text }) {
  const tokens = useMemo(() => tokenizeExploreCommentText(text), [text]);
  return (
    <>
      {tokens.map((token, idx) => {
        if (token.kind === "text") {
          return <React.Fragment key={`t-${idx}`}>{token.value}</React.Fragment>;
        }
        return (
          <strong key={`h-${idx}`} className={highlightClassForToken(token)}>
            {token.value}
          </strong>
        );
      })}
    </>
  );
}

/**
 * Prose komentář s výrazným zvýrazněním skóre (5.8/10) a hodnocení (smíšený, …).
 * Pokud text obsahuje odrážky (- / • / *), vyrenderuje &lt;ul&gt;/&lt;li&gt;.
 */
export default function ExploreCommentText({ text, className = "" }) {
  const bullets = useMemo(() => splitExploreBulletLines(text), [text]);
  if (bullets) {
    return (
      <ul className={`list-disc pl-4 space-y-1.5 ${className}`.trim()}>
        {bullets.map((item, idx) => (
          <li key={`b-${idx}`}>
            <HighlightedInline text={item} />
          </li>
        ))}
      </ul>
    );
  }
  return (
    <span className={className}>
      <HighlightedInline text={text} />
    </span>
  );
}
