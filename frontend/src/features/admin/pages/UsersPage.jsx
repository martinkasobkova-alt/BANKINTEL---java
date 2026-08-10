import React, { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import api, { formatApiError } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import { Plus, Trash2, X } from "lucide-react";
import { fmtDateTime } from "@/lib/format";
import { useAuth } from "@/contexts/AuthContext";
import { toast } from "sonner";
import { PASSWORD_POLICY_HINT, validatePasswordClient } from "@/lib/passwordPolicy";

const TAB_USERS = "users";
const TAB_CODE = "code";

function userAccessBadge(u) {
  if (u.role === "admin") {
    return { label: "Admin", className: "chip-mint" };
  }
  if (u.access_tier === "subscriber" && u.has_premium_access) {
    return { label: "Premium", className: "bg-emerald-100 text-emerald-800 border border-emerald-200/80" };
  }
  return { label: "Free", className: "bg-slate-100 text-slate-600 border border-slate-200/80" };
}

function premiumSourceList(u) {
  if (u.premium_access_source === "registration_code") return "kód (registrace)";
  if (u.premium_access_source === "admin") return "admin";
  if (u.role === "admin") return "—";
  return "—";
}

function isPremiumUser(u) {
  if (u.role === "admin") return true;
  return u.access_tier === "subscriber" && u.has_premium_access;
}

export default function UsersPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user: me } = useAuth();
  const [tab, setTab] = useState(TAB_USERS);
  const [users, setUsers] = useState([]);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({
    email: "",
    password: "",
    name: "",
    company: "",
    phone: "",
    role: "admin",
  });
  const [err, setErr] = useState("");

  const [codeStatus, setCodeStatus] = useState(null);
  const [revealedCode, setRevealedCode] = useState(null);
  const [newCode, setNewCode] = useState("");
  const [codeErr, setCodeErr] = useState("");
  const [codeLoading, setCodeLoading] = useState(false);

  const load = async () => {
    const { data } = await api.get("/users");
    setUsers(data);
  };

  const loadCodeStatus = async () => {
    setCodeErr("");
    try {
      const { data } = await api.get("/admin/subscriber-registration-code/status");
      setCodeStatus(data);
    } catch (e) {
      setCodeErr(formatApiError(e.response?.data?.detail) || e.message);
    }
  };

  useEffect(() => {
    load();
  }, []);

  useEffect(() => {
    if (searchParams.get("tab") === "code") {
      setTab(TAB_CODE);
    }
  }, [searchParams]);

  useEffect(() => {
    if (tab === TAB_CODE) loadCodeStatus();
  }, [tab]);

  const save = async (e) => {
    e.preventDefault();
    setErr("");
    const pwCheck = validatePasswordClient(form.password);
    if (!pwCheck.ok) {
      setErr(pwCheck.message);
      return;
    }
    try {
      const body = { ...form };
      if (!body.company?.trim()) delete body.company;
      else body.company = body.company.trim();
      if (!body.phone?.trim()) delete body.phone;
      else body.phone = body.phone.trim();
      await api.post("/users", body);
      setCreating(false);
      setForm({ email: "", password: "", name: "", company: "", phone: "", role: "admin" });
      load();
    } catch (e) {
      setErr(formatApiError(e.response?.data?.detail) || e.message);
    }
  };

  const saveCode = async (e) => {
    e.preventDefault();
    setCodeErr("");
    if (!newCode || newCode.length < 6) {
      setCodeErr("Kód musí mít alespoň 6 znaků.");
      return;
    }
    setCodeLoading(true);
    setRevealedCode(null);
    try {
      const { data } = await api.put("/admin/subscriber-registration-code", { registration_code: newCode });
      setNewCode("");
      if (data?.registration_code) {
        setRevealedCode(String(data.registration_code));
        toast.success("Kód byl uložen. Zkopírujte ho hned teď – znovu se nezobrazí.");
      } else {
        toast.success("Registrační kód byl změněn.");
      }
      await loadCodeStatus();
    } catch (e) {
      setCodeErr(formatApiError(e.response?.data?.detail) || e.message);
    } finally {
      setCodeLoading(false);
    }
  };

  const copyRevealedCode = async () => {
    if (!revealedCode) return;
    try {
      await navigator.clipboard.writeText(revealedCode);
      toast.success("Kód byl zkopírován do schránky.");
    } catch {
      toast.error("Kopírování se nezdařilo — zkopírujte ručně.");
    }
  };

  const patchUserPremium = async (u, grant) => {
    if (u.role === "admin" || me?.id === u.id) {
      toast.error("Účet administrátora tímto způsobem měnit nelze.");
      return;
    }
    const ok = window.confirm(
      grant
        ? "Nastavit tomuto uživateli tarif Premium?"
        : "Přepnout uživatele na Free a odebrat premium přístup?"
    );
    if (!ok) return;
    const body = grant
      ? { access_tier: "subscriber", has_premium_access: true }
      : { access_tier: "free", has_premium_access: false };
    try {
      await api.patch(`/users/${u.id}`, body);
      toast.success(grant ? "Uživatel má Premium." : "Uživatel je na Free.");
      await load();
    } catch (e) {
      toast.error(formatApiError(e.response?.data?.detail) || e.message);
    }
  };

  const del = async (id) => {
    if (!window.confirm("Opravdu smazat uživatele?")) return;
    await api.delete(`/users/${id}`);
    load();
  };

  return (
    <AppShell
      title={t("pages.admin.usersTitle")}
      subtitle={t("pages.admin.usersSubtitle")}
      actions={
        tab === TAB_USERS && (
          <button
            data-testid="user-new-btn"
            onClick={() => setCreating(true)}
            className="btn-mint flex items-center gap-2 px-4 h-9 text-sm"
          >
            <Plus className="h-4 w-4" /> Nový uživatel
          </button>
        )
      }
    >
      <div className="flex gap-1 mb-4 border-b border-border/60 max-w-5xl">
        <button
          type="button"
          onClick={() => setTab(TAB_USERS)}
          className={`px-3 py-2 text-sm rounded-t-md border-b-2 -mb-px ${
            tab === TAB_USERS
              ? "border-[hsl(var(--mint-600))] text-slate-900 font-medium"
              : "border-transparent text-slate-500 hover:text-slate-800"
          }`}
        >
          Uživatelé
        </button>
        <button
          type="button"
          onClick={() => setTab(TAB_CODE)}
          className={`px-3 py-2 text-sm rounded-t-md border-b-2 -mb-px ${
            tab === TAB_CODE
              ? "border-[hsl(var(--mint-600))] text-slate-900 font-medium"
              : "border-transparent text-slate-500 hover:text-slate-800"
          }`}
        >
          Registrační kód pro předplatitele
        </button>
      </div>

      {tab === TAB_USERS && (
        <div className="soft-card rounded-2xl overflow-hidden border-border/80 max-w-6xl">
          <div className="overflow-x-auto [scrollbar-gutter:stable]">
            <table
              className="data-table min-w-[1100px] [&_thead_th]:px-3 [&_thead_th]:py-2.5 [&_tbody_td]:px-3 [&_tbody_td]:py-2 [&_tbody_tr]:cursor-pointer [&_tbody_tr]:hover:bg-slate-50/60 [&_tbody_tr]:transition-colors [&_tbody_tr]:duration-150 [&_thead_th]:!bg-[hsl(205_76%_96%)] [&_thead_th]:shadow-[inset_0_-1px_0_hsl(var(--border)/0.5)]"
              data-testid="users-table"
            >
              <thead>
                <tr>
                  <th className="rounded-tl-2xl">Firma</th>
                  <th>Jméno</th>
                  <th>E-mail</th>
                  <th>Telefon</th>
                  <th>Role</th>
                  <th>Přístup</th>
                  <th>Zdroj premium</th>
                  <th>Vytvořeno</th>
                  <th>Změna přístupu</th>
                  <th className="rounded-tr-2xl w-[1%]" aria-label="Akce" />
                </tr>
              </thead>
              <tbody>
                {users.map((u) => {
                  const badge = userAccessBadge(u);
                  const canToggle = u.role !== "admin" && me?.id !== u.id;
                  const isPrem = isPremiumUser(u);
                  return (
                  <tr
                    key={u.id}
                    onClick={() => navigate(`/users/${u.id}`)}
                    data-testid={`user-row-${u.id}`}
                  >
                    <td className="max-w-[140px] truncate" title={u.company || ""}>
                      {u.company || "—"}
                    </td>
                    <td className="font-medium">{u.name}</td>
                    <td className="mono text-xs">{u.email}</td>
                    <td className="mono text-xs">{u.phone || "—"}</td>
                    <td>
                      <span
                        className={`text-[10px] uppercase tracking-[0.12em] px-2.5 py-0.5 rounded-full font-medium ${
                          u.role === "admin"
                            ? "chip-mint"
                            : u.role === "editor"
                              ? "bg-violet-100 text-violet-900"
                              : "chip-cream"
                        }`}
                      >
                        {u.role === "admin" ? "Admin" : u.role === "editor" ? "Editor" : u.role}
                      </span>
                    </td>
                    <td>
                      <span
                        className={`text-[10px] uppercase tracking-[0.1em] px-2.5 py-0.5 rounded-full font-semibold ${badge.className}`}
                      >
                        {badge.label}
                      </span>
                    </td>
                    <td className="text-xs text-slate-600" title={u.premium_access_source || ""}>
                      {premiumSourceList(u)}
                    </td>
                    <td className="mono text-xs">{fmtDateTime(u.created_at)}</td>
                    <td className="text-left" onClick={(e) => e.stopPropagation()}>
                      {canToggle ? (
                        <div className="flex flex-wrap gap-1">
                          {!isPrem && (
                            <button
                              type="button"
                              className="text-[10px] px-2 py-0.5 rounded-md bg-emerald-100 text-emerald-900 border border-emerald-200/80 font-medium"
                              onClick={() => patchUserPremium(u, true)}
                            >
                              Nastavit Premium
                            </button>
                          )}
                          {isPrem && u.role !== "admin" && (
                            <button
                              type="button"
                              className="text-[10px] px-2 py-0.5 rounded-md bg-slate-100 text-slate-800 border border-slate-200/80 font-medium"
                              onClick={() => patchUserPremium(u, false)}
                            >
                              Přepnout na Free
                            </button>
                          )}
                        </div>
                      ) : (
                        <span className="text-[10px] text-slate-400">—</span>
                      )}
                    </td>
                    <td className="text-right" onClick={(e) => e.stopPropagation()}>
                      {me?.id !== u.id && (
                        <button
                          onClick={() => del(u.id)}
                          className="p-2 rounded-lg text-slate-500 hover:text-red-600 hover:bg-slate-100/80 transition-colors"
                          data-testid={`user-delete-${u.email}`}
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      )}
                    </td>
                  </tr>
                );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === TAB_CODE && (
        <div className="soft-card rounded-2xl border-border/80 max-w-lg p-6 space-y-4">
          <h3 className="font-serif text-xl">Registrační kód pro předplatitele</h3>
          <p className="text-sm text-slate-600">
            <strong className="font-medium text-slate-800">Aktuální kód nelze znovu zobrazit.</strong> Pokud
            jste ho ztratili, nastavte nový. Po každém uložení se zobrazí jen ten nový kód, jednorázově.
          </p>
          {revealedCode && (
            <div
              className="rounded-xl border border-amber-200 bg-amber-50/80 p-4 space-y-2"
              data-testid="subscriber-code-reveal"
            >
              <p className="text-sm text-amber-950 font-medium">
                Z bezpečnostních důvodů se kód zobrazí pouze nyní. Uložte si ho.
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <code className="text-sm font-mono bg-white px-2 py-1 rounded border border-amber-200/80 text-technical-wrap">
                  {revealedCode}
                </code>
                <button type="button" className="btn-mint text-xs py-1.5 px-3" onClick={copyRevealedCode}>
                  Kopírovat kód
                </button>
              </div>
            </div>
          )}
          {codeStatus && (
            <ul className="text-sm space-y-1 text-slate-800">
              <li>
                <span className="text-slate-500">Kód v databázi:</span>{" "}
                {codeStatus.is_set ? "ano" : "ne"}
              </li>
              {codeStatus.updated_at && (
                <li>
                  <span className="text-slate-500">Poslední změna:</span> {fmtDateTime(codeStatus.updated_at)}
                </li>
              )}
              {codeStatus.updated_by && (
                <li>
                  <span className="text-slate-500">Změnil:</span> {codeStatus.updated_by}
                </li>
              )}
            </ul>
          )}
          <form onSubmit={saveCode} className="space-y-3">
            <div>
              <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">
                Nový registrační kód
              </label>
              <input
                type="password"
                autoComplete="new-password"
                className="input mt-1"
                value={newCode}
                onChange={(e) => setNewCode(e.target.value)}
                minLength={6}
                placeholder="Min. 6 znaků"
                data-testid="subscriber-code-input"
              />
            </div>
            {codeErr && <div className="text-destructive text-sm">{codeErr}</div>}
            <button
              type="submit"
              disabled={codeLoading}
              className="btn-mint px-4 h-9 text-sm disabled:opacity-50"
              data-testid="subscriber-code-save"
            >
              {codeLoading ? "Ukládám…" : "Uložit nový kód"}
            </button>
          </form>
        </div>
      )}

      {creating && (
        <div className="fixed inset-0 bg-slate-900/40 backdrop-blur-sm z-40 grid place-items-center p-4">
          <form onSubmit={save} data-testid="user-create-modal" className="soft-card rounded-2xl border-border/80 w-full max-w-md p-6 bg-gradient-to-b from-white to-slate-50/30">
            <div className="flex items-start justify-between">
              <h3 className="font-serif text-2xl">Vytvořit uživatele</h3>
              <button type="button" onClick={() => setCreating(false)}>
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="mt-5 space-y-4">
              <Field label="Jméno">
                <input
                  data-testid="u-name"
                  required
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  className="input"
                />
              </Field>
              <Field label="E-mail">
                <input
                  data-testid="u-email"
                  required
                  value={form.email}
                  onChange={(e) => setForm({ ...form, email: e.target.value })}
                  className="input font-mono"
                />
              </Field>
              <Field label="Společnost (volitelné)">
                <input
                  value={form.company}
                  onChange={(e) => setForm({ ...form, company: e.target.value })}
                  className="input"
                />
              </Field>
              <Field label="Telefon (volitelné)">
                <input
                  value={form.phone}
                  onChange={(e) => setForm({ ...form, phone: e.target.value })}
                  className="input font-mono"
                />
              </Field>
              <Field label="Heslo">
                <input
                  data-testid="u-password"
                  type="password"
                  required
                  minLength={8}
                  value={form.password}
                  onChange={(e) => setForm({ ...form, password: e.target.value })}
                  className="input font-mono"
                />
                <p className="text-[11px] text-muted-foreground mt-1">{PASSWORD_POLICY_HINT}</p>
              </Field>
              <Field label="Role">
                <select
                  data-testid="u-role"
                  value={form.role}
                  onChange={(e) => setForm({ ...form, role: e.target.value })}
                  className="input"
                >
                  <option value="admin">Administrátor</option>
                  <option value="editor">Editor</option>
                  <option value="viewer">Prohlížející</option>
                </select>
              </Field>
              {err && <div className="border border-destructive/40 bg-destructive/5 text-destructive text-sm p-3 rounded-xl">{err}</div>}
            </div>
            <div className="flex justify-end gap-2 mt-6">
              <button
                type="button"
                onClick={() => setCreating(false)}
                className="px-3 h-9 text-sm border border-border/80 rounded-lg bg-white hover:bg-slate-50"
              >
                Zrušit
              </button>
              <button type="submit" data-testid="u-save" className="btn-mint px-4 h-9 text-sm">
                Vytvořit
              </button>
            </div>
          </form>
        </div>
      )}

      <style>{`.input{width:100%;height:36px;border:1px solid hsl(var(--border));border-radius:2px;padding:0 10px;font-size:13px;background:white}
        .input:focus{outline:none;box-shadow:0 0 0 1px hsl(var(--ring))}`}</style>
    </AppShell>
  );
}

function Field({ label, children }) {
  return (
    <div>
      <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">{label}</label>
      <div className="mt-1">{children}</div>
    </div>
  );
}
