import React, { useEffect, useMemo, useState } from "react";
import axios from "axios";
import WidgetRenderer from "@/components/widgets/WidgetRenderer";
import { MobileEmbedFeatureAccessProvider } from "@/contexts/MobileEmbedFeatureAccess";
import { LoadingBlock } from "@/components/ui/loading";
import {
  MOBILE_EMBED_MSG,
  parseMobileEmbedMessage,
  postToReactNative,
} from "@/lib/mobileEmbed";
import {
  applyAppearancePresetToDocument,
  getAppearancePresetById,
  normalizeStoredAppearanceId,
} from "@/theme/appearancePresets";

function buildEmbedApi(apiRoot, token) {
  const root = String(apiRoot || "/api").replace(/\/$/, "");
  return axios.create({
    baseURL: root,
    timeout: 120000,
    headers: {
      "Content-Type": "application/json",
      "X-Bankoapp-Client": "mobile",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
}

export default function MobileWidgetChartEmbedPage() {
  const [widget, setWidget] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    document.documentElement.classList.add("banko-mobile-embed");
    document.body.classList.add("banko-mobile-embed");
    return () => {
      document.documentElement.classList.remove("banko-mobile-embed");
      document.body.classList.remove("banko-mobile-embed");
    };
  }, []);

  useEffect(() => {
    const onMessage = (event) => {
      const data = parseMobileEmbedMessage(event?.data);
      if (!data || data.type !== MOBILE_EMBED_MSG.INIT) return;

      const run = async () => {
        setLoading(true);
        setError("");
        try {
          if (data.appearanceId) {
            applyAppearancePresetToDocument(
              getAppearancePresetById(normalizeStoredAppearanceId(data.appearanceId))
            );
          }
          const incoming = data.widget && typeof data.widget === "object" ? data.widget : null;
          if (!incoming) {
            throw new Error("Chybí konfigurace widgetu.");
          }

          let resolved = incoming;
          if (!incoming.data && data.apiRoot) {
            const api = buildEmbedApi(data.apiRoot, data.token);
            const path =
              data.renderPath === "me"
                ? "/me/dashboard/render-widget"
                : "/homepage/render-widget";
            const { data: body } = await api.post(path, {
              id: incoming.id,
              type: incoming.type,
              title: incoming.title,
              width: incoming.width,
              config: incoming.config,
              skip_ai: true,
            });
            resolved = { ...incoming, ...body };
          }

          if (resolved?.data?.error) {
            throw new Error(String(resolved.data.error));
          }

          setWidget(resolved);
        } catch (e) {
          const msg = e?.response?.data?.detail
            ? typeof e.response.data.detail === "string"
              ? e.response.data.detail
              : e.response.data.detail?.message || "Graf se nepodařilo načíst."
            : e?.message || "Graf se nepodařilo načíst.";
          setError(msg);
          postToReactNative({ type: MOBILE_EMBED_MSG.ERROR, message: msg });
        } finally {
          setLoading(false);
        }
      };

      void run();
    };

    window.addEventListener("message", onMessage);
    document.addEventListener("message", onMessage);
    postToReactNative({ type: MOBILE_EMBED_MSG.READY });

    return () => {
      window.removeEventListener("message", onMessage);
      document.removeEventListener("message", onMessage);
    };
  }, []);

  const chart = useMemo(() => {
    if (!widget) return null;
    return (
      <div className="banko-mobile-embed-chart h-full min-h-[min(100dvh,920px)] w-full">
        <WidgetRenderer w={widget} />
      </div>
    );
  }, [widget]);

  return (
    <MobileEmbedFeatureAccessProvider>
      <div className="banko-mobile-embed-root fixed inset-0 z-[2000] flex flex-col bg-[hsl(var(--background))] overflow-hidden">
        {loading ? (
          <div className="flex-1 flex items-center justify-center p-6">
            <LoadingBlock label="Načítám graf…" />
          </div>
        ) : null}
        {!loading && error ? (
          <div className="flex-1 flex items-center justify-center p-6 text-sm text-destructive font-mono text-center">
            {error}
          </div>
        ) : null}
        {!loading && !error && widget ? (
          <div className="flex-1 min-h-0 overflow-hidden">{chart}</div>
        ) : null}
      </div>
    </MobileEmbedFeatureAccessProvider>
  );
}
