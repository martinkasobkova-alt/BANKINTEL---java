import React from "react";

/** Rozdělí odpověď AI na odstavce/seznamy podle prázdných řádků a "- "/"1. " prefixů. */
function splitBlocks(raw) {
  const normalized = String(raw || "")
    .replace(/\r\n/g, "\n")
    .replace(/([^\n])\n(\s*[-*•])/g, "$1\n\n$2")
    .replace(/([^\n])\n(\s*\d+[.)]\s)/g, "$1\n\n$2");
  return normalized
    .split(/\n{2,}/)
    .map((b) => b.trim())
    .filter(Boolean);
}

const BULLET_RE = /^[-*•]\s+/;
const NUMBERED_RE = /^\d+[.)]\s+/;

function blockKind(lines) {
  if (lines.every((l) => BULLET_RE.test(l))) return "ul";
  if (lines.every((l) => NUMBERED_RE.test(l))) return "ol";
  return "p";
}

/** Inline **bold** → <strong>; zbytek beze změny. */
function renderInline(text, keyPrefix) {
  const parts = String(text).split(/(\*\*[^*]+\*\*)/g);
  return parts.map((part, i) => {
    if (part.startsWith("**") && part.endsWith("**") && part.length > 4) {
      return (
        <strong key={`${keyPrefix}-${i}`} className="font-semibold text-slate-900">
          {part.slice(2, -2)}
        </strong>
      );
    }
    return <React.Fragment key={`${keyPrefix}-${i}`}>{part}</React.Fragment>;
  });
}

/**
 * Lehký, bezzávislostní renderer odpovědí AI chatu: odstavce, odrážkové/číslované
 * seznamy a **tučně** — bez plného markdown parseru. Sdíleno napříč AI nad grafem /
 * AI nad dashboardem / AI nad výsledky hledání, aby odpovědi vypadaly všude stejně.
 */
export default function ChatMarkdownText({ text, className = "" }) {
  const blocks = splitBlocks(text);
  if (!blocks.length) return null;

  return (
    <div className={`space-y-2 ${className}`}>
      {blocks.map((block, blockIdx) => {
        const lines = block.split("\n").map((l) => l.trim()).filter(Boolean);
        const kind = blockKind(lines);

        if (kind === "ul") {
          return (
            <ul key={blockIdx} className="list-disc space-y-1 pl-5 marker:text-[hsl(var(--primary))]">
              {lines.map((line, i) => (
                <li key={i} className="leading-relaxed">
                  {renderInline(line.replace(BULLET_RE, ""), `${blockIdx}-${i}`)}
                </li>
              ))}
            </ul>
          );
        }
        if (kind === "ol") {
          return (
            <ol key={blockIdx} className="list-decimal space-y-1 pl-5 marker:text-[hsl(var(--primary))] marker:font-semibold">
              {lines.map((line, i) => (
                <li key={i} className="leading-relaxed">
                  {renderInline(line.replace(NUMBERED_RE, ""), `${blockIdx}-${i}`)}
                </li>
              ))}
            </ol>
          );
        }
        return (
          <p key={blockIdx} className="leading-relaxed">
            {renderInline(lines.join(" "), `${blockIdx}`)}
          </p>
        );
      })}
    </div>
  );
}
