import React, { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import api from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import { Search, FileSpreadsheet, FileText } from "lucide-react";
import SecuredExportButton from "@/components/SecuredExportButton";
import { fmtDateTime } from "@/lib/format";

export default function RecordsPage() {
  const { t } = useTranslation();
  const [datasets, setDatasets] = useState([]);
  const [active, setActive] = useState(null);
  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [q, setQ] = useState("");
  const [loading, setLoading] = useState(true);
  const limit = 50;
  const [skip, setSkip] = useState(0);

  useEffect(() => {
    (async () => {
      const { data } = await api.get("/datasets");
      setDatasets(data);
      if (data.length && !active) setActive(data[0].id);
      setLoading(false);
    })();
    // eslint-disable-next-line
  }, []);

  useEffect(() => {
    const onRefresh = async () => {
      try {
        const { data } = await api.get("/datasets");
        setDatasets(data);
        setActive((prev) => prev || data[0]?.id || null);
      } catch {
        /* ignore */
      }
    };
    window.addEventListener("banko:datasets-changed", onRefresh);
    return () => window.removeEventListener("banko:datasets-changed", onRefresh);
  }, []);

  useEffect(() => {
    if (!active) return;
    (async () => {
      const { data } = await api.get("/records", {
        params: { dataset_id: active, q: q || undefined, limit, skip },
      });
      setRows(data.rows || []);
      setTotal(data.total || 0);
    })();
  }, [active, q, skip]);

  const columns = useMemo(() => {
    const ds = datasets.find((d) => d.id === active);
    return ds?.fields?.length ? ds.fields : ["date", "institution", "category", "amount"];
  }, [datasets, active]);

  return (
    <AppShell title={t("pages.admin.recordsTitle")} subtitle={t("pages.admin.recordsSubtitle")}>
      {loading ? (
        <div className="text-sm text-muted-foreground font-mono">Načítání…</div>
      ) : datasets.length === 0 ? (
        <div className="border border-dashed border-border bg-muted/25 p-12 text-center rounded-sm text-sm text-muted-foreground font-mono">
          Zatím nejsou k dispozici žádné datové sady.
        </div>
      ) : (
        <div className="grid grid-cols-1 xl:grid-cols-[260px_1fr] gap-6">
          <aside className="bg-card border border-border rounded-sm p-3" data-testid="dataset-list">
            <div className="kpi-label px-2 pb-2">Datové sady</div>
            {datasets.map((d) => (
              <button
                key={d.id}
                data-testid={`dataset-${d.name}`}
                onClick={() => {
                  setActive(d.id);
                  setSkip(0);
                }}
                className={`w-full text-left px-3 py-2 rounded-sm text-sm flex justify-between items-center ${
                  active === d.id ? "row-selected font-medium" : "hover:bg-[hsl(var(--primary-soft))]"
                }`}
              >
                <span className="truncate">{d.name}</span>
                <span className={`text-[11px] font-mono ${active === d.id ? "text-slate-300" : "text-muted-foreground"}`}>
                  {d.record_count}
                </span>
              </button>
            ))}
          </aside>

          <section className="bg-card border border-border rounded-sm">
            <div className="p-4 border-b border-border flex items-center gap-3 flex-wrap">
              <div className="relative flex-1 min-w-[240px]">
                <Search className="h-4 w-4 text-muted-foreground absolute left-2.5 top-1/2 -translate-y-1/2" />
                <input
                  data-testid="records-search"
                  value={q}
                  onChange={(e) => {
                    setQ(e.target.value);
                    setSkip(0);
                  }}
                  placeholder="Hledat (datum|instituce|kategorie)…"
                  className="w-full h-9 pl-8 pr-3 border border-border rounded-sm text-sm font-mono"
                />
              </div>
              <div className="text-xs text-muted-foreground font-mono">
                Zobrazeno {rows.length} z {total}
              </div>
              {active && (
                <div className="flex items-center gap-1">
                  <SecuredExportButton
                    testid="records-export-xlsx"
                    relativePath={`export/dataset/${active}.xlsx`}
                    filename="export.xlsx"
                    className="flex items-center gap-1.5 px-3 h-9 text-xs border border-border rounded-sm hover:bg-muted/60"
                  >
                    <FileSpreadsheet className="h-4 w-4" /> Excel
                  </SecuredExportButton>
                  <SecuredExportButton
                    testid="records-export-pdf"
                    relativePath={`export/dataset/${active}.pdf`}
                    filename="export.pdf"
                    className="flex items-center gap-1.5 px-3 h-9 text-xs border border-border rounded-sm hover:bg-muted/60"
                  >
                    <FileText className="h-4 w-4" /> PDF
                  </SecuredExportButton>
                </div>
              )}
            </div>

            <div className="overflow-auto">
              <table className="data-table" data-testid="records-table">
                <thead>
                  <tr>
                    {columns.map((c) => (
                      <th key={c} className={["amount", "count", "volume", "close", "open", "high", "low"].includes(c) ? "num" : ""}>
                        {c}
                      </th>
                    ))}
                    <th>Načteno</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id}>
                      {columns.map((c) => {
                        const v = r.data?.[c];
                        const isNum = typeof v === "number";
                        return (
                          <td key={c} className={isNum ? "num" : ""} title={typeof v === "object" ? JSON.stringify(v) : String(v ?? "")}>
                            {typeof v === "object" ? JSON.stringify(v) : isNum ? v.toLocaleString("cs-CZ", { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : v ?? "—"}
                          </td>
                        );
                      })}
                      <td className="mono text-xs text-muted-foreground">{fmtDateTime(r.created_at)}</td>
                    </tr>
                  ))}
                  {rows.length === 0 && (
                    <tr>
                      <td colSpan={columns.length + 1} className="text-center p-10 text-muted-foreground font-mono text-sm">
                        Žádné záznamy.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            <div className="flex items-center justify-between p-3 border-t border-border">
              <button disabled={skip === 0} onClick={() => setSkip((s) => Math.max(0, s - limit))} className="text-xs px-3 h-8 border border-border rounded-sm disabled:opacity-40">
                ← Předchozí
              </button>
              <div className="text-xs font-mono text-muted-foreground">
                {skip + 1} – {Math.min(skip + limit, total)}
              </div>
              <button disabled={skip + limit >= total} onClick={() => setSkip((s) => s + limit)} className="text-xs px-3 h-8 border border-border rounded-sm disabled:opacity-40">
                Další →
              </button>
            </div>
          </section>
        </div>
      )}
    </AppShell>
  );
}
