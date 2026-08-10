import React, { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Loader2, RefreshCw, Search } from "lucide-react";
import { toast } from "sonner";
import { CartesianGrid, Line, LineChart, Tooltip, XAxis, YAxis } from "recharts";

import AppShell from "@/components/layout/AppShell";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { SafeRechartsContainer } from "@/lib/SafeRechartsContainer";

function defaultFromDate() {
  const d = new Date();
  d.setFullYear(d.getFullYear() - 10);
  return d.toISOString().slice(0, 10);
}

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

/** Pořadí skupin odpovídá TE `group=` / backend `_TE_COUNTRY_SNAPSHOT_GROUPS`. */
const TE_CATEGORY_GROUP_ORDER = [
  "Overview",
  "Markets",
  "GDP",
  "Labour",
  "Prices",
  "Money",
  "Trade",
  "Government",
  "Business",
  "Consumer",
  "Housing",
  "Taxes",
  "Energy",
  "Health",
  "Climate",
];

function teCategoryGroupSortKey(label) {
  const raw = String(label || "").trim();
  const low = raw.toLowerCase();
  if (!low || low === "other") return 4000;
  const idx = TE_CATEGORY_GROUP_ORDER.findIndex((g) => g.toLowerCase() === low);
  if (idx !== -1) return idx;
  return 2000;
}

function indicatorRowKey(row, idx) {
  const sym = String(row?.historical_data_symbol || "").trim();
  if (sym) return `sym:${sym}`;
  return [
    "row",
    String(row?.category || ""),
    String(row?.category_group || ""),
    String(row?.title || ""),
    String(row?.frequency || ""),
    String(row?.unit || ""),
    idx,
  ].join("|");
}

function indicatorsAreSameSelection(a, b) {
  if (!a || !b) return false;
  const sa = String(a.historical_data_symbol || "").trim();
  const sb = String(b.historical_data_symbol || "").trim();
  if (sa && sb) return sa === sb;
  return (
    String(a.category || "") === String(b.category || "") &&
    String(a.category_group || "") === String(b.category_group || "") &&
    String(a.title || "") === String(b.title || "") &&
    String(a.frequency || "") === String(b.frequency || "") &&
    String(a.unit || "") === String(b.unit || "")
  );
}

function fmtNumber(n) {
  if (typeof n !== "number" || Number.isNaN(n)) return "—";
  return new Intl.NumberFormat("cs-CZ", { maximumFractionDigits: 4 }).format(n);
}

/** Viditelná zpětná vazba při voláních Trading Economics API. */
function TradingEconomicsLoader({ label }) {
  return (
    <div
      className="flex flex-col items-center justify-center gap-3 py-10 px-4 text-muted-foreground"
      role="status"
      aria-live="polite"
      aria-busy="true"
    >
      <div className="relative flex h-12 w-12 items-center justify-center">
        <span
          className="absolute inset-0 rounded-full border-2 border-sky-600/20 border-t-sky-600 animate-spin"
          aria-hidden
        />
        <Loader2 className="relative h-6 w-6 text-sky-700" aria-hidden />
      </div>
      <p className="text-sm font-medium text-slate-600 text-center max-w-sm">{label}</p>
      <div className="flex items-center justify-center gap-1.5 pt-1" aria-hidden>
        {[0, 120, 240].map((delayMs) => (
          <span
            key={delayMs}
            className="inline-block h-2 w-2 rounded-full bg-sky-600/70 animate-bounce"
            style={{ animationDelay: `${delayMs}ms` }}
          />
        ))}
      </div>
    </div>
  );
}

