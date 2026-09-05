import { useEffect, useState } from "react";
import api from "@/lib/api";
import { addCleanIndicatorLabel } from "@/lib/indicatorLabels";

/**
 * Názvy ARAD ukazatelů pro jednu sadu — jedno načtení sdílené celou aplikací.
 *
 * Dřív si `/arad/catalog/set-indicators` tahaly tři komponenty nezávisle a ostatní
 * (výběr řady pro widget, výběr pro KPI dlaždici) vůbec, takže tam zůstávaly popisky
 * z náhledu — u ARAD často jen měna. Cache je na úrovni modulu, takže druhé otevření
 * téhož výběru už nesahá na síť.
 */

/** @type {Map<string, Record<string, string>>} */
const cache = new Map();
/** @type {Map<string, Promise<Record<string, string>>>} */
const inFlight = new Map();

function normalizeSetId(setId) {
  return String(setId ?? "").trim();
}

async function fetchLabels(setId) {
  const { data } = await api.get("/arad/catalog/set-indicators", {
    params: { set_id: setId },
    timeout: 20000,
  });
  const out = {};
  const list = Array.isArray(data?.indicators) ? data.indicators : [];
  for (const item of list) {
    const id = item?.indicator_id || item?.id || item?.code || item?.value;
    const label = item?.name || item?.label || item?.indicator_name || item?.full_name;
    addCleanIndicatorLabel(out, id, label);
  }
  return out;
}

/**
 * Načte mapu `id → název`. Neúspěch není chyba, jen prázdná mapa — popisky pak
 * doplní `withDistinctLabels`, takže seznam zůstane použitelný i bez názvů.
 *
 * @param {string} setId
 * @returns {Record<string, string>}
 */
export function useAradIndicatorLabels(setId) {
  const key = normalizeSetId(setId);
  const [labels, setLabels] = useState(() => cache.get(key) || {});

  useEffect(() => {
    if (!key) {
      setLabels({});
      return undefined;
    }
    const cached = cache.get(key);
    if (cached) {
      setLabels(cached);
      return undefined;
    }

    let cancelled = false;
    let pending = inFlight.get(key);
    if (!pending) {
      pending = fetchLabels(key)
        .catch(() => ({}))
        .then((out) => {
          cache.set(key, out);
          inFlight.delete(key);
          return out;
        });
      inFlight.set(key, pending);
    }
    pending.then((out) => {
      if (!cancelled) setLabels(out);
    });

    return () => {
      cancelled = true;
    };
  }, [key]);

  return labels;
}

/** Jen pro testy — cache přežívá mezi případy a zamaskovala by regresi. */
export function __clearAradIndicatorLabelCache() {
  cache.clear();
  inFlight.clear();
}
