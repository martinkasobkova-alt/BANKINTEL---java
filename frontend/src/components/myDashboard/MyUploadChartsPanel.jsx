import React, { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { BarChart2, FileSpreadsheet, ExternalLink } from "lucide-react";
import { LoadingInline } from "@/components/ui/loading";
import { toast } from "sonner";

const CHART_TYPE_LABELS = {
  line: "Spojnicový",
  bar: "Sloupcový",
  area: "Plošný",
  pie: "Koláčový",
};

function fmtDate(iso) {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleDateString("cs-CZ", {
      day: "numeric",
      month: "numeric",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return iso;
  }
}

/**
 * Zobrazí všechny widgety "Graf z mých dat" uživatele napříč dashboardy.
 * Každý řádek ukazuje: název widgetu, zdrojový soubor, stránka dashboardu, typ grafu.
 */
export default function MyUploadChartsPanel() {
  const [charts, setCharts] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await api.get("/me/upload-charts");
      setCharts(Array.isArray(data) ? data : []);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se načíst grafy z mých dat");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <section className="soft-card p-4 mb-4 border border-border/80 copper-text-fix-scope">
      <div className="flex items-center gap-2 mb-1">
        <BarChart2 className="h-4 w-4 text-[hsl(var(--primary))]" />
        <h3 className="text-sm font-semibold text-foreground">Grafy z mých dat</h3>
      </div>
      <p className="text-xs text-muted-foreground mb-3 leading-relaxed">
        Widgety typu „Graf z mých dat" na vašich dashboardech — přehled všech grafů a souborů, ze
        kterých vychází.
      </p>

      {loading ? (
        <LoadingInline label="Načítám grafy…" size="sm" className="py-2" muted />
      ) : charts.length === 0 ? (
        <p className="text-xs text-muted-foreground">
          Zatím žádné grafy z vlastních dat. Přidejte widget „Graf z mých dat" na svůj{" "}
          <Link to="/my-dashboard" className="text-[hsl(var(--primary))] underline">
            dashboard
          </Link>
          .
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs data-table">
            <thead>
              <tr>
                <th className="text-left">Název grafu</th>
                <th className="text-left">Zdrojový soubor</th>
                <th className="text-left">Dashboard stránka</th>
                <th className="text-left">Typ grafu</th>
                <th className="text-left">Upraveno</th>
                <th className="w-[1%]" />
              </tr>
            </thead>
            <tbody>
              {charts.map((c) => (
                <tr key={c.id}>
                  <td className="font-medium max-w-[180px] truncate" title={c.title || "—"}>
                    {c.title || <span className="text-muted-foreground italic">bez názvu</span>}
                  </td>
                  <td className="max-w-[160px] truncate" title={c.upload_name || c.upload_id || "—"}>
                    {c.upload_name ? (
                      <span className="flex items-center gap-1">
                        <FileSpreadsheet className="h-3 w-3 shrink-0 text-muted-foreground" />
                        {c.upload_name}
                      </span>
                    ) : (
                      <span className="text-muted-foreground">—</span>
                    )}
                  </td>
                  <td className="max-w-[140px] truncate text-muted-foreground" title={c.page_title}>
                    {c.page_title || "—"}
                  </td>
                  <td className="text-muted-foreground">
                    {CHART_TYPE_LABELS[c.chart_type] || c.chart_type || "—"}
                  </td>
                  <td className="mono text-[11px] text-muted-foreground whitespace-nowrap">
                    {fmtDate(c.updated_at)}
                  </td>
                  <td className="text-right">
                    <Link
                      to="/my-dashboard"
                      className="inline-flex items-center gap-1 text-[11px] text-[hsl(var(--primary))] underline whitespace-nowrap"
                      title="Přejít na dashboard"
                    >
                      <ExternalLink className="h-3 w-3" />
                      Dashboard
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
