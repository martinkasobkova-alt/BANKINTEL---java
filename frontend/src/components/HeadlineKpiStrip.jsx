/**
 * HeadlineKpiStrip — horizontální lišta s klíčovými makroekonomickými ukazateli.
 *
 * Zobrazuje se nad widgety na homepage / sekci. Admin může ukazatele přidat,
 * odebrat a přeuspořádat přes HeadlineKpiAdminPanel.
 *
 * Props:
 *   mode     – "homepage" | "section"
 *   slug     – slug sekce (jen pro mode === "section")
 *   isAdmin  – zobrazit admin ovládací prvky
 */
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Settings2, TrendingDown, TrendingUp, Minus, Pencil, Check, X as XIcon } from "lucide-react";
import api from "@/lib/api";
import HeadlineKpiAdminPanel from "@/components/HeadlineKpiAdminPanel";
import { useLocalizedContent } from "@/hooks/useLocalizedContent";
import { fmtCompact } from "@/lib/format";

/* ── helpers ─────────────────────────────────────────────────────────────── */

/**
 * Velká čísla se na dlaždici zkracují po tisících (tis. / mil. / mld. / bil.) — stejně
 * jako na osách grafů, přes sdílený `fmtCompact`. Bez toho tu stálo
 * „1 007 626 000 000,0", což se nedá přečíst ani porovnat.
 *
 * `decimalPlaces` z konfigurace dlaždice má přednost — kdo si nastaví přesnost, dostane
 * plné číslo bez zkracování.
 */
function fmtValue(value, unit, decimalPlaces) {
  if (value == null) return "—";
  const num = typeof value === "number" ? value : parseFloat(value);
  if (Number.isNaN(num)) return "—";
  const formatted =
    decimalPlaces != null
      ? num.toLocaleString("cs-CZ", { minimumFractionDigits: decimalPlaces, maximumFractionDigits: decimalPlaces })
      : fmtCompact(num);
  return unit ? `${formatted} ${unit}` : formatted;
}

function fmtDiff(value, prevValue, decimalPlaces) {
  if (value == null || prevValue == null) return null;
  const diff = value - prevValue;
  const sign = diff > 0 ? "+" : "";
  const formatted =
    decimalPlaces != null
      ? diff.toLocaleString("cs-CZ", { minimumFractionDigits: decimalPlaces, maximumFractionDigits: decimalPlaces })
      : fmtCompact(diff);
  return `${sign}${formatted}`;
}

const COMPARISON_LABELS = {
  prev: "předch. bod",
  yoy:  "před rokem",
  qoq:  "před čtvrtletím",
  mom:  "před měsícem",
};

/** Formats raw period strings like "20260331" → "2026-03-31", "202603" → "03/2026", etc. */
function fmtPeriod(p) {
  if (!p) return "";
  const s = String(p).trim();
  // YYYYMMDD → DD.MM.YYYY
  if (/^\d{8}$/.test(s)) {
    return `${s.slice(6)}.${s.slice(4, 6)}.${s.slice(0, 4)}`;
  }
  // YYYYMM → MM/YYYY
  if (/^\d{6}$/.test(s)) {
    return `${s.slice(4, 6)}/${s.slice(0, 4)}`;
  }
  // YYYY-MM-DD or YYYY-MM
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    const [y, m, d] = s.split("-");
    return `${d}.${m}.${y}`;
  }
  if (/^\d{4}-\d{2}$/.test(s)) {
    const [y, m] = s.split("-");
    return `${m}/${y}`;
  }
  // YYYY
  if (/^\d{4}$/.test(s)) return s;
  return s;
}

/* ── Single KPI card ─────────────────────────────────────────────────────── */

