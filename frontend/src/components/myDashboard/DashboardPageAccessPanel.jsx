import React, { useCallback, useEffect, useState } from "react";
import { ChevronDown, Copy, Download, Lock, LockOpen, Users, Globe, Link2 } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";

const COLLAPSE_STORAGE_KEY = "mydash_page_access_panel_collapsed";

const ACCESS_OPTIONS = [
  { id: "owner_only", label: "Zamčený", hint: "Pouze vy — nikdo jiný stránku neuvidí.", Icon: Lock },
  {
    id: "invite_only",
    label: "Vybraní uživatelé",
    hint: "Přístup jen pro vybrané účty nebo přes tajný odkaz.",
    Icon: Users,
  },
  { id: "public", label: "Veřejný", hint: "Kdokoli může stránku otevřít a najde ji ve vyhledávání.", Icon: Globe },
];

function isChartWidget(w) {
  return (
    w?.type === "chart" ||
    w?.type === "external_catalog_chart" ||
    w?.type === "computed_chart" ||
    w?.type === "uploaded_data_chart" ||
    w?.type === "user_upload_chart" ||
    w?.engine_type === "external_catalog_chart"
  );
}

function isWidgetSourceDataLocked(w) {
  return Boolean(w?.config?.lock_source_data || w?.lock_source_data);
}

