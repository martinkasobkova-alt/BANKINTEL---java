import React, { useEffect, useMemo, useRef, useState } from "react";
import { Loader2 } from "lucide-react";

const TIPS = [
  `Aplikace je napojena celkem na 10 datových zdrojů — ČNB, ARAD, BIS, ČSÚ, World Bank, ECB, Eurostat, FRED, IMF a OECD.`,
  `Pokud hledáte ve všech zdrojích, AI sama vybere ten nejrelevantnější pro váš dotaz.`,
  `Hledání ve všech zdrojích je pomalejší než hledání jen ve vybraném zdroji.`,
  `Víte, v jakém zdroji je vaše řada? Vyberte ho — hledání bude výrazně rychlejší.`,
  `Nalezené řady mohou obsahovat další dimenze, například geografické nebo typové (inflace potravin, služeb…).`,
  `Nevíte co hledat? Zkuste zadat jen zemi a téma — například "Německo inflace" nebo "USA úrokové sazby".`,
  `Výsledky jsou seřazeny podle relevance — řady s nejlepší shodou s dotazem jsou zobrazeny první.`,
  `Řady označené jako Ověřeno odpovídají dotazu s vysokou pravděpodobností. Možné jsou méně jisté kandidáty.`,
  `Nalezené řady lze přidat do přehledu a porovnat je přímo v grafu.`,
  `Každá řada má odkaz na původní datový zdroj — vždy si můžete ověřit původ dat.`,
  `Pro upřesnění výsledků použijte vstup pod výsledky — například "chci jen česká data" nebo "přidej Německo".`,
  `FRED pokrývá stovky tisíc makroekonomických řad — úrokové sazby, trh práce, inflaci i finanční trhy USA a světa.`,
  `Eurostat pokrývá statistiky zemí EU, včetně regionálních a sektorových dat.`,
  `Data z BIS jsou klíčová pro analýzy bankovního sektoru, platebních systémů a finančních toků.`,
];

const PHASES_ROUTING = [
  "AI vybírá vhodné katalogy podle významu dotazu…",
  "Analyzuji doménu a geografii dotazu…",
];

const PHASES_SEARCH = [
  "Prohledávám lokální index vybraných katalogů…",
  "Hodnotím relevanci kandidátů…",
  "AI vybírá nejvhodnější řady…",
];

const SOURCE_SCAN_MESSAGES = [
  "Prohledávám lokální index katalogu",
  "Fulltext v metadatích a popisech",
  "Hodnotím shodu s dotazem",
];

const PROGRESS_CAP = 88;

function abbrevSourceLabel(label) {
  const s = String(label || "").trim();
  if (!s) return "ZDROJ";
  if (s.length <= 11) return s.toUpperCase();
  const first = s.split(/\s+/)[0];
  if (first.length >= 4) return first.toUpperCase();
  return s.slice(0, 10).toUpperCase();
}

function buildScanLines(sourceIds, sourceLabelFn) {
  const ids = (Array.isArray(sourceIds) ? sourceIds : []).filter(Boolean);
  if (!ids.length) {
    return [{ source: "ROUTER", title: "Vybírám vhodné katalogy pro dotaz…" }];
  }
  const lines = [];
  for (const id of ids) {
    const label = abbrevSourceLabel(sourceLabelFn(id));
    for (const msg of SOURCE_SCAN_MESSAGES) {
      lines.push({ source: label, title: msg });
    }
  }
  return lines;
}

function runningSourceLabel(sourceStatuses, sourceLabelFn) {
  const running = (Array.isArray(sourceStatuses) ? sourceStatuses : []).find(
    (st) => String(st?.status || "").toLowerCase() === "running",
  );
  if (!running) return null;
  const id = String(running.source || running.label || "").trim();
  if (!id) return null;
  return abbrevSourceLabel(running.label || sourceLabelFn(id));
}

