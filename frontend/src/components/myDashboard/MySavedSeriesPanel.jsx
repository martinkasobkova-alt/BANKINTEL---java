import React, { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import { LoadingInline } from "@/components/ui/loading";
import { fmtCompact, fmtPeriodAxisTick } from "@/lib/format";
import CompareWithSavedModal from "@/components/myDashboard/CompareWithSavedModal";

export default function MySavedSeriesPanel({
  compareLeftFromNav,
  onConsumedCompareNav,
  initialOpenSeriesId,
  onConsumedInitialOpenSeries,
}) {
  const { allowed, ready } = useFeatureAccess("saved_calculations");
  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(true);
  const [chartOpen, setChartOpen] = useState(null);
  const [compareOpen, setCompareOpen] = useState(false);
  const [compareLeft, setCompareLeft] = useState(null);

  const load = useCallback(async () => {
    if (!ready || !allowed) {
      setList([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const { data } = await api.get("/my-series");
      setList(Array.isArray(data) ? data : []);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nelze načíst Moje datové řady.");
    } finally {
      setLoading(false);
    }
  }, [allowed, ready]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (compareLeftFromNav) {
      setCompareLeft(compareLeftFromNav);
      setCompareOpen(true);
      onConsumedCompareNav?.();
    }
  }, [compareLeftFromNav, onConsumedCompareNav]);

  useEffect(() => {
    const sid = String(initialOpenSeriesId || "").trim();
    if (!sid || loading) return;
    const row = list.find((x) => String(x?.id || "").trim() === sid);
    if (!row) return;
    void showChart(row);
    onConsumedInitialOpenSeries?.();
  }, [initialOpenSeriesId, list, loading, onConsumedInitialOpenSeries]);

  const remove = async (id) => {
    if (!window.confirm("Smazat tuto uloženou řadu?")) return;
    try {
      await api.delete(`/my-series/${id}`);
      toast.success("Řada smazána.");
      await load();
      if (chartOpen?.id === id) setChartOpen(null);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Mazání selhalo.");
    }
  };

  const showChart = async (row) => {
    try {
      const { data } = await api.get(`/my-series/${row.id}`);
      setChartOpen(data);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Detail se nepodařil načíst.");
    }
  };

  if (!ready) {
    return <LoadingInline label="Kontroluji přístup…" size="sm" className="py-2" muted />;
  }
  if (!allowed) {
    return (
      <section className="soft-card p-4 border border-border/80 copper-text-fix-scope">
        <h3 className="text-sm font-semibold text-foreground mb-1">Moje datové řady</h3>
        <p className="text-xs text-muted-foreground">Tato funkce je součástí plánu s uloženými výpočty.</p>
      </section>
    );
  }

  const rows = (chartOpen?.data_points || []).map((p) => ({
    x: String(p.period ?? ""),
    y: Number(p.value),
  }));

  return (
    <>
      <section className="soft-card p-4 border border-border/80 copper-text-fix-scope">
        <h3 className="text-sm font-semibold text-foreground mb-1">Moje datové řady</h3>
        <p className="text-xs text-muted-foreground mb-3 leading-relaxed">
          Uložené normalizované řady pro <strong>porovnání</strong>, <strong>složené grafy</strong> a vlastní výpočty na{" "}
          <Link to="/my-dashboard" className="text-[hsl(var(--primary))] underline font-medium">
            Můj osobní dashboard
          </Link>
          . Nejprve řadu přidejte z náhledu zdroje nebo z grafu widgetu.
        </p>
        {loading ? (
          <LoadingInline label="Načítám řady…" size="sm" className="py-2" muted />
        ) : list.length === 0 ? (
          <p className="text-xs text-muted-foreground">Zatím žádné uložené řady.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs data-table">
              <thead>
                <tr>
                  <th className="text-left">Název</th>
                  <th className="text-left">Zdroj</th>
                  <th className="text-left">Jednotka</th>
                  <th className="text-left">Freq.</th>
                  <th className="text-right">Poslední</th>
                  <th className="w-[1%]" />
                </tr>
              </thead>
              <tbody>
                {list.map((r) => (
                  <tr key={r.id}>
                    <td className="font-medium max-w-[180px] truncate" title={r.title}>
                      {r.title}
                    </td>
                    <td className="text-muted-foreground truncate max-w-[120px]" title={r.source}>
                      {r.source || r.source_type || "—"}
                    </td>
                    <td>{r.unit || "—"}</td>
                    <td className="mono">{r.frequency || "—"}</td>
                    <td className="text-right mono text-[10px]">
                      {r.last_value != null ? fmtCompact(r.last_value) : "—"}
                      <div className="text-muted-foreground font-normal">{r.last_period || ""}</div>
                    </td>
                    <td className="text-right whitespace-nowrap">
                      <button type="button" className="text-[10px] text-[hsl(var(--primary))] underline mr-1.5" onClick={() => showChart(r)}>
                        Graf
                      </button>
                      <button
                        type="button"
                        className="text-[10px] text-[hsl(var(--primary))] underline mr-1.5"
                        onClick={() => {
                          setCompareLeft({ mode: "saved", saved_series_id: r.id, label: r.title });
                          setCompareOpen(true);
                        }}
                      >
                        Porovnat
                      </button>
                      <button type="button" className="text-[10px] text-[hsl(var(--primary))] underline mr-1.5" onClick={() => remove(r.id)}>
                        Smazat
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {chartOpen ? (
        <div
          className="fixed inset-0 z-[80] flex items-center justify-center p-4 bg-black/45"
          role="dialog"
          aria-modal="true"
        >
          <div className="bg-card rounded-xl shadow-xl max-w-lg w-full max-h-[85vh] overflow-y-auto p-4 border border-border/80">
            <div className="flex justify-between items-start gap-2 mb-2">
              <h4 className="text-sm font-semibold pr-6">{chartOpen.title}</h4>
              <button type="button" className="text-xs underline text-muted-foreground shrink-0" onClick={() => setChartOpen(null)}>
                Zavřít
              </button>
            </div>
            <p className="text-[10px] text-muted-foreground mb-2">
              {chartOpen.source} · {chartOpen.point_count ?? rows.length} bodů
            </p>
            <div className="h-56 w-full">
              {rows.length > 0 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={rows} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                    <XAxis dataKey="x" tick={{ fontSize: 9 }} tickFormatter={fmtPeriodAxisTick} interval="preserveStartEnd" minTickGap={24} />
                    <YAxis width={44} tick={{ fontSize: 9 }} tickFormatter={(v) => fmtCompact(v)} />
                    <Tooltip formatter={(v) => fmtCompact(v)} />
                    <Line type="monotone" dataKey="y" stroke="hsl(202 90% 48%)" strokeWidth={2} dot={false} />
                  </LineChart>
                </ResponsiveContainer>
              ) : (
                <p className="text-xs text-muted-foreground">Žádná data.</p>
              )}
            </div>
          </div>
        </div>
      ) : null}

      {compareOpen && compareLeft ? (
        <CompareWithSavedModal
          compareLeft={compareLeft}
          onClose={() => {
            setCompareOpen(false);
            setCompareLeft(null);
          }}
          onCreated={() => {
            load();
            setCompareOpen(false);
            setCompareLeft(null);
          }}
        />
      ) : null}
    </>
  );
}