/** Pouze majitel stránky — hosté se sdíleným přístupem panel nevidí. */
export default function DashboardPageAccessPanel({
  pageId,
  page,
  onUpdated,
  widgets = [],
  onPatchWidget,
  isOwner = false,
}) {
  const [accessMode, setAccessMode] = useState("owner_only");
  const [shareEnabled, setShareEnabled] = useState(false);
  const [shareToken, setShareToken] = useState("");
  const [allowEmbed, setAllowEmbed] = useState(false);
  const [allowedUsers, setAllowedUsers] = useState([]);
  const [userQuery, setUserQuery] = useState("");
  const [userHits, setUserHits] = useState([]);
  const [saving, setSaving] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [lockingBulk, setLockingBulk] = useState(false);
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem(COLLAPSE_STORAGE_KEY) === "1";
    } catch {
      return false;
    }
  });

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(COLLAPSE_STORAGE_KEY, next ? "1" : "0");
      } catch {
        /* ignore */
      }
      return next;
    });
  };

  const accessModeLabel =
    ACCESS_OPTIONS.find((o) => o.id === accessMode)?.label || "Zamčený";

  useEffect(() => {
    if (!page) return;
    setAccessMode(page.access_mode || "owner_only");
    setShareEnabled(Boolean(page.share_enabled));
    setShareToken(page.share_token || "");
    setAllowEmbed(Boolean(page.allow_embed));
    setAllowedUsers(Array.isArray(page.allowed_user_ids) ? page.allowed_user_ids : []);
  }, [page]);

  const saveAccess = async (patch) => {
    if (!pageId) return;
    setSaving(true);
    try {
      const { data } = await api.patch(`/me/dashboard/pages/${pageId}`, patch);
      onUpdated?.(data);
      toast.success("Nastavení přístupu uloženo");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba uložení");
    } finally {
      setSaving(false);
    }
  };

  const searchUsers = useCallback(async (q) => {
    const needle = String(q || "").trim();
    if (needle.length < 2) {
      setUserHits([]);
      return;
    }
    try {
      const { data } = await api.get("/chat/users", { params: { q: needle, limit: 12 } });
      setUserHits(Array.isArray(data) ? data : []);
    } catch {
      setUserHits([]);
    }
  }, []);

  useEffect(() => {
    const t = window.setTimeout(() => searchUsers(userQuery), 250);
    return () => window.clearTimeout(t);
  }, [userQuery, searchUsers]);

  const shareUrl = shareToken
    ? `${window.location.origin}/shared/dashboard/${encodeURIComponent(shareToken)}`
    : "";

  const embedUrlPattern = shareToken
    ? `${window.location.origin}/embed/${encodeURIComponent(shareToken)}/WIDGET_ID`
    : "";

  const copyShareLink = async () => {
    if (!shareUrl) return;
    try {
      await navigator.clipboard.writeText(shareUrl);
      toast.success("Tajný odkaz zkopírován");
    } catch {
      toast.error("Kopírování se nepodařilo");
    }
  };

  const exportPageExcel = async () => {
    if (!pageId) return;
    setExporting(true);
    try {
      const res = await api.post(`/me/dashboard/pages/${pageId}/export.xlsx`, {}, { responseType: "blob" });
      const blob = new Blob([res.data], {
        type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${(page?.title || "dashboard").replace(/[^\w-]+/g, "_")}.xlsx`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      toast.success("Excel stránky stažen");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Export se nepodařil");
    } finally {
      setExporting(false);
    }
  };

  const toggleWidgetLock = async (widget) => {
    if (!widget?.id || !onPatchWidget || lockingBulk) return;
    const next = !isWidgetSourceDataLocked(widget);
    try {
      await onPatchWidget(widget.id, { config: { lock_source_data: next } });
      toast.success(next ? "Zdrojová data zamčena" : "Zdrojová data odemčena");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se změnit zamčení grafu");
    }
  };

  const setAllChartsLock = async (nextLocked) => {
    if (!onPatchWidget) return;
    const targets = widgets.filter(
      (w) => isChartWidget(w) && isWidgetSourceDataLocked(w) !== nextLocked
    );
    if (targets.length === 0) {
      toast.info(nextLocked ? "Všechny grafy jsou už zamčené" : "Všechny grafy jsou už odemčené");
      return;
    }
    setLockingBulk(true);
    let ok = 0;
    try {
      for (const w of targets) {
        await onPatchWidget(w.id, { config: { lock_source_data: nextLocked } });
        ok += 1;
      }
      toast.success(nextLocked ? `Zamčeno ${ok} graf(ů)` : `Odemčeno ${ok} graf(ů)`);
    } catch (e) {
      toast.error(
        formatApiErrorFromAxios(e) ||
          (ok > 0
            ? `U ${ok} z ${targets.length} grafů se stav změnil — zkuste znovu u zbývajících`
            : "Nepodařilo se změnit zamčení grafů")
      );
    } finally {
      setLockingBulk(false);
    }
  };

  const chartWidgets = widgets.filter(isChartWidget);

  if (!isOwner) return null;

  return (
    <div className="soft-card text-sm overflow-hidden">
      <button
        type="button"
        onClick={toggleCollapsed}
        className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left hover:bg-slate-50/80 transition-colors"
        aria-expanded={!collapsed}
        title={collapsed ? "Rozbalit nastavení přístupu" : "Sbalit nastavení přístupu"}
      >
        <div className="min-w-0">
          <div className="font-semibold text-slate-900">Přístup, export a zabezpečení</div>
          {collapsed ? (
            <p className="mt-0.5 text-xs text-slate-500 truncate">
              {accessModeLabel}
              {accessMode === "invite_only" && shareEnabled ? " · tajný odkaz" : ""}
              {allowEmbed ? " · embed" : ""}
            </p>
          ) : (
            <p className="mt-0.5 text-xs text-slate-600 leading-snug">
              Vložení grafů do článku (iframe), sdílení stránky, export a zamčení dat u grafů.
            </p>
          )}
        </div>
        <ChevronDown
          className={`h-4 w-4 shrink-0 text-slate-500 transition-transform duration-200 ${
            collapsed ? "" : "rotate-180"
          }`}
          aria-hidden
        />
      </button>

      {!collapsed ? (
        <div className="space-y-4 px-4 pb-4 border-t border-border/60 pt-3">
      <div className="rounded-lg border border-[hsl(var(--primary)/0.25)] bg-[hsl(var(--primary-soft)/0.2)] p-3 space-y-2">
        <div className="font-medium text-slate-900">Vložení grafů do článku</div>
        <p className="text-[11px] text-slate-600 leading-snug">
          Interaktivní graf lze vložit do článku přes iframe. Čtenář se nemusí přihlašovat; data jsou ze snapshotu
          stránky.
        </p>
        <label className="flex items-center gap-2 text-xs text-slate-800 font-medium">
          <input
            type="checkbox"
            checked={allowEmbed}
            disabled={saving}
            onChange={(e) => {
              const v = e.target.checked;
              setAllowEmbed(v);
              saveAccess({ allow_embed: v });
            }}
          />
          Povolit vložení do článku (embed)
        </label>
        {allowEmbed && shareToken ? (
          <div className="space-y-2 rounded-lg border border-border/60 bg-white/80 p-3">
            <label className="block text-[11px] font-medium text-slate-700">Embed token stránky</label>
            <input className="input w-full text-xs font-mono" readOnly value={shareToken} />
            <p className="text-[11px] text-slate-500">
              U každého grafu na stránce pak použijte tlačítko „Embed“ (vpravo nahoře u grafu) pro hotový iframe
              kód.
            </p>
            <label className="block text-[11px] font-medium text-slate-700">Vzor URL (nahraďte WIDGET_ID)</label>
            <input className="input w-full text-xs font-mono" readOnly value={embedUrlPattern} />
          </div>
        ) : allowEmbed ? (
          <p className="text-[11px] text-slate-500">Token se načítá… uložte nastavení znovu, pokud se neobjeví.</p>
        ) : null}
      </div>

      <div>
        <div className="font-semibold text-slate-900">Přístup ke stránce</div>
        <p className="mt-1 text-xs text-slate-600 leading-relaxed">
          Sdílejte celou stránku dashboardu — vybraným uživatelům, přes tajný odkaz (i bez registrace) nebo
          veřejně.
        </p>
      </div>

      <div className="flex flex-wrap gap-2">
        {ACCESS_OPTIONS.map((opt) => {
          const Icon = opt.Icon;
          const active = accessMode === opt.id;
          return (
            <button
              key={opt.id}
              type="button"
              disabled={saving}
              onClick={() => {
                setAccessMode(opt.id);
                saveAccess({ access_mode: opt.id });
              }}
              className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-2 text-xs font-medium ${
                active
                  ? "border-[hsl(var(--primary)/0.45)] bg-[hsl(var(--primary-soft)/0.45)] text-[hsl(var(--primary-deep))]"
                  : "border-border/70 bg-white text-slate-700 hover:bg-slate-50"
              }`}
              title={opt.hint}
            >
              <Icon className="h-3.5 w-3.5 shrink-0" />
              {opt.label}
            </button>
          );
        })}
      </div>

      {accessMode === "invite_only" ? (
        <div className="space-y-3 rounded-lg border border-border/60 bg-slate-50/80 p-3">
          <label className="flex items-center gap-2 text-xs text-slate-700">
            <input
              type="checkbox"
              checked={shareEnabled}
              onChange={(e) => {
                const v = e.target.checked;
                setShareEnabled(v);
                saveAccess({ access_mode: "invite_only", share_enabled: v });
              }}
            />
            Povolit tajný odkaz (pro nepřihlášené)
          </label>
          {shareEnabled && shareUrl ? (
            <div className="flex flex-wrap items-center gap-2">
              <input className="input flex-1 min-w-[12rem] text-xs font-mono" readOnly value={shareUrl} />
              <button type="button" className="btn-ghost h-8 px-2 text-xs" onClick={copyShareLink}>
                <Copy className="h-3.5 w-3.5 inline mr-1" />
                Kopírovat odkaz
              </button>
              <button
                type="button"
                className="btn-ghost h-8 px-2 text-xs"
                disabled={saving}
                onClick={() => saveAccess({ regenerate_share_token: true })}
              >
                <Link2 className="h-3.5 w-3.5 inline mr-1" />
                Nový odkaz
              </button>
            </div>
          ) : null}
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Přidaní uživatelé (e-mail / jméno)</label>
            <input
              className="input w-full text-sm"
              placeholder="Hledat uživatele…"
              value={userQuery}
              onChange={(e) => setUserQuery(e.target.value)}
            />
            {userHits.length > 0 ? (
              <ul className="mt-2 space-y-1">
                {userHits.map((u) => (
                  <li key={u.id}>
                    <button
                      type="button"
                      className="w-full text-left rounded px-2 py-1 text-xs hover:bg-white border border-transparent hover:border-border/60"
                      onClick={() => {
                        if (allowedUsers.includes(u.id)) return;
                        const next = [...allowedUsers, u.id];
                        setAllowedUsers(next);
                        saveAccess({ allowed_user_ids: next });
                        setUserQuery("");
                        setUserHits([]);
                      }}
                    >
                      {u.name} <span className="text-slate-500">{u.email}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
            {allowedUsers.length > 0 ? (
              <p className="mt-2 text-[11px] text-slate-500">Přístup má {allowedUsers.length} uživatel(ů).</p>
            ) : null}
          </div>
        </div>
      ) : null}

      <div className="border-t border-border/60 pt-3">
        <div className="font-medium text-slate-900 mb-2">Export stránky</div>
        <button
          type="button"
          disabled={exporting}
          onClick={() => void exportPageExcel()}
          className="inline-flex items-center gap-1.5 rounded-lg border border-border/70 bg-white px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          <Download className="h-3.5 w-3.5" />
          {exporting ? "Exportuji…" : "Stáhnout stránku jako Excel"}
        </button>
        <p className="mt-1 text-[11px] text-slate-500">
          Grafy se zamčenými zdrojovými daty do Excelu nejdou — lze je exportovat jen jako obrázek/PDF.
        </p>
      </div>

      {chartWidgets.length > 0 && onPatchWidget ? (
        <div className="border-t border-border/60 pt-3 space-y-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="font-medium text-slate-900">Zamčení zdrojových dat u grafů</div>
            <div className="flex flex-wrap gap-1.5">
              <button
                type="button"
                disabled={lockingBulk}
                onClick={() => void setAllChartsLock(true)}
                className="inline-flex items-center gap-1 rounded border border-amber-300 bg-amber-50 px-2 py-1 text-[11px] font-medium text-amber-900 hover:bg-amber-100 disabled:opacity-50"
                title="Zamknout zdrojová data u všech grafů na stránce"
              >
                <Lock className="h-3 w-3" />
                {lockingBulk ? "Měním…" : "Zamknout vše"}
              </button>
              <button
                type="button"
                disabled={lockingBulk}
                onClick={() => void setAllChartsLock(false)}
                className="inline-flex items-center gap-1 rounded border border-border/70 bg-white px-2 py-1 text-[11px] font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-50"
                title="Odemknout zdrojová data u všech grafů na stránce"
              >
                <LockOpen className="h-3 w-3" />
                Odemknout vše
              </button>
            </div>
          </div>
          <p className="text-[11px] text-slate-500">
            Zamčený graf: export do Excelu a kopírování dat zakázáno, PDF/PNG povoleny.
          </p>
          <ul className="space-y-1">
            {chartWidgets.map((w) => {
              const locked = isWidgetSourceDataLocked(w);
              return (
                <li key={w.id} className="flex items-center justify-between gap-2 rounded border border-border/50 px-2 py-1.5 text-xs">
                  <span className="truncate">{w.title || w.id}</span>
                  <button
                    type="button"
                    disabled={lockingBulk}
                    className={`shrink-0 rounded px-2 py-0.5 border text-[11px] disabled:opacity-50 ${
                      locked
                        ? "border-amber-300 bg-amber-50 text-amber-900"
                        : "border-border/70 text-slate-600"
                    }`}
                    onClick={() => void toggleWidgetLock(w)}
                  >
                    <Lock className="h-3 w-3 inline mr-0.5" />
                    {locked ? "Zamčeno" : "Odemčeno"}
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}
        </div>
      ) : null}
    </div>
  );
}
