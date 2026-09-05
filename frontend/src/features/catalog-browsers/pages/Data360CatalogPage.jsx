import React, { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, ExternalLink, Plus, RefreshCw, Search } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import CatalogBackToHubButton from "@/components/catalog/CatalogBackToHubButton";
import { LoadingBlock, LoadingSpinner } from "@/components/ui/loading";
import { DATA360_CATALOG_DESCRIPTION_CZ } from "@/lib/catalogDefinitions";
import { useAuth } from "@/contexts/AuthContext";

export default function Data360CatalogPage() {
  const { isAdmin } = useAuth();
  const nav = useNavigate();
  const [payload, setPayload] = useState(null);
  const [loading, setLoading] = useState(true);
  const [searchQ, setSearchQ] = useState("World Bank population WDI");
  const [refArea, setRefArea] = useState("");
  const [freq, setFreq] = useState("");
  const [timeFrom, setTimeFrom] = useState("");
  const [timeTo, setTimeTo] = useState("");
  const [sex, setSex] = useState("");
  const [age, setAge] = useState("");
  const [urbanisation, setUrbanisation] = useState("");
  const [unitMeasure, setUnitMeasure] = useState("");
  const [unitMult, setUnitMult] = useState("");
  const [compBreakdown1, setCompBreakdown1] = useState("");
  const [compBreakdown2, setCompBreakdown2] = useState("");
  const [compBreakdown3, setCompBreakdown3] = useState("");
  const [advDatabaseId, setAdvDatabaseId] = useState("");
  const [advIndicator, setAdvIndicator] = useState("");
  const [adding, setAdding] = useState(null);

  const fetchCatalog = async (qv) => {
    setLoading(true);
    try {
      const { data } = await api.get("/data360/catalog", {
        params: { q: (qv ?? "").trim() || undefined, top: 50 },
      });
      setPayload(data);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setLoading(false);
  };

  useEffect(() => {
    fetchCatalog("World Bank population WDI");
  }, []);


  const rows = useMemo(() => payload?.categories?.[0]?.sets || [], [payload]);

  const mergeFilters = (base) => {
    const o = { ...base };
    if (refArea.trim()) o.REF_AREA = refArea.trim();
    if (freq.trim()) o.FREQ = freq.trim();
    if (timeFrom.trim()) o.timePeriodFrom = timeFrom.trim();
    if (timeTo.trim()) o.timePeriodTo = timeTo.trim();
    if (sex.trim()) o.SEX = sex.trim();
    if (age.trim()) o.AGE = age.trim();
    if (urbanisation.trim()) o.URBANISATION = urbanisation.trim();
    if (unitMeasure.trim()) o.UNIT_MEASURE = unitMeasure.trim();
    if (unitMult.trim()) o.UNIT_MULT = unitMult.trim();
    if (compBreakdown1.trim()) o.COMP_BREAKDOWN_1 = compBreakdown1.trim();
    if (compBreakdown2.trim()) o.COMP_BREAKDOWN_2 = compBreakdown2.trim();
    if (compBreakdown3.trim()) o.COMP_BREAKDOWN_3 = compBreakdown3.trim();
    return o;
  };

  /** Běžné UI nepřepisuje DATABASE_ID/INDICATOR u řádku ze search — jen země/období/…. Adminní debug: rozšířený přepis s příznakem na backendu. */
  const buildAddSourceBodyForRow = (row) => {
    const qp = mergeFilters({ ...(row.query_params || {}) });
    const body = {
      set_id: row.set_id,
      name: row.name,
      query_params: qp,
    };
    if (
      isAdmin &&
      (advDatabaseId.trim() || advIndicator.trim()) &&
      (advDatabaseId.trim() !== String(row.data360_database_id || "").trim() ||
        advIndicator.trim() !== String(row.data360_indicator || "").trim())
    ) {
      body.allow_identifier_override = true;
      if (advDatabaseId.trim()) qp.DATABASE_ID = advDatabaseId.trim();
      if (advIndicator.trim()) qp.INDICATOR = advIndicator.trim();
    }
    return body;
  };

  const addSource = async (row) => {
    const key = row.set_id;
    setAdding(key);
    try {
      await api.post("/data360/catalog/add-source", buildAddSourceBodyForRow(row));
      toast.success("Zdroj World Bank byl přidán.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setAdding(null);
  };

  return (
    <AppShell
      title="Katalog World Bank"
      subtitle={`${DATA360_CATALOG_DESCRIPTION_CZ} · provider world_bank_data360`}
      actions={
        <div className="flex flex-wrap gap-2">
          <a
            href="https://data.worldbank.org/"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-2 px-3 h-9 text-sm border rounded-xl shadow-sm hover:bg-[hsl(var(--primary-soft))]"
          >
            <ExternalLink className="h-4 w-4" /> data.worldbank.org
          </a>
          <button
            type="button"
            onClick={() => nav("/sources")}
            className="flex items-center gap-2 px-3 h-9 text-sm border rounded-xl"
          >
            <ArrowLeft className="h-4 w-4" /> Zpět na zdroje
          </button>
          <CatalogBackToHubButton catalogId="worldbank_data360" />
          <button
            type="button"
            onClick={() => fetchCatalog(searchQ)}
            disabled={loading}
            aria-busy={loading ? "true" : undefined}
            className="flex items-center gap-2 px-3 h-9 text-sm border rounded-xl disabled:opacity-50"
          >
            {loading ? <LoadingSpinner suppressAria size="sm" aria-label="Obnovuji Data360…" /> : <RefreshCw className="h-4 w-4" />}
            Obnovit
          </button>
        </div>
      }
    >
      <div className="space-y-6 max-w-6xl">
        <div className="rounded-xl border border-emerald-200/80 bg-emerald-50/80 px-4 py-3 text-sm">
          <p className="font-medium mb-1">World Bank</p>
          <p className="leading-snug text-foreground">{DATA360_CATALOG_DESCRIPTION_CZ}</p>
          <p className="mt-2 text-[12px] text-muted-foreground">
            Výsledky: název řady · id indikátora · databáze ({`database_id`}) · provider World Bank. Vyhledání jde ladit níže —
            používá POST <span className="font-mono">/data360/searchv2</span>; náhled a synchronizace dat přes GET{" "}
            <span className="font-mono">/data360/data</span>.
          </p>
        </div>

        {(payload?.errors || []).length > 0 ? (
          <div className="rounded-xl border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 px-4 py-3 text-sm text-amber-950 canvas-dark:text-amber-50">
            <ul className="list-disc pl-5">
              {(payload.errors || []).map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ul>
          </div>
        ) : null}

        <div className="flex flex-wrap gap-3 items-end">
          <label className="flex-1 min-w-[240px]">
            <span className="block text-xs text-muted-foreground mb-1">Dotaz (searchv2)</span>
            <div className="relative">
              <Search className="h-4 w-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
              <input
                className="w-full h-10 pl-9 pr-3 border rounded-xl text-sm"
                value={searchQ}
                onChange={(e) => setSearchQ(e.target.value)}
                placeholder="World Bank poverty, WDI, GDP..."
              />
            </div>
          </label>
          <button
            type="button"
            className="btn-mint px-4 h-10 text-sm"
            onClick={() => fetchCatalog(searchQ)}
          >
            Hledat
          </button>
        </div>

        <div className="rounded-2xl border bg-muted/35 p-4 space-y-2">
          <div className="text-sm font-medium">Filtry při přidání zdroje (volitelné)</div>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5 text-xs">
            <label>
              REF_AREA
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={refArea} onChange={(e) => setRefArea(e.target.value)} placeholder="např. CZE" />
            </label>
            <label>
              FREQ
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={freq} onChange={(e) => setFreq(e.target.value)} placeholder="např. A" />
            </label>
            <label>
              timePeriodFrom
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={timeFrom} onChange={(e) => setTimeFrom(e.target.value)} />
            </label>
            <label>
              timePeriodTo
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={timeTo} onChange={(e) => setTimeTo(e.target.value)} />
            </label>
            <label>
              SEX
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={sex} onChange={(e) => setSex(e.target.value)} />
            </label>
            <label>
              AGE
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={age} onChange={(e) => setAge(e.target.value)} />
            </label>
            <label>
              URBANISATION
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={urbanisation} onChange={(e) => setUrbanisation(e.target.value)} />
            </label>
            <label>
              UNIT_MEASURE
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={unitMeasure} onChange={(e) => setUnitMeasure(e.target.value)} />
            </label>
            <label>
              UNIT_MULT
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={unitMult} onChange={(e) => setUnitMult(e.target.value)} />
            </label>
            <label>
              COMP_BREAKDOWN_1
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={compBreakdown1} onChange={(e) => setCompBreakdown1(e.target.value)} />
            </label>
            <label>
              COMP_BREAKDOWN_2
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={compBreakdown2} onChange={(e) => setCompBreakdown2(e.target.value)} />
            </label>
            <label>
              COMP_BREAKDOWN_3
              <input className="w-full mt-1 h-9 border rounded-lg px-2 font-mono" value={compBreakdown3} onChange={(e) => setCompBreakdown3(e.target.value)} />
            </label>
          </div>
          <p className="mt-2 text-[11px] text-muted-foreground">
            Hodnoty <span className="font-mono">DATABASE_ID</span> a <span className="font-mono">INDICATOR</span> jsou
            vždy z vybraného výsledku vyhledávání (set_id) — běžné filtry je nemění.
          </p>
        </div>

        {isAdmin ? (
          <details className="rounded-xl border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 px-4 py-3 text-xs max-w-3xl">
            <summary className="cursor-pointer font-medium text-amber-950 canvas-dark:text-amber-50">Pokročilé (admin) — přepis DATABASE_ID / INDICATOR</summary>
            <p className="mt-2 text-amber-950 canvas-dark:text-amber-50/90 leading-snug">
              Pouze pro ladění. Odešle se s <span className="font-mono">allow_identifier_override</span> na serveru; jinak
              zůstávají identifikátory z řádku katalogu.
            </p>
            <div className="mt-2 grid gap-2 sm:grid-cols-2">
              <label>
                DATABASE_ID (volitelně)
                <input
                  className="w-full mt-1 h-9 border rounded-lg px-2 font-mono"
                  value={advDatabaseId}
                  onChange={(e) => setAdvDatabaseId(e.target.value)}
                  placeholder="např. WB_WDI"
                />
              </label>
              <label>
                INDICATOR (volitelně)
                <input
                  className="w-full mt-1 h-9 border rounded-lg px-2 font-mono"
                  value={advIndicator}
                  onChange={(e) => setAdvIndicator(e.target.value)}
                  placeholder="idno"
                />
              </label>
            </div>
          </details>
        ) : null}

        {loading ? (
          <LoadingBlock label="Načítám Data360…" minHeightClass="min-h-[180px]" showSkeletonLines skeletonRows={5} />
        ) : (
          <div className="border rounded-2xl overflow-hidden bg-card shadow-sm">
            <div className="grid grid-cols-12 gap-2 px-3 py-2 bg-muted text-[11px] font-semibold uppercase tracking-wide">
              <div className="col-span-3">Řada</div>
              <div className="col-span-2 font-mono">idno / indikátor</div>
              <div className="col-span-2 font-mono">database_id</div>
              <div className="col-span-2 font-mono text-[10px]">provider</div>
              <div className="col-span-2 font-mono">set_id</div>
              <div className="col-span-1 text-right">Akce</div>
            </div>
            <div className="divide-y max-h-[min(560px,60vh)] overflow-y-auto">
              {rows.length === 0 ? (
                <div className="p-6 text-sm text-muted-foreground font-mono">Žádné výsledky — zkuste změnit dotaz.</div>
              ) : (
                rows.map((row) => (
                  <div key={row.set_id} className="grid grid-cols-12 gap-2 px-3 py-2.5 text-sm items-center">
                    <div className="col-span-3">
                      <div className="font-medium text-foreground">{row.data360_series_name || row.name}</div>
                      <div className="text-[11px] text-muted-foreground">World Bank</div>
                    </div>
                    <div className="col-span-2 font-mono text-[11px] text-technical-wrap">{row.data360_indicator}</div>
                    <div className="col-span-2 font-mono text-[11px] text-technical-wrap">{row.data360_database_id}</div>
                    <div className="col-span-2 font-mono text-[10px] text-technical-wrap">{row.provider || "world_bank_data360"}</div>
                    <div className="col-span-2 font-mono text-[10px] text-technical-wrap opacity-90">{row.set_id}</div>
                    <div className="col-span-1 text-right">
                      <button
                        type="button"
                        disabled={adding === row.set_id}
                        onClick={() => addSource(row)}
                        className="btn-mint inline-flex items-center gap-1 px-2.5 py-1.5 text-xs disabled:opacity-50"
                      >
                        {adding === row.set_id ? <RefreshCw className="h-3 w-3 animate-spin" /> : <Plus className="h-3 w-3" />}
                        Přidat zdroj
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        )}
      </div>
    </AppShell>
  );
}