export default function CatalogDeepSearchLoader({
  active,
  query,
  sourceIds = [],
  sourceLabelFn = (id) => String(id || ""),
  sourceStatuses = [],
  estimateSec = 70,
  onStop,
}) {
  const hasRoutedSources = Array.isArray(sourceIds) && sourceIds.length > 0;
  const phases = hasRoutedSources ? PHASES_SEARCH : PHASES_ROUTING;
  const lines = useMemo(() => buildScanLines(sourceIds, sourceLabelFn), [sourceIds, sourceLabelFn]);
  const statusesRef = useRef(sourceStatuses);
  statusesRef.current = sourceStatuses;

  const [recent, setRecent] = useState([]);
  const [phaseIdx, setPhaseIdx] = useState(0);
  const [tick, setTick] = useState(0);
  const [secondsLeft, setSecondsLeft] = useState(estimateSec);
  const [elapsedSec, setElapsedSec] = useState(0);
  const [tipIdx, setTipIdx] = useState(() => Math.floor(Math.random() * TIPS.length));
  const [tipVisible, setTipVisible] = useState(true);

  const queryPreview = String(query || "").trim();
  const shortQuery =
    queryPreview.length > 72 ? `${queryPreview.slice(0, 69).trim()}…` : queryPreview;

  useEffect(() => {
    if (!active) {
      setRecent([]);
      setPhaseIdx(0);
      setTick(0);
      setSecondsLeft(estimateSec);
      setElapsedSec(0);
      return undefined;
    }
    setSecondsLeft(estimateSec);
    setElapsedSec(0);
    let lineIdx = 0;
    const fast = setInterval(() => {
      const line = { ...lines[lineIdx % lines.length] };
      lineIdx += 1;
      const runningLabel = runningSourceLabel(statusesRef.current, sourceLabelFn);
      if (runningLabel) line.source = runningLabel;
      setRecent((prev) => [...prev.slice(-11), line]);
      setTick((t) => t + 1);
    }, 68);
    const slow = setInterval(() => {
      setPhaseIdx((p) => (p + 1) % phases.length);
    }, 2300);
    const countdown = setInterval(() => {
      setElapsedSec((prev) => prev + 1);
      setSecondsLeft((prev) => Math.max(0, prev - 1));
    }, 1000);
    // Tip rotation: fade out → change → fade in every 6 seconds
    const tipTimer = setInterval(() => {
      setTipVisible(false);
      setTimeout(() => {
        setTipIdx((i) => (i + 1) % TIPS.length);
        setTipVisible(true);
      }, 500);
    }, 6000);

    return () => {
      clearInterval(fast);
      clearInterval(slow);
      clearInterval(countdown);
      clearInterval(tipTimer);
    };
  }, [active, lines, estimateSec, sourceLabelFn, phases.length]);

  if (!active) return null;

  const current = recent[recent.length - 1];
  const inOvertime = elapsedSec >= estimateSec;
  const progressPct = inOvertime
    ? Math.min(97, PROGRESS_CAP + Math.floor((elapsedSec - estimateSec) / 4))
    : Math.min(PROGRESS_CAP, Math.round((elapsedSec / estimateSec) * PROGRESS_CAP));
  const countdownLabel = inOvertime
    ? `stále prohledávám · ${elapsedSec} s`
    : `zbývá ~${secondsLeft} s`;
  const countdownHint = inOvertime
    ? "Vyhledávání trvá déle než obvykle — stále běží na serveru"
    : "Orientační odhad — může trvat i déle";

  return (
    <div
      className="rounded-xl border border-emerald-400/40 bg-slate-950 shadow-lg overflow-hidden"
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label={`Probíhá AI vyhledávání v katalozích, ${countdownLabel}`}
    >
      <div className="flex items-center justify-between gap-2 px-3 py-2 border-b border-emerald-500/25 bg-slate-900/95">
        <div className="flex items-center gap-2 min-w-0">
          <span className="relative flex h-2 w-2 shrink-0">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-400" />
          </span>
          <span className="text-[11px] font-mono uppercase tracking-wide text-emerald-200/95 truncate">
            {phases[phaseIdx]}
          </span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <span
            className={`text-[10px] font-mono tabular-nums whitespace-nowrap ${
              inOvertime ? "text-amber-300/95" : "text-emerald-300/90"
            }`}
            title={countdownHint}
          >
            {countdownLabel}
          </span>
          <Loader2 className="h-3.5 w-3.5 animate-spin text-emerald-400" />
          {onStop ? (
            <button
              type="button"
              className="h-7 px-2 inline-flex items-center justify-center gap-1 rounded-md border border-amber-400/50 bg-amber-900/40 text-amber-200 text-[10px] font-semibold hover:bg-amber-800/60 hover:text-white whitespace-nowrap"
              onClick={onStop}
              aria-label="Zastavit hledání a zachovat nalezené kandidáty"
              title="Zastaví hledání — nalezené kandidáty zůstanou"
            >
              Zastavit
            </button>
          ) : null}
        </div>
      </div>

      {shortQuery ? (
        <div className="px-3 py-1.5 border-b border-emerald-500/15 bg-emerald-950/40 text-[11px] text-emerald-100/90 truncate">
          <span className="text-emerald-400/90 font-medium">Dotaz:</span> {shortQuery}
        </div>
      ) : null}

      {hasRoutedSources ? (
        <div className="px-3 py-1.5 border-b border-emerald-500/15 bg-slate-900/60 text-[10px] text-emerald-200/85 truncate">
          <span className="text-emerald-400/90 font-medium">Katalogy:</span>{" "}
          {sourceIds.map((id) => sourceLabelFn(id)).join(" · ")}
        </div>
      ) : null}

      <div className="h-0.5 bg-slate-800" aria-hidden>
        <div
          className="h-full bg-gradient-to-r from-emerald-600 to-teal-400 transition-[width] duration-1000 ease-linear"
          style={{ width: `${progressPct}%` }}
        />
      </div>

      <div className="relative h-[132px] overflow-hidden px-3 py-2 font-mono text-[11px] leading-relaxed">
        <div
          className="pointer-events-none absolute inset-x-0 top-0 h-8 z-10 bg-gradient-to-b from-slate-950 to-transparent"
          aria-hidden
        />
        <div
          className="pointer-events-none absolute inset-x-0 bottom-0 h-10 z-10 bg-gradient-to-t from-slate-950 to-transparent"
          aria-hidden
        />
        <ul className="space-y-0.5">
          {recent.map((line, idx) => {
            const age = recent.length - 1 - idx;
            const opacity = age === 0 ? 1 : age === 1 ? 0.55 : age === 2 ? 0.32 : 0.14;
            return (
              <li
                key={`${line.source}-${line.title}-${idx}-${tick}`}
                className="flex gap-2 truncate transition-opacity duration-75"
                style={{ opacity }}
              >
                <span className="shrink-0 text-emerald-400/90 w-[4.5rem]">{line.source}</span>
                <span className="truncate text-emerald-50/95">{line.title}</span>
              </li>
            );
          })}
        </ul>
        {current ? (
          <div className="absolute bottom-2 left-3 right-3 z-20 rounded-lg border border-emerald-400/35 bg-emerald-950/80 px-2.5 py-1.5 shadow-md">
            <div className="text-[9px] uppercase tracking-widest text-emerald-400/80 mb-0.5">
              {hasRoutedSources ? "Právě procházím" : "Příprava"}
            </div>
            <div className="flex gap-2 text-[11px] animate-pulse">
              <span className="text-teal-300 shrink-0">{current.source}</span>
              <span className="text-white truncate">{current.title}</span>
            </div>
          </div>
        ) : null}
      </div>

      <div className="h-1 bg-slate-800 overflow-hidden">
        <div className="h-full w-1/3 bg-gradient-to-r from-transparent via-emerald-400 to-transparent animate-[explore-scan_1.1s_ease-in-out_infinite]" />
      </div>

      <div className="px-3 py-2 border-t border-emerald-500/15 bg-slate-900/70 min-h-[44px] flex items-start gap-2">
        <span className="shrink-0 mt-0.5 text-[9px] font-semibold uppercase tracking-widest text-emerald-400/70">Tip</span>
        <p
          className="text-[11px] text-emerald-100/75 leading-snug transition-opacity duration-500"
          style={{ opacity: tipVisible ? 1 : 0 }}
        >
          {TIPS[tipIdx]}
        </p>
      </div>
    </div>
  );
}
