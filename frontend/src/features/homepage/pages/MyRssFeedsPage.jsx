import React from "react";
import { useTranslation } from "react-i18next";
import AppShell from "@/components/layout/AppShell";
import PersonalRssPanel from "@/components/myDashboard/PersonalRssPanel";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";

export default function MyRssFeedsPage() {
  const { t } = useTranslation();
  const { allowed: canRss, loading, message } = useFeatureAccess("rss_monitoring");

  return (
    <AppShell
      title={t("pages.myRss.title")}
      subtitle={t("pages.myRss.subtitle")}
      hideAds
    >
      {loading ? (
        <div className="text-sm text-muted-foreground">{t("pages.myRss.checkingAccess")}</div>
      ) : !canRss ? (
        <div className="soft-card p-4 text-sm text-muted-foreground">
          {message || t("pages.myRss.notAvailable")}
        </div>
      ) : (
        <div className="copper-text-fix-scope">
          <PersonalRssPanel />
        </div>
      )}
    </AppShell>
  );
}
