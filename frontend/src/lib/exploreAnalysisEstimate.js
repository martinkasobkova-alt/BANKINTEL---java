/**

 * Odhady délky Manager Explorer analýzy.

 *

 * Kalibrace (E2E, 2026-05): Automotive / Polsko, 566 vybraných → 530 refined →

 * ~127 zdrojů pro AI → summarize job ~117 s celkem.

 *

 * Důležité: 600+ řad v UI ≠ 600 fetchů — backend pro AI bere max 50 (128 u kontinentu)

 * + makro/geo kontext + řady ze souvisejících segmentů.

 */



/** Musí odpovídat backend EXPLORE_SUMMARIZE_SECTOR_SERIES_LIMIT */

export const EXPLORE_SUMMARIZE_SECTOR_CAP = 50;

/** Musí odpovídat backend EXPLORE_SUMMARIZE_SECTOR_BROAD_LIMIT */

export const EXPLORE_SUMMARIZE_SECTOR_BROAD_CAP = 128;

const MACRO_CONTEXT_BASE = 35;

const MACRO_CONTINENT_EXTRA = 12;

const RELATED_SEGMENT_WEIGHT = 8;

const RELATED_SEGMENT_CAP = 48;



function clampRound(value, min, max, step = 15) {

  const v = Math.max(min, Math.min(max, value));

  return Math.round(v / step) * step;

}



/** Kolik datových řad backend reálně stáhne pro AI (orientačně). */

export function estimateSummarizeFetchTotal(

  selectedCount = 0,

  geoMode = "countries",

  relatedSegmentsCount = 0,

) {

  const broad = String(geoMode || "").toLowerCase() === "continent";

  const cap = broad ? EXPLORE_SUMMARIZE_SECTOR_BROAD_CAP : EXPLORE_SUMMARIZE_SECTOR_CAP;

  const n = Math.max(0, Number(selectedCount) || 0);

  const sectorFetch = Math.min(n, cap);

  const macro = MACRO_CONTEXT_BASE + (broad ? MACRO_CONTINENT_EXTRA : 0);

  const related = Math.min(

    Math.max(0, Number(relatedSegmentsCount) || 0) * RELATED_SEGMENT_WEIGHT,

    RELATED_SEGMENT_CAP,

  );

  return sectorFetch + macro + related;

}



/** Skutečný počet z progress hintu serveru (fetch plán), pokud je k dispozici. */

export function parseFetchTotalFromServerHint(serverHint) {

  const hint = String(serverHint || "");

  const direct = hint.match(/(\d+)\s*zdroj(?:ů|u)?/i);

  if (direct) {

    const n = Number(direct[1]);

    if (Number.isFinite(n) && n > 0) return n;

  }

  const progress = hint.match(/\((\d+)\s*\/\s*(\d+)\)/);

  if (progress) {

    const total = Number(progress[2]);

    if (Number.isFinite(total) && total > 0) return total;

  }

  return null;

}



/** Odhad jen fáze summarize (fetch + AI sekce + syntéza). */

export function estimateSummarizeDurationSec({

  selectedCount = 0,

  geoMode = "countries",

  relatedSegmentsCount = 0,

} = {}) {

  const fetchTotal = estimateSummarizeFetchTotal(selectedCount, geoMode, relatedSegmentsCount);

  const fetchSec = Math.ceil(fetchTotal / 8) * 3.2 + 10;

  const aiSec = 52 + Math.min(30, Math.floor(fetchTotal / 20) * 5);

  let total = fetchSec + aiSec;

  if (String(geoMode || "").toLowerCase() === "continent") total *= 1.25;

  if (Number(relatedSegmentsCount) > 2) total *= 1.12;

  return clampRound(total, 75, 900, 15);

}



/** Odhad celého kroku 2: načtení řad + refine + summarize. */

export function estimateExplorePipelineSec({

  selectedCount = 0,

  geoMode = "countries",

  relatedSegmentsCount = 0,

} = {}) {

  const broad = String(geoMode || "").toLowerCase() === "continent";

  const sectorLoad = broad ? 35 : 18;

  const refine = Math.min(55, 22 + Math.floor(Math.max(0, Number(selectedCount) || 0) / 40));

  const summarize = estimateSummarizeDurationSec({ selectedCount, geoMode, relatedSegmentsCount });

  return clampRound(sectorLoad + refine + summarize, 90, 960, 15);

}



/** Lidsky čitelný ETA text, např. „~3 min“ nebo „~2 min 30 s“. */

