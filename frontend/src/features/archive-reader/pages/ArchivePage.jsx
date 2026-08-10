import React, { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { BookOpen, Plus } from "lucide-react";
import AppShell from "@/components/layout/AppShell";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";

const ARCHIVE_REQUEST_TIMEOUT_MS = 12000;

/** @param {{ manage?: boolean }} props — `manage`: správa archivu (admin), bez: čtení pro uživatele */
export default function ArchivePage({ manage = false }) {
  const { t } = useTranslation();
  const { isAdmin } = useAuth();
  const showManage = manage && isAdmin;
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");

  const [newTitle, setNewTitle] = useState("");
  const [newDesc, setNewDesc] = useState("");
  const [saving, setSaving] = useState(false);

  const load = async () => {
    setLoading(true);
    setErr("");
    try {
      const { data } = await api.get("/magazines", { timeout: ARCHIVE_REQUEST_TIMEOUT_MS });
      setRows(Array.isArray(data) ? data : []);
    } catch (e) {
      setErr(formatApiErrorFromAxios(e));
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const createMagazine = async (e) => {
    e.preventDefault();
    if (!newTitle.trim()) return;
    setSaving(true);
    setErr("");
    try {
      await api.post("/magazines", {
        title: newTitle.trim(),
        description: newDesc.trim(),
      });
      setNewTitle("");
      setNewDesc("");
      await load();
    } catch (e2) {
      setErr(formatApiErrorFromAxios(e2));
    } finally {
      setSaving(false);
    }
  };

  return (
    <AppShell
      title={showManage ? t("pages.archive.adminTitle") : t("pages.archive.title")}
      subtitle={showManage ? t("pages.archive.adminSubtitle") : t("pages.archive.subtitle")}
    >
      <div className="max-w-5xl space-y-5">
        {showManage ? (
          <form onSubmit={createMagazine} className="soft-card p-4 space-y-3">
            <div className="text-sm font-semibold text-slate-900 flex items-center gap-2">
              <Plus className="h-4 w-4" /> {t("pages.archive.newMagazine")}
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              <input
                value={newTitle}
                onChange={(e) => setNewTitle(e.target.value)}
                placeholder={t("pages.archive.magazineNamePlaceholder")}
                className="input w-full"
                required
              />
              <input
                value={newDesc}
                onChange={(e) => setNewDesc(e.target.value)}
                placeholder={t("pages.archive.magazineDescPlaceholder")}
                className="input w-full"
              />
            </div>
            <button type="submit" disabled={saving} className="btn-primary h-10 px-4 text-sm">
              {saving ? t("common.saving") : t("pages.archive.createMagazine")}
            </button>
          </form>
        ) : null}

        {err ? (
          <div className="chip-rose rounded-md p-3 text-sm">
            <div>{err}</div>
            <button type="button" onClick={load} className="mt-2 btn-ghost h-8 px-3 text-xs">
              Zkusit načíst znovu
            </button>
          </div>
        ) : null}

        {loading ? (
          <div className="text-sm text-slate-600">{t("pages.archive.loading")}</div>
        ) : rows.length === 0 ? (
          <div className="soft-card p-6 text-sm text-slate-600">{t("pages.archive.empty")}</div>
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            {rows.map((m) => (
              <Link
                key={m.id}
                to={showManage ? `/admin/archive/${m.id}` : `/archive/${m.id}`}
                className="soft-card p-4 hover:border-[hsl(var(--primary)/0.45)] transition block"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="font-semibold text-slate-900 truncate">{m.title}</div>
                    <div className="text-xs text-slate-500 mt-1 line-clamp-2">
                      {m.description || t("pages.archive.noDescription")}
                    </div>
                  </div>
                  <BookOpen className="h-4 w-4 text-slate-400 shrink-0" />
                </div>
                <div className="text-xs text-slate-500 mt-3">
                  {showManage
                    ? t("pages.archive.issueStats", {
                        count: m.issue_count || 0,
                        ready: m.ready_issue_count || 0,
                      })
                    : t("pages.archive.issueCount", {
                        count: m.ready_issue_count || m.issue_count || 0,
                      })}
                </div>
              </Link>
            ))}
          </div>
        )}
      </div>
    </AppShell>
  );
}
