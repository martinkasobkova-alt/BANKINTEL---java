import React, { useEffect, useMemo, useState } from "react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import { toast } from "sonner";

const SOURCE_TYPE_LABELS = {
  arad: "ARAD",
  csu: "ČSÚ",
  eurostat: "Eurostat",
  ecb: "ECB",
  fred: "FRED",
  worldbank: "World Bank",
  alphavantage: "Alpha Vantage",
  bis: "BIS",
  imf: "IMF",
  oecd: "OECD",
};

const unarySeriesA = new Set([
  "log_a",
  "index_100_first",
  "yoy_pct_auto",
  "yoy_abs_auto",
  "mom_pct_auto",
  "qoq_pct_auto",
  "roll_mean",
  "cumsum",
  "volatility_ret",
  "zscore",
  "drawdown_pct",
  "cagr_range",
  "cum_change_first",
  "pct_rank_hist",
  "trend_linear_time",
]);

const unarySeriesB = new Set(["index_b100_first"]);

function refOkForComputed(r) {
  if (!r || typeof r !== "object") return false;
  if ((r.saved_series_id || "").trim()) return true;
  if (!(r.source_id || "").trim()) return false;
  if ((r.indicator_id || "").trim()) return true;
  return !!(r.x_field || "").trim() && !!(r.y_field || "").trim();
}

function emptyRef() {
  return { source_id: "", indicator_id: "", saved_series_id: "", x_field: "", y_field: "", name: "" };
}

function packComputedRefPayload(r) {
  return {
    source_id: (r?.source_id || "").trim(),
    indicator_id: (r?.indicator_id || "").trim(),
    saved_series_id: (r?.saved_series_id || "").trim(),
    x_field: (r?.x_field || "").trim(),
    y_field: (r?.y_field || "").trim(),
    name: r?.name || "",
  };
}

function sourceTypeLabel(sourceTypeRaw) {
  const key = String(sourceTypeRaw || "").trim().toLowerCase();
  return SOURCE_TYPE_LABELS[key] || (key ? key.toUpperCase() : "Ostatní");
}

function sourceName(source) {
  return String(source?.name || source?.title || source?.dataset_name || source?.id || "").trim();
}

function savedSeriesName(row) {
  return String(row?.title || row?.name || row?.id || "").trim();
}