export function formatExploreEtaSec(totalSec) {

  const sec = Math.max(15, Math.round(Number(totalSec) || 0));

  if (sec >= 3600) {

    const h = Math.floor(sec / 3600);

    const m = Math.round((sec % 3600) / 60);

    return m > 0 ? `~${h} h ${m} min` : `~${h} h`;

  }

  if (sec >= 120) {

    const m = Math.floor(sec / 60);

    const s = sec % 60;

    return s >= 20 ? `~${m} min ${s} s` : `~${m} min`;

  }

  if (sec >= 60) {

    const m = Math.floor(sec / 60);

    const s = sec % 60;

    return s > 0 ? `~${m} min ${s} s` : `~1 min`;

  }

  return `~${sec} s`;

}



/** Zbývající sekundy pro odpočet (nikdy pod 0). */

export function formatExploreCountdownSec(secondsLeft) {

  const left = Math.max(0, Math.round(Number(secondsLeft) || 0));

  if (left >= 120) {

    const m = Math.ceil(left / 60);

    return `~${m} min`;

  }

  if (left >= 60) {

    const m = Math.floor(left / 60);

    const s = left % 60;

    return s > 0 ? `~${m} min ${s} s` : `~1 min`;

  }

  return `~${left} s`;

}



/**

 * Upraví odhad podle serverového progress hintu (fetch X/Y, AI sekce X/Y).

 * Vrací nový odhad celkové délky od začátku, nebo null pokud nelze parsovat.

 */

export function adaptSummarizeEstimateFromServerHint(serverHint, { elapsedSec = 0, initialEstimateSec = 120 } = {}) {

  const hint = String(serverHint || "");

  const fetchMatch = hint.match(/\((\d+)\s*\/\s*(\d+)\)/);

  if (fetchMatch && /načítám|nact/i.test(hint)) {

    const done = Number(fetchMatch[1]);

    const total = Number(fetchMatch[2]);

    if (total > 0 && done >= 0) {

      const remainingFetch = Math.ceil((total - done) / 8) * 3.5 + 5;

      const aiReserve = 52 + Math.min(30, Math.floor(total / 20) * 5);

      return Math.max(elapsedSec + remainingFetch + aiReserve, initialEstimateSec);

    }

  }

  const aiMatch = hint.match(/interpretaci\s*\((\d+)\s*\/\s*(\d+)\)/i);

  if (aiMatch) {

    const done = Number(aiMatch[1]);

    const total = Number(aiMatch[2]);

    if (total > 0) {

      const remainingAi = Math.max(15, (total - done) * 7 + 25);

      return Math.max(elapsedSec + remainingAi, initialEstimateSec);

    }

  }

  if (/finální manažerské|finalni managerske/i.test(hint)) {

    return Math.max(elapsedSec + 20, initialEstimateSec);

  }

  return null;

}



/** Text pro loadHint — kolik řad vs kolik jde do AI. */

export function buildExplorePipelineEtaHint({

  selectedCount = 0,

  geoMode = "countries",

  relatedSegmentsCount = 0,

  actualFetchTotal = null,

} = {}) {

  const pipelineSec = estimateExplorePipelineSec({ selectedCount, geoMode, relatedSegmentsCount });

  const eta = formatExploreEtaSec(pipelineSec);

  const fetchLine = formatPipelineFetchLine({

    selectedCount,

    geoMode,

    relatedSegmentsCount,

    actualFetchTotal,

  });

  if (fetchLine) {

    return `Odhadovaná doba analýzy ${eta} (${fetchLine}).`;

  }

  return `Odhadovaná doba analýzy ${eta}.`;

}



/** Jednotný fetch total — server má prioritu před odhadem. */

export function resolvePipelineFetchTotal({

  selectedCount = 0,

  geoMode = "countries",

  relatedSegmentsCount = 0,

  actualFetchTotal = null,

} = {}) {

  if (actualFetchTotal != null && Number.isFinite(Number(actualFetchTotal))) {

    return Number(actualFetchTotal);

  }

  return estimateSummarizeFetchTotal(selectedCount, geoMode, relatedSegmentsCount);

}



/** Krátký popis fetch plánu pro běžící pipeline (jedna konzistentní věta). */

export function formatPipelineFetchLine({

  selectedCount = 0,

  geoMode = "countries",

  relatedSegmentsCount = 0,

  actualFetchTotal = null,

} = {}) {

  const n = Math.max(0, Number(selectedCount) || 0);

  if (!n) return "";

  const fetchTotal = resolvePipelineFetchTotal({

    selectedCount: n,

    geoMode,

    relatedSegmentsCount,

    actualFetchTotal,

  });

  if (fetchTotal === n) {

    return `AI stáhne cca ${fetchTotal} datových řad`;

  }

  return `${n} řad po geo refine → AI stáhne cca ${fetchTotal} datových řad`;

}