export default function TradingEconomicsBrowserPage() {
  const nav = useNavigate();

  const [countries, setCountries] = useState([]);
  const [countriesLoading, setCountriesLoading] = useState(true);
  const [selectedCountry, setSelectedCountry] = useState("");

  const [indicators, setIndicators] = useState([]);
  const [indicatorSearch, setIndicatorSearch] = useState("");
  const [indicatorsLoading, setIndicatorsLoading] = useState(false);
  const [selectedIndicator, setSelectedIndicator] = useState(null);

  const [fromDate, setFromDate] = useState(defaultFromDate());
  const [toDate, setToDate] = useState(todayIso());
  const [historical, setHistorical] = useState(null);
  const [historicalLoading, setHistoricalLoading] = useState(false);

  const loadCountries = async () => {
    setCountriesLoading(true);
    try {
      const { data } = await api.get("/trading-economics/countries");
      if (data?.status === "missing_api_key") {
        toast.error(data.message || "Trading Economics API key není nastaven.");
      }
      const rows = Array.isArray(data?.countries) ? data.countries : [];
      setCountries(rows);
      if (!selectedCountry && rows.length > 0) {
        setSelectedCountry(String(rows[0].country || ""));
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setCountriesLoading(false);
    }
  };

  const loadCountryIndicators = async (country) => {
    const c = String(country || "").trim();
    if (!c) return;
    setIndicatorsLoading(true);
    setSelectedIndicator(null);
    setHistorical(null);
    try {
      const { data } = await api.get(`/trading-economics/country/${encodeURIComponent(c)}/indicators`);
      if (data?.status && data.status !== "ok") {
        toast.error(data.message || "Nepodařilo se načíst indikátory.");
      }
      const rows = Array.isArray(data?.indicators) ? data.indicators : [];
      setIndicators(rows);
    } catch (e) {
      setIndicators([]);
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setIndicatorsLoading(false);
    }
  };

  const loadHistorical = async () => {
    if (!selectedCountry || !selectedIndicator?.category) return;
    setHistoricalLoading(true);
    setHistorical(null);
    try {
      const { data } = await api.get("/trading-economics/historical", {
        params: {
          country: selectedCountry,
          indicator: selectedIndicator.category,
          from: fromDate,
          to: toDate,
        },
      });
      if (data?.status && data.status !== "ok") {
        toast.error(data.message || "Nepodařilo se načíst historická data.");
      }
      setHistorical(data);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setHistoricalLoading(false);
    }
  };

  useEffect(() => {
    loadCountries();
  }, []);

  useEffect(() => {
    if (!selectedCountry) return;
    loadCountryIndicators(selectedCountry);
  }, [selectedCountry]);

  const filteredIndicators = useMemo(() => {
    const q = indicatorSearch.trim().toLowerCase();
    if (!q) return indicators;
    return indicators.filter((row) => {
      const text = [
        row?.category,
        row?.title,
        row?.category_group,
        row?.historical_data_symbol,
        row?.unit,
        row?.frequency,
      ]
        .map((v) => String(v || "").toLowerCase())
        .join(" ");
      return text.includes(q);
    });
  }, [indicators, indicatorSearch]);

  const indicatorsByGroup = useMemo(() => {
    const map = new Map();
    for (const row of filteredIndicators) {
      const g = String(row?.category_group || "").trim() || "Other";
      if (!map.has(g)) map.set(g, []);
      map.get(g).push(row);
    }
    return Array.from(map.entries()).sort((a, b) => {
      const ka = teCategoryGroupSortKey(a[0]);
      const kb = teCategoryGroupSortKey(b[0]);
      if (ka !== kb) return ka - kb;
      return String(a[0]).localeCompare(String(b[0]), "cs");
    });
  }, [filteredIndicators]);

  const chartRows = useMemo(() => {
    const rows = Array.isArray(historical?.data) ? historical.data : [];
    return rows
      .map((row) => ({
        date: String(row?.date || ""),
        value: Number(row?.value),
      }))
      .filter((row) => row.date && Number.isFinite(row.value));
  }, [historical]);

  return (
    <AppShell
      title="Trading Economics Browser"
      subtitle="Vyber zemi, potom indikátor, a načti historii konkrétní řady."
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => nav("/sources")}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
          >
            <ArrowLeft className="h-4 w-4" /> Zpět na zdroje
          </button>
          <button
            type="button"
            onClick={() => loadCountries()}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
          >
            <RefreshCw className={`h-4 w-4 ${countriesLoading ? "animate-spin" : ""}`} />
            Obnovit
          </button>
        </div>
      }
    >
      <div className="space-y-5">
        <section className="soft-card border-border/80 rounded-2xl p-4 space-y-3 relative min-h-[5.5rem]">
          <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Krok 1 — Země</div>
          <div className="relative max-w-lg">
            <select
              className="w-full h-10 px-3 border border-border rounded-xl text-sm bg-card disabled:opacity-50"
              value={selectedCountry}
              onChange={(e) => setSelectedCountry(e.target.value)}
              disabled={countriesLoading}
            >
              {countries.map((c) => (
                <option key={`${c.country}-${c.iso3 || ""}`} value={c.country}>
                  {c.country}
                </option>
              ))}
            </select>
            {countriesLoading ? (
              <div className="absolute inset-0 z-10 flex items-center justify-center rounded-xl bg-card/85 backdrop-blur-[2px] border border-border/60">
                <TradingEconomicsLoader label="Načítám seznam zemí z Trading Economics…" />
              </div>
            ) : null}
          </div>
        </section>

        <section className="soft-card border-border/80 rounded-2xl p-4 space-y-3">
          <div className="flex items-center justify-between gap-2">
            <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Krok 2 a 3 — Snapshot indikátorů pro zemi
            </div>
            <div className="text-xs text-muted-foreground flex items-center gap-1.5">
              {indicatorsLoading ? (
                <>
                  <Loader2 className="h-3.5 w-3.5 animate-spin text-sky-700 shrink-0" aria-hidden />
                  <span>Načítám…</span>
                </>
              ) : (
                `${filteredIndicators.length} záznamů`
              )}
            </div>
          </div>

          <div className="relative max-w-xl">
            <Search className="h-4 w-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              value={indicatorSearch}
              onChange={(e) => setIndicatorSearch(e.target.value)}
              className="w-full h-10 pl-9 pr-3 border border-border rounded-xl text-sm bg-card"
              placeholder="Hledat v category, title, skupině, symbolu, unit, frequency"
            />
          </div>

          <div className="relative max-h-[min(80vh,960px)] overflow-auto border border-border/70 rounded-xl min-h-[200px]">
            <table className={`data-table text-xs min-w-[1100px] ${indicatorsLoading ? "opacity-40 pointer-events-none" : ""}`}>
              <thead className="sticky top-0 z-10 bg-white">
                <tr>
                  <th>Category</th>
                  <th>Title</th>
                  <th>Category Group</th>
                  <th>Symbol</th>
                  <th>Latest Value</th>
                  <th>Unit</th>
                  <th>Frequency</th>
                  <th>Last Update</th>
                </tr>
              </thead>
              {indicatorsByGroup.map(([groupName, rows]) => (
                <tbody key={`grp-${groupName}`}>
                  <tr className="bg-muted/80">
                    <td colSpan={8} className="py-2 font-semibold text-foreground tracking-tight">
                      {groupName}
                      <span className="ml-2 font-normal text-muted-foreground">({rows.length})</span>
                    </td>
                  </tr>
                  {rows.map((row, idx) => {
                    const isSelected = selectedIndicator && indicatorsAreSameSelection(selectedIndicator, row);
                    return (
                      <tr
                        key={indicatorRowKey(row, idx)}
                        className={`${isSelected ? "bg-[hsl(var(--primary-soft)/0.65)]" : ""} cursor-pointer`}
                        onClick={() => setSelectedIndicator(row)}
                      >
                        <td className="font-medium">{row.category || "—"}</td>
                        <td className="max-w-[220px] truncate" title={row.title || ""}>
                          {row.title || "—"}
                        </td>
                        <td>{row.category_group || "—"}</td>
                        <td className="font-mono text-[11px]">{row.historical_data_symbol || "—"}</td>
                        <td className="font-mono">{fmtNumber(Number(row.latest_value))}</td>
                        <td>{row.unit || "—"}</td>
                        <td>{row.frequency || "—"}</td>
                        <td className="font-mono">{row.last_update || "—"}</td>
                      </tr>
                    );
                  })}
                </tbody>
              ))}
            </table>
            {indicatorsLoading ? (
              <div className="absolute inset-0 z-20 flex items-center justify-center bg-white/75 backdrop-blur-[1px]">
                <div className="rounded-2xl border border-border/70 bg-card/95 px-6 py-4 shadow-md">
                  <TradingEconomicsLoader label="Načítám snapshot indikátorů pro vybranou zemi…" />
                </div>
              </div>
            ) : null}
          </div>
        </section>

        <section className="soft-card border-border/80 rounded-2xl p-4 space-y-3">
          <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Krok 4 a 5 — Historie vybraného indikátoru
          </div>
          <div className="text-sm space-y-1">
            <div>
              Vybraný indikátor:{" "}
              <span className="font-semibold">
                {selectedIndicator?.category || "—"}
              </span>
            </div>
            {selectedIndicator?.title ? (
              <div className="text-muted-foreground text-xs">
                {selectedIndicator.title}
                {selectedIndicator.historical_data_symbol ? (
                  <span className="ml-2 font-mono">{selectedIndicator.historical_data_symbol}</span>
                ) : null}
              </div>
            ) : null}
          </div>
          <div className="flex flex-wrap items-end gap-3">
            <label className="text-xs text-muted-foreground">
              Od
              <input
                type="date"
                value={fromDate}
                onChange={(e) => setFromDate(e.target.value)}
                className="mt-1 block h-9 px-2 border border-border rounded-lg bg-card text-sm"
              />
            </label>
            <label className="text-xs text-muted-foreground">
              Do
              <input
                type="date"
                value={toDate}
                onChange={(e) => setToDate(e.target.value)}
                className="mt-1 block h-9 px-2 border border-border rounded-lg bg-card text-sm"
              />
            </label>
            <button
              type="button"
              onClick={loadHistorical}
              disabled={!selectedIndicator?.category || historicalLoading}
              className="btn-mint inline-flex items-center gap-2 px-4 h-9 text-sm disabled:opacity-50"
            >
              {historicalLoading ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin shrink-0" aria-hidden />
                  Načítám graf…
                </>
              ) : (
                "Load chart"
              )}
            </button>
          </div>

          {historicalLoading ? (
            <div className="relative h-72 border border-border/60 rounded-xl bg-gradient-to-b from-slate-50/90 to-card/80 overflow-hidden">
              <div className="absolute inset-0 bg-slate-200/25 animate-pulse" aria-hidden />
              <TradingEconomicsLoader label="Načítám historická data z Trading Economics…" />
            </div>
          ) : chartRows.length > 0 ? (
            <>
              <div className="h-72 border border-border/60 rounded-xl p-2 bg-card/70">
                <SafeRechartsContainer minHeight={240}>
                  <LineChart data={chartRows} margin={{ top: 8, right: 20, left: 8, bottom: 24 }}>
                    <CartesianGrid vertical={false} strokeDasharray="2 4" />
                    <XAxis dataKey="date" tick={{ fontSize: 11 }} tickLine={false} />
                    <YAxis tick={{ fontSize: 11 }} tickLine={false} />
                    <Tooltip
                      formatter={(value) => fmtNumber(Number(value))}
                      labelFormatter={(label) => `Date: ${label}`}
                    />
                    <Line type="monotone" dataKey="value" stroke="hsl(202 90% 42%)" strokeWidth={2} dot={false} />
                  </LineChart>
                </SafeRechartsContainer>
              </div>

              <div className="max-h-[260px] overflow-auto border border-border/70 rounded-xl">
                <table className="data-table text-xs">
                  <thead className="sticky top-0 z-10 bg-white">
                    <tr>
                      <th>Date</th>
                      <th>Value</th>
                    </tr>
                  </thead>
                  <tbody>
                    {chartRows.slice().reverse().map((row) => (
                      <tr key={`${row.date}-${row.value}`}>
                        <td className="font-mono">{row.date}</td>
                        <td className="font-mono">{fmtNumber(row.value)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          ) : (
            <div className="text-sm text-muted-foreground border border-dashed border-border rounded-xl p-4">
              Po výběru indikátoru klikni na <strong>Load chart</strong>.
            </div>
          )}
        </section>
      </div>
    </AppShell>
  );
}
