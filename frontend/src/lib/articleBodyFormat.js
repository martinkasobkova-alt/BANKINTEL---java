import { normalizeSharedChart } from "@/lib/sharedChartLink";

const FENCED_BLOCK_RE = /:::(chart|video)\n([\s\S]*?)\n:::/g;

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** Inline markdown: bold, italic, images, links in text. */
export function renderArticleInline(text) {
  let t = escapeHtml(text);
  t = t.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_m, alt, url) => {
    return `<img src="${url}" alt="${alt}" class="my-2 max-w-full rounded-lg border border-slate-200" />`;
  });
  t = t.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_m, label, url) => {
    return `<a href="${url}" target="_blank" rel="noopener noreferrer" class="text-indigo-700 underline">${label}</a>`;
  });
  t = t.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  t = t.replace(/__([^_]+)__/g, "<strong>$1</strong>");
  t = t.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, "$1<em>$2</em>");
  t = t.replace(/(^|[^_])_([^_\n]+)_(?!_)/g, "$1<em>$2</em>");
  return t;
}

function isTableLines(lines) {
  const trimmed = lines.map((l) => l.trim()).filter(Boolean);
  if (trimmed.length < 2) return false;
  return trimmed.every((l) => l.includes("|"));
}

function parseTableLines(lines) {
  const rows = lines
    .map((l) => l.trim())
    .filter((l) => l && !/^[-|:\s]+$/.test(l))
    .map((l) =>
      l
        .replace(/^\|/, "")
        .replace(/\|$/, "")
        .split("|")
        .map((c) => c.trim())
    );
  return { type: "table", rows };
}

function parseTextSegment(segment) {
  const text = String(segment || "").trim();
  if (!text) return [];
  const blocks = text.split(/\n{2,}/).map((b) => b.trim()).filter(Boolean);
  const out = [];
  for (const block of blocks) {
    const lines = block.split("\n");
    const first = lines[0]?.trim() || "";
    const heading = first.match(/^(#{1,3})\s+(.*)$/);
    if (heading && lines.length === 1) {
      out.push({ type: "heading", level: heading[1].length, text: heading[2] });
      continue;
    }
    if (lines.every((l) => /^[-*]\s+/.test(l.trim()))) {
      out.push({
        type: "list",
        items: lines.map((l) => l.trim().replace(/^[-*]\s+/, "")),
      });
      continue;
    }
    if (isTableLines(lines)) {
      out.push(parseTableLines(lines));
      continue;
    }
    const imgOnly = first.match(/^!\[([^\]]*)\]\(([^)\s]+)\)$/);
    if (imgOnly && lines.length === 1) {
      out.push({ type: "image", alt: imgOnly[1], url: imgOnly[2] });
      continue;
    }
    out.push({ type: "paragraph", text: block });
  }
  return out;
}

/** Parse article body into renderable blocks (headings, lists, tables, charts, video, paragraphs). */
export function parseArticleBody(body) {
  const raw = String(body || "");
  if (!raw.trim()) return [];

  const blocks = [];
  let last = 0;
  let match;
  FENCED_BLOCK_RE.lastIndex = 0;
  while ((match = FENCED_BLOCK_RE.exec(raw)) !== null) {
    const before = raw.slice(last, match.index);
    if (before.trim()) blocks.push(...parseTextSegment(before));
    const kind = match[1];
    const inner = String(match[2] || "").trim();
    if (kind === "chart") {
      try {
        const chart = normalizeSharedChart(JSON.parse(inner));
        if (chart) blocks.push({ type: "chart", chart });
      } catch {
        blocks.push({ type: "paragraph", text: inner });
      }
    } else if (kind === "video") {
      const url = inner.split("\n")[0]?.trim() || "";
      if (url) blocks.push({ type: "video", url });
    }
    last = match.index + match[0].length;
  }
  const tail = raw.slice(last);
  if (tail.trim()) blocks.push(...parseTextSegment(tail));
  return blocks;
}

/** Build fenced chart block for editor insertion. */
export function chartBlockSnippet(chart) {
  const norm = normalizeSharedChart(chart);
  if (!norm) return "";
  return `:::chart\n${JSON.stringify(norm)}\n:::\n\n`;
}

/** Build fenced video block for editor insertion. */
export function videoBlockSnippet(url) {
  const u = String(url || "").trim();
  if (!u) return "";
  return `:::video\n${u}\n:::\n\n`;
}

const YOUTUBE_RE =
  /(?:youtube\.com\/(?:watch\?v=|embed\/|shorts\/)|youtu\.be\/)([a-zA-Z0-9_-]{6,})/i;
const VIMEO_RE = /vimeo\.com\/(?:video\/)?(\d+)/i;

/** Spotify / Apple Podcasts → embed URL pro přehrávač v overlay. */
export function podcastEmbedUrl(url) {
  const raw = String(url || "").trim();
  if (!raw) return null;
  try {
    const u = new URL(raw);
    if (u.hostname.includes("spotify.com")) {
      const m = u.pathname.match(/\/(episode|show)\/([a-zA-Z0-9]+)/i);
      if (m) return `https://open.spotify.com/embed/${m[1].toLowerCase()}/${m[2]}`;
      if (u.pathname.includes("/embed/")) return raw;
    }
    if (u.hostname.includes("podcasts.apple.com")) {
      return raw.replace("podcasts.apple.com", "embed.podcasts.apple.com");
    }
  } catch {
    return null;
  }
  return null;
}

export function canEmbedPodcastUrl(url) {
  return Boolean(podcastEmbedUrl(url));
}

/** Resolve embed URL for supported video hosts. */
export function videoEmbedUrl(url) {
  const raw = String(url || "").trim();
  if (!raw) return null;
  const yt = raw.match(YOUTUBE_RE);
  if (yt) return `https://www.youtube.com/embed/${yt[1]}`;
  const vm = raw.match(VIMEO_RE);
  if (vm) return `https://player.vimeo.com/video/${vm[1]}`;
  if (/\.(mp4|webm|ogg)(\?|$)/i.test(raw)) return raw;
  return null;
}

export const ARTICLE_TABLE_TEMPLATE = `| Sloupec 1 | Sloupec 2 |\n|-----------|-----------|\n| Buňka 1   | Buňka 2   |\n`;
