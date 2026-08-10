import React, { useEffect, useState } from "react";
import { Plus, Play, Trash2, FileSpreadsheet, FileText, X, Table as TableIcon, BarChart3 } from "lucide-react";
import {
  ResponsiveContainer,
  LineChart,
  Line,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ReferenceLine,
} from "recharts";
import api, { formatApiError } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import SecuredExportButton from "@/components/SecuredExportButton";
import { useAuth } from "@/contexts/AuthContext";
import { fmtNumber } from "@/lib/format";
import { getRechartsTooltipContentStyle, mergeRechartsTooltipProps } from "@/lib/rechartsTooltipShared";

const INITIAL = {
  name: "",
  expression: "profit.amount - loss.amount",
  group_by: "date,institution,category",
  datasets: "profit,loss",
  description: "",
};

export default function FormulasPage() {
  const [formulas, setFormulas] = useState([]);
  const [result, setResult] = useState(null);
  const [activeId, setActiveId] = useState(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState(INITIAL);
  const [err, setErr] = useState("");
  const [running, setRunning] = useState(false);
  const [view, setView] = useState("table"); // "table" | "chart"
  const { isAdmin } = useAuth();

  const load = async () => {
    const { data } = await api.get("/formulas");
    setFormulas(data);
  };
  useEffect(() => {
    load();
  }, []);

  const run = async (id) => {
    setRunning(true);
    setActiveId(id);
    try {
      const { data } = await api.get(`/formulas/${id}/run`);
      setResult(data);
    } finally {
      setRunning(false);
    }
  };

  const save = async (e) => {
    e.preventDefault();
    setErr("");
    const payload = {
      name: form.name,
      expression: form.expression,
      group_by: form.group_by.split(",").map((s) => s.trim()).filter(Boolean),
      datasets: form.datasets.split(",").map((s) => s.trim()).filter(Boolean),
      description: form.description,
    };
    try {
      await api.post("/formulas", payload);
      setCreating(false);
      setForm(INITIAL);
      await load();
    } catch (e) {
      setErr(formatApiError(e.response?.data?.detail) || e.message);
    }
  };

  const del = async (id) => {
    if (!window.confirm("Opravdu smazat vzorec?")) return;
    await api.delete(`/formulas/${id}`);
    if (activeId === id) {
      setActiveId(null);
      setResult(null);
    }
    load();
  };

  const resultColumns = result?.rows?.length > 0 ? Object.keys(result.rows[0]) : [];

  return (
    <AppShell
      title="Editor vzorců"
      subtitle="Výpočty napříč datovými sadami · odvozené metriky"
      actions={
        isAdmin && (
          <button data-testid="formulas-new-btn" onClick={() => setCreating(true)} className="btn-mint flex items-center gap-2 px-4 h-9 text-sm">
            <Plus className="h-4 w-4" /> Nový vzorec
          </button>
        )
      }
    >
      <div className="grid grid-cols-1 xl:grid-cols-[360px_1fr] gap-6">
        <aside className="bg-white border border-border rounded-sm overflow-hidden">
          <div className="px-4 py-3 border-b border-border kpi-label">Vzorce</div>
          {formulas.length === 0 ? (
            <div className="p-8 text-sm text-slate-500 font-mono text-center">Zatím nejsou definovány žádné vzorce.</div>
          ) : (
            <ul data-testid="formulas-list">
              {formulas.map((f) => (
                <li
                  key={f.id}
                  className={`px-4 py-3 border-b border-border cursor-pointer hover:bg-slate-50 ${activeId === f.id ? "bg-slate-50" : ""}`}
                  onClick={() => run(f.id)}
                  data-testid={`formula-row-${f.name}`}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="font-medium text-slate-900">{f.name}</div>
                      <div className="text-xs font-mono text-slate-500 mt-0.5 truncate">{f.expression}</div>
                      <div className="text-[10px] uppercase tracking-wider text-slate-400 mt-1.5">
                        seskupeno dle {f.group_by.join(" · ")}
                      </div>
                    </div>
                    {isAdmin && (
                      <button onClick={(e) => { e.stopPropagation(); del(f.id); }} className="text-slate-400 hover:text-red-600">
                        <Trash2 className="h-4 w-4" />
                      </button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </aside>

        <section>
          {!result ? (
            <div className="border border-dashed border-border bg-slate-50 rounded-sm p-16 text-center text-sm text-slate-500 font-mono">
              Vyberte vzorec vlevo pro jeho výpočet.
            </div>
          ) : (
            <div className="bg-white border border-border rounded-sm" data-testid="formula-result">
              <div className="p-6 border-b border-border flex items-start justify-between gap-4 flex-wrap">
                <div>
                  <div className="kpi-label">{result.formula.name}</div>
                  <div className="font-mono text-sm mt-2 text-slate-700">{result.formula.expression}</div>
                  <div className="text-[11px] uppercase tracking-wider text-slate-500 mt-2">
                    seskupeno dle {result.group_by.join(" · ")}
                  </div>
                </div>
                <div className="text-right">
                  <div className="kpi-label">Celkem</div>
                  <div className="font-serif text-4xl mt-1">{fmtNumber(result.total)}</div>
                </div>
                <div className="flex items-center gap-2">
                  <button onClick={() => run(result.formula.id)} className="flex items-center gap-1.5 px-3 h-9 text-xs border border-border rounded-sm hover:bg-slate-100">
                    <Play className={`h-4 w-4 ${running ? "animate-pulse" : ""}`} /> Spustit znovu
                  </button>
                  <SecuredExportButton
                    testid="formula-export-xlsx"
                    relativePath={`export/formula/${result.formula.id}.xlsx`}
                    filename="formula.xlsx"
                    className="flex items-center gap-1.5 px-3 h-9 text-xs border border-border rounded-sm hover:bg-slate-100"
                  >
                    <FileSpreadsheet className="h-4 w-4" /> Excel
                  </SecuredExportButton>
                  <SecuredExportButton
                    testid="formula-export-pdf"
                    relativePath={`export/formula/${result.formula.id}.pdf`}
                    filename="formula.pdf"
                    className="flex items-center gap-1.5 px-3 h-9 text-xs border border-border rounded-sm hover:bg-slate-100"
                  >
                    <FileText className="h-4 w-4" /> PDF
                  </SecuredExportButton>
                </div>
              </div>

              {result.warnings?.length > 0 && (
                <div className="px-6 py-3 border-b border-border bg-amber-50 text-amber-800 text-xs font-mono">
                  {result.warnings.join(" · ")}
                </div>
              )}

              <div className="px-6 py-3 border-b border-border flex items-center gap-2">
                <span className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-semibold mr-2">
                  Zobrazení
                </span>
                <button
                  data-testid="formula-view-table"
                  onClick={() => setView("table")}
                  className={`flex items-center gap-1.5 px-3 h-8 text-xs rounded-full border transition-colors ${
                    view === "table"
                      ? "chip-mint border-transparent font-medium"
                      : "border-border/60 hover:bg-[hsl(var(--primary-soft))]"
                  }`}
                >
                  <TableIcon className="h-3.5 w-3.5" /> Tabulka
                </button>
                <button
                  data-testid="formula-view-chart"
                  onClick={() => setView("chart")}
                  className={`flex items-center gap-1.5 px-3 h-8 text-xs rounded-full border transition-colors ${
                    view === "chart"
                      ? "chip-mint border-transparent font-medium"
                      : "border-border/60 hover:bg-[hsl(var(--primary-soft))]"
                  }`}
                >
                  <BarChart3 className="h-3.5 w-3.5" /> Graf
                </button>
              </div>

              {view === "chart" ? (
                <FormulaChart result={result} />
              ) : (
                <div className="overflow-auto max-h-[560px]">
                <table className="data-table">
                  <thead>
                    <tr>
                      {resultColumns.map((c) => (
                        <th key={c} className={c === "result" || c.includes(".") ? "num" : ""}>{c}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {result.rows.map((r, i) => (
                      <tr key={i}>
                        {resultColumns.map((c) => {
                          const v = r[c];
                          const isNum = typeof v === "number";
                          return (
                            <td
                              key={c}
                              className={isNum ? "num" : ""}
                              style={c === "result" ? { color: v < 0 ? "#B91C1C" : "#15803D", fontWeight: 500 } : {}}
                            >
                              {isNum ? fmtNumber(v) : String(v ?? "—")}
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              )}
            </div>
          )}
        </section>
      </div>

      {creating && (
        <div className="fixed inset-0 bg-slate-900/40 backdrop-blur-sm z-40 grid place-items-center p-4">
          <form data-testid="formula-create-modal" onSubmit={save} className="bg-white border border-border rounded-sm w-full max-w-lg p-6 shadow-xl">
            <div className="flex items-start justify-between">
              <div>
                <div className="kpi-label">Vytvořit vzorec</div>
                <h3 className="font-serif text-2xl mt-1">Odvodit novou metriku</h3>
              </div>
              <button type="button" onClick={() => setCreating(false)} className="text-slate-500 hover:text-slate-900">
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="mt-5 space-y-4">
              <Field label="Název">
                <input data-testid="f-name" required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="input" />
              </Field>
              <Field label="Výraz">
                <input data-testid="f-expression" required value={form.expression} onChange={(e) => setForm({ ...form, expression: e.target.value })} className="input font-mono" />
                <div className="text-[11px] text-slate-500 font-mono mt-1">
                  podpora: datovaSada.pole, +, −, ×, ÷, závorky
                </div>
              </Field>
              <Field label="Datové sady (odděleno čárkou)">
                <input data-testid="f-datasets" value={form.datasets} onChange={(e) => setForm({ ...form, datasets: e.target.value })} className="input font-mono" />
              </Field>
              <Field label="Seskupit dle (odděleno čárkou)">
                <input data-testid="f-group-by" value={form.group_by} onChange={(e) => setForm({ ...form, group_by: e.target.value })} className="input font-mono" />
              </Field>
              <Field label="Popis">
                <textarea rows={2} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} className="input" />
              </Field>
              {err && (
                <div className="border border-destructive/40 bg-destructive/5 text-destructive text-sm p-3 rounded-sm">{err}</div>
              )}
            </div>
            <div className="flex justify-end gap-2 mt-6">
              <button type="button" onClick={() => setCreating(false)} className="px-3 h-9 text-sm border border-border rounded-sm">Zrušit</button>
              <button type="submit" data-testid="f-save-btn" className="btn-mint px-4 h-9 text-sm">Vytvořit</button>
            </div>
          </form>
        </div>
      )}

      <style>{`.input{width:100%;height:36px;border:1px solid hsl(var(--border));border-radius:2px;padding:0 10px;font-size:13px;background:white}
        textarea.input{height:auto;padding:8px 10px}
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

function FormulaChart({ result }) {
  const rows = result.rows || [];
  const groupBy = result.group_by || [];
  const hasDate = groupBy.includes("date");

  if (rows.length === 0) {
    return (
      <div className="p-12 text-center text-slate-500 font-mono text-sm">
        Žádná data k vykreslení.
      </div>
    );
  }

  // Pokud máme datum + další dimenze, agregujeme podle data (součet result).
  let data;
  if (hasDate) {
    const byDate = new Map();
    for (const r of rows) {
      const k = r.date || "";
      byDate.set(k, (byDate.get(k) || 0) + (Number(r.result) || 0));
    }
    data = Array.from(byDate.entries())
      .map(([date, result]) => ({ date, result: Number(result.toFixed(2)) }))
      .sort((a, b) => String(a.date).localeCompare(String(b.date)));
  } else {
    // Bar chart: vezmi prvních 25 řádků podle abs(result).
    data = [...rows]
      .sort((a, b) => Math.abs(Number(b.result) || 0) - Math.abs(Number(a.result) || 0))
      .slice(0, 25)
      .map((r) => ({
        label: groupBy.map((g) => r[g]).join(" · ") || "—",
        result: Number(r.result) || 0,
      }));
  }

  const tickStyle = { fontSize: 10, fill: "#64748B", fontFamily: "JetBrains Mono" };
  const tooltipStyle = getRechartsTooltipContentStyle({
    border: "1px solid #E2E8F0",
    borderRadius: 2,
    fontSize: 12,
  });

  return (
    <div className="p-6" data-testid="formula-chart">
      <div className="h-[420px]">
        <ResponsiveContainer width="100%" height="100%">
          {hasDate ? (
            <LineChart data={data}>
              <defs>
                <linearGradient id="fg" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="hsl(222 47% 11%)" stopOpacity={0.18} />
                  <stop offset="100%" stopColor="hsl(222 47% 11%)" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid vertical={false} stroke="#E2E8F0" strokeDasharray="2 4" />
              <XAxis dataKey="date" tick={tickStyle} tickLine={false} axisLine={{ stroke: "#E2E8F0" }} />
              <YAxis
                tick={tickStyle}
                tickLine={false}
                axisLine={false}
                tickFormatter={(v) =>
                  Math.abs(v) >= 1000 ? `${(v / 1000).toFixed(0)}k` : v
                }
              />
              <Tooltip
                cursor={{ stroke: "#94A3B8", strokeDasharray: "2 4" }}
                {...mergeRechartsTooltipProps({
                  contentStyle: tooltipStyle,
                  formatter: (v) => fmtNumber(v),
                })}
              />
              <ReferenceLine y={0} stroke="#CBD5E1" />
              <Line
                type="monotone"
                dataKey="result"
                stroke="hsl(222 47% 11%)"
                strokeWidth={2}
                dot={{ r: 2.5, fill: "hsl(222 47% 11%)" }}
              />
            </LineChart>
          ) : (
            <BarChart data={data} layout="vertical" margin={{ left: 60 }}>
              <CartesianGrid horizontal={false} stroke="#E2E8F0" strokeDasharray="2 4" />
              <XAxis
                type="number"
                tick={tickStyle}
                tickLine={false}
                axisLine={false}
                tickFormatter={(v) =>
                  Math.abs(v) >= 1000 ? `${(v / 1000).toFixed(0)}k` : v
                }
              />
              <YAxis
                type="category"
                dataKey="label"
                tick={tickStyle}
                tickLine={false}
                axisLine={{ stroke: "#E2E8F0" }}
                width={180}
              />
              <Tooltip
                cursor={{ fill: "rgba(15,23,42,0.04)" }}
                {...mergeRechartsTooltipProps({
                  contentStyle: tooltipStyle,
                  formatter: (v) => fmtNumber(v),
                })}
              />
              <ReferenceLine x={0} stroke="#CBD5E1" />
              <Bar dataKey="result" fill="hsl(222 47% 11%)" radius={[0, 2, 2, 0]} />
            </BarChart>
          )}
        </ResponsiveContainer>
      </div>
    </div>
  );
}
