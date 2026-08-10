import React, { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { FolderSearch, HardDrive, LayoutDashboard, Loader2, Search, X } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { CATALOG_PICKER_OPTIONS, catalogPickerLabel, searchCatalogPickerItems } from "@/lib/catalogChartPickerSearch";
import { DASHBOARD_SHAREABLE_WIDGET_TYPES, normalizeSharedChart } from "@/lib/sharedChartLink";

const PICKER_SOURCES = [
  { id: "catalog", label: "Katalog", icon: FolderSearch },
  { id: "dashboard", label: "Můj dashboard", icon: LayoutDashboard },
  { id: "my-data", label: "Moje data", icon: HardDrive },
];

/**
 * Modal pro výběr grafu k propojení s místem v PDF.
 * onPick({ title, source_type, set_id, link_url })
 */
export default function ArchiveChartLinkPicker({ open, onClose, onPick }) {
  const [pickerSource, setPickerSource] = useState("catalog");
  const [catalogFilter, setCatalogFilter] = useState("");
  const [pickerQuery, setPickerQuery] = useState("");
  const [pickerItems, setPickerItems] = useState([]);
  const [pickerLoading, setPickerLoading] = useState(false);

  const isCatalog = pickerSource === "catalog";
  const catalogNeedsQuery = isCatalog && pickerQuery.trim().length < 2;
  const selectedCatalogLabel = catalogFilter
    ? CATALOG_PICKER_OPTIONS.find((o) => o.id === catalogFilter)?.label || catalogFilter
    : "všech katalozích";

  useEffect(() => {
    if (!open) {
      setCatalogFilter("");
      setPickerQuery("");
      setPickerItems([]);
    }
  }, [open]);

  useEffect(() => {
    if (!open || isCatalog) return;
    let cancelled = false;

    const load = async () => {
      setPickerLoading(true);
      try {
        if (pickerSource === "dashboard") {
          const { data: pages } = await api.get("/me/dashboard/pages");
          if (cancelled) return;
          const pageList = Array.isArray(pages) ? pages : [];
          const widgetsByPage = await Promise.all(
            pageList.map(async (p) => {
              const pid = String(p?.id || "").trim();
              if (!pid) return [];
              try {
                const { data: widgets } = await api.get(`/me/dashboard/pages/${encodeURIComponent(pid)}/widgets`);
                const arr = Array.isArray(widgets) ? widgets : [];
                return arr
                  .filter((w) => DASHBOARD_SHAREABLE_WIDGET_TYPES.has(String(w?.type || "").trim().toLowerCase()))
                  .map((w) => {
                    const wid = String(w?.id || "").trim();
                    if (!wid) return null;
                    const pageTitle = String(p?.title || "Můj dashboard").trim();
                    const widgetTitle = String(w?.title || "").trim();
                    return {
                      id: `dashboard_widget:${wid}`,
                      title: widgetTitle ? `${widgetTitle} (${pageTitle})` : pageTitle,
                      source_type: "dashboard_widget",
                      set_id: `dashboard_widget:${wid}`,
                      link_url: `/my-dashboard?page=${encodeURIComponent(pid)}#widget-${encodeURIComponent(wid)}`,
                    };
                  })
                  .filter(Boolean);
              } catch {
                return [];
              }
            })
          );
          setPickerItems(widgetsByPage.flat());
          return;
        }
        if (pickerSource === "my-data") {
          const [uploadChartsRes, seriesRes] = await Promise.all([api.get("/me/upload-charts"), api.get("/my-series")]);
          if (cancelled) return;
          const uploadCharts = (Array.isArray(uploadChartsRes.data) ? uploadChartsRes.data : []).map((c) => ({
            id: `upload_chart:${c.id}`,
            title: c.title || c.upload_name || `Graf ${c.id}`,
            source_type: "my_upload_chart",
            set_id: `my_upload_chart:${c.id}`,
            link_url: c.page_id ? `/my-dashboard?page=${encodeURIComponent(String(c.page_id))}` : "/my-dashboard",
          }));
          const series = (Array.isArray(seriesRes.data) ? seriesRes.data : []).map((s) => ({
            id: `series:${s.id}`,
            title: s.title || s.name || `Řada ${s.id}`,
            source_type: "my_series",
            set_id: `my_series:${s.id}`,
            link_url: `/my-data?series=${encodeURIComponent(String(s.id || ""))}`,
          }));
          setPickerItems([...uploadCharts, ...series]);
        }
      } catch (e) {
        if (!cancelled) toast.error(formatApiErrorFromAxios(e));
      } finally {
        if (!cancelled) setPickerLoading(false);
      }
    };
    load();
    return () => {
      cancelled = true;
    };
  }, [open, pickerSource, isCatalog]);

  useEffect(() => {
    if (!open || !isCatalog) return undefined;
    const q = pickerQuery.trim();
    if (q.length < 2) {
      setPickerItems([]);
      setPickerLoading(false);
      return undefined;
    }
    let cancelled = false;
    setPickerLoading(true);
    const timer = window.setTimeout(() => {
      searchCatalogPickerItems(q, { catalogId: catalogFilter, mode: "classic" })
        .then((items) => {
          if (!cancelled) setPickerItems(items);
        })
        .catch((e) => {
          if (!cancelled) {
            toast.error(formatApiErrorFromAxios(e));
            setPickerItems([]);
          }
        })
        .finally(() => {
          if (!cancelled) setPickerLoading(false);
        });
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [open, isCatalog, pickerQuery, catalogFilter]);

  const filtered = useMemo(() => {
    if (isCatalog) return pickerItems;
    const q = pickerQuery.trim().toLowerCase();
    if (!q) return pickerItems;
    return pickerItems.filter((it) => String(it.title || "").toLowerCase().includes(q));
  }, [isCatalog, pickerItems, pickerQuery]);

  if (!open || typeof document === "undefined") return null;

  return createPortal(
    <div className="fixed inset-0 z-[500] flex items-center justify-center bg-black/40 p-4" role="dialog" aria-modal="true">
      <div className="w-full max-w-lg rounded-xl border border-border/70 bg-white shadow-2xl flex flex-col max-h-[85vh]">
        <div className="flex items-center justify-between border-b border-border/60 px-4 py-3">
          <div className="text-sm font-semibold text-slate-900">Vybrat graf k propojení</div>
          <button type="button" onClick={onClose} className="inline-flex h-8 w-8 items-center justify-center rounded-md text-slate-600 hover:bg-slate-100" aria-label="Zavřít">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="flex flex-wrap gap-1.5 border-b border-border/60 px-4 py-2">
          {PICKER_SOURCES.map((src) => {
            const Icon = src.icon;
            const active = pickerSource === src.id;
            return (
              <button
                key={src.id}
                type="button"
                onClick={() => {
                  setPickerSource(src.id);
                  setPickerQuery("");
                  setPickerItems([]);
                }}
                className={`inline-flex h-8 items-center gap-1.5 rounded-md border px-2.5 text-xs font-medium ${
                  active ? "border-[hsl(var(--primary)/0.4)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]" : "border-border/70 text-slate-700 hover:bg-slate-50"
                }`}
              >
                <Icon className="h-3.5 w-3.5" />
                {src.label}
              </button>
            );
          })}
        </div>
        <div className="px-4 py-2 space-y-2">
          {isCatalog ? (
            <select
              className="input w-full h-9 text-sm"
              value={catalogFilter}
              onChange={(e) => {
                setCatalogFilter(e.target.value);
                setPickerItems([]);
              }}
              aria-label="Vybrat katalog"
            >
              {CATALOG_PICKER_OPTIONS.map((opt) => (
                <option key={opt.id || "all"} value={opt.id}>
                  {opt.label}
                </option>
              ))}
            </select>
          ) : null}
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              className="input w-full pl-9 h-9 text-sm"
              placeholder={
                isCatalog
                  ? catalogFilter
                    ? `Hledat v katalogu ${selectedCatalogLabel}…`
                    : "Hledat ve všech katalozích (ARAD, ČSÚ, Eurostat…)"
                  : "Filtrovat seznam…"
              }
              value={pickerQuery}
              onChange={(e) => setPickerQuery(e.target.value)}
            />
          </div>
          {isCatalog ? (
            <p className="text-[11px] text-slate-500 leading-snug">
              {catalogFilter
                ? `Klasické hledání v katalogu ${selectedCatalogLabel} — zadejte alespoň 2 znaky.`
                : "Klasické hledání napříč katalogy — zadejte alespoň 2 znaky, nebo nejdřív vyberte konkrétní katalog."}
            </p>
          ) : null}
        </div>
        <div className="flex-1 overflow-y-auto px-4 pb-4 space-y-1.5 min-h-[200px]">
          {pickerLoading ? (
            <div className="flex items-center justify-center py-10 text-slate-500 text-sm gap-2">
              <Loader2 className="h-4 w-4 animate-spin" />
              {isCatalog ? "Hledám v katalogu…" : "Načítám…"}
            </div>
          ) : catalogNeedsQuery ? (
            <p className="text-sm text-slate-500 py-6 text-center">Zadejte dotaz pro vyhledání v katalogu dat.</p>
          ) : filtered.length === 0 ? (
            <p className="text-sm text-slate-500 py-6 text-center">
              {isCatalog
                ? `V katalogu ${selectedCatalogLabel} nebyla nalezena žádná řada.`
                : "Nic k výběru."}
            </p>
          ) : (
            filtered.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => {
                  const chart = normalizeSharedChart(item);
                  if (chart) onPick?.(chart);
                  onClose?.();
                }}
                className="w-full text-left rounded-md border border-border/70 bg-white px-3 py-2 text-sm hover:bg-slate-50"
              >
                <div className="font-medium text-slate-900">{item.title}</div>
                {item.source_type ? (
                  <div className="text-[11px] text-slate-500 mt-0.5">{catalogPickerLabel(item.source_type)}</div>
                ) : null}
              </button>
            ))
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}
