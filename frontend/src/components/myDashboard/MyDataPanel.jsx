import React, { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios, postFormData } from "@/lib/api";
import { Trash2, Upload } from "lucide-react";
import PersonalUploadChartForm from "@/components/myDashboard/PersonalUploadChartForm";
import PdfChartExtractPanel from "@/components/myDashboard/PdfChartExtractPanel";
import { LoadingInline, LoadingSpinner } from "@/components/ui/loading";

function fmtSize(n) {
  if (n == null || Number.isNaN(Number(n))) return "—";
  const v = Number(n);
  if (v < 1024) return `${v} B`;
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`;
  return `${(v / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * @param {() => void} [onUploaded]
 * @param {boolean} [showChartAndCalc] — zobrazí formulář grafu + vlastního výpočtu (osobní dashboard se stránkou)
 * @param {(payload: object) => Promise<void>} [onUploadChartApply]
 * @param {boolean} [chartApplyDisabled] — např. když není vybraná stránka
 */
export default function MyDataPanel({
  onUploaded,
  showChartAndCalc = false,
  onUploadChartApply,
  chartApplyDisabled = false,
}) {
  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(true);
  const [upBusy, setUpBusy] = useState(false);
  const [previewId, setPreviewId] = useState(null);
  const [preview, setPreview] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await api.get("/me/uploads");
      setList(Array.isArray(data) ? data : []);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nelze načíst soubory");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const onFile = async (e) => {
    const f = e.target.files?.[0];
    e.target.value = "";
    if (!f) return;
    setUpBusy(true);
    try {
      const fd = new FormData();
      fd.append("file", f, f.name);
      await postFormData("/me/uploads", fd);
      toast.success("Soubor byl nahrán.");
    } catch (err) {
      toast.error(formatApiErrorFromAxios(err) || "Nahrání se nepodařilo");
      return;
    } finally {
      setUpBusy(false);
    }
    try {
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Soubor byl nahrán, ale seznam se nepodařilo obnovit.");
    }
    onUploaded?.();
  };

  const remove = async (id) => {
    if (!window.confirm("Opravdu smazat tento soubor?")) return;
    try {
      await api.delete(`/me/uploads/${id}`);
      toast.success("Soubor smazán");
      await load();
      onUploaded?.();
      if (previewId === id) {
        setPreviewId(null);
        setPreview(null);
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Mazání selhalo");
    }
  };

  const showPreview = async (id) => {
    setPreviewId(id);
    setPreview(null);
    try {
      const { data } = await api.get(`/me/uploads/${id}/preview`);
      setPreview(data);
    } catch {
      setPreview({ error: "Náhled se nepodařil načíst." });
    }
  };

  return (
    <section className="soft-card p-4 mb-4 border border-border/80 copper-text-fix-scope">
      <h3 className="text-sm font-semibold text-foreground mb-1">Moje data</h3>
      <p className="text-xs text-muted-foreground mb-3 leading-relaxed">
        Nahrané soubory jsou dostupné jen vám. U widgetu typu „Graf z mých dat“ můžete zvolit i{" "}
        <strong>vlastní výpočet</strong> ze dvou sloupců (např. podíl nebo rozdíl) před sčítáním podle
        osy X.
      </p>
      {showChartAndCalc && typeof onUploadChartApply === "function" && (
        <div className="mb-4">
          <PersonalUploadChartForm
            uploads={list}
            onApply={onUploadChartApply}
            disabled={chartApplyDisabled}
          />
        </div>
      )}
      {showChartAndCalc && typeof onUploadChartApply === "function" && (
        <div className="mb-4">
          <PdfChartExtractPanel
            onWidgetCreated={onUploadChartApply}
            widgetApplyDisabled={chartApplyDisabled}
          />
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2 mb-3">
        <label
          aria-busy={upBusy ? "true" : undefined}
          className={`inline-flex items-center gap-2 px-3 py-2 rounded-xl border border-dashed border-border/80 text-sm bg-card/75 hover:bg-card ${
            upBusy ? "cursor-wait opacity-90" : "cursor-pointer"
          }`}
        >
          {upBusy ? <LoadingSpinner suppressAria size="sm" aria-label="" /> : <Upload className="h-4 w-4" />}
          {upBusy ? "Nahrávám…" : "Nahrát CSV nebo XLSX"}
          <input type="file" accept=".csv,.xlsx,.xlsm" className="hidden" onChange={onFile} disabled={upBusy} />
        </label>
      </div>
      {loading ? (
        <LoadingInline label="Načítám vaše soubory…" size="sm" className="py-2" muted />
      ) : list.length === 0 ? (
        <p className="text-xs text-muted-foreground">Zatím žádné soubory.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs data-table">
            <thead>
              <tr>
                <th className="text-left">Název</th>
                <th className="text-left">Nahráno</th>
                <th className="text-left">Velikost</th>
                <th className="w-[1%]" />
              </tr>
            </thead>
            <tbody>
              {list.map((u) => (
                <tr key={u.id}>
                  <td className="font-medium max-w-[200px] truncate" title={u.original_name}>
                    {u.original_name}
                  </td>
                  <td className="mono text-[11px] text-muted-foreground">{u.created_at || "—"}</td>
                  <td className="mono">{fmtSize(u.size)}</td>
                  <td className="text-right whitespace-nowrap">
                    <button
                      type="button"
                      className="text-[11px] text-[hsl(var(--primary))] underline mr-2"
                      onClick={() => showPreview(u.id)}
                    >
                      Náhled sloupců
                    </button>
                    <button
                      type="button"
                      className="p-1.5 rounded-md text-muted-foreground hover:text-red-600"
                      onClick={() => remove(u.id)}
                      title="Smazat"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {previewId && preview && (
        <div className="mt-3 p-3 rounded-lg bg-muted/35 border border-border/60 text-xs">
          <div className="font-medium text-foreground mb-1">Náhled sloupců</div>
          {preview.error ? (
            <span className="text-red-700">{preview.error}</span>
          ) : (
            <>
              <p className="text-muted-foreground mb-1">Sloupce: {(preview.columns || []).join(", ") || "—"}</p>
              {preview.sample_rows?.length > 0 && (
                <pre className="text-[10px] overflow-x-auto max-h-32 bg-card p-2 rounded border border-border/50">
                  {JSON.stringify(preview.sample_rows[0], null, 0)}
                </pre>
              )}
            </>
          )}
        </div>
      )}
    </section>
  );
}
