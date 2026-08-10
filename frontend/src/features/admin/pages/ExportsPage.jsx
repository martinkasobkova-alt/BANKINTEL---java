import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import api from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import SecuredExportButton from "@/components/SecuredExportButton";
import { FileSpreadsheet, FileText, Database, Calculator } from "lucide-react";
import { fmtInt } from "@/lib/format";

export default function ExportsPage() {
  const { t } = useTranslation();
  const [datasets, setDatasets] = useState([]);
  const [formulas, setFormulas] = useState([]);
  useEffect(() => {
    (async () => {
      const [{ data: ds }, { data: fs }] = await Promise.all([
        api.get("/datasets"),
        api.get("/formulas"),
      ]);
      setDatasets(ds);
      setFormulas(fs);
    })();
  }, []);

  return (
    <AppShell title={t("pages.admin.exportsTitle")} subtitle={t("pages.admin.exportsSubtitle")}>
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <Card icon={Database} title="Datové sady" subtitle="Surové a normalizované záznamy">
          {datasets.length === 0 ? (
            <Empty>Zatím nejsou k dispozici žádné datové sady.</Empty>
          ) : (
            <ul data-testid="export-datasets-list">
              {datasets.map((d) => (
                <Row
                  key={d.id}
                  name={d.name}
                  hint={`${fmtInt(d.record_count)} záznamů`}
                  xlsxPath={`export/dataset/${d.id}.xlsx`}
                  pdfPath={`export/dataset/${d.id}.pdf`}
                  tid={`export-ds-${d.name}`}
                />
              ))}
            </ul>
          )}
        </Card>

        <Card icon={Calculator} title="Vzorce" subtitle="Vypočtené metriky napříč datovými sadami">
          {formulas.length === 0 ? (
            <Empty>Žádné vzorce nebyly zatím definovány.</Empty>
          ) : (
            <ul data-testid="export-formulas-list">
              {formulas.map((f) => (
                <Row
                  key={f.id}
                  name={f.name}
                  hint={f.expression}
                  xlsxPath={`export/formula/${f.id}.xlsx`}
                  pdfPath={`export/formula/${f.id}.pdf`}
                  tid={`export-f-${f.name}`}
                />
              ))}
            </ul>
          )}
        </Card>
      </div>
    </AppShell>
  );
}

function Card({ icon: Icon, title, subtitle, children }) {
  return (
    <div className="bg-white border border-border rounded-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-border flex items-start gap-3">
        <div className="h-8 w-8 grid place-items-center rounded-lg text-white" style={{ background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))" }}>
          <Icon className="h-4 w-4" strokeWidth={1.6} />
        </div>
        <div>
          <div className="kpi-label">{title}</div>
          <div className="text-sm text-slate-500">{subtitle}</div>
        </div>
      </div>
      {children}
    </div>
  );
}

function Row({ name, hint, xlsxPath, pdfPath, tid }) {
  return (
    <li data-testid={tid} className="px-6 py-3 border-b border-border flex items-center justify-between gap-4 last:border-b-0">
      <div className="min-w-0">
        <div className="font-medium text-slate-900">{name}</div>
        <div className="text-xs font-mono text-slate-500 truncate">{hint}</div>
      </div>
      <div className="flex items-center gap-2">
        <SecuredExportButton
          relativePath={xlsxPath}
          filename="export.xlsx"
          className="flex items-center gap-1.5 px-3 h-9 text-xs border border-border rounded-sm hover:bg-slate-100"
        >
          <FileSpreadsheet className="h-4 w-4" /> Excel
        </SecuredExportButton>
        <SecuredExportButton
          relativePath={pdfPath}
          filename="export.pdf"
          className="flex items-center gap-1.5 px-3 h-9 text-xs border border-border rounded-sm hover:bg-slate-100"
        >
          <FileText className="h-4 w-4" /> PDF
        </SecuredExportButton>
      </div>
    </li>
  );
}

function Empty({ children }) {
  return <div className="px-6 py-10 text-center text-slate-500 font-mono text-sm">{children}</div>;
}
