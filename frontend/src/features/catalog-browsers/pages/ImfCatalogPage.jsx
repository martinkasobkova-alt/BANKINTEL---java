import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ArrowLeft, ExternalLink, Loader2, Plus } from "lucide-react";
import { toast } from "sonner";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
} from "recharts";

import AppShell from "@/components/layout/AppShell";
import CatalogBackToHubButton from "@/components/catalog/CatalogBackToHubButton";
import { IMF_CATALOG_NOTE_CZ } from "@/lib/catalogDefinitions";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { SafeRechartsContainer } from "@/lib/SafeRechartsContainer";
import { useAuth } from "@/contexts/AuthContext";
import { imfFreqOptionsFromRow } from "@/lib/imfCatalogFreq";

async function apiFetchLocal(path) {
  const { data } = await api.get(path.replace(/^\/api/, ""));
  return data;
}

const PALETTE = ["#3b82f6", "#f59e0b", "#10b981", "#ef4444", "#8b5cf6", "#06b6d4"];

function fmtValue(value, unit) {
  if (value === null || value === undefined) return "–";
  const num = parseFloat(value);
  if (Number.isNaN(num)) return "–";
  if (unit?.includes("%")) return `${num.toFixed(2)} %`;
  return num.toLocaleString("cs-CZ", { maximumFractionDigits: 2 });
}

function Spinner() {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "center", padding: "48px", color: "#64748b" }}>
      <Loader2 className="h-6 w-6 animate-spin" style={{ marginRight: 12 }} />
      Načítám…
    </div>
  );
}

