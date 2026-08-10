import React, { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import AppShell from "@/components/layout/AppShell";
import WidgetRenderer from "@/components/widgets/WidgetRenderer";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";

export default function SharedDashboardPage() {
  const { token, pageId } = useParams();
  const { user } = useAuth();
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");
  const [payload, setPayload] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setErr("");
      try {
        const path = token
          ? `/dashboard-share/token/${encodeURIComponent(token)}`
          : `/dashboard-share/page/${encodeURIComponent(pageId)}`;
        const { data } = await api.get(path);
        if (!cancelled) setPayload(data);
      } catch (e) {
        if (!cancelled) {
          setErr(formatApiErrorFromAxios(e) || "Stránka není dostupná");
          setPayload(null);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, pageId]);

  const page = payload?.page;
  const widgets = Array.isArray(payload?.widgets) ? payload.widgets : [];
  const isGuest = Boolean(payload?.viewer_mode);

  return (
    <AppShell
      title={page?.title || "Sdílený dashboard"}
      subtitle={
        page?.owner_name
          ? `Dashboard uživatele ${page.owner_name}${isGuest ? " · pouze pro čtení" : ""}`
          : "Sdílená stránka dashboardu"
      }
      hideAds
    >
      {loading ? (
        <p className="text-sm text-slate-600">Načítám…</p>
      ) : err ? (
        <div className="chip-rose rounded-md p-3 text-sm">{err}</div>
      ) : (
        <div className="space-y-4">
          {!user && token ? (
            <p className="text-xs text-slate-600 rounded-lg border border-border/60 bg-slate-50 px-3 py-2">
              Prohlížíte stránku přes tajný odkaz. Pro plný účet se můžete{" "}
              <button type="button" className="underline text-[hsl(var(--primary-deep))]" onClick={() => {}}>
                přihlásit
              </button>
              .
            </p>
          ) : null}
          <div className="grid gap-4">
            {widgets.map((w) => (
              <div key={w.id} className="soft-card p-3 min-h-[200px]">
                <WidgetRenderer
                  w={{ ...w, _loading: false }}
                  aradMultiSeriesHelpContext="personal_dashboard"
                  readOnlyDashboardView
                  viewerCompareContext={{ token: token || null, pageId: page?.id || null }}
                />
              </div>
            ))}
          </div>
        </div>
      )}
    </AppShell>
  );
}
