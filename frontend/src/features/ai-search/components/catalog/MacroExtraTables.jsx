import React, { useCallback, useEffect, useMemo, useState } from "react";
import axios from "axios";
import { Database, ExternalLink, RefreshCw } from "lucide-react";

import api, { formatApiErrorFromAxios } from "@/lib/api";
import { LoadingSpinner } from "@/components/ui/loading";
import { formatSnapshotGeneratedAt } from "@/lib/macroTopicSnapshot";
import { useIsMobileDashboard } from "@/hooks/useMediaQuery";

const TABLE_KEY = {
  commodities: "commodity_table",
  czech: "czech_detail_table",
};

const STATIC_SNAPSHOT_URL = "/data/macro_extra_tables_snapshot.json";

const toneClass = {
  up: "text-sky-700",
  down: "text-rose-700",
  neutral: "text-foreground",
  muted: "text-muted-foreground",
};

function formatValue(value, unit = "") {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return "—";
  const n = Number(value);
  const compact = unit === "Kč" && Math.abs(n) >= 1_000_000_000;
  return new Intl.NumberFormat("cs-CZ", {
    notation: compact ? "compact" : "standard",
    maximumFractionDigits: Math.abs(n) >= 100 ? 1 : 2,
  }).format(n);
}

function formatPercent(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return "—";
  return `${new Intl.NumberFormat("cs-CZ", {
    maximumFractionDigits: Math.abs(Number(value)) >= 10 ? 1 : 2,
  }).format(Number(value))} %`;
}

function visibleRows(table, activeGroup) {
  const rows = table?.rows || [];
  if (!activeGroup || activeGroup === "all") return rows;
  return rows.filter((row) => row.group_id === activeGroup);
}

async function loadStaticSnapshot() {
  const { data } = await axios.get(STATIC_SNAPSHOT_URL, {
    headers: { "Cache-Control": "no-cache" },
    timeout: 15_000,
  });
  return data;
}

