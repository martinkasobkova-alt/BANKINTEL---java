import { toPng } from "html-to-image";
import { formatScore, resolveSectionHighlights } from "@/lib/exploreAnalysisInsights";

/** Recharts + html-to-image u většího počtu grafů — UI zvládá desítky karet; PDF export má rozumný strop. */
const CHART_CAPTURE_MAX = 48;
const CHART_CAPTURE_TIMEOUT_MS = 5000;
const EXPORT_TOTAL_TIMEOUT_MS = 180000;

const SECTION_SCORE_LABELS = {
  company: "Firma",
  sector: "Odvětví",
  related_sectors: "Související odvětví",
  commodities: "Komodity",
  financial_markets: "Finanční trhy",
  macro: "Makro",
  political_situation: "Politická situace",
  demographics: "Demografie",
  fx: "Kurzy",
  neighbors: "Sousedé",
  partners: "Partneři",
  eu: "Region",
  global: "Globální",
};

function escapeHtml(text) {
  return String(text ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function formatExportDate(d = new Date()) {
  try {
    return d.toLocaleString("cs-CZ", {
      dateStyle: "long",
      timeStyle: "short",
    });
  } catch {
    return d.toISOString();
  }
}

function normalizeSections(result) {
  if (!Array.isArray(result?.analysis_sections)) return [];
  return result.analysis_sections
    .map((section, idx) => ({
      id: String(section?.id || `section-${idx}`),
      title: String(section?.title || "").trim(),
      text: String(section?.text || "").trim(),
      score: section?.score,
      highlights: Array.isArray(section?.highlights) ? section.highlights : [],
      sourceUrls: Array.isArray(section?.source_urls) ? section.source_urls : [],
    }))
    .filter((section) => section.text);
}

/** Same field shape as ExploreWebSourcesSection (ExplorePage.jsx) - web-research fallback
 * findings, kept as their own report block so PDF export doesn't silently drop them. */
function normalizeWebSources(result) {
  if (!Array.isArray(result?.web_sources)) return [];
  return result.web_sources
    .map((item) => ({
      title: String(item?.title || "").trim(),
      valueText: String(item?.value_text || "").trim(),
      period: String(item?.period || "").trim(),
      summary: String(item?.summary_cz || "").trim(),
      sourceTier: String(item?.source_tier || "").trim(),
      sourceUrls: Array.isArray(item?.source_urls) ? item.source_urls.filter(Boolean) : [],
    }))
    .filter((item) => item.title);
}

const WEB_SOURCE_TIER_LABEL = { official: "Oficiální zdroj", press: "Tisk" };

/** Render prose or bullet list HTML (same rules as ExploreCommentText). */
function formatCommentHtml(text) {
  const raw = String(text ?? "").trim();
  if (!raw) return "";
  const lines = raw.split(/\r?\n/);
  const bullets = [];
  for (const line of lines) {
    const trimmed = line.trim();
    const match = trimmed.match(/^([-•*])\s+(.*)$/);
    if (match) bullets.push(match[2]);
  }
  if (bullets.length >= 2) {
    return `<ul class="section-bullets">${bullets
      .map((item) => `<li>${escapeHtml(item)}</li>`)
      .join("")}</ul>`;
  }
  return `<p class="section-text">${escapeHtml(raw).replace(/\n/g, "<br/>")}</p>`;
}

function normalizeKeyItems(keyNumbers) {
  if (!Array.isArray(keyNumbers)) return [];
  return keyNumbers
    .map((item) => {
      if (!item || typeof item !== "object") return null;
      const label = String(item.label || item.name || "").trim();
      const value = String(item.value ?? "").trim();
      if (!label || !value) return null;
      return {
        label,
        value,
        description: String(item.description || "").trim(),
      };
    })
    .filter(Boolean);
}

function collectSeriesUsed(result) {
  const groups = [
    ["Firemní data", result?.company_series_used],
    ["Odvětví", result?.sector_series_used],
    ["Makro", result?.macro_series_used],
    ["Komodity", result?.commodity_series_used],
    ["Finanční trhy", result?.financial_markets_series_used],
    ["Demografie", result?.demographics_series_used],
    ["Kurzy", result?.fx_series_used],
    ["Sousedé", result?.neighbor_series_used],
    ["Partneři", result?.partner_series_used],
    ["Region / EU", result?.eu_series_used],
    ["Globální", result?.global_series_used],
  ];
  const out = [];
  for (const [label, rows] of groups) {
    if (!Array.isArray(rows) || !rows.length) continue;
    out.push({ label, items: rows.map((x) => String(x || "").trim()).filter(Boolean) });
  }
  if (!out.length && Array.isArray(result?.series_used)) {
    out.push({
      label: "Použité řady",
      items: result.series_used.map((x) => String(x || "").trim()).filter(Boolean),
    });
  }
  return out;
}

function buildHighlightsHtml(section) {
  const rows = resolveSectionHighlights(section);
  if (!rows.length) return "";
  const cards = rows
    .map(
      (h) => `
        <div class="hl-card">
          <div class="hl-label">${escapeHtml(h.label)}</div>
          ${h.description ? `<div class="hl-desc">${escapeHtml(h.description)}</div>` : ""}
          <div class="hl-value">${escapeHtml(h.value)}${h.period ? ` <span class="hl-period">${escapeHtml(h.period)}</span>` : ""}</div>
        </div>`
    )
    .join("");
  return `<div class="highlights">${cards}</div>`;
}

function formatDecisionAnswer(answer) {
  const key = String(answer || "").trim().toLowerCase();
  const map = {
    yes: "spíše ano",
    no: "spíše ne",
    rather_no: "spíše ne",
    rather_wait: "spíše počkat",
    consider_exit: "spíše zvažovat exit",
    mixed_positive: "smíšeně příznivé",
    general_outlook: "obecný výhled",
    positive: "spíše příznivé",
    negative: "spíše nepříznivé",
    mixed: "smíšené",
    insufficient_data: "nedostatek dat",
  };
  return map[key] || (key ? String(answer) : "");
}

function extractContradictions(result) {
  const briefing = result?.analysis_score?.economic_briefing;
  if (!briefing || typeof briefing !== "object") return [];
  return Array.isArray(briefing.contradictory_signals)
    ? briefing.contradictory_signals.filter((row) => row && typeof row === "object")
    : [];
}

export function buildExploreReportPrintHtml({ result, exploreMeta, chartCaptures = [], generatedAt }) {
  const meta = exploreMeta && typeof exploreMeta === "object" ? exploreMeta : {};
  const sections = normalizeSections(result);
  const webSources = normalizeWebSources(result);
  const keyItems = normalizeKeyItems(result?.key_numbers);
  const limitations = String(result?.limitations || result?.limitations_cz || "").trim();
  const seriesGroups = collectSeriesUsed(result);
  const decisionScore =
    result?.analysis_score?.decision_score ?? result?.analysis_score?.composite;
  const summaryText = String(result?.assistant_answer_cz || "").trim();
  const economicBriefingText = String(
    result?.economic_briefing_cz || result?.analysis_score?.economic_briefing?.full_text_cs || ""
  ).trim();
  const briefingHeadline = String(result?.analysis_score?.economic_briefing?.headline_cs || "").trim();
  const contradictions = extractContradictions(result);
  const environmentScore = result?.analysis_score?.environment_score;
  const decisionAnswer = formatDecisionAnswer(result?.analysis_score?.decision_answer);
  const intentLabel = String(
    result?.analysis_score?.question_understanding?.recognized_intent_label_cs ||
      result?.analysis_score?.recognized_intent_label_cs ||
      ""
  ).trim();
  const sectionScores =
    result?.analysis_score?.section_scores && typeof result.analysis_score.section_scores === "object"
      ? result.analysis_score.section_scores
      : result?.analysis_score?.sections && typeof result.analysis_score.sections === "object"
        ? result.analysis_score.sections
        : {};

  const titleParts = [
    String(meta.sector || "").trim(),
    String(meta.countries || "").trim(),
  ].filter(Boolean);

  const headlineTitle = titleParts.length ? titleParts.join(" · ") : "Manažerská analýza";

  const coverHtml = `<section class="cover">
    <div class="cover-accent">
      <div class="cover-brand">Bankoapp · Manager Explorer</div>
      <h1 class="cover-title">${escapeHtml(headlineTitle)}</h1>
      ${intentLabel ? `<p class="cover-intent">Záměr dotazu: ${escapeHtml(intentLabel)}</p>` : ""}
      ${meta.question ? `<blockquote class="cover-question">„${escapeHtml(String(meta.question))}“</blockquote>` : ""}
    </div>
    <div class="cover-meta">
      <div class="cover-meta-lines">
        ${meta.relatedSegments ? `<p><strong>Související témata:</strong> ${escapeHtml(String(meta.relatedSegments))}</p>` : ""}
        <p><strong>Vygenerováno:</strong> ${escapeHtml(formatExportDate(generatedAt ? new Date(generatedAt) : new Date()))}</p>
        <p class="cover-note">Interní ekonomický report pro manažerské rozhodování.</p>
      </div>
      ${
        decisionScore != null && Number.isFinite(Number(decisionScore))
          ? `<div class="cover-score">
              <div class="cover-score-label">Decision score</div>
              <div class="cover-score-value">${escapeHtml(formatScore(decisionScore))}</div>
              <div class="cover-score-max">/ 10</div>
              ${decisionAnswer ? `<div class="cover-score-answer">${escapeHtml(decisionAnswer)}</div>` : ""}
              ${
                environmentScore != null && Number.isFinite(Number(environmentScore))
                  ? `<div class="cover-score-env">Prostředí ${escapeHtml(formatScore(environmentScore))}/10</div>`
                  : ""
              }
            </div>`
          : ""
      }
    </div>
  </section>`;

  const scorePills = Object.entries(sectionScores)
    .filter(([, val]) => Number.isFinite(Number(val)))
    .map(([id, val]) => {
      const label = SECTION_SCORE_LABELS[id] || id;
      return `<span class="score-pill">${escapeHtml(label)}: ${escapeHtml(formatScore(val))}</span>`;
    })
    .join("");

  const compositeBlock =
    decisionScore != null && Number.isFinite(Number(decisionScore))
      ? `<section class="report-section score-strip">
          <div class="score-strip-inner">
            <div>
              <div class="composite-label">Shrnutí skóre</div>
              <div class="score-strip-row">
                <div class="score-chip"><span>Decision</span><strong>${escapeHtml(formatScore(decisionScore))}</strong>/10</div>
                ${
                  environmentScore != null && Number.isFinite(Number(environmentScore))
                    ? `<div class="score-chip"><span>Prostředí</span><strong>${escapeHtml(formatScore(environmentScore))}</strong>/10</div>`
                    : ""
                }
              </div>
            </div>
            ${scorePills ? `<div class="score-pills">${scorePills}</div>` : ""}
          </div>
        </section>`
      : "";

  const contradictionsHtml = contradictions.length
    ? `<section class="report-section tensions">
        <h2>Protichůdné signály</h2>
        <ul class="tension-list">
          ${contradictions
            .slice(0, 5)
            .map(
              (row) => `<li>
                ${row.label_cs ? `<strong>${escapeHtml(String(row.label_cs))}:</strong> ` : ""}
                ${escapeHtml(String(row.detail_cs || ""))}
              </li>`
            )
            .join("")}
        </ul>
      </section>`
    : "";

  const economicBriefingHtml = economicBriefingText
    ? `<section class="report-section report-featured">
        <h2>Ekonomický komentář sektoru</h2>
        ${briefingHeadline ? `<p class="featured-lead">${escapeHtml(briefingHeadline)}</p>` : ""}
        <div class="report-summary-text">${escapeHtml(economicBriefingText).replace(/\n/g, "<br/>")}</div>
      </section>`
    : "";

  const summaryHtml = summaryText
    ? `<section class="report-section report-summary-block">
        <h2>Hlavní komentář</h2>
        <div class="report-summary-text">${formatCommentHtml(summaryText)}</div>
      </section>`
    : "";

  const sectionsHtml = sections
    .filter((section) => {
      const id = String(section.id || "").trim().toLowerCase();
      return id !== "conclusion" && id !== "economic_briefing";
    })
    .map((section) => {
      const score =
        section.score != null && Number.isFinite(Number(section.score))
          ? `<span class="section-score">${escapeHtml(formatScore(section.score))}/10</span>`
          : "";
      const sources =
        section.id === "political_situation" && Array.isArray(section.sourceUrls) && section.sourceUrls.length
          ? `<ul class="section-sources">${section.sourceUrls
              .slice(0, 6)
              .map((src) => {
                const url = String(src?.url || "").trim();
                if (!url) return "";
                const title = String(src?.title || url).trim();
                return `<li><a href="${escapeHtml(url)}">${escapeHtml(title)}</a></li>`;
              })
              .filter(Boolean)
              .join("")}</ul>`
          : "";
      return `<section class="report-section${section.id === "political_situation" ? " report-section-political" : ""}">
        <h2>${escapeHtml(section.title)} ${score}</h2>
        ${buildHighlightsHtml(section)}
        ${formatCommentHtml(section.text)}
        ${sources}
      </section>`;
    })
    .join("");

  const keyNumbersHtml = keyItems.length
    ? `<section class="report-section">
        <h2>Klíčová čísla</h2>
        <div class="key-grid">
          ${keyItems
            .map(
              (item) => `<div class="key-card">
                <div class="key-label">${escapeHtml(item.label)}</div>
                ${item.description ? `<div class="key-desc">${escapeHtml(item.description)}</div>` : ""}
                <div class="key-value">${escapeHtml(item.value)}</div>
              </div>`
            )
            .join("")}
        </div>
      </section>`
    : "";

  const chartsHtml = chartCaptures.length
    ? `<section class="report-section page-break">
        <h2>Grafy — podklady analýzy</h2>
        <div class="chart-grid">
          ${chartCaptures
            .map(
              (c) => `<figure class="chart-figure">
                <figcaption>
                  <div class="chart-figure-title">${escapeHtml(c.title)}</div>
                  <p class="chart-figure-source"><strong>Zdroj:</strong> ${escapeHtml(c.source || "Neuvedený zdroj")}</p>
                  ${c.note ? `<p class="chart-figure-note">${escapeHtml(c.note)}</p>` : ""}
                </figcaption>
                <img src="${c.dataUrl}" alt="${escapeHtml(c.title)}" />
              </figure>`
            )
            .join("")}
        </div>
      </section>`
    : "";

  const seriesHtml = seriesGroups.length
    ? `<section class="report-section">
        <h2>Použité datové řady</h2>
        ${seriesGroups
          .map(
            (group) => `<div class="series-group">
              <h3>${escapeHtml(group.label)} (${group.items.length})</h3>
              <ul>${group.items.map((t) => `<li>${escapeHtml(t)}</li>`).join("")}</ul>
            </div>`
          )
          .join("")}
      </section>`
    : "";

  const webSourcesHtml = webSources.length
    ? `<section class="report-section web-sources">
        <h2>Zjištění z webu (mimo interní katalog)</h2>
        <p class="web-sources-note">Katalog k tomuto dotazu nenašel vlastní data — následující zjištění pochází z webu.</p>
        ${webSources
          .map(
            (item) => `<div class="web-source-item">
              <div class="web-source-head">
                <span class="web-source-title">${escapeHtml(item.title)}</span>
                ${
                  WEB_SOURCE_TIER_LABEL[item.sourceTier]
                    ? `<span class="web-source-badge">${escapeHtml(WEB_SOURCE_TIER_LABEL[item.sourceTier])}</span>`
                    : ""
                }
              </div>
              ${
                item.valueText
                  ? `<div class="web-source-value">${escapeHtml(item.valueText)}${item.period ? ` (${escapeHtml(item.period)})` : ""}</div>`
                  : ""
              }
              ${item.summary ? `<div class="web-source-summary">${escapeHtml(item.summary)}</div>` : ""}
              ${
                item.sourceUrls.length
                  ? `<ul class="section-sources">${item.sourceUrls
                      .slice(0, 4)
                      .map((url) => `<li><a href="${escapeHtml(String(url))}">${escapeHtml(String(url))}</a></li>`)
                      .join("")}</ul>`
                  : ""
              }
            </div>`
          )
          .join("")}
      </section>`
    : "";

  const limitationsHtml = limitations
    ? `<section class="report-section">
        <h2>Omezení</h2>
        <p class="section-text muted">${escapeHtml(limitations).replace(/\n/g, "<br/>")}</p>
      </section>`
    : "";

  const fallbackNote = result?.fallback
    ? `<p class="fallback-note">AI analýza nebyla plně dostupná — report obsahuje strukturovanou syntézu ze načtených dat.</p>`
    : "";

  return `<!DOCTYPE html>
<html lang="cs">
<head>
  <meta charset="utf-8" />
  <title>Manažerská analýza${titleParts.length ? ` — ${escapeHtml(titleParts[0])}` : ""}</title>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
  <link href="https://fonts.googleapis.com/css2?family=Manrope:wght@500;600;700;800&display=swap" rel="stylesheet" />
  <style>
    @page { margin: 14mm 12mm; }
    * { box-sizing: border-box; }
    body {
      font-family: "Manrope", "Segoe UI", system-ui, sans-serif;
      color: #1e293b;
      line-height: 1.5;
      font-size: 10.5pt;
      margin: 0;
      padding: 0;
      background: #fff;
    }
    .wrap { max-width: 800px; margin: 0 auto; padding: 0 0 16mm; }
    .cover {
      border: 1px solid hsl(205 45% 84%);
      border-radius: 16px;
      overflow: hidden;
      margin-bottom: 18px;
      page-break-inside: avoid;
      box-shadow: 0 10px 28px hsl(218 55% 25% / 0.1);
    }
    .cover-accent {
      background: linear-gradient(135deg, hsl(202 90% 48%), hsl(218 65% 28%));
      color: #fff;
      padding: 22px 24px 20px;
    }
    .cover-brand {
      font-size: 8.5pt;
      font-weight: 700;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      opacity: 0.82;
      margin-bottom: 8px;
    }
    .cover-title {
      font-size: 20pt;
      font-weight: 800;
      line-height: 1.15;
      margin: 0;
      letter-spacing: -0.02em;
    }
    .cover-intent { margin: 8px 0 0; font-size: 10pt; opacity: 0.92; font-weight: 600; }
    .cover-question {
      margin: 14px 0 0;
      padding-left: 14px;
      border-left: 3px solid rgba(255,255,255,0.45);
      font-size: 11pt;
      font-style: italic;
      font-weight: 500;
      line-height: 1.55;
    }
    .cover-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
      justify-content: space-between;
      padding: 16px 24px 18px;
      background: #fff;
    }
    .cover-meta-lines { flex: 1 1 280px; font-size: 9.5pt; color: #475569; }
    .cover-meta-lines p { margin: 0 0 6px; }
    .cover-note { color: #64748b; margin-top: 8px !important; }
    .cover-score {
      flex: 0 0 auto;
      min-width: 120px;
      border: 1px solid hsl(205 45% 84%);
      border-radius: 14px;
      background: linear-gradient(180deg, hsl(205 75% 96%), #fff);
      padding: 12px 16px;
      text-align: center;
    }
    .cover-score-label {
      font-size: 8pt;
      font-weight: 800;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: hsl(218 65% 28%);
    }
    .cover-score-value {
      font-size: 26pt;
      font-weight: 800;
      color: hsl(202 90% 42%);
      line-height: 1;
      margin-top: 4px;
    }
    .cover-score-max { font-size: 9pt; color: #64748b; font-weight: 600; }
    .cover-score-answer { font-size: 9pt; font-weight: 700; color: #334155; margin-top: 8px; line-height: 1.35; }
    .cover-score-env { font-size: 8pt; color: #64748b; margin-top: 6px; }
    .fallback-note {
      background: #fffbeb; border: 1px solid #fcd34d; border-radius: 10px;
      padding: 10px 12px; font-size: 9.5pt; margin: 0 0 14px;
    }
    .score-strip {
      border: 1px solid hsl(205 45% 84%);
      border-radius: 12px;
      background: linear-gradient(180deg, hsl(205 75% 96%), #fff);
      padding: 0;
    }
    .score-strip-inner { padding: 14px 16px; }
    .composite-label {
      font-size: 8pt;
      text-transform: uppercase;
      letter-spacing: 0.1em;
      color: hsl(218 65% 28%);
      font-weight: 800;
      margin-bottom: 8px;
    }
    .score-strip-row { display: flex; flex-wrap: wrap; gap: 10px; }
    .score-chip {
      font-size: 10pt;
      border: 1px solid hsl(205 45% 84%);
      border-radius: 999px;
      background: #fff;
      padding: 4px 12px;
    }
    .score-chip span { color: #64748b; font-size: 8.5pt; font-weight: 700; text-transform: uppercase; margin-right: 6px; }
    .score-chip strong { color: hsl(202 90% 42%); font-size: 12pt; }
    .score-pills { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
    .score-pill {
      font-size: 8pt; border: 1px solid hsl(205 45% 84%); border-radius: 999px;
      padding: 3px 8px; background: #fff; color: #334155;
    }
    .report-section {
      margin: 16px 0 0;
      page-break-inside: avoid;
      border: 1px solid hsl(205 45% 84%);
      border-radius: 14px;
      padding: 14px 16px;
      background: #fff;
      box-shadow: 0 4px 16px hsl(218 55% 25% / 0.06);
    }
    .report-section.page-break { page-break-before: always; margin-top: 0; border: none; box-shadow: none; padding: 0; }
    .report-featured { border-left: 4px solid hsl(202 90% 52%); }
    .featured-lead { font-size: 11pt; font-weight: 700; color: hsl(218 65% 28%); margin: 0 0 10px; line-height: 1.45; }
    h2 {
      font-size: 8.5pt;
      text-transform: uppercase;
      letter-spacing: 0.12em;
      color: hsl(218 65% 28%);
      margin: 0 0 10px;
      font-weight: 800;
    }
    .report-summary-text {
      font-size: 12pt;
      line-height: 1.65;
      font-weight: 500;
      color: #1e293b;
      margin: 0;
      white-space: pre-wrap;
    }
    .section-score {
      font-size: 9pt;
      color: hsl(202 90% 42%);
      text-transform: none;
      letter-spacing: 0;
      font-weight: 800;
      margin-left: 6px;
    }
    .section-text { margin: 0; white-space: pre-wrap; font-size: 10.5pt; line-height: 1.55; }
    .section-text.muted { color: #64748b; font-size: 9.5pt; }
    .section-bullets { margin: 0.4rem 0 0; padding-left: 1.25rem; font-size: 10.5pt; line-height: 1.55; }
    .section-bullets li { margin: 0.25rem 0; }
    .section-sources { margin: 0.6rem 0 0; padding-left: 1.1rem; font-size: 8.5pt; color: #64748b; }
    .section-sources a { color: #334155; }
    .web-sources-note { font-size: 8pt; color: #64748b; margin: 0 0 10px; }
    .web-source-item {
      border: 1px solid hsl(202 60% 88%);
      border-radius: 10px;
      background: hsl(202 75% 98%);
      padding: 8px 10px;
      margin-bottom: 8px;
    }
    .web-source-item:last-child { margin-bottom: 0; }
    .web-source-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
    .web-source-title { font-size: 9.5pt; font-weight: 700; color: #0f172a; }
    .web-source-badge {
      font-size: 7.5pt; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em;
      color: hsl(160 55% 28%); background: hsl(160 55% 92%); border-radius: 999px; padding: 2px 8px;
      white-space: nowrap;
    }
    .web-source-value { font-size: 10pt; font-weight: 700; color: #1e293b; margin-top: 4px; }
    .web-source-summary { font-size: 9pt; color: #475569; margin-top: 3px; line-height: 1.4; }
    .report-section-political { border-left: 3px solid #a8a29e; padding-left: 0.75rem; }
    .tensions { background: linear-gradient(180deg, hsl(205 75% 97%), #fff); }
    .tension-list { margin: 0; padding-left: 18px; font-size: 10pt; line-height: 1.5; }
    .tension-list li { margin-bottom: 6px; }
    .highlights { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-bottom: 10px; }
    .hl-card {
      border: 1px solid hsl(205 45% 84%);
      border-radius: 10px;
      background: hsl(205 75% 97%);
      padding: 8px 10px;
    }
    .hl-label { font-size: 8.5pt; font-weight: 700; color: #1e293b; line-height: 1.3; }
    .hl-desc { font-size: 7.5pt; color: #64748b; margin-top: 3px; line-height: 1.3; }
    .hl-value { font-size: 11pt; font-weight: 800; margin-top: 4px; color: hsl(218 65% 22%); }
    .hl-period { font-size: 7.5pt; font-weight: 600; color: #64748b; }
    .key-grid { display: flex; flex-wrap: wrap; gap: 8px; }
    .key-card {
      border: 1px solid hsl(205 45% 84%);
      border-radius: 10px;
      background: linear-gradient(180deg, hsl(205 75% 96%), #fff);
      padding: 8px 10px;
      min-width: 140px;
      flex: 1 1 180px;
    }
    .key-label { font-size: 8.5pt; font-weight: 700; color: hsl(218 65% 28%); }
    .key-desc { font-size: 7.5pt; color: #64748b; margin-top: 2px; }
    .key-value { font-size: 11pt; font-weight: 800; margin-top: 4px; color: #0f172a; }
    .chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .chart-figure {
      margin: 0;
      page-break-inside: avoid;
      border: 1px solid hsl(205 45% 84%);
      border-radius: 12px;
      padding: 8px;
      background: #fff;
      box-shadow: 0 4px 14px hsl(218 55% 25% / 0.08);
    }
    .chart-figure figcaption { font-size: 8.5pt; margin-bottom: 6px; color: #334155; }
    .chart-figure-title { font-weight: 800; text-transform: uppercase; letter-spacing: 0.04em; color: hsl(218 65% 28%); margin-bottom: 4px; font-size: 8pt; }
    .chart-figure-source { font-size: 8pt; font-weight: 400; line-height: 1.4; color: #475569; margin: 0 0 4px; }
    .chart-figure-note { font-size: 8pt; font-style: italic; line-height: 1.45; color: #64748b; margin: 0; }
    .chart-figure img { width: 100%; height: auto; display: block; border-radius: 8px; }
    .series-group { margin-bottom: 10px; }
    .series-group h3 { font-size: 9.5pt; margin: 0 0 4px; color: hsl(218 65% 28%); font-weight: 700; }
    .series-group ul { margin: 0; padding-left: 18px; font-size: 9pt; }
    .series-group li { margin-bottom: 3px; }
    .footer {
      margin-top: 20px;
      padding-top: 10px;
      border-top: 1px solid hsl(205 45% 84%);
      font-size: 8pt;
      color: #94a3b8;
      text-align: center;
    }
    @media print {
      body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
      .wrap { max-width: none; }
      .report-section, .cover { box-shadow: none; }
    }
  </style>
</head>
<body>
  <div class="wrap">
    ${coverHtml}
    ${fallbackNote}
    ${keyNumbersHtml}
    ${compositeBlock}
    ${economicBriefingHtml}
    ${contradictionsHtml}
    ${summaryHtml}
    ${sectionsHtml}
    ${chartsHtml}
    ${webSourcesHtml}
    ${limitationsHtml}
    ${seriesHtml}
    <div class="footer">
      Bankoapp · Manager Explorer · Report slouží k interní analýze, není investiční doporučením.
    </div>
  </div>
</body>
</html>`;
}

function withTimeout(promise, ms, label = "operace") {
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      setTimeout(() => reject(new Error(`${label} — časový limit ${Math.round(ms / 1000)} s`)), ms);
    }),
  ]);
}