function SeriesBlock({
  label,
  refValue,
  onChange,
  sources,
  savedSeries,
  indicatorsLoading,
  indicators,
  disabled,
}) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [openGroups, setOpenGroups] = useState(() => new Set(["user_data"]));
  const q = String(query || "").trim().toLowerCase();
  const groupedSources = useMemo(() => {
    const out = new Map();
    for (const src of sources) {
      const groupKey = String(src?.source_type || "other").trim().toLowerCase() || "other";
      if (!out.has(groupKey)) out.set(groupKey, []);
      out.get(groupKey).push(src);
    }
    for (const [key, list] of out.entries()) {
      list.sort((a, b) => sourceName(a).localeCompare(sourceName(b), "cs", { sensitivity: "base" }));
      out.set(key, list);
    }
    return out;
  }, [sources]);
  const groups = useMemo(() => {
    const out = [];
    out.push({
      key: "user_data",
      label: "Vlastní data",
      kind: "saved",
      items: savedSeries.filter((row) => {
        if (!q) return true;
        const hay = `${savedSeriesName(row)} ${row?.source || ""} ${row?.source_type || ""}`.toLowerCase();
        return hay.includes(q);
      }),
    });
    const sourceGroupRows = Array.from(groupedSources.entries())
      .map(([key, items]) => ({
        key,
        label: sourceTypeLabel(key),
        kind: "source",
        items: items.filter((src) => {
          if (!q) return true;
          const hay = `${sourceName(src)} ${src?.id || ""} ${sourceTypeLabel(src?.source_type)}`.toLowerCase();
          return hay.includes(q);
        }),
      }))
      .filter((group) => group.items.length > 0)
      .sort((a, b) => a.label.localeCompare(b.label, "cs", { sensitivity: "base" }));
    out.push(...sourceGroupRows);
    return out.filter((g) => g.items.length > 0);
  }, [groupedSources, savedSeries, q]);

  useEffect(() => {
    if (!q) return;
    setOpenGroups((prev) => {
      const next = new Set(prev);
      for (const g of groups) next.add(g.key);
      return next;
    });
  }, [q, groups]);

  const sourceSelected = useMemo(
    () => sources.find((s) => String(s.id) === String(refValue.source_id || "")) || null,
    [sources, refValue.source_id]
  );
  const selectedSaved = useMemo(
    () => savedSeries.find((s) => String(s.id) === String(refValue.saved_series_id || "")) || null,
    [savedSeries, refValue.saved_series_id]
  );
  const selectedLabel = selectedSaved
    ? `${savedSeriesName(selectedSaved)} (vlastní data)`
    : sourceSelected
      ? `${sourceName(sourceSelected)} (${sourceTypeLabel(sourceSelected.source_type)})`
      : "— zdroj —";

  const toggleGroup = (key) =>
    setOpenGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const onPickSource = (src) => {
    onChange({
      ...refValue,
      source_id: String(src?.id || ""),
      indicator_id: "",
      saved_series_id: "",
      x_field: "",
      y_field: "",
      name: sourceName(src),
    });
    setPickerOpen(false);
  };

  const onPickSaved = (row) => {
    onChange({
      ...refValue,
      source_id: "",
      indicator_id: "",
      saved_series_id: String(row?.id || ""),
      x_field: "",
      y_field: "",
      name: savedSeriesName(row),
    });
    setPickerOpen(false);
  };

  return (
    <div className="space-y-2 rounded-lg border border-border/50 bg-white/80 p-2.5">
      <div className="text-[11px] font-medium text-slate-700">{label}</div>
      <label className="block text-[10px] text-slate-600">
        Datový zdroj
        <button
          type="button"
          className="mt-0.5 w-full border rounded-md px-2 py-1.5 text-xs text-left bg-white disabled:opacity-60"
          disabled={disabled}
          onClick={() => setPickerOpen((v) => !v)}
          aria-expanded={pickerOpen}
        >
          {selectedLabel}
        </button>
        {pickerOpen && !disabled ? (
          <div className="mt-1 rounded-md border border-border/70 bg-white p-2 space-y-1.5">
            <input
              type="search"
              className="w-full border rounded-md px-2 py-1 text-[11px]"
              placeholder="Hledat zdroj nebo vlastní data…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <div className="max-h-40 overflow-y-auto border border-border/50 rounded-md">
              {groups.length === 0 ? (
                <div className="p-2 text-[10px] text-slate-500">Žádná shoda.</div>
              ) : (
                groups.map((group) => {
                  const open = openGroups.has(group.key);
                  return (
                    <div key={group.key} className="border-b border-border/30 last:border-b-0">
                      <button
                        type="button"
                        className="w-full px-2 py-1 text-[10px] uppercase tracking-wide text-slate-600 bg-slate-50 text-left font-semibold"
                        onClick={() => toggleGroup(group.key)}
                      >
                        {open ? "▾" : "▸"} {group.label} ({group.items.length})
                      </button>
                      {open ? (
                        <div className="p-1 space-y-0.5">
                          {group.kind === "saved"
                            ? group.items.map((row) => (
                              <button
                                key={`saved-${row.id}`}
                                type="button"
                                className="w-full text-left px-2 py-1 rounded text-[11px] hover:bg-slate-50"
                                onClick={() => onPickSaved(row)}
                              >
                                <span className="block truncate">{savedSeriesName(row)}</span>
                                <span className="block text-[10px] text-slate-500 truncate">
                                  {String(row?.source || row?.source_type || "").trim() || "Moje datová řada"}
                                </span>
                              </button>
                            ))
                            : group.items.map((src) => (
                              <button
                                key={`src-${src.id}`}
                                type="button"
                                className="w-full text-left px-2 py-1 rounded text-[11px] hover:bg-slate-50"
                                onClick={() => onPickSource(src)}
                              >
                                <span className="block truncate">{sourceName(src)}</span>
                                <span className="block text-[10px] text-slate-500 truncate">{src.id}</span>
                              </button>
                            ))}
                        </div>
                      ) : null}
                    </div>
                  );
                })
              )}
            </div>
          </div>
        ) : null}
      </label>
      <label className="block text-[10px] text-slate-600">
        Řada (indikátor)
        <select
          className="mt-0.5 w-full border rounded-md px-2 py-1.5 text-xs"
          value={refValue.indicator_id}
          disabled={disabled || !refValue.source_id || !!refValue.saved_series_id || indicatorsLoading}
          onChange={(e) => onChange({ ...refValue, indicator_id: e.target.value })}
        >
          <option value="">
            {refValue.saved_series_id ? "Řada z vlastních dat (uložená)" : (indicatorsLoading ? "Načítám…" : "— řada —")}
          </option>
          {indicators.map((ind) => (
            <option key={String(ind.id)} value={String(ind.id)}>
              {(ind.name || ind.label || ind.id || "").toString()}
            </option>
          ))}
        </select>
      </label>
      {refValue.saved_series_id ? (
        <p className="text-[10px] text-emerald-700 leading-snug">
          Použita uložená řada z „Vlastní data“. Výpočet poběží nad tímto uloženým zdrojem.
        </p>
      ) : null}
      {!refValue.source_id ? null : !indicatorsLoading && indicators.length === 0 ? (
        <p className="text-[10px] text-amber-800 leading-snug">
          U tohoto zdroje nejsou v náhledu indikátory — zvolte jiný zdroj (např. ARAD) nebo výpočet založte ve správě dat.
        </p>
      ) : null}
    </div>
  );
}