function CountryPicker({ countries, selected, onSelect, comparing, onAddCompare, onRemoveCompare }) {
  const [search, setSearch] = useState("");
  const filtered = Object.entries(countries).filter(([code, info]) =>
    info.name.toLowerCase().includes(search.toLowerCase()) ||
    code.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <input
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder="Hledat zemi…"
        style={{
          padding: "8px 12px", borderRadius: 8,
          border: "1px solid #e2e8f0", fontSize: 13,
          outline: "none", width: "100%", boxSizing: "border-box",
        }}
      />
      <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
        {filtered.map(([code, info]) => {
          const isMain = selected === code;
          const isCompare = comparing.includes(code);
          return (
            <div
              key={code}
              onClick={() => onSelect(code)}
              style={{
                display: "flex", alignItems: "center", justifyContent: "space-between",
                padding: "8px 10px", borderRadius: 6, cursor: "pointer",
                background: isMain ? "#eff6ff" : "transparent",
                border: isMain ? "1px solid #bfdbfe" : "1px solid transparent",
              }}
            >
              <div>
                <span style={{ fontWeight: isMain ? 600 : 400, fontSize: 13, color: isMain ? "#1d4ed8" : "#1e293b" }}>
                  {info.name}
                </span>
                <span style={{ fontSize: 11, color: "#94a3b8", marginLeft: 6 }}>{code}</span>
              </div>
              {!isMain && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    if (isCompare) onRemoveCompare(code);
                    else onAddCompare(code);
                  }}
                  style={{
                    fontSize: 11, padding: "2px 8px", borderRadius: 5, cursor: "pointer",
                    border: isCompare ? "1px solid #fca5a5" : "1px solid #bfdbfe",
                    background: isCompare ? "#fef2f2" : "#eff6ff",
                    color: isCompare ? "#dc2626" : "#2563eb",
                  }}
                >
                  {isCompare ? "– odebrat" : "+ srovnat"}
                </button>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** Země → dataset (WEO, CPI…) → ukazatele — stejná hierarchie jako IMF dataflow. */
function datasetsFromCategories(categories) {
  return Object.entries(categories || {})
    .map(([key, cat]) => {
      const first = Object.values(cat?.ukazatele || {})[0];
      const flow = String(first?.flow || key).trim();
      return {
        key,
        flow,
        label: cat?.nazev_kategorie || cat?.nazev || key,
        count: Object.keys(cat?.ukazatele || {}).length,
      };
    })
    .filter((d) => d.count > 0)
    .sort((a, b) => a.flow.localeCompare(b.flow));
}

function DatasetPicker({ datasets, selectedKey, onSelect }) {
  if (!datasets?.length) {
    return (
      <p style={{ fontSize: 12, color: "#94a3b8", padding: "8px 4px", lineHeight: 1.45 }}>
        Pro tuto zemi zatím není žádný dataset v katalogu.
      </p>
    );
  }
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
      {datasets.map((ds) => {
        const active = selectedKey === ds.key;
        return (
          <button
            key={ds.key}
            type="button"
            onClick={() => onSelect(ds.key)}
            style={{
              textAlign: "left",
              padding: "9px 10px",
              borderRadius: 7,
              border: active ? "1px solid #bfdbfe" : "1px solid #e2e8f0",
              cursor: "pointer",
              background: active ? "#eff6ff" : "white",
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span
                style={{
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: "0.04em",
                  color: active ? "#1d4ed8" : "#64748b",
                  background: active ? "#dbeafe" : "#f1f5f9",
                  padding: "2px 6px",
                  borderRadius: 4,
                }}
              >
                {ds.flow}
              </span>
              <span style={{ fontSize: 10, color: "#94a3b8" }}>{ds.count} řad</span>
            </div>
            <div
              style={{
                fontSize: 12.5,
                fontWeight: active ? 600 : 500,
                color: active ? "#1d4ed8" : "#334155",
                marginTop: 4,
                lineHeight: 1.35,
              }}
            >
              {ds.label}
            </div>
          </button>
        );
      })}
    </div>
  );
}

function IndicatorList({ category, selectedIndicator, onSelect }) {
  if (!category) return null;
  const entries = Object.entries(category.ukazatele || {});
  if (!entries.length) {
    return (
      <p style={{ fontSize: 12, color: "#94a3b8", padding: "8px 4px" }}>
        V tomto datasetu nejsou pro zemi ověřené řady.
      </p>
    );
  }
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
      {entries.map(([indKey, ind]) => (
        <button
          key={indKey}
          type="button"
          onClick={() => onSelect(indKey, ind)}
          style={{
            textAlign: "left",
            padding: "7px 10px",
            borderRadius: 6,
            border: "none",
            cursor: "pointer",
            background: selectedIndicator?.key === indKey ? "#eff6ff" : "transparent",
            color: selectedIndicator?.key === indKey ? "#1d4ed8" : "#334155",
            fontSize: 12.5,
            fontWeight: selectedIndicator?.key === indKey ? 600 : 400,
            borderLeft: selectedIndicator?.key === indKey ? "3px solid #3b82f6" : "3px solid transparent",
          }}
        >
          {ind.nazev}
          <div style={{ fontSize: 10, color: "#94a3b8", marginTop: 1 }}>
            {ind.frekvence_label || ind.frekvence}
            {ind.jednotka ? ` · ${ind.jednotka}` : ""}
            {ind.ma_projekce ? " · projekce" : ""}
          </div>
        </button>
      ))}
    </div>
  );
}

function DateRange({ od, setOd, doDate, setDo }) {
  const presets = [
    { label: "10 let", od: "2015" },
    { label: "20 let", od: "2005" },
    { label: "Vše", od: "" },
  ];
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
      <span style={{ fontSize: 12, color: "#64748b" }}>Období:</span>
      {presets.map((p) => (
        <button
          key={p.label}
          type="button"
          onClick={() => { setOd(p.od); setDo("2030"); }}
          style={{
            fontSize: 11, padding: "4px 10px", borderRadius: 5, cursor: "pointer",
            border: od === p.od ? "1px solid #3b82f6" : "1px solid #e2e8f0",
            background: od === p.od ? "#eff6ff" : "white",
            color: od === p.od ? "#2563eb" : "#64748b",
          }}
        >
          {p.label}
        </button>
      ))}
      <input
        type="text"
        value={od}
        onChange={(e) => setOd(e.target.value)}
        placeholder="od"
        style={{ fontSize: 11, padding: "3px 8px", borderRadius: 5, border: "1px solid #e2e8f0", width: 56 }}
      />
      <span style={{ fontSize: 11, color: "#94a3b8" }}>–</span>
      <input
        type="text"
        value={doDate}
        onChange={(e) => setDo(e.target.value)}
        placeholder="do"
        style={{ fontSize: 11, padding: "3px 8px", borderRadius: 5, border: "1px solid #e2e8f0", width: 56 }}
      />
    </div>
  );
}

export default function ImfCatalogPage() {
  const nav = useNavigate();
  const [searchParams] = useSearchParams();
  const { isAdmin } = useAuth();
  const [countries, setCountries] = useState({});
  const [selectedCountry, setSelectedCountry] = useState("CZ");
  const [comparingCountries, setComparingCountries] = useState([]);
  const [categories, setCategories] = useState(null);
  const [selectedDatasetKey, setSelectedDatasetKey] = useState(null);
  const [selectedIndicator, setSelectedIndicator] = useState(null);
  const [selectedFreq, setSelectedFreq] = useState(null);
  const [chartData, setChartData] = useState(null);
  const [countryNotice, setCountryNotice] = useState("");
  const [od, setOd] = useState("2015");
  const [doDate, setDoDate] = useState("2030");
  const [loadingCountries, setLoadingCountries] = useState(true);
  const [loadingIndicators, setLoadingIndicators] = useState(false);
  const [loadingChart, setLoadingChart] = useState(false);
  const [bootError, setBootError] = useState(null);
  const deepLinkChartDone = useRef(false);

  useEffect(() => {
    deepLinkChartDone.current = false;
  }, [selectedCountry, searchParams]);

  useEffect(() => {
    setLoadingCountries(true);
    const deepCountry = String(searchParams.get("country") || "").trim().toUpperCase();
    apiFetchLocal("/api/imf/countries")
      .then((d) => {
        const map = d.countries || {};
        setCountries(map);
        if (deepCountry && map[deepCountry]) {
          setSelectedCountry(deepCountry);
        } else if (Object.keys(map).length && !map[selectedCountry]) {
          setSelectedCountry(Object.keys(map)[0]);
        }
      })
      .catch((e) => setBootError(formatApiErrorFromAxios(e)))
      .finally(() => setLoadingCountries(false));
  }, [searchParams]);

  useEffect(() => {
    if (!selectedCountry) return;
    setLoadingIndicators(true);
    setCategories(null);
    setSelectedDatasetKey(null);
    setSelectedIndicator(null);
    setSelectedFreq(null);
    setChartData(null);
    const deepFlow = String(searchParams.get("flow") || "").trim().toUpperCase();
    const deepInd = String(searchParams.get("indicator") || "").trim().toUpperCase();
    apiFetchLocal(`/api/imf/country/${selectedCountry}`)
      .then((d) => {
        const kat = d.kategorie || {};
        setCategories(kat);
        const ds = datasetsFromCategories(kat);
        let dsKey = ds[0]?.key ?? null;
        if (deepFlow) {
          const match = ds.find((x) => x.flow.toUpperCase() === deepFlow);
          if (match) dsKey = match.key;
        }
        setSelectedDatasetKey(dsKey);
        setCountryNotice(d.browse_notice || "");
        if (deepInd && dsKey && kat[dsKey]?.ukazatele) {
          const uk = kat[dsKey].ukazatele || {};
          const indKey = Object.keys(uk).find((k) => k.toUpperCase() === deepInd);
          if (indKey && uk[indKey]) {
            const ind = uk[indKey];
            const indicator = {
              key: indKey,
              flow: ind.flow,
              nazev: ind.nazev,
              jednotka: ind.jednotka,
              varianty: ind.varianty || [],
              frekvence: ind.frekvence,
              frekvence_label: ind.frekvence_label,
            };
            const freq =
              indicator.varianty.length > 0
                ? indicator.varianty[0].frekvence
                : ind.frekvence || "A";
            setSelectedIndicator(indicator);
            setSelectedFreq(freq);
          }
        }
      })
      .catch((e) => setBootError(formatApiErrorFromAxios(e)))
      .finally(() => setLoadingIndicators(false));
  }, [selectedCountry, searchParams]);

  const countryInfo = countries[selectedCountry] || { name: selectedCountry };

  const datasets = useMemo(() => datasetsFromCategories(categories), [categories]);

  const activeDataset = useMemo(
    () => datasets.find((d) => d.key === selectedDatasetKey) || datasets[0] || null,
    [datasets, selectedDatasetKey]
  );

  const activeCategory = useMemo(() => {
    if (!categories || !activeDataset) return null;
    return categories[activeDataset.key] || null;
  }, [categories, activeDataset]);

  const loadChart = useCallback(
    async (indicator, freq, country, comparing, odParam, doParam) => {
      if (!indicator?.flow) return;
      setLoadingChart(true);
      setBootError(null);
      try {
        const all = [country, ...comparing];
        const params = new URLSearchParams();
        if (odParam) params.set("od", odParam);
        if (doParam) params.set("do", doParam);
        if (freq) params.set("frekvence", freq);
        const qs = params.toString() ? `?${params}` : "";

        const results = await Promise.all(
          all.map((c) =>
            apiFetchLocal(
              `/api/imf/country/${c}/data/${indicator.flow}/${indicator.key}${qs}`
            )
              .then((d) => ({
                country: c,
                name: countries[c]?.name || d.nazev_zeme || c,
                data: d.data || [],
                unit: d.jednotka,
                ma_projekce: d.ma_projekce,
              }))
              .catch(() => ({
                country: c,
                name: countries[c]?.name || c,
                data: [],
                unit: indicator.jednotka,
              }))
          )
        );

        const allDates = [...new Set(results.flatMap((r) => r.data.map((d) => d.date)))].sort();
        const merged = allDates.map((date) => {
          const row = { date };
          results.forEach((r) => {
            const found = r.data.find((d) => d.date === date);
            row[r.country] = found ? found.value : null;
          });
          return row;
        });

        setChartData({
          merged,
          results,
          unit: results[0]?.unit,
          ma_projekce: results.some((r) => r.ma_projekce),
        });
      } catch (e) {
        setBootError(formatApiErrorFromAxios(e));
      } finally {
        setLoadingChart(false);
      }
    },
    [countries]
  );

  useEffect(() => {
    if (deepLinkChartDone.current) return;
    const deepInd = String(searchParams.get("indicator") || "").trim();
    if (!deepInd || !selectedIndicator?.flow) return;
    if (String(selectedIndicator.key).toUpperCase() !== deepInd.toUpperCase()) return;
    deepLinkChartDone.current = true;
    const freq = selectedFreq || selectedIndicator.frekvence || "A";
    void loadChart(selectedIndicator, freq, selectedCountry, [], od, doDate);
  }, [selectedIndicator, selectedFreq, selectedCountry, searchParams, loadChart, od, doDate]);

  const handleSelectIndicator = (indKey, ind) => {
    const indicator = {
      key: indKey,
      flow: ind.flow,
      nazev: ind.nazev,
      jednotka: ind.jednotka,
      varianty: ind.varianty || [],
      frekvence: ind.frekvence,
      frekvence_label: ind.frekvence_label,
    };
    setSelectedIndicator(indicator);
    const variants = indicator.varianty;
    const freq = variants.length > 0 ? variants[0].frekvence : (ind.frekvence || "A");
    setSelectedFreq(freq);
    loadChart(indicator, freq, selectedCountry, comparingCountries, od, doDate);
  };

  const handleFreqChange = (freq) => {
    setSelectedFreq(freq);
    if (selectedIndicator) {
      loadChart(selectedIndicator, freq, selectedCountry, comparingCountries, od, doDate);
    }
  };

  const handleSelectCountry = (code) => {
    setSelectedCountry(code);
    setComparingCountries([]);
  };

  const handleSelectDataset = (key) => {
    setSelectedDatasetKey(key);
    setSelectedIndicator(null);
    setSelectedFreq(null);
    setChartData(null);
  };

  const handleAddCompare = (code) => {
    if (comparingCountries.length >= 4) return;
    setComparingCountries((prev) => [...prev, code]);
    if (selectedIndicator) {
      loadChart(selectedIndicator, selectedFreq, selectedCountry, [...comparingCountries, code], od, doDate);
    }
  };

  const handleRemoveCompare = (code) => {
    const next = comparingCountries.filter((c) => c !== code);
    setComparingCountries(next);
    if (selectedIndicator) {
      loadChart(selectedIndicator, selectedFreq, selectedCountry, next, od, doDate);
    }
  };

  const handleAddSource = async () => {
    if (!isAdmin || !selectedIndicator) return;
    try {
      await api.post("/api/imf/add-source", {
        country: selectedCountry,
        flow: selectedIndicator.flow,
        indicator: selectedIndicator.key,
        name: `${countryInfo.name} · ${selectedIndicator.nazev}`,
      });
      toast.success("IMF zdroj vytvořen.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
  };

  const freqVariants = useMemo(() => {
    if (!selectedIndicator) return [];
    if (selectedIndicator.varianty?.length > 0) {
      return selectedIndicator.varianty;
    }
    return imfFreqOptionsFromRow(selectedIndicator);
  }, [selectedIndicator]);

  if (bootError && !Object.keys(countries).length && !loadingCountries) {
    return (
      <AppShell title="Katalog IMF" subtitle={IMF_CATALOG_NOTE_CZ}>
        <p className="text-destructive text-sm">{bootError}</p>
      </AppShell>
    );
  }

  return (
    <AppShell
      title="Katalog IMF"
      subtitle="Země → dataset (WEO, CPI…) → ukazatel → graf"
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <a href="https://data.imf.org/" target="_blank" rel="noreferrer" className="flex items-center gap-2 px-3 h-9 text-sm border rounded-xl hover:bg-muted/50">
            <ExternalLink className="h-4 w-4" /> data.imf.org
          </a>
          <button type="button" onClick={() => nav("/sources")} className="flex items-center gap-2 px-3 h-9 text-sm border rounded-xl">
            <ArrowLeft className="h-4 w-4" /> Zpět
          </button>
          <CatalogBackToHubButton catalogId="imf" />
        </div>
      }
    >
      <style>{`
        @keyframes spin { to { transform: rotate(360deg); } }
        * { box-sizing: border-box; }
      `}</style>

      <div style={{ display: "flex", height: "calc(100vh - 140px)", overflow: "hidden", fontFamily: "'IBM Plex Sans', system-ui, sans-serif" }}>

        <div style={{ width: 240, minWidth: 240, background: "white", borderRight: "1px solid #e2e8f0", display: "flex", flexDirection: "column", overflow: "hidden" }}>
          <div style={{ padding: "20px 16px 12px", borderBottom: "1px solid #f1f5f9" }}>
            <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", color: "#94a3b8", textTransform: "uppercase", marginBottom: 12 }}>IMF Data</div>
            <div style={{ fontSize: 16, fontWeight: 700, color: "#0f172a" }}>Vyberte zemi</div>
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: "12px 12px" }}>
            {loadingCountries ? <Spinner /> : (
              <CountryPicker
                countries={countries}
                selected={selectedCountry}
                onSelect={handleSelectCountry}
                comparing={comparingCountries}
                onAddCompare={handleAddCompare}
                onRemoveCompare={handleRemoveCompare}
              />
            )}
          </div>
        </div>

        <div style={{ width: 300, minWidth: 300, background: "#fafafa", borderRight: "1px solid #e2e8f0", display: "flex", flexDirection: "column", overflow: "hidden" }}>
          <div style={{ padding: "20px 16px 12px", borderBottom: "1px solid #f1f5f9" }}>
            <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", color: "#94a3b8", textTransform: "uppercase", marginBottom: 4 }}>
              {countryInfo.name || selectedCountry}
            </div>
            <div style={{ fontSize: 16, fontWeight: 700, color: "#0f172a" }}>Dataset a ukazatele</div>
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: "12px 10px" }}>
            {countryNotice ? (
              <div style={{ marginBottom: 10, padding: "10px 12px", borderRadius: 8, background: "#fffbeb", border: "1px solid #fde68a", fontSize: 12, color: "#92400e", lineHeight: 1.45 }}>
                {countryNotice}
              </div>
            ) : null}
            {loadingIndicators ? <Spinner /> : (
              <>
                <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.06em", color: "#94a3b8", textTransform: "uppercase", marginBottom: 8 }}>
                  1 · Dataset
                </div>
                <DatasetPicker
                  datasets={datasets}
                  selectedKey={activeDataset?.key ?? null}
                  onSelect={handleSelectDataset}
                />
                <div
                  style={{
                    fontSize: 11,
                    fontWeight: 700,
                    letterSpacing: "0.06em",
                    color: "#94a3b8",
                    textTransform: "uppercase",
                    margin: "16px 0 8px",
                    paddingTop: 12,
                    borderTop: "1px solid #e2e8f0",
                  }}
                >
                  2 · Ukazatele
                  {activeDataset?.flow ? (
                    <span style={{ fontWeight: 600, color: "#64748b", marginLeft: 6 }}>({activeDataset.flow})</span>
                  ) : null}
                </div>
                <IndicatorList
                  category={activeCategory}
                  selectedIndicator={selectedIndicator}
                  onSelect={handleSelectIndicator}
                />
              </>
            )}
          </div>
        </div>

        <div style={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>
          <div style={{ padding: "16px 24px", background: "white", borderBottom: "1px solid #e2e8f0", display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 16 }}>
            <div>
              <div style={{ fontSize: 18, fontWeight: 700, color: "#0f172a" }}>
                {selectedIndicator?.nazev || "Vyberte ukazatel v seznamu vlevo"}
              </div>
              {selectedIndicator && (
                <div style={{ fontSize: 12, color: "#64748b", marginTop: 4 }}>
                  {activeDataset?.flow ? (
                    <span style={{ marginRight: 8, fontWeight: 600, color: "#475569" }}>{activeDataset.flow}</span>
                  ) : null}
                  {selectedIndicator.jednotka}
                  {comparingCountries.length > 0 && (
                    <span style={{ marginLeft: 12, color: "#3b82f6" }}>
                      Srovnání: {[selectedCountry, ...comparingCountries].join(", ")}
                    </span>
                  )}
                </div>
              )}
              {selectedIndicator && freqVariants.length > 0 && (
                <div style={{ display: "flex", gap: 6, marginTop: 10, flexWrap: "wrap" }} data-testid="imf-freq-switcher">
                  <span style={{ fontSize: 11, color: "#64748b", alignSelf: "center" }}>Frekvence:</span>
                  {freqVariants.map((v) => (
                    <button
                      key={v.frekvence}
                      type="button"
                      onClick={() => handleFreqChange(v.frekvence)}
                      style={{
                        fontSize: 11, padding: "4px 12px", borderRadius: 6, cursor: "pointer",
                        border: selectedFreq === v.frekvence ? "1px solid #3b82f6" : "1px solid #e2e8f0",
                        background: selectedFreq === v.frekvence ? "#eff6ff" : "white",
                        color: selectedFreq === v.frekvence ? "#1d4ed8" : "#64748b",
                      }}
                    >
                      {v.frekvence_label || v.frekvence}
                    </button>
                  ))}
                </div>
              )}
            </div>
            {isAdmin && selectedIndicator ? (
              <button type="button" onClick={handleAddSource} className="flex items-center gap-1 px-3 py-1.5 rounded-lg border text-sm shrink-0">
                <Plus className="h-4 w-4" /> Přidat zdroj
              </button>
            ) : null}
          </div>

          <div style={{ flex: 1, overflowY: "auto", padding: "16px 24px", background: "#f8fafc" }}>
            {bootError ? (
              <div style={{ background: "#fef2f2", border: "1px solid #fecaca", borderRadius: 8, padding: 12, color: "#dc2626", fontSize: 13, marginBottom: 12 }}>
                {bootError}
              </div>
            ) : null}

            {selectedIndicator ? (
              <>
                <DateRange od={od} setOd={setOd} doDate={doDate} setDo={setDoDate} />
                <button
                  type="button"
                  style={{ fontSize: 11, marginLeft: 8, padding: "4px 10px", borderRadius: 5, border: "1px solid #e2e8f0", background: "white", cursor: "pointer" }}
                  onClick={() => loadChart(selectedIndicator, selectedFreq, selectedCountry, comparingCountries, od, doDate)}
                >
                  Obnovit graf
                </button>

                {chartData?.ma_projekce ? (
                  <p style={{ fontSize: 11, color: "#b45309", marginTop: 12 }}>Řada obsahuje IMF projekce (WEO).</p>
                ) : null}

                <div style={{ marginTop: 16, background: "white", borderRadius: 12, border: "1px solid #e2e8f0", padding: 16 }}>
                  {loadingChart ? (
                    <Spinner />
                  ) : chartData?.merged?.length ? (
                    <SafeRechartsContainer height={400}>
                      <LineChart data={chartData.merged}>
                        <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                        <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                        <YAxis tick={{ fontSize: 11 }} />
                        <Tooltip formatter={(v) => fmtValue(v, chartData.unit)} />
                        <Legend />
                        {[selectedCountry, ...comparingCountries].map((c, i) => (
                          <Line
                            key={c}
                            type="monotone"
                            dataKey={c}
                            name={countries[c]?.name || c}
                            stroke={PALETTE[i % PALETTE.length]}
                            dot={false}
                            connectNulls
                          />
                        ))}
                      </LineChart>
                    </SafeRechartsContainer>
                  ) : (
                    <p style={{ fontSize: 13, color: "#64748b", padding: 24, textAlign: "center" }}>Pro tento ukazatel nejsou data.</p>
                  )}
                </div>
              </>
            ) : (
              <p style={{ fontSize: 14, color: "#64748b", marginTop: 32, textAlign: "center" }}>
                Po výběru země klikněte na ukazatel — graf se zobrazí zde. Technické dimenze nevybíráte; případně jen frekvenci (Ročně / Čtvrtletně) přímo nad grafem.
              </p>
            )}
          </div>
        </div>
      </div>
    </AppShell>
  );
}