export default function MacroExtraTables({ tableId, onPreviewSeries }) {
  const isMobile = useIsMobileDashboard();
  const [payload, setPayload] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [activeGroup, setActiveGroup] = useState("all");

  const loadTables = useCallback(async (options = {}) => {
    const preferApi = Boolean(options.preferApi);
    setLoading(true);
    setError("");
    try {
      if (!preferApi) {
        try {
          const staticPayload = await loadStaticSnapshot();
          setPayload(staticPayload);
          return;
        } catch  {
          // Static asset can be missing in older deployments; fall back to API.
        }
      }
      const { data } = await api.get("/catalog/macro-topics/extra-tables", {
        params: { _ts: Date.now() },
        headers: { "Cache-Control": "no-cache" },
        timeout: 90_000,
      });
      setPayload(data);
    } catch (e) {
      if (preferApi) {
        try {
          const staticPayload = await loadStaticSnapshot();
          setPayload(staticPayload);
          return;
        } catch  {
          // Fall through to the original API error below.
        }
      }
      setError(formatApiErrorFromAxios(e));
      setPayload(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTables();
  }, [loadTables]);

  useEffect(() => {
    setActiveGroup("all");
  }, [tableId]);

  const table = payload?.[TABLE_KEY[tableId]];
  const rows = useMemo(() => visibleRows(table, activeGroup), [activeGroup, table]);
  const snapshotLabel = formatSnapshotGeneratedAt(payload?.generated_at);

  if (loading) {
    return (
      <div className="rounded-xl border border-border/80 bg-card p-4 text-sm text-muted-foreground flex items-center gap-2">
        <LoadingSpinner className="h-4 w-4" />
        Načítám připravený snapshot tabulky…
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 space-y-2">
        <p className="text-sm text-destructive">{error}</p>
        <button
          type="button"
          onClick={() => void loadTables({ preferApi: true })}
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-border bg-card px-2.5 text-xs font-semibold text-foreground hover:bg-muted"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Zkusit znovu
        </button>
      </div>
    );
  }

  if (!table) {
    return (
      <div className="rounded-xl border border-border/80 bg-card p-4 text-sm text-muted-foreground">
        Tabulka zatím není ve snapshotu k dispozici.
      </div>
    );
  }

  return (
    <div className="space-y-3" data-testid={`macro-extra-table-${tableId}`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
            <Database className="h-4 w-4 text-violet-600 shrink-0" />
            {table.title_cs}
          </h3>
          <p className="text-[12px] text-muted-foreground mt-1 max-w-3xl leading-snug">
            {table.subtitle_cs} {table.row_count ?? rows.length} ukazatelů.
            {snapshotLabel ? <> Data k {snapshotLabel}.</> : null}
          </p>
        </div>
      </div>

      {table.groups?.length ? (
        <div className="flex flex-wrap items-center gap-1.5" aria-label="Skupiny doplňkové tabulky">
          <button
            type="button"
            onClick={() => setActiveGroup("all")}
            className={`h-8 rounded-lg border px-2.5 text-[11px] font-semibold transition-colors ${
              activeGroup === "all"
                ? "border-violet-300 bg-violet-100 text-violet-950"
                : "border-border/80 bg-card text-muted-foreground hover:bg-muted/50"
            }`}
          >
            Vše
          </button>
          {table.groups.map((group) => (
            <button
              key={group.id}
              type="button"
              onClick={() => setActiveGroup(group.id)}
              className={`h-8 rounded-lg border px-2.5 text-[11px] font-semibold transition-colors ${
                activeGroup === group.id
                  ? "border-violet-300 bg-violet-100 text-violet-950"
                  : "border-border/80 bg-card text-muted-foreground hover:bg-muted/50"
              }`}
            >
              {group.label_cs}
            </button>
          ))}
        </div>
      ) : null}

      <div
        className={`relative macro-extra-table-scroll w-full max-w-full rounded-xl border border-border/80 bg-card shadow-sm ${
          isMobile
            ? "overflow-x-auto overflow-y-visible overscroll-x-contain"
            : "max-h-[min(72vh,860px)] overflow-auto"
        }`}
      >
        <table className={`w-full text-left leading-snug ${isMobile ? "min-w-[640px] text-[10px]" : "min-w-[920px] text-[12px]"}`}>
          <thead className="sticky top-0 z-20">
            <tr className="border-b border-border/70 bg-violet-50 text-foreground shadow-[inset_0_-1px_0_hsl(var(--border)/0.55),0_3px_6px_-3px_rgba(0,0,0,0.08)]">
              <th className="px-3 py-2.5 font-semibold min-w-[230px]">Ukazatel</th>
              <th className="px-3 py-2.5 font-semibold text-right">Hodnota</th>
              <th className="px-3 py-2.5 font-semibold">Jednotka</th>
              <th className="px-3 py-2.5 font-semibold">Období</th>
              <th className="px-3 py-2.5 font-semibold text-right">Změna</th>
              {tableId === "commodities" ? (
                <th className="px-3 py-2.5 font-semibold text-right">Y/Y</th>
              ) : null}
              <th className="px-3 py-2.5 font-semibold">Zdroj</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const clickable = row.series && onPreviewSeries;
              return (
                <tr
                  key={row.id}
                  className={`border-b border-border/50 hover:bg-sky-50/45 ${
                    clickable ? "cursor-pointer" : ""
                  }`}
                  onClick={() => {
                    if (clickable) {
                      onPreviewSeries({
                        ...row.series,
                        name: row.series?.name || row.label_cs,
                        title: row.series?.title || row.label_cs,
                        selected_indicator: row.series?.selected_indicator || row.selected_indicator,
                        selected_indicators: row.series?.selected_indicators || row.selected_indicators,
                      });
                    }
                  }}
                >
                  <td className="px-3 py-2.5 align-top">
                    <div className="font-medium text-foreground flex items-center gap-1.5">
                      {row.label_cs}
                      {clickable ? <ExternalLink className="h-3 w-3 text-muted-foreground" /> : null}
                    </div>
                    {row.note_cs ? (
                      <div className="text-[11px] text-muted-foreground mt-0.5">{row.note_cs}</div>
                    ) : null}
                    {row.status !== "ok" ? (
                      <div className="text-[11px] text-amber-700 mt-0.5">{row.error || "Hodnota chybí."}</div>
                    ) : null}
                  </td>
                  <td className="px-3 py-2.5 align-top text-right font-semibold text-foreground">
                    {formatValue(row.value, row.unit)}
                  </td>
                  <td className="px-3 py-2.5 align-top text-muted-foreground whitespace-nowrap">
                    {row.unit || "—"}
                  </td>
                  <td className="px-3 py-2.5 align-top text-muted-foreground whitespace-nowrap">
                    {row.period || "—"}
                  </td>
                  <td className={`px-3 py-2.5 align-top text-right font-medium ${toneClass[row.direction] || ""}`}>
                    {row.pct_change !== null && row.pct_change !== undefined
                      ? formatPercent(row.pct_change)
                      : formatValue(row.delta)}
                  </td>
                  {tableId === "commodities" ? (
                    <td className="px-3 py-2.5 align-top text-right text-muted-foreground font-medium">
                      {formatPercent(row.yoy_percent)}
                    </td>
                  ) : null}
                  <td className="px-3 py-2.5 align-top text-muted-foreground">
                    {row.source_label || table.source_label || "—"}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