/**
 * Zkrácený editor vlastního výpočtu pro modal „Můj dashboard“ — uloží přes POST /api/me/computed.
 */
export default function PersonalComputedInlineForm({ onCreated, disabled }) {
  const { allowed: canComposite, ready: compositeReady } = useFeatureAccess("composite_charts");
  const [sources, setSources] = useState([]);
  const [savedSeries, setSavedSeries] = useState([]);
  const [operations, setOperations] = useState([]);
  const [name, setName] = useState("");
  const [operation, setOperation] = useState("ratio");
  const [left, setLeft] = useState(() => emptyRef());
  const [right, setRight] = useState(() => emptyRef());
  const [leftInd, setLeftInd] = useState([]);
  const [rightInd, setRightInd] = useState([]);
  const [leftLoading, setLeftLoading] = useState(false);
  const [rightLoading, setRightLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let c = false;
    (async () => {
      let sl = [];
      try {
        const { data } = await api.get("/sources/catalog-stubs");
        if (!c) {
          sl = Array.isArray(data) ? data : [];
          sl.sort((a, b) =>
            String(a.name || a.title || "").localeCompare(String(b.name || b.title || ""), "cs", {
              sensitivity: "base",
            }),
          );
          setSources(sl);
        }
      } catch {
        if (!c) setSources([]);
      }
      try {
        const { data: ms } = await api.get("/my-series");
        if (!c) setSavedSeries(Array.isArray(ms) ? ms : []);
      } catch {
        if (!c) setSavedSeries([]);
      }
      let raw = [];
      try {
        const { data: ops } = await api.get("/computed/operations");
        if (!c) raw = Array.isArray(ops) ? ops : [];
      } catch {
        raw = [];
      }
      if (!c) {
        setOperations(
          raw.filter((o) => {
            const v = String(o?.value || "").trim();
            if (v === "multi") return compositeReady && canComposite;
            return Boolean(v);
          }),
        );
      }
    })();
    return () => {
      c = true;
    };
  }, [canComposite, compositeReady]);

  useEffect(() => {
    if (!operations.length) return;
    if (!operations.some((o) => o.value === operation)) {
      setOperation(operations[0].value);
    }
  }, [operations, operation]);

  useEffect(() => {
    if (!left.source_id) {
      setLeftInd([]);
      setLeftLoading(false);
      return undefined;
    }
    let cancel = false;
    setLeftLoading(true);
    (async () => {
      try {
        const { data } = await api.get(`/sources/${left.source_id}/preview`, { params: { limit: 800 } });
        if (cancel) return;
        setLeftInd(Array.isArray(data?.indicators) ? data.indicators : []);
      } catch {
        if (!cancel) setLeftInd([]);
      } finally {
        if (!cancel) setLeftLoading(false);
      }
    })();
    return () => {
      cancel = true;
    };
  }, [left.source_id]);

  useEffect(() => {
    if (!right.source_id) {
      setRightInd([]);
      setRightLoading(false);
      return undefined;
    }
    let cancel = false;
    setRightLoading(true);
    (async () => {
      try {
        const { data } = await api.get(`/sources/${right.source_id}/preview`, { params: { limit: 800 } });
        if (cancel) return;
        setRightInd(Array.isArray(data?.indicators) ? data.indicators : []);
      } catch {
        if (!cancel) setRightInd([]);
      } finally {
        if (!cancel) setRightLoading(false);
      }
    })();
    return () => {
      cancel = true;
    };
  }, [right.source_id]);

  const problems = useMemo(() => {
    const p = [];
    if (!name.trim()) p.push("název výpočtu");
    if (unarySeriesB.has(operation)) {
      if (!refOkForComputed(right)) p.push("řadu B");
    } else if (unarySeriesA.has(operation)) {
      if (!refOkForComputed(left)) p.push("řadu A");
    } else {
      if (!refOkForComputed(left)) p.push("řadu A");
      if (!refOkForComputed(right)) p.push("řadu B");
    }
    return p;
  }, [name, operation, left, right]);

  const valid = problems.length === 0;

  const submit = async () => {
    if (!valid) {
      toast.error(`Doplňte: ${problems.join(", ")}`);
      return;
    }
    setBusy(true);
    try {
      const payload = {
        name: name.trim(),
        operation,
        left: packComputedRefPayload(left),
        right: packComputedRefPayload(right),
        series: [],
        description: "",
        unit: "",
        options: {},
      };
      const { data } = await api.post("/me/computed", payload);
      if (data?.id) {
        toast.success("Výpočet uložen a vybrán.");
        onCreated?.(data);
        setName("");
        setOperation("ratio");
        setLeft(emptyRef());
        setRight(emptyRef());
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se uložit výpočet.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-3 border border-dashed border-border/70 rounded-xl p-3 bg-white/60">
      <div className="text-xs font-medium text-slate-800">Nový výpočet (přímo zde)</div>
      <p className="text-[10px] text-slate-600 leading-relaxed">
        Zvolte operaci a dvě datové řady (nebo jednu u unárních operací). Uložený výpočet se objeví v seznamu výše a
        můžete ho použít i jinde v aplikaci podle vašeho tarifu.
      </p>
      <label className="block text-[11px] text-slate-600">
        Název uloženého výpočtu
        <input
          className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
          value={name}
          disabled={disabled || busy}
          onChange={(e) => setName(e.target.value)}
          placeholder="např. Poměr úvěrů k HDP"
        />
      </label>
      <label className="block text-[11px] text-slate-600">
        Operace
        <select
          className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
          value={operation}
          disabled={disabled || busy}
          onChange={(e) => setOperation(e.target.value)}
        >
          {operations.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      {!unarySeriesB.has(operation) ? (
        <SeriesBlock
          label={unarySeriesA.has(operation) ? "Datová řada" : "Řada A"}
          refValue={left}
          onChange={setLeft}
          sources={sources}
          savedSeries={savedSeries}
          indicatorsLoading={leftLoading}
          indicators={leftInd}
          disabled={disabled || busy}
        />
      ) : null}
      {!unarySeriesA.has(operation) ? (
        <SeriesBlock
          label={unarySeriesB.has(operation) ? "Datová řada" : "Řada B"}
          refValue={right}
          onChange={setRight}
          sources={sources}
          savedSeries={savedSeries}
          indicatorsLoading={rightLoading}
          indicators={rightInd}
          disabled={disabled || busy}
        />
      ) : null}
      <button
        type="button"
        className="btn-secondary text-xs py-1.5 px-3 w-full sm:w-auto"
        disabled={disabled || busy || !valid}
        onClick={submit}
      >
        {busy ? "Ukládám…" : "Vytvořit výpočet a vybrat"}
      </button>
    </div>
  );
}
