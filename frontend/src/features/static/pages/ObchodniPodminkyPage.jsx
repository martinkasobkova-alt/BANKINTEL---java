import React from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import AppShell from "@/components/layout/AppShell";

/** Minimální stránka pro pilot; plné obchodní podmínky doplň právní revizí. */
export default function ObchodniPodminkyPage() {
  const { t } = useTranslation();
  return (
    <AppShell title={t("pages.legal.termsTitle")} subtitle={t("pages.legal.termsSubtitle")}>
      <div className="max-w-3xl space-y-4 text-sm text-slate-700 leading-relaxed pb-12">
        <p className="rounded-lg border border-amber-200 bg-amber-50/90 px-4 py-3 text-amber-900 text-xs">
          Tento text je pracovní verze pro pilotní provoz. Před veřejným uvedením nahraďte úplným
          zněním obchodních podmínek a právní kontrolou.
        </p>
        <p>
          Používáním datové platformy Bankovnictví souhlasíte s pravidly provozu stanovenými
          provozovatelem. Údaje o předplatném a časopise jsou na webu vydavatele.
        </p>
        <p className="text-xs text-slate-500 pt-4 border-t border-border/50">
          <Link to="/ochrana-osobnich-udaju" className="underline hover:text-[hsl(var(--primary))]">
            Ochrana osobních údajů
          </Link>
          {" · "}
          <Link to="/" className="underline hover:text-[hsl(var(--primary))]">
            Úvod
          </Link>
        </p>
      </div>
    </AppShell>
  );
}
