import api, { formatApiErrorFromAxios } from "@/lib/api";

const YEAR_RE = /(?:19|20)\d{2}/;

export function extractYearFromPeriod(period) {
  const m = YEAR_RE.exec(String(period || ""));
  return m ? Number(m[0]) : null;
}

export function freshnessMinYear(now = new Date()) {
  return now.getFullYear() - 2;
}

export function isChartPeriodStale(period, minYear = freshnessMinYear()) {
  const year = extractYearFromPeriod(period);
  if (year == null) return false;
  return year < minYear;
}

const TIME_KEYS = ["date", "period", "time", "x", "gas_day", "obs_time"];

function pickTimeField(rows, explicit) {
  if (explicit && rows?.[0] && Object.prototype.hasOwnProperty.call(rows[0], explicit)) {
    return explicit;
  }
  if (!Array.isArray(rows) || !rows.length || typeof rows[0] !== "object") return null;
  for (const key of TIME_KEYS) {
    if (Object.prototype.hasOwnProperty.call(rows[0], key)) return key;
  }
  return null;
}

function pickValueField(rows, explicit) {
  if (explicit && rows?.[0] && Object.prototype.hasOwnProperty.call(rows[0], explicit)) {
    return explicit;
  }
  if (!Array.isArray(rows) || !rows.length || typeof rows[0] !== "object") return null;
  const keys = Object.keys(rows[0]).filter((k) => !TIME_KEYS.includes(k) && k !== "label" && k !== "name");
  return keys[0] || null;
}

function periodSortKey(period) {
  const digits = String(period || "").replace(/\D/g, "");
  return digits ? Number(digits.padEnd(8, "0").slice(0, 8)) : 0;
}

/**
 * Poslední bod v grafových řádcích (stejná logika jako backend freshness gate).
 */
export function extractLastChartObservation(rows, { timeField, valueField } = {}) {
  if (!Array.isArray(rows) || !rows.length) return null;
  const tf = pickTimeField(rows, timeField);
  if (!tf) return null;
  const vf = pickValueField(rows, valueField);
  let best = null;
  for (const row of rows) {
    if (!row || typeof row !== "object") continue;
    const period = String(row[tf] ?? "").trim();
    if (!period) continue;
    const value = vf != null ? row[vf] : undefined;
    if (!best || periodSortKey(period) >= periodSortKey(best.period)) {
      best = { period, value };
    }
  }
  return best;
}

/**
 * Faktické shrnutí grafu z řádků (první/poslední/min/max/změna) — posílá se do /explain-series jako
 * `chart_summary`, aby AI vysvětlení mluvilo o TOMTO grafu, ne jen obecnou definici, i tam, kde volající
 * nemá plný ChartDataContract (✨ v exploreru / widgetech). Vrací null, když nejsou aspoň 2 číselné body.
 */
export function summarizeChartRows(rows, { timeField, valueField } = {}) {
  if (!Array.isArray(rows) || rows.length === 0) return null;
  const tf = pickTimeField(rows, timeField);
  const vf = pickValueField(rows, valueField);
  if (!tf || !vf) return null;
  const pts = [];
  for (const row of rows) {
    if (!row || typeof row !== "object") continue;
    const period = String(row[tf] ?? "").trim();
    if (!period) continue;
    const value = Number(row[vf]);
    if (!Number.isFinite(value)) continue;
    pts.push({ period, value });
  }
  if (pts.length < 2) return null;
  pts.sort((a, b) => periodSortKey(a.period) - periodSortKey(b.period));
  const first = pts[0];
  const last = pts[pts.length - 1];
  let minPt = pts[0];
  let maxPt = pts[0];
  for (const p of pts) {
    if (p.value < minPt.value) minPt = p;
    if (p.value > maxPt.value) maxPt = p;
  }
  const delta = last.value - first.value;
  const summary = {
    first_period: first.period,
    first_value: first.value,
    last_period: last.period,
    last_value: last.value,
    min_value: minPt.value,
    min_period: minPt.period,
    max_value: maxPt.value,
    max_period: maxPt.period,
    delta,
    n_points: pts.length,
  };
  if (first.value !== 0) summary.delta_pct = (delta / Math.abs(first.value)) * 100;
  return summary;
}

export function enrichChartExplainMeta(baseMeta, rows, { timeField, valueField } = {}) {
  const meta = { ...(baseMeta || {}) };
  const last = extractLastChartObservation(rows, { timeField, valueField });
  if (last?.period) {
    meta.last_period = last.period;
    if (last.value != null && last.value !== "") meta.last_value = last.value;
  }
  if (Array.isArray(rows) && rows.length) meta.row_count = rows.length;
  if (meta.last_period) meta.data_stale = isChartPeriodStale(meta.last_period);
  const summary = summarizeChartRows(rows, { timeField, valueField });
  if (summary) {
    const label = String(baseMeta?.title || baseMeta?.name || "").trim();
    if (label) summary.label = label;
    const unit = String(baseMeta?.unit || "").trim();
    if (unit) summary.unit = unit;
    meta.chart_summary = summary;
  }
  return meta;
}

export async function fetchChartDataQuality(meta) {
  try {
    const { data } = await api.post("/catalog/chart-data-quality", meta, { timeout: 15000 });
    return data;
  } catch (e) {
    const status = Number(e?.response?.status || 0);
    if (status === 404 || status === 502 || status === 503 || status === 0) {
      const lastPeriod = String(meta?.last_period || "").trim();
      const stale = Boolean(meta?.data_stale) || isChartPeriodStale(lastPeriod);
      if (!stale) return { ok: true, stale: false, last_period: lastPeriod || null };
      return {
        ok: true,
        stale: true,
        last_period: lastPeriod || null,
        notice_cs: lastPeriod
          ? `Poslední hodnota v grafu je z období ${lastPeriod} — data mohou být u zdroje ukončená nebo zvolen špatný ukazatel.`
          : "Data v grafu vypadají zastarale — ověřte definici u vydavatele.",
        detail_cs:
          "Zdroj může tuto řadu už neaktualizovat. Zkuste ✨ vysvětlení nebo vyhledat aktuálnější řadu stejného tématu.",
        suggested_questions: [
          "Proč data končí tak brzy?",
          "Je to správný ukazatel?",
          "Kde najdu aktuálnější řadu?",
        ],
        source: "local_fallback",
      };
    }
    return { ok: false, message_cs: formatApiErrorFromAxios(e) || "Kontrola dat se nepodařila." };
  }
}
