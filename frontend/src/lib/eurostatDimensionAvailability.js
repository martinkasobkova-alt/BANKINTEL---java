import api from "@/lib/api";

export const INCOMPLETE_AVAILABILITY_NOTICE_CS =
  "Dostupné hodnoty jsou ověřené, ale výběr může být u tohoto velkého datasetu neúplný.";

const AVAILABILITY_MODE_LABELS = {
  latest_only: "poslední dostupné období",
  any_time: "historický rozsah",
  time_range: "zvolené časové období",
};

export function formatTimeFilterUsed(timeFilter) {
  if (!timeFilter || typeof timeFilter !== "object") return "";
  const parts = [];
  const since = String(timeFilter.sinceTimePeriod || "").trim();
  const until = String(timeFilter.untilTimePeriod || "").trim();
  const last = String(timeFilter.lastTimePeriod || "").trim();
  if (since && until && since !== until) {
    parts.push(`od ${since} do ${until}`);
  } else if (since) {
    parts.push(`od ${since}`);
  } else if (until) {
    parts.push(`do ${until}`);
  }
  if (last) {
    parts.push(`posledních ${last} období`);
  }
  return parts.join(", ");
}

export function buildAvailabilityBannerLines(cascade) {
  if (!cascade || typeof cascade !== "object") return [];
  const lines = [];
  const mode = String(cascade.availability_mode || "").trim().toLowerCase();
  const timeText = formatTimeFilterUsed(cascade.time_filter_used);
  const method = String(cascade.method_used || cascade.method || "").trim();

  if (mode === "latest_only") {
    lines.push("Dostupnost je ověřena pro poslední dostupné období.");
  } else if (mode === "any_time") {
    lines.push("Zobrazujeme hodnoty dostupné v historickém rozsahu.");
  } else if (mode === "time_range") {
    lines.push("Dostupnost je ověřena pro zvolené časové období.");
  }

  if (mode && AVAILABILITY_MODE_LABELS[mode]) {
    lines.push(`Režim: ${AVAILABILITY_MODE_LABELS[mode]}.`);
  }
  if (timeText) {
    lines.push(`Časový filtr: ${timeText}.`);
  }
  if (method && method !== "cache") {
    lines.push(`Metoda ověření: ${method}.`);
  }
  if (cascade.complete === false) {
    lines.push("Výběr může být neúplný, protože dostupnost byla ověřena jen na části kandidátů.");
  }
  if (cascade.candidate_limit_hit === true) {
    lines.push("Počet ověřených kandidátů byl omezen kvůli velikosti datasetu.");
  }
  const extraWarnings = Array.isArray(cascade.availability_warnings) ? cascade.availability_warnings : [];
  if (extraWarnings.includes("any_time_fallback_to_latest_only")) {
    lines.push("Historický rozsah nevrátil data — použit bezpečný fallback na poslední období.");
  }
  return lines.filter(Boolean);
}

export function shouldShowIncompleteAvailabilityWarning(cascade) {
  if (!cascade || typeof cascade !== "object") return false;
  return cascade.complete === false || cascade.candidate_limit_hit === true;
}

export function incompleteAvailabilityNotice(cascade) {
  if (!shouldShowIncompleteAvailabilityWarning(cascade)) return "";
  return cascade.incomplete_notice_cs || INCOMPLETE_AVAILABILITY_NOTICE_CS;
}

export async function fetchEurostatCascadeState({
  datasetId,
  selectedDimensions = {},
  userQuery = "",
  geoIntent = null,
  availabilityMode = null,
}) {
  const sid = String(datasetId || "").trim();
  if (!sid) return null;
  const { data } = await api.post(`/eurostat/datasets/${encodeURIComponent(sid)}/dimension-availability`, {
    selected_dimensions: selectedDimensions,
    user_query: userQuery,
    geo_intent: geoIntent && typeof geoIntent === "object" ? geoIntent : undefined,
    availability_mode: availabilityMode || undefined,
  });
  return data;
}

export async function fetchEurostatDimensionOptions({
  datasetId,
  selectedDimensions = {},
  targetDimension,
  userQuery = "",
}) {
  const sid = String(datasetId || "").trim();
  const target = String(targetDimension || "").trim();
  if (!sid || !target) return null;
  const { data } = await api.post(`/eurostat/datasets/${encodeURIComponent(sid)}/dimension-availability`, {
    selected_dimensions: selectedDimensions,
    target_dimension: target,
    user_query: userQuery,
  });
  return data;
}

export async function fetchEurostatDimensionDefaults({
  datasetId,
  userQuery = "",
  geoIntent = null,
}) {
  const sid = String(datasetId || "").trim();
  if (!sid) return null;
  const { data } = await api.post(`/eurostat/datasets/${encodeURIComponent(sid)}/dimension-defaults`, {
    user_query: userQuery,
    geo_intent: geoIntent && typeof geoIntent === "object" ? geoIntent : undefined,
  });
  return data;
}