async function captureSingleChart(node, title) {
  return withTimeout(
    toPng(node, {
      backgroundColor: "#ffffff",
      pixelRatio: 2,
      skipFonts: true,
      cacheBust: true,
      filter: (n) => !(n instanceof Element && n.dataset?.exportIgnore === "true"),
    }),
    CHART_CAPTURE_TIMEOUT_MS,
    `Graf „${title}“`
  );
}

async function captureChartImages(reportEl) {
  if (!reportEl) return { captures: [], skipped: 0, failed: 0 };
  const allNodes = Array.from(reportEl.querySelectorAll("[data-explore-chart-export]")).filter(
    (n) => n instanceof Element
  );
  const skipped = Math.max(0, allNodes.length - CHART_CAPTURE_MAX);
  const nodes = allNodes.slice(0, CHART_CAPTURE_MAX);
  const captures = [];
  let failed = 0;

  for (const node of nodes) {
    const title = node.getAttribute("data-chart-title") || "Graf";
    const note = String(node.getAttribute("data-chart-note") || "").trim();
    const source = String(node.getAttribute("data-chart-source") || "").trim();
    try {
      const dataUrl = await captureSingleChart(node, title);
      captures.push({ title, note, source, dataUrl });
    } catch (err) {
      failed += 1;
      console.warn("explore report chart capture failed:", title, err);
    }
  }

  return { captures, skipped, failed };
}

