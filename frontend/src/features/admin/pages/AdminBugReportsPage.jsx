import React, { useCallback, useEffect, useState } from "react";
import AppShell from "@/components/layout/AppShell";
import api, { API_ROOT, formatApiErrorFromAxios } from "@/lib/api";
import { toast } from "sonner";

function short(s, n) {
  if (!s) return "—";
  const t = String(s).trim();
  if (t.length <= n) return t;
  return t.slice(0, n) + "…";
}

export default function AdminBugReportsPage() {
  const [rows, setRows] = useState([]);
  const [status, setStatus] = useState("all");
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState(null);
  const [openId, setOpenId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const q = status === "all" ? "" : `?status=${encodeURIComponent(status)}`;
      const { data } = await api.get(`/admin/bug-reports${q}`);
      setRows(Array.isArray(data) ? data : []);
    } catch (e) {
      setErr(formatApiErrorFromAxios(e) || "Chyba načtení");
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    load();
  }, [load]);

  const setResolved = async (id) => {
    try {
      await api.patch(`/admin/bug-reports/${id}`, { status: "resolved" });
      toast.success("Označeno jako vyřízené");
      load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  const setOpen = async (id) => {
    try {
      await api.patch(`/admin/bug-reports/${id}`, { status: "open" });
      toast.success("Znovu otevřeno");
      load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  const doDelete = async (id) => {
    if (!window.confirm("Opravdu smazat tento záznam?")) return;
    try {
      await api.delete(`/admin/bug-reports/${id}`);
      toast.success("Smazáno");
      load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  return (
    <AppShell
      title="Bug reporty"
      subtitle="Hlášení chyb z aplikace"
    >
      <div className="bankoapp-white-panels-scope">
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <span className="text-sm text-slate-700 font-medium">Filtr:</span>
        {["all", "open", "resolved"].map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => setStatus(s)}
            className={`px-3 py-1.5 rounded-xl text-sm font-medium border transition-colors ${
              status === s
                ? "border-[hsl(var(--primary))] bg-white text-slate-900 shadow-sm"
                : "border-border/70 bg-white/80 text-slate-800 hover:bg-white hover:border-border"
            }`}
          >
            {s === "all" ? "Vše" : s === "open" ? "Otevřené" : "Vyřízené"}
          </button>
        ))}
        <button
          type="button"
          onClick={load}
          className="ml-auto text-sm text-[hsl(var(--primary))] underline"
        >
          Obnovit
        </button>
      </div>

      {err ? <div className="text-sm text-red-700 mb-2">{err}</div> : null}
      {loading ? <div className="text-sm text-slate-500">Načítání…</div> : null}

      <div className="overflow-x-auto border border-border/60 rounded-xl bg-white/90">
        <table className="w-full text-sm text-left text-slate-800 min-w-[800px]">
          <thead>
            <tr className="border-b border-border/60 text-xs uppercase text-slate-700 bg-slate-100/90">
              <th className="p-2">Datum</th>
              <th className="p-2">Stav</th>
              <th className="p-2">Priorita</th>
              <th className="p-2">Název</th>
              <th className="p-2">Popis</th>
              <th className="p-2">Kontakt / uživatel</th>
              <th className="p-2">Stránka</th>
              <th className="p-2">Snímek</th>
              <th className="p-2 w-[220px]">Akce</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <React.Fragment key={r.id}>
                <tr
                  className="border-b border-border/40 hover:bg-slate-50/80 cursor-pointer"
                  onClick={() => setOpenId((x) => (x === r.id ? null : r.id))}
                >
                  <td className="p-2 whitespace-nowrap text-xs text-slate-600">
                    {r.created_at ? new Date(r.created_at).toLocaleString("cs-CZ") : "—"}
                  </td>
                  <td className="p-2">{r.status === "resolved" ? "Vyřízeno" : "Otevřeno"}</td>
                  <td className="p-2">{r.priority || "—"}</td>
                  <td className="p-2 font-medium">{short(r.title, 50)}</td>
                  <td className="p-2 text-slate-700">{short(r.description, 60)}</td>
                  <td className="p-2 text-xs text-normal-wrap">{r.user_email || r.contact_email || "—"}</td>
                  <td className="p-2 text-xs text-technical-wrap max-w-[280px]">{short(r.page_url, 40)}</td>
                  <td className="p-2">{r.has_screenshot ? "Ano" : "Ne"}</td>
                  <td className="p-2" onClick={(e) => e.stopPropagation()}>
                    <div className="flex flex-wrap gap-1">
                      {r.status !== "resolved" ? (
                        <button
                          type="button"
                          className="text-xs px-2 py-0.5 rounded border border-border/60 text-slate-800 bg-white hover:bg-slate-50"
                          onClick={() => setResolved(r.id)}
                        >
                          Vyřídit
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="text-xs px-2 py-0.5 rounded border border-border/60 text-slate-800 bg-white hover:bg-slate-50"
                          onClick={() => setOpen(r.id)}
                        >
                          Znovu otevřít
                        </button>
                      )}
                      {r.has_screenshot ? (
                        <a
                          href={`${API_ROOT}/admin/bug-reports/${r.id}/screenshot`}
                          target="_blank"
                          rel="noreferrer"
                          className="text-xs px-2 py-0.5 rounded border border-border/60 text-[hsl(var(--primary))]"
                        >
                          Screenshot
                        </a>
                      ) : null}
                      <button
                        type="button"
                        className="text-xs px-2 py-0.5 rounded border border-red-200 text-red-800"
                        onClick={() => doDelete(r.id)}
                      >
                        Smazat
                      </button>
                    </div>
                  </td>
                </tr>
                {openId === r.id ? (
                  <tr>
                    <td colSpan={9} className="p-3 bg-slate-50/90 text-xs text-slate-700 space-y-2 border-b border-border/40">
                      <p>
                        <span className="font-semibold">Popis: </span>
                        {r.description}
                      </p>
                      <p>
                        <span className="font-semibold">URL: </span> {r.page_url || "—"}
                      </p>
                      <p>
                        <span className="font-semibold">User agent: </span> {r.user_agent || "—"}
                      </p>
                      <p>
                        <span className="font-semibold">Viewport: </span> {r.viewport || "—"}
                      </p>
                      <p>
                        <span className="font-semibold">Route: </span> {r.route || "—"}
                      </p>
                      <p>
                        <span className="font-semibold">user_id: </span> {r.user_id || "—"}{" "}
                        <span className="font-semibold">role: </span> {r.user_role || "—"}
                      </p>
                      {r.has_screenshot ? (
                        <p>
                          <img
                            src={`${API_ROOT}/admin/bug-reports/${r.id}/screenshot`}
                            alt="Screenshot"
                            className="mt-1 max-w-full max-h-64 rounded border object-contain bg-white"
                            loading="lazy"
                          />
                        </p>
                      ) : null}
                    </td>
                  </tr>
                ) : null}
              </React.Fragment>
            ))}
          </tbody>
        </table>
        {!loading && rows.length === 0 ? (
          <div className="p-6 text-center text-slate-600 text-sm">Žádné záznamy</div>
        ) : null}
      </div>
      </div>
    </AppShell>
  );
}
