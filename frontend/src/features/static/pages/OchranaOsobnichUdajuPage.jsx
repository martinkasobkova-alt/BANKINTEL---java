import React from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import AppShell from "@/components/layout/AppShell";

export default function OchranaOsobnichUdajuPage() {
  const { t } = useTranslation();
  return (
    <AppShell title={t("pages.legal.privacyTitle")} subtitle={t("pages.legal.privacySubtitle")}>
      <div className="max-w-3xl space-y-6 text-sm text-slate-700 leading-relaxed pb-12">
        <p className="rounded-lg border border-amber-200 bg-amber-50/90 px-4 py-3 text-amber-900 text-xs">
          Tento text je pracovní verze pro pilotní provoz a měl by být před veřejným spuštěním právně
          zkontrolován.
        </p>

        <section>
          <h2 className="font-serif text-lg text-slate-900 mb-2">Jaké údaje můžeme zpracovávat</h2>
          <ul className="list-disc pl-5 space-y-1.5">
            <li>údaje uvedené při registraci: firma, jméno, e-mail, telefon,</li>
            <li>úroveň účtu a role / přístup k funkcím (např. free / předplatitel / admin),</li>
            <li>technické údaje pro bezpečnost a provoz (IP adresa, prohlížeč v odůvodněném rozsahu),</li>
            <li>hlášení chyb (bug report) včetně volitelného screenshotu a popisu,</li>
            <li>obsah osobního dashboardu, widgetů a volitelné nahrané soubory u předplatitele.</li>
          </ul>
        </section>

        <section>
          <h2 className="font-serif text-lg text-slate-900 mb-2">Účel zpracování</h2>
          <p>
            Přihlášení a správa účtu, uplatnění předplatitelského nebo roli správce, provoz datové
            platformy, ochrana před zneužitím, zlepšování spolehlivosti a podpora uživatelů, řešení
            nahlášených chyb.
          </p>
        </section>

        <section>
          <h2 className="font-serif text-lg text-slate-900 mb-2">Kdo má k informacím přístup</h2>
          <p>
            Správci aplikace v rozsahu potřebném pro správu provozu a podporu. Uživatel spravuje a vidí
            své vlastní stránky, widgety a nahrané soubory; data jsou oddělena podle identifikátoru
            účtu (<code className="text-xs bg-slate-100 px-1 rounded">user_id</code>).
          </p>
        </section>

        <section>
          <h2 className="font-serif text-lg text-slate-900 mb-2">Kontakt</h2>
          <p>Pro uplatnění práv týkajících se osobních údajů: Doplňte kontaktní e-mail správce.</p>
        </section>

        <p className="text-xs text-slate-500 pt-4 border-t border-border/50">
          <Link to="/cookies" className="underline hover:text-[hsl(var(--primary))] mr-4">
            Zásady cookies
          </Link>
          <Link to="/" className="underline hover:text-[hsl(var(--primary))]">
            ← Zpět na úvod
          </Link>
        </p>
      </div>
    </AppShell>
  );
}
