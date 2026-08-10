import React from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import AppShell from "@/components/layout/AppShell";

const CTA_EXTERNAL = "https://www.bankovnictvionline.cz";

export default function PredplatnePage() {
  const { t } = useTranslation();

  return (
    <AppShell title={t("pages.subscription.title")} subtitle={t("pages.subscription.subtitle")}>
      <div className="max-w-3xl space-y-8 text-sm text-foreground/90 leading-relaxed pb-12 copper-text-fix-scope">
        <p>{t("pages.subscription.intro")}</p>

        <section>
          <h2 className="font-serif text-xl text-foreground mb-3">{t("pages.subscription.accessLevels")}</h2>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <strong>{t("pages.subscription.guestLevel")}</strong>
              {t("pages.subscription.guestDesc")}
            </li>
            <li>
              <strong>{t("pages.subscription.freeLevel")}</strong>
              {t("pages.subscription.freeDesc")}
            </li>
            <li>
              <strong>{t("pages.subscription.subscriberLevel")}</strong>
              {t("pages.subscription.subscriberDesc")}
            </li>
          </ul>
        </section>

        <section>
          <h2 className="font-serif text-xl text-foreground mb-3">{t("pages.subscription.benefits")}</h2>
          <ul className="list-disc pl-5 space-y-1.5">
            <li>{t("pages.subscription.benefit1")}</li>
            <li>{t("pages.subscription.benefit2")}</li>
            <li>{t("pages.subscription.benefit3")}</li>
            <li>{t("pages.subscription.benefit4")}</li>
            <li>{t("pages.subscription.benefit5")}</li>
            <li>{t("pages.subscription.benefit6")}</li>
            <li>{t("pages.subscription.benefit7")}</li>
          </ul>
        </section>

        <div className="pt-2">
          <a
            href={CTA_EXTERNAL}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center justify-center min-h-[48px] px-6 rounded-xl text-sm font-semibold text-white"
            style={{
              background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
              boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
            }}
            data-testid="predplatne-cta-external"
          >
            {t("pages.subscription.cta")}
          </a>
        </div>

        <p className="text-xs text-muted-foreground pt-4 border-t border-border/50">
          <Link to="/" className="underline hover:text-[hsl(var(--primary))]">
            {t("common.backHome")}
          </Link>
        </p>
      </div>
    </AppShell>
  );
}
