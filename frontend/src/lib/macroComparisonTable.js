const VALUE_KEYS = ["value", "OBS_VALUE", "obs_value", "Value", "y", "amount", "val"];
const PERIOD_KEYS = ["date", "TIME_PERIOD", "period", "time", "TIME", "x"];

function readObservationFromRow(row) {
  if (!row || typeof row !== "object") return null;
  let value = null;
  for (const k of VALUE_KEYS) {
    if (row[k] != null && row[k] !== "") {
      const n = Number(row[k]);
      if (Number.isFinite(n)) {
        value = n;
        break;
      }
    }
  }
  if (value == null) return null;
  let period = null;
  for (const k of PERIOD_KEYS) {
    if (row[k] != null && String(row[k]).trim()) {
      period = String(row[k]).trim();
      break;
    }
  }
  return { value, period };
}

function compareObservationDirection(current, previous) {
  if (!current || previous == null || !Number.isFinite(previous)) {
    return { direction: "neutral", delta: null };
  }
  const delta = current - previous;
  if (delta > 0) return { direction: "up", delta };
  if (delta < 0) return { direction: "down", delta };
  return { direction: "neutral", delta: 0 };
}

/** Extrahuje poslední numerickou hodnotu z náhledu katalogové řady. */
export function extractLatestPreviewObservation(preview) {
  const pair = extractPreviewObservationPair(preview);
  return {
    value: pair.value,
    period: pair.period,
  };
}

/** Poslední a předchozí hodnota — pro barvení buňky podle změny oproti minulému období. */
export function extractPreviewObservationPair(preview) {
  const rows = Array.isArray(preview?.rows) ? preview.rows : [];
  const observations = [];
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const obs = readObservationFromRow(rows[i]);
    if (!obs) continue;
    observations.push(obs);
    if (observations.length >= 2) break;
  }
  const current = observations[0] || { value: null, period: null };
  const previous = observations[1] || null;
  const { direction, delta } = compareObservationDirection(
    current.value,
    previous?.value ?? null,
  );
  return {
    value: current.value,
    period: current.period,
    previousValue: previous?.value ?? null,
    previousPeriod: previous?.period ?? null,
    direction,
    delta,
  };
}

export function formatComparisonCellValue(value, column) {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  const n = Number(value);
  const kind = column?.value_kind_override || column?.value_kind || "number";
  if (kind === "percent") {
    return `${n.toLocaleString("cs-CZ", { maximumFractionDigits: 2, minimumFractionDigits: 0 })}%`;
  }
  if (kind === "inflation_yoy") {
    return `${n.toLocaleString("cs-CZ", { maximumFractionDigits: 2, minimumFractionDigits: 0 })}%`;
  }
  if (kind === "million_eur") {
    if (Math.abs(n) >= 1_000_000) {
      return `${(n / 1_000_000).toLocaleString("cs-CZ", { maximumFractionDigits: 2 })} bil. EUR`;
    }
    if (Math.abs(n) >= 1_000) {
      return `${(n / 1_000).toLocaleString("cs-CZ", { maximumFractionDigits: 1 })} mld. EUR`;
    }
    return `${n.toLocaleString("cs-CZ", { maximumFractionDigits: 0 })} mil. EUR`;
  }
  if (kind === "million_usd") {
    if (Math.abs(n) >= 1_000_000) {
      return `${(n / 1_000_000).toLocaleString("cs-CZ", { maximumFractionDigits: 2 })} bil. USD`;
    }
    if (Math.abs(n) >= 1_000) {
      return `${(n / 1_000).toLocaleString("cs-CZ", { maximumFractionDigits: 1 })} mld. USD`;
    }
    return `${n.toLocaleString("cs-CZ", { maximumFractionDigits: 0 })} mil. USD`;
  }
  if (kind === "eur") {
    return `${n.toLocaleString("cs-CZ", { maximumFractionDigits: 0 })} EUR`;
  }
  if (kind === "usd") {
    return `${n.toLocaleString("cs-CZ", { maximumFractionDigits: 0 })} USD`;
  }
  if (kind === "eur_kwh") {
    return `${n.toLocaleString("cs-CZ", { maximumFractionDigits: 4 })} EUR/kWh`;
  }
  if (kind === "count") {
    if (Math.abs(n) >= 1_000_000) {
      return `${(n / 1_000_000).toLocaleString("cs-CZ", { maximumFractionDigits: 2 })} mil.`;
    }
    if (Math.abs(n) >= 1_000) {
      return `${(n / 1_000).toLocaleString("cs-CZ", { maximumFractionDigits: 1 })} tis.`;
    }
    return n.toLocaleString("cs-CZ", { maximumFractionDigits: 0 });
  }
  return n.toLocaleString("cs-CZ", { maximumFractionDigits: 2 });
}

/** Směr změny vůči předchozímu období — modrá = růst, červená = pokles. */
export function comparisonCellDirection(cellData) {
  const dir = String(cellData?.direction || "").trim().toLowerCase();
  if (dir === "up") return "up";
  if (dir === "down") return "down";
  if (cellData?.value == null || !Number.isFinite(Number(cellData.value))) return "muted";
  return "neutral";
}

/** @deprecated Použij comparisonCellDirection — barva podle směru změny, ne „dobré/špatné“. */
export function comparisonCellTone(value, column) {
  if (value == null || !Number.isFinite(Number(value))) return "muted";
  const positiveGood = column?.positive_good;
  if (positiveGood === true) return Number(value) > 0 ? "up" : Number(value) < 0 ? "down" : "neutral";
  if (positiveGood === false) return Number(value) > 0 ? "down" : Number(value) < 0 ? "up" : "neutral";
  return "neutral";
}

export async function mapWithConcurrency(items, limit, worker) {
  const out = new Array(items.length);
  let idx = 0;
  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (idx < items.length) {
      const i = idx;
      idx += 1;
      out[i] = await worker(items[i], i);
    }
  });
  await Promise.all(runners);
  return out;
}
