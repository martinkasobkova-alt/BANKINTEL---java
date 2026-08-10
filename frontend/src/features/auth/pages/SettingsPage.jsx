import React, { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccessContextOptional } from "@/contexts/FeatureAccessContext";
import { ShieldCheck, ChevronRight, Save, Eye, EyeOff } from "lucide-react";
import { LoadingInline } from "@/components/ui/loading";
import { PASSWORD_POLICY_HINT, validatePasswordClient } from "@/lib/passwordPolicy";

function accessLabel(user, t) {
  if (!user || user === false) return "—";
  if (user.role === "admin") return t("pages.settings.accessAdmin");
  if (user.role === "editor") return t("pages.settings.accessEditor");
  if (user.has_premium_access && user.access_tier === "subscriber") return t("pages.settings.accessPremium");
  return t("pages.settings.accessFree");
}

/** Profilová pole jako „buňky“ — zřetelný rámeček, hover a fokus. */
function ProfileField({ label, readOnly, monospace, className = "", ...inputProps }) {
  const shell =
    "block rounded-xl px-3 py-2.5 " +
    (readOnly
      ? "border border-dashed border-border/80 bg-muted/30 shadow-[inset_0_1px_0_hsl(var(--border)/0.35)] cursor-default"
      : "group cursor-text border border-border/90 bg-card shadow-sm transition-[box-shadow,border-color,background-color] duration-150 hover:border-[hsl(var(--primary)/0.42)] hover:shadow-md hover:bg-card focus-within:border-[hsl(var(--primary)/0.55)] focus-within:ring-2 focus-within:ring-ring/30 focus-within:shadow-md");
  const inputCls =
    "mt-1.5 w-full min-h-[1.375rem] border-0 bg-transparent p-0 text-sm outline-none ring-0 focus:ring-0 " +
    (monospace ? "font-mono text-[12px] leading-snug " : "") +
    (readOnly ? "cursor-default text-foreground/90 selection:bg-primary/15 " : "text-foreground placeholder:text-muted-foreground/55 ") +
    className;
  return (
    <label className={shell}>
      <span
        className={
          "text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground " +
          (readOnly ? "" : "group-hover:text-foreground/80")
        }
      >
        {label}
      </span>
      <input {...inputProps} readOnly={readOnly} className={inputCls} />
    </label>
  );
}