function KpiCard({ kpi, isAdmin, onTitleSave, onRemove, onSizeChange }) {
  const { kpiTitle } = useLocalizedContent();
  const displayTitle = kpiTitle(kpi);
  const trend = kpi.trend || "neutral";
  const [editing,   setEditing]   = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const inputRef = useRef(null);
  const cardSize = kpi.card_size === "wide" ? "wide" : "normal";
  const isWide = cardSize === "wide";

  const TrendIcon   = trend === "up" ? TrendingUp : trend === "down" ? TrendingDown : Minus;
  const trendColor  = trend === "up" ? "text-emerald-500" : trend === "down" ? "text-red-500" : "text-muted-foreground";
  const dp          = kpi.decimal_places ?? null;
  const diffStr     = fmtDiff(kpi.value, kpi.prev_value, dp);
  const compLabel   = kpi.comparison_type && kpi.comparison_type !== "prev"
    ? COMPARISON_LABELS[kpi.comparison_type] || kpi.comparison_type
    : null;

  const startEdit = () => {
    setTitleDraft(kpi.title || "");
    setEditing(true);
    setTimeout(() => inputRef.current?.focus(), 0);
  };

  const commitEdit = () => {
    setEditing(false);
    const t = titleDraft.trim();
    if (t && t !== kpi.title) onTitleSave?.(kpi.id, t);
  };

  return (
    <div
      className={`group relative flex h-[128px] flex-col overflow-hidden rounded-xl border border-border/70 bg-card px-4 py-3 shrink-0 shadow-sm ${
        isAdmin ? "pr-14" : ""
      } ${isWide ? "w-[460px]" : "w-[300px]"}`}
    >
      {isAdmin && (
        <div className="absolute right-1.5 top-1.5 flex items-center gap-1 opacity-80 transition-opacity group-hover:opacity-100">
          <button
            type="button"
            onClick={() => onSizeChange?.(kpi.id, isWide ? "normal" : "wide")}
            title={isWide ? "Zmenšit kartu" : "Roztáhnout kartu přes 2 pozice"}
            className="h-5 min-w-6 rounded-md border border-border/70 bg-background/90 px-1 text-[10px] font-mono text-muted-foreground hover:bg-muted/70 hover:text-foreground"
          >
            {isWide ? "2×" : "1×"}
          </button>
          <button
            type="button"
            onClick={() => onRemove?.(kpi.id)}
            title="Odebrat KPI kartu"
            className="flex h-5 w-5 items-center justify-center rounded-md border border-border/70 bg-background/90 text-muted-foreground hover:border-destructive/30 hover:bg-destructive/10 hover:text-destructive"
          >
            <XIcon className="h-3 w-3" />
          </button>
        </div>
      )}
      {kpi.error ? (
        <span className="text-xs text-destructive/80 leading-snug">{displayTitle || "KPI"}: {kpi.error}</span>
      ) : (
        <>
          {/* Value */}
          <span className="truncate text-xl font-bold tabular-nums leading-none text-foreground">
            {fmtValue(kpi.value, kpi.unit, dp)}
          </span>

          {/* Trend diff */}
          {diffStr && (
            <span className={`flex min-w-0 items-center gap-0.5 truncate text-xs font-medium mt-1 ${trendColor}`}>
              <TrendIcon className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">{diffStr}</span>
              {compLabel && (
                <span className="shrink-0 text-[10px] font-normal text-muted-foreground ml-0.5">({compLabel})</span>
              )}
            </span>
          )}

          {/* Title — editable for admin */}
          <div className="mt-1.5">
            {editing ? (
              <div className="flex items-center gap-1">
                <input
                  ref={inputRef}
                  type="text"
                  value={titleDraft}
                  onChange={(e) => setTitleDraft(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") commitEdit(); if (e.key === "Escape") setEditing(false); }}
                  className="flex-1 min-w-0 h-5 border border-primary rounded px-1 text-[11px] bg-card text-foreground focus:outline-none"
                />
                <button type="button" onClick={commitEdit}
                  className="h-4 w-4 flex items-center justify-center rounded text-primary hover:bg-primary/10 shrink-0">
                  <Check className="h-2.5 w-2.5" />
                </button>
                <button type="button" onClick={() => setEditing(false)}
                  className="h-4 w-4 flex items-center justify-center rounded text-muted-foreground hover:bg-muted/60 shrink-0">
                  <XIcon className="h-2.5 w-2.5" />
                </button>
              </div>
            ) : (
              <div className="flex items-start gap-1">
                <span className="text-xs font-medium text-foreground leading-snug line-clamp-2 flex-1">
                  {displayTitle || "—"}
                </span>
                {isAdmin && (
                  <button
                    type="button"
                    onClick={startEdit}
                    title="Přejmenovat"
                    className="h-4 w-4 flex items-center justify-center rounded text-muted-foreground opacity-0 group-hover:opacity-100 hover:text-foreground transition-opacity shrink-0 mt-0.5"
                  >
                    <Pencil className="h-2.5 w-2.5" />
                  </button>
                )}
              </div>
            )}
          </div>

          {/* Period */}
          {kpi.period && (
            <span className="truncate text-[10px] text-muted-foreground mt-auto leading-none">
              {fmtPeriod(kpi.period)}
              {kpi.prev_period && (
                <span className="opacity-55"> · předch. {fmtPeriod(kpi.prev_period)}</span>
              )}
            </span>
          )}
        </>
      )}
    </div>
  );
}

/* ── Loading skeleton ────────────────────────────────────────────────────── */

function KpiSkeleton() {
  return (
    <div className="h-[128px] w-[300px] shrink-0 rounded-xl border border-border/50 bg-card/50 px-4 py-3 animate-pulse space-y-1.5">
      <div className="h-6 w-20 rounded bg-muted/60" />
      <div className="h-3 w-24 rounded bg-muted/40" />
      <div className="h-2.5 w-16 rounded bg-muted/30" />
    </div>
  );
}

/* ── Main component ──────────────────────────────────────────────────────── */

export default function HeadlineKpiStrip({ mode = "homepage", slug, pageId, isAdmin = false }) {
  const [kpisRaw,      setKpisRaw]      = useState([]); // raw config (for admin panel)
  const [kpisResolved, setKpisResolved] = useState([]); // resolved values (for display)
  const [loading,      setLoading]      = useState(true);
  const [adminOpen,    setAdminOpen]    = useState(false);

  /** `personal` = dlaždice osobní stránky (Můj dashboard), vázané na pageId místo slugu. */
  const resolvedUrl =
    mode === "homepage"
      ? "/homepage/kpis-resolved"
      : mode === "personal"
        ? `/me/dashboard/pages/${pageId}/kpis-resolved`
        : `/sections/${slug}/kpis-resolved`;

  const configUrl =
    mode === "homepage"
      ? "/homepage/config"
      : mode === "personal"
        ? `/me/dashboard/pages/${pageId}/kpis`
        : `/sections/${slug}`;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [resolvedRes, configRes] = await Promise.all([
        api.get(resolvedUrl),
        api.get(configUrl),
      ]);
      setKpisResolved(resolvedRes.data?.kpis || []);
      // osobní endpoint vrací {kpis}, homepage i sekce {headline_kpis}
      setKpisRaw(configRes.data?.headline_kpis || configRes.data?.kpis || []);
    } catch {
      // silently ignore — strip simply doesn't show
      setKpisResolved([]);
      setKpisRaw([]);
    } finally {
      setLoading(false);
    }
  }, [resolvedUrl, configUrl, mode]);

  useEffect(() => {
    load();
  }, [load]);

  const handleSaved = useCallback((newRaw) => {
    setKpisRaw(newRaw);
    load();
  }, [load]);

  const saveKpiList = useCallback(async (updated) => {
    const url =
      mode === "homepage"
        ? "/homepage/kpis"
        : mode === "personal"
          ? `/me/dashboard/pages/${pageId}/kpis`
          : `/sections/${slug}/kpis`;
    const { data } = await api.put(url, { kpis: updated });
    const saved = data.kpis || updated;
    setKpisRaw(saved);
    return saved;
  }, [mode, slug, pageId]);

  /** Admin inline title edit from a KPI card */
  const handleTitleSave = useCallback(async (kpiId, newTitle) => {
    const updated = kpisRaw.map((k) => k.id === kpiId ? { ...k, title: newTitle } : k);
    try {
      await saveKpiList(updated);
      // update resolved titles locally (no full re-fetch needed)
      setKpisResolved((prev) => prev.map((k) => k.id === kpiId ? { ...k, title: newTitle } : k));
    } catch {
      // silently ignore — user can retry via admin panel
    }
  }, [kpisRaw, saveKpiList]);

  const handleRemove = useCallback(async (kpiId) => {
    const updated = kpisRaw.filter((k) => k.id !== kpiId);
    try {
      await saveKpiList(updated);
      setKpisResolved((prev) => prev.filter((k) => k.id !== kpiId));
    } catch {
      // silently ignore — user can retry via admin panel
    }
  }, [kpisRaw, saveKpiList]);

  const handleSizeChange = useCallback(async (kpiId, size) => {
    const cardSize = size === "wide" ? "wide" : "normal";
    const updated = kpisRaw.map((k) =>
      k.id === kpiId
        ? { ...k, config: { ...(k.config || {}), card_size: cardSize } }
        : k
    );
    try {
      await saveKpiList(updated);
      setKpisResolved((prev) =>
        prev.map((k) => (k.id === kpiId ? { ...k, card_size: cardSize } : k))
      );
    } catch {
      // silently ignore — user can retry via admin panel
    }
  }, [kpisRaw, saveKpiList]);

  // Don't render the strip at all if there are no KPIs and user is not admin
  if (!loading && kpisResolved.length === 0 && !isAdmin) return null;

  return (
    <div className="mb-4 space-y-2">
      {/* KPI cards row */}
      {(loading || kpisResolved.length > 0) && (
        <div className="flex gap-2.5 overflow-x-auto overscroll-x-contain max-w-full pb-1 scrollbar-thin scrollbar-thumb-border/40">
          {loading
            ? Array.from({ length: 3 }).map((_, i) => <KpiSkeleton key={i} />)
            : kpisResolved.map((k) => (
                <KpiCard
                  key={k.id || k.title}
                  kpi={k}
                  isAdmin={isAdmin}
                  onTitleSave={handleTitleSave}
                  onRemove={handleRemove}
                  onSizeChange={handleSizeChange}
                />
              ))}
        </div>
      )}

      {/* Admin: empty state + button */}
      {isAdmin && !loading && kpisResolved.length === 0 && !adminOpen && (
        <button
          type="button"
          onClick={() => setAdminOpen(true)}
          className="flex items-center gap-1.5 px-3 h-8 rounded-xl border border-dashed border-primary/40 text-primary/70 text-xs hover:bg-primary/5 transition-colors"
        >
          <Settings2 className="h-3.5 w-3.5" />
          Přidat headline KPI ukazatele
        </button>
      )}

      {/* Admin: gear icon when KPIs exist */}
      {isAdmin && !loading && kpisResolved.length > 0 && !adminOpen && (
        <button
          type="button"
          onClick={() => setAdminOpen(true)}
          className="flex items-center gap-1 px-2 h-6 rounded-lg text-muted-foreground text-[10px] hover:bg-muted/40 hover:text-foreground transition-colors"
          title="Upravit KPI ukazatele"
        >
          <Settings2 className="h-3 w-3" />
          <span>Upravit ukazatele</span>
        </button>
      )}

      {/* Admin panel */}
      {isAdmin && adminOpen && (
        <HeadlineKpiAdminPanel
          mode={mode}
          slug={slug}
          kpis={kpisRaw}
          onSaved={handleSaved}
          onClose={() => setAdminOpen(false)}
        />
      )}
    </div>
  );
}
