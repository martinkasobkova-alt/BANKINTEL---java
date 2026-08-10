import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import WidgetRenderer from "@/components/widgets/WidgetRenderer";
import api, { formatApiErrorFromAxios } from "@/lib/api";

/**
 * Veřejná embed stránka pro `<iframe>` v článku — bez AppShell, reklam a sidebaru.
 */
export default function EmbedWidgetPage() {
  const { token, widgetId } = useParams();
  const [loading, setLoading] = useState(true);
  const [payload, setPayload] = useState(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    document.documentElement.classList.add("banko-article-embed");
    document.body.classList.add("banko-article-embed");
    return () => {
      document.documentElement.classList.remove("banko-article-embed");
      document.body.classList.remove("banko-article-embed");
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const tok = String(token || "").trim();
    const wid = String(widgetId || "").trim();
    if (!tok || !wid) {
      setFailed(true);
      setLoading(false);
      return undefined;
    }
    (async () => {
      setLoading(true);
      setFailed(false);
      setPayload(null);
      try {
        const { data } = await api.get(
          `/dashboard-share/embed/${encodeURIComponent(tok)}/${encodeURIComponent(wid)}`
        );
        if (!cancelled) setPayload(data);
      } catch (e) {
        if (!cancelled) {
          void formatApiErrorFromAxios(e);
          setFailed(true);
          setPayload(null);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, widgetId]);

  const widget = payload?.widget;

  if (loading) {
    return <div className="banko-article-embed-root min-h-[360px] h-full w-full bg-white" aria-busy="true" />;
  }

  if (failed || !widget) {
    return <div className="banko-article-embed-root min-h-[360px] h-full w-full bg-white" aria-hidden="true" />;
  }

  return (
    <div className="banko-article-embed-root relative flex min-h-[360px] h-full w-full flex-col bg-white overflow-hidden">
      <div className="flex-1 min-h-[360px] min-h-0 h-full w-full">
        <WidgetRenderer
          w={{ ...widget, _loading: false }}
          aradMultiSeriesHelpContext="personal_dashboard"
          readOnlyDashboardView
        />
      </div>
      <Link
        to="/"
        className="absolute bottom-1.5 right-2 z-20 rounded px-1.5 py-0.5 text-[10px] text-slate-400/90 hover:text-slate-600 hover:underline bg-white/80"
        title="Otevřít Bankoapp"
      >
        Bankoapp
      </Link>
    </div>
  );
}