function openPrintDocument(html) {
  const iframe = document.createElement("iframe");
  iframe.setAttribute("title", "Export PDF — Manager Explorer");
  Object.assign(iframe.style, {
    position: "fixed",
    inset: "0",
    width: "100%",
    height: "100%",
    border: "0",
    zIndex: "99999",
    background: "#fff",
  });
  document.body.appendChild(iframe);

  const win = iframe.contentWindow;
  const doc = iframe.contentDocument || win?.document;
  if (!win || !doc) {
    iframe.remove();
    throw new Error("Prohlížeč neumožnil přípravu tiskového náhledu.");
  }

  doc.open();
  doc.write(html);
  doc.close();

  return { win, iframe };
}

/**
 * Otevře tiskový dialog prohlížeče — cíl „Uložit jako PDF“.
 * @returns {Promise<{ chartCount: number, chartsSkipped: number, chartsFailed: number, partialCharts: boolean }>}
 */
export async function exportExploreReportToPdf({ reportEl, result, exploreMeta }) {
  const runExport = async () => {
    const { captures, skipped, failed } = await captureChartImages(reportEl);
    const html = buildExploreReportPrintHtml({
      result,
      exploreMeta,
      chartCaptures: captures,
      generatedAt: new Date().toISOString(),
    });

    const { win, iframe } = openPrintDocument(html);

    await new Promise((resolve) => {
      const done = () => resolve();
      win.addEventListener("load", done, { once: true });
      setTimeout(done, 800);
    });

    await new Promise((resolve) => {
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        try {
          iframe.remove();
        } catch {
          /* ignore */
        }
        resolve();
      };
      win.addEventListener("afterprint", finish, { once: true });
      try {
        win.focus();
        win.print();
      } catch (err) {
        finish();
        throw err;
      }
      setTimeout(finish, 120000);
    });

    const partialCharts = skipped > 0 || failed > 0;
    return {
      chartCount: captures.length,
      chartsSkipped: skipped,
      chartsFailed: failed,
      partialCharts,
    };
  };

  return withTimeout(runExport(), EXPORT_TOTAL_TIMEOUT_MS, "Export PDF");
}