export default function SettingsPage() {
  const { t } = useTranslation();
  const { user, isAdmin, isSubscriber, refreshUser } = useAuth();
  const fe = useFeatureAccessContextOptional();
  const canPersonal = fe?.accessMapReady && fe?.effective?.personal_dashboard?.allowed === true;
  const [prefs, setPrefs] = useState({ open: false, default_id: null });
  const [pages, setPages] = useState([]);
  const [loading, setLoading] = useState(false);
  const [profileDraft, setProfileDraft] = useState({ name: "", company: "", phone: "" });
  const [profileSaving, setProfileSaving] = useState(false);
  const [pwdCurrent, setPwdCurrent] = useState("");
  const [pwdNew, setPwdNew] = useState("");
  const [pwdNew2, setPwdNew2] = useState("");
  const [pwdSaving, setPwdSaving] = useState(false);
  const [pwdShow, setPwdShow] = useState(false);

  useEffect(() => {
    setProfileDraft({
      name: user?.name || "",
      company: user?.company || "",
      phone: user?.phone || "",
    });
  }, [user?.name, user?.company, user?.phone]);

  useEffect(() => {
    if (!user || user === false) return;
    let c = false;
    (async () => {
      setLoading(true);
      try {
        const { data } = await api.get("/me/preferences");
        if (c) return;
        setPrefs({
          open: !!data.open_personal_dashboard_on_login,
          default_id: data.default_dashboard_page_id || null,
        });
        if (isSubscriber && canPersonal) {
          const { data: pl } = await api.get("/me/dashboard/pages");
          setPages(Array.isArray(pl) ? pl : []);
        }
      } catch {
        if (!c) toast.error("Nepodařilo se načíst předvolby.");
      } finally {
        if (!c) setLoading(false);
      }
    })();
    return () => {
      c = true;
    };
  }, [user, isSubscriber, canPersonal]);

  const patchPrefs = async (patch, toastMessage = "Uloženo.") => {
    try {
      const { data } = await api.patch("/me/preferences", patch);
      setPrefs({
        open: !!data.open_personal_dashboard_on_login,
        default_id: data.default_dashboard_page_id || null,
      });
      await refreshUser?.();
      toast.success(toastMessage);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba ukládání");
    }
  };

  const saveProfile = async () => {
    if (!profileDraft.name.trim()) {
      toast.error("Jméno je povinné.");
      return;
    }
    setProfileSaving(true);
    try {
      await api.patch("/me/profile", {
        name: profileDraft.name,
        company: profileDraft.company,
        phone: profileDraft.phone,
      });
      await refreshUser?.();
      toast.success("Profil byl uložen.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se uložit profil.");
    } finally {
      setProfileSaving(false);
    }
  };

  const savePassword = async () => {
    const v = validatePasswordClient(pwdNew);
    if (!v.ok) {
      toast.error(v.message);
      return;
    }
    if (pwdNew !== pwdNew2) {
      toast.error("Nová hesla se neshodují.");
      return;
    }
    if (!pwdCurrent) {
      toast.error("Zadejte současné heslo.");
      return;
    }
    setPwdSaving(true);
    try {
      await api.post("/me/change-password", {
        current_password: pwdCurrent,
        new_password: pwdNew,
      });
      setPwdCurrent("");
      setPwdNew("");
      setPwdNew2("");
      toast.success("Heslo bylo změněno.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Změna hesla se nezdařila.");
    } finally {
      setPwdSaving(false);
    }
  };

  return (
    <AppShell title={t("pages.settings.title")} subtitle={t("pages.settings.subtitle")}>
      <div className="max-w-3xl space-y-6 copper-text-fix-scope">
        <section className="soft-card rounded-2xl border-border/80 p-6">
          <h2 className="text-sm font-semibold text-foreground">{t("pages.settings.profile")}</h2>
          <p className="text-xs text-muted-foreground mt-1 mb-4">
            {t("pages.settings.profileHint")}
          </p>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <ProfileField
              label={t("common.name")}
              autoComplete="name"
              value={profileDraft.name}
              onChange={(e) => setProfileDraft((p) => ({ ...p, name: e.target.value }))}
            />
            <ProfileField
              label={t("common.company")}
              autoComplete="organization"
              value={profileDraft.company}
              onChange={(e) => setProfileDraft((p) => ({ ...p, company: e.target.value }))}
            />
            <ProfileField
              label={t("common.phone")}
              autoComplete="tel"
              inputMode="tel"
              value={profileDraft.phone}
              onChange={(e) => setProfileDraft((p) => ({ ...p, phone: e.target.value }))}
            />
            <ProfileField label={t("common.email")} readOnly monospace value={user?.email || ""} />
          </div>
          <dl className="text-sm space-y-2 mt-4">
            <Row
              label={t("pages.settings.role")}
              value={
                user?.role === "admin"
                  ? t("pages.settings.roleAdmin")
                  : user?.role === "editor"
                    ? t("pages.settings.roleEditor")
                    : t("pages.settings.roleViewer")
              }
            />
            <Row label={t("pages.settings.access")} value={accessLabel(user, t)} last />
          </dl>
          <div className="mt-4 flex justify-end">
            <button
              type="button"
              onClick={saveProfile}
              disabled={profileSaving}
              className="btn-primary inline-flex items-center gap-1.5 h-9 px-4 text-sm disabled:opacity-60"
            >
              <Save className="h-4 w-4" />
              {profileSaving ? t("common.saving") : t("pages.settings.saveProfile")}
            </button>
          </div>
        </section>

        {isSubscriber && canPersonal && (
          <section className="soft-card rounded-2xl border-border/80 p-6">
            <h2 className="text-sm font-semibold text-foreground mb-2">{t("pages.settings.myPreferences")}</h2>
            <p className="text-xs text-muted-foreground mb-4">
              {t("pages.settings.preferencesHint")}
            </p>
            {loading ? (
              <LoadingInline label={t("pages.settings.loadingPrefs")} size="sm" muted className="py-1" />
            ) : (
              <div className="space-y-4 text-sm">
                <label
                  className="flex items-start gap-2 cursor-pointer"
                  title={t("pages.settings.openOnDashboardTitle")}
                >
                  <input
                    type="checkbox"
                    checked={prefs.open}
                    onChange={(e) => {
                      const on = e.target.checked;
                      patchPrefs(
                        {
                          open_personal_dashboard_on_login: on,
                          default_dashboard_page_id: prefs.default_id,
                        },
                        on
                          ? t("pages.settings.prefSavedDashboard")
                          : t("pages.settings.prefSavedOverview")
                      );
                    }}
                  />
                  <span>{t("pages.settings.openOnDashboard")}</span>
                </label>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">{t("pages.settings.defaultDashboardPage")}</label>
                  <select
                    className="input w-full max-w-md"
                    value={prefs.default_id || ""}
                    onChange={(e) => {
                      const v = e.target.value || null;
                      patchPrefs({
                        open_personal_dashboard_on_login: prefs.open,
                        default_dashboard_page_id: v,
                      });
                    }}
                  >
                    <option value="">{t("common.none")}</option>
                    {pages.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.title}
                      </option>
                    ))}
                  </select>
                  {pages.length === 0 && (
                    <p className="text-xs text-muted-foreground mt-1">{t("pages.settings.noDashboardPages")}</p>
                  )}
                </div>
              </div>
            )}
          </section>
        )}

        <section className="soft-card rounded-2xl border-border/80 p-6">
          <h2 className="text-sm font-semibold text-foreground mb-2">{t("pages.settings.security")}</h2>
          <p className="text-xs text-muted-foreground mb-4">{PASSWORD_POLICY_HINT}</p>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-w-xl">
            <label className="md:col-span-2">
              <span className="text-[11px] text-muted-foreground">{t("pages.settings.currentPassword")}</span>
              <div className="relative mt-1">
                <input
                  className="input w-full pr-10"
                  type={pwdShow ? "text" : "password"}
                  autoComplete="current-password"
                  value={pwdCurrent}
                  onChange={(e) => setPwdCurrent(e.target.value)}
                />
                <button
                  type="button"
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground p-1"
                  onClick={() => setPwdShow((s) => !s)}
                  aria-label={pwdShow ? t("common.hidePassword") : t("common.showPassword")}
                >
                  {pwdShow ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </label>
            <label>
              <span className="text-[11px] text-muted-foreground">{t("pages.settings.newPassword")}</span>
              <input
                className="input mt-1 w-full"
                type={pwdShow ? "text" : "password"}
                autoComplete="new-password"
                value={pwdNew}
                onChange={(e) => setPwdNew(e.target.value)}
              />
            </label>
            <label>
              <span className="text-[11px] text-muted-foreground">{t("pages.settings.confirmNewPassword")}</span>
              <input
                className="input mt-1 w-full"
                type={pwdShow ? "text" : "password"}
                autoComplete="new-password"
                value={pwdNew2}
                onChange={(e) => setPwdNew2(e.target.value)}
              />
            </label>
          </div>
          <div className="mt-4 flex justify-end max-w-xl">
            <button
              type="button"
              onClick={savePassword}
              disabled={pwdSaving}
              className="btn-primary inline-flex items-center gap-1.5 h-9 px-4 text-sm disabled:opacity-60"
            >
              {pwdSaving ? t("common.saving") : t("pages.settings.changePassword")}
            </button>
          </div>
        </section>

        {isAdmin && (
          <section className="soft-card rounded-2xl border border-[hsl(var(--primary)/0.2)] p-6 bg-gradient-to-b from-white to-slate-50/50">
            <div className="flex items-center gap-2 text-sm font-semibold text-foreground mb-3">
              <ShieldCheck className="h-4 w-4 text-[hsl(var(--primary))]" />
              {t("pages.settings.administration")}
            </div>
            <p className="text-xs text-muted-foreground mb-4">
              {t("pages.settings.adminHint")}
            </p>
            <ul className="space-y-2">
              <AdminLink to="/users" label={t("pages.settings.adminUsers")} />
              <AdminLink to="/users" search="?tab=code" label={t("pages.settings.adminRegCode")} />
              <AdminLink to="/admin/feature-access" label={t("pages.settings.adminFeatureLock")} />
              <AdminLink to="/admin/bug-reports" label={t("pages.settings.adminBugReports")} />
              <AdminLink to="/admin/homepage" label={t("pages.settings.adminWidgets")} />
              <AdminLink to="/sources" label={t("pages.settings.adminSources")} />
              <AdminLink to="/sync-logs" label={t("pages.settings.adminSyncLogs")} />
            </ul>
          </section>
        )}
      </div>
    </AppShell>
  );
}

function Row({ label, value, last, mono }) {
  return (
    <div
      className={`flex items-start justify-between gap-4 py-2 ${last ? "" : "border-b border-dashed border-border/70"}`}
    >
      <dt className="text-muted-foreground text-[11px] uppercase tracking-wider shrink-0">{label}</dt>
      <dd className={`text-foreground text-right min-w-0 break-words ${mono ? "font-mono text-xs" : ""}`}>
        {value || "—"}
      </dd>
    </div>
  );
}

function AdminLink({ to, label, search = "" }) {
  return (
    <li>
      <Link
        to={to + search}
        className="flex items-center justify-between gap-2 rounded-xl border border-border/70 bg-white/80 px-3 py-2.5 text-sm text-foreground hover:border-[hsl(var(--primary)/0.4)] transition-colors"
      >
        <span>{label}</span>
        <ChevronRight className="h-4 w-4 text-muted-foreground" />
      </Link>
    </li>
  );
}
