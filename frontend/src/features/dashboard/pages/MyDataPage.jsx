import React, { useCallback, useEffect, useState } from "react";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import AppShell from "@/components/layout/AppShell";
import MyDataPanel from "@/components/myDashboard/MyDataPanel";
import MySavedSeriesPanel from "@/components/myDashboard/MySavedSeriesPanel";
import MyUploadChartsPanel from "@/components/myDashboard/MyUploadChartsPanel";

export default function MyDataPage() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [navCompare, setNavCompare] = useState(null);
  const [navOpenSeriesId, setNavOpenSeriesId] = useState("");

  useEffect(() => {
    const cl = location.state?.compareLeft;
    if (cl) {
      setNavCompare(cl);
      navigate(location.pathname, { replace: true, state: {} });
    }
  }, [location.pathname, location.state, navigate]);

  const clearNavCompare = useCallback(() => setNavCompare(null), []);
  const consumeOpenSeriesId = useCallback(() => {
    setNavOpenSeriesId("");
    const sp = new URLSearchParams(searchParams);
    if (!sp.has("series")) return;
    sp.delete("series");
    setSearchParams(sp, { replace: true });
  }, [searchParams, setSearchParams]);

  useEffect(() => {
    const sid = String(searchParams.get("series") || "").trim();
    if (!sid) return;
    setNavOpenSeriesId(sid);
  }, [searchParams]);

  return (
    <AppShell title={t("pages.myData.title")} subtitle={t("pages.myData.subtitle")}>
      <div className="max-w-4xl space-y-6">
        <p className="text-sm text-slate-600">
          {t("pages.myData.intro")}{" "}
          <Link to="/my-dashboard" className="text-[hsl(var(--primary))] font-medium underline">
            {t("pages.myData.personalDashboardLink")}
          </Link>
          .
        </p>
        <MySavedSeriesPanel
          compareLeftFromNav={navCompare}
          onConsumedCompareNav={clearNavCompare}
          initialOpenSeriesId={navOpenSeriesId}
          onConsumedInitialOpenSeries={consumeOpenSeriesId}
        />
        <MyUploadChartsPanel />
        <MyDataPanel />
      </div>
    </AppShell>
  );
}
