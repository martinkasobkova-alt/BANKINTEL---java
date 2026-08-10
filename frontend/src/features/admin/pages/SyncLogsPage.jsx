import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import api from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import { StatusBadge } from "@/components/widgets/WidgetRenderer";
import { fmtDateTime } from "@/lib/format";
import { RefreshCw } from "lucide-react";

export default function SyncLogsPage() {
  const { t } = useTranslation();
  const [logs, setLogs] = useState([]);
  const [health, setHealth] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    const [{ data: l }, { data: h }] = await Promise.all([
      api.get("/sync/logs", { params: { limit: 200 } }),
      api.get("/sync/health"),
    ]);
    setLogs(l);
    setHealth(h);
    setLoading(false);
  };
  useEffect(() => {
    load();
    const id = setInterval(load, 15000);
    return () => clearInterval(id);
  }, []);

  return (
    <AppShell
      title={t("pages.admin.syncLogsTitle")}
      subtitle={t("pages.admin.syncLogsSubtitle")}
      actions={
        <button onClick={load} className="flex items-center gap-2 px-3 h-9 text-sm border border-border rounded-xl hover:bg-muted/60">
          <RefreshCw className="h-4 w-4" /> {t("pages.admin.refresh")}
        </button>
      }
    >
      {health && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6" data-testid="sync-health">
          {[
            [t("pages.admin.totalSources"), health.total_sources],
            [t("pages.admin.activeSources"), health.active_sources],
            [t("pages.admin.successful"), health.sources_successful],
            [t("pages.admin.withErrors"), health.sources_with_errors],
          ].map(([label, val]) => (
            <div key={label} className="kpi-card">
              <div className="kpi-label">{label}</div>
              <div className="kpi-value">{val}</div>
            </div>
          ))}
        </div>
      )}

      <div className="data-table-shell shadow-sm">
        {loading ? (
          <div className="p-12 text-center text-muted-foreground font-mono text-sm">{t("common.loading")}</div>
        ) : logs.length === 0 ? (
          <div className="p-12 text-center text-muted-foreground font-mono text-sm">{t("pages.admin.noSyncYet")}</div>
        ) : (
          <table className="data-table" data-testid="sync-logs-table">
            <thead>
              <tr>
                <th>{t("pages.admin.sourceCol")}</th>
                <th>{t("pages.admin.statusCol")}</th>
                <th>{t("pages.admin.startedCol")}</th>
                <th>{t("pages.admin.finishedCol")}</th>
                <th className="num">HTTP</th>
                <th className="num">{t("pages.admin.recordsCol")}</th>
                <th>{t("pages.admin.messageCol")}</th>
              </tr>
            </thead>
            <tbody>
              {logs.map((l) => (
                <tr key={l.id}>
                  <td className="font-medium">{l.source_name}</td>
                  <td><StatusBadge status={l.status} /></td>
                  <td className="mono text-xs">{fmtDateTime(l.started_at)}</td>
                  <td className="mono text-xs">{fmtDateTime(l.finished_at)}</td>
                  <td className="num mono text-xs">{l.http_status ?? "—"}</td>
                  <td className="num mono">{l.records_ingested}</td>
                  <td className="text-xs text-muted-foreground max-w-xs truncate">{l.message || "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </AppShell>
  );
}
