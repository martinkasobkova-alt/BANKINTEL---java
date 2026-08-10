import React, { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import api, { formatApiError } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import { ArrowLeft } from "lucide-react";
import { LoadingInline } from "@/components/ui/loading";
import { fmtDateTime } from "@/lib/format";
import { useAuth } from "@/contexts/AuthContext";
import { toast } from "sonner";

function sourceLabel(src) {
  if (!src) return "—";
  if (src === "registration_code") return "registrační kód";
  if (src === "admin") return "administrátor";
  return String(src);
}

function hasActivePremiumRow(u) {
  if (u.role === "admin") return true;
  return u.access_tier === "subscriber" && u.has_premium_access;
}

export default function UserDetailPage() {
  const { t } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();
  const { user: me } = useAuth();
  const [u, setU] = useState(null);
  const [err, setErr] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setErr("");
    const { data } = await api.get(`/users/${id}`);
    setU(data);
  }, [id]);

  useEffect(() => {
    (async () => {
      try {
        await load();
      } catch (e) {
        setErr(formatApiError(e.response?.data?.detail) || e.message);
        setU(null);
      }
    })();
  }, [load]);

  const applyPremium = async (grant) => {
    if (!u || u.role === "admin") return;
    setSaving(true);
    try {
      const body = grant
        ? { access_tier: "subscriber", has_premium_access: true }
        : { access_tier: "free", has_premium_access: false };
      const { data } = await api.patch(`/users/${id}`, body);
      setU(data);
      toast.success(grant ? "Premium přístup byl udělen." : "Premium přístup byl odebrán.");
    } catch (e) {
      toast.error(formatApiError(e.response?.data?.detail) || e.message);
    } finally {
      setSaving(false);
    }
  };

  if (err && !u) {
    return (
      <AppShell title={t("pages.admin.userDetailTitle")} subtitle={t("pages.admin.userDetailSubtitle")}>
        <p className="text-destructive text-sm mb-4">{err}</p>
        <button type="button" onClick={() => navigate("/users")} className="btn-mint px-4 h-9 text-sm">
          Zpět na seznam
        </button>
      </AppShell>
    );
  }

  if (!u) {
    return (
      <AppShell title={t("pages.admin.userDetailTitle")} subtitle={t("pages.admin.userDetailSubtitle")}>
        <LoadingInline label="Načítám uživatele…" size="sm" muted className="text-sm" />
      </AppShell>
    );
  }

  const isTargetAdmin = u.role === "admin";
  const premiumActive = hasActivePremiumRow(u);
  const canManagePremium = me?.role === "admin" && !isTargetAdmin;

  return (
    <AppShell
      title="Uživatel"
      subtitle="Detail a správa předplatitelského přístupu"
      actions={
        <button
          type="button"
          onClick={() => navigate("/users")}
          className="inline-flex items-center gap-2 px-3 h-9 text-sm border border-border/80 rounded-lg bg-white hover:bg-slate-50"
        >
          <ArrowLeft className="h-4 w-4" /> Zpět
        </button>
      }
    >
      <div className="soft-card rounded-2xl border-border/80 max-w-2xl p-6 space-y-6 text-sm">
        <section>
          <h3 className="text-xs uppercase tracking-wider text-slate-500 font-medium mb-3">Základní údaje</h3>
          <div className="space-y-3">
            <Field label="Firma" value={u.company || "—"} />
            <Field label="Jméno" value={u.name} />
            <Field label="E-mail" value={u.email} className="font-mono" />
            <Field label="Telefon" value={u.phone || "—"} />
            <Field
              label="Role"
              value={
                u.role === "admin"
                  ? "Administrátor"
                  : u.role === "editor"
                    ? "Editor"
                    : "Prohlížející"
              }
            />
            <Field label="Access tier" value={u.access_tier || "—"} />
            <Field label="Má flag premium" value={u.has_premium_access ? "Ano" : "Ne"} />
            <Field
              label="Premium uděleno (datum)"
              value={u.premium_access_granted_at ? fmtDateTime(u.premium_access_granted_at) : "—"}
            />
            <Field label="Zdroj premium" value={sourceLabel(u.premium_access_source)} />
            <Field label="Vytvořeno" value={u.created_at ? fmtDateTime(u.created_at) : "—"} />
          </div>
        </section>

        <section className="pt-2 border-t border-border/60">
          <h3 className="text-xs uppercase tracking-wider text-slate-500 font-medium mb-3">Premium přístup</h3>
          {isTargetAdmin ? (
            <div className="rounded-xl bg-slate-50 border border-border/70 px-4 py-3 text-slate-700 text-sm">
              Admin má premium přístup automaticky. Úprava přes tento panel není k dispozici.
            </div>
          ) : (
            <>
              <div className="space-y-2 mb-4">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-slate-500">Aktuální stav:</span>
                  {premiumActive ? (
                    <span className="chip-mint text-[10px] uppercase tracking-[0.1em] px-2.5 py-0.5 font-semibold">
                      Aktivní
                    </span>
                  ) : (
                    <span className="px-2.5 py-0.5 rounded-full text-xs font-medium bg-slate-100 text-slate-600 border border-slate-200/80">
                      Neaktivní
                    </span>
                  )}
                </div>
                <p>
                  <span className="text-slate-500">Access tier: </span>
                  <span className="font-mono text-xs">{u.access_tier || "—"}</span>
                </p>
                <p>
                  <span className="text-slate-500">Zdroj přístupu: </span>
                  {sourceLabel(u.premium_access_source)}
                </p>
                <p>
                  <span className="text-slate-500">Datum udělení: </span>
                  {u.premium_access_granted_at ? fmtDateTime(u.premium_access_granted_at) : "—"}
                </p>
              </div>

              {canManagePremium && (
                <div>
                  {premiumActive ? (
                    <button
                      type="button"
                      disabled={saving}
                      onClick={() => {
                        if (window.confirm("Opravdu odebrat premium přístup tomuto uživateli?")) {
                          void applyPremium(false);
                        }
                      }}
                      className="px-4 h-9 text-sm border border-amber-600/50 text-amber-800 bg-amber-50 hover:bg-amber-100/80 rounded-lg font-medium transition-colors disabled:opacity-50"
                    >
                      {saving ? "Ukládám…" : "Odebrat premium přístup"}
                    </button>
                  ) : (
                    <button
                      type="button"
                      disabled={saving}
                      onClick={() => void applyPremium(true)}
                      className="btn-mint px-4 h-9 text-sm font-medium disabled:opacity-50"
                    >
                      {saving ? "Ukládám…" : "Udělit premium přístup"}
                    </button>
                  )}
                </div>
              )}
            </>
          )}
        </section>
      </div>
    </AppShell>
  );
}

function Field({ label, value, className = "" }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">{label}</div>
      <div className={`mt-0.5 text-slate-900 ${className}`}>{value}</div>
    </div>
  );
}
