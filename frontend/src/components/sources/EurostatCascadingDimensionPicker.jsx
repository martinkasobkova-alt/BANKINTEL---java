import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChevronDown, RefreshCw, RotateCcw, Sparkles } from "lucide-react";
import {
  buildAvailabilityBannerLines,
  fetchEurostatCascadeState,
  fetchEurostatDimensionDefaults,
  incompleteAvailabilityNotice,
  shouldShowIncompleteAvailabilityWarning,
} from "@/lib/eurostatDimensionAvailability";
import { formatUserChoiceOptionLabel } from "@/lib/sourcePreviewUserChoiceDimensions";

const TIER_LABELS = {
  primary: "Země a oblast",
  key: "Klíčové dimenze",
  advanced: "Pokročilé dimenze",
};

function normalizeSelected(input) {
  const out = {};
  if (!input || typeof input !== "object") return out;
  for (const [key, raw] of Object.entries(input)) {
    const field = String(key || "").trim();
    if (!field) continue;
    const val = Array.isArray(raw) ? String(raw[0] ?? "").trim() : String(raw ?? "").trim();
    if (val) out[field] = val;
  }
  return out;
}

function dimensionSelectTestId(field) {
  const key = String(field || "").trim().toLowerCase();
  return key ? `eurostat-dim-select-${key}` : "eurostat-dim-select";
}

function SearchableDimensionSelect({ dim, value, onChange, disabled, testId }) {
  const [query, setQuery] = useState("");
  const options = Array.isArray(dim?.options) ? dim.options : [];
  const useSearch = Boolean(dim?.searchable) || options.length >= 8;
  const filtered = useMemo(() => {
    const q = String(query || "").trim().toLowerCase();
    if (!q) return options;
    return options.filter((opt) => {
      const code = String(opt?.code || "").toLowerCase();
      const label = String(opt?.label || "").toLowerCase();
      return code.includes(q) || label.includes(q);
    });
  }, [options, query]);

  if (!useSearch) {
    return (
      <select
        data-testid={testId}
        aria-label={dim.label || dim.field}
        value={value || ""}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        className="h-8 w-full text-[12px] border border-border/70 rounded-md px-2 bg-white"
      >
        <option value="">Vyberte…</option>
        {options.map((opt) => (
          <option key={`${dim.field}-${opt.code}`} value={opt.code}>
            {formatUserChoiceOptionLabel({ value: opt.code, label: opt.label })}
          </option>
        ))}
      </select>
    );
  }

  return (
    <div className="space-y-1">
      <input
        type="search"
        value={query}
        disabled={disabled}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={`Hledat v ${dim.label || dim.field}…`}
        className="h-7 w-full text-[11px] border border-border/60 rounded-md px-2 bg-white"
      />
      <select
        data-testid={testId}
        aria-label={dim.label || dim.field}
        value={value || ""}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        className="h-8 w-full text-[12px] border border-border/70 rounded-md px-2 bg-white"
      >
        <option value="">{filtered.length ? "Vyberte…" : "Žádná hodnota"}</option>
        {filtered.map((opt) => (
          <option key={`${dim.field}-${opt.code}`} value={opt.code}>
            {formatUserChoiceOptionLabel({ value: opt.code, label: opt.label })}
          </option>
        ))}
      </select>
      {filtered.length < options.length ? (
        <p className="text-[10px] text-slate-500">
          Zobrazeno {filtered.length} z {options.length} hodnot
        </p>
      ) : null}
    </div>
  );
}

export default function EurostatCascadingDimensionPicker({
  datasetId,
  selectedDimensions,
  userQuery = "",
  geoIntent = null,
  onApply,
  disabled = false,
  className = "",
}) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [cascade, setCascade] = useState(null);
  const [localSelected, setLocalSelected] = useState(() => normalizeSelected(selectedDimensions));
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [invalidNotice, setInvalidNotice] = useState("");
  const [incompleteNotice, setIncompleteNotice] = useState("");
  const [defaultHint, setDefaultHint] = useState("");
  const requestSeq = useRef(0);

  const refreshCascade = useCallback(
    async (nextSelected) => {
      const sid = String(datasetId || "").trim();
      if (!sid) return;
      const seq = ++requestSeq.current;
      setLoading(true);
      setError("");
      try {
        const data = await fetchEurostatCascadeState({
          datasetId: sid,
          selectedDimensions: nextSelected,
          userQuery,
          geoIntent,
        });
        if (seq !== requestSeq.current) return;
        setCascade(data);
        const merged = { ...nextSelected };
        const removed = Array.isArray(data?.invalid_removed) ? data.invalid_removed : [];
        if (removed.length) {
          for (const row of removed) {
            if (row?.field) delete merged[row.field];
          }
          const labels = removed
            .map((r) => `${r.field}=${r.value}`)
            .filter(Boolean)
            .join(", ");
          setInvalidNotice(
            labels
              ? `Dříve zvolená hodnota už není dostupná (${labels}) — byla odstraněna.`
              : "Některé dříve zvolené hodnoty už nejsou dostupné — byly odstraněny.",
          );
        } else {
          setInvalidNotice("");
        }
        const synced = { ...merged };
        for (const dim of data?.dimensions || []) {
          const field = String(dim?.field || "").trim();
          if (!field) continue;
          if (!synced[field] && dim.selected) synced[field] = String(dim.selected);
        }
        setLocalSelected(synced);
        if (shouldShowIncompleteAvailabilityWarning(data)) {
          setIncompleteNotice(incompleteAvailabilityNotice(data));
        } else {
          setIncompleteNotice("");
        }
      } catch (err) {
        if (seq !== requestSeq.current) return;
        setError(err?.response?.data?.detail || err?.message || "Nepodařilo se načíst dostupné dimenze.");
      } finally {
        if (seq === requestSeq.current) setLoading(false);
      }
    },
    [datasetId, userQuery, geoIntent],
  );

  useEffect(() => {
    const next = normalizeSelected(selectedDimensions);
    setLocalSelected(next);
    refreshCascade(next);
  }, [datasetId, selectedDimensions, refreshCascade]);

  const dimensionGroups = useMemo(() => {
    const dims = Array.isArray(cascade?.dimensions) ? cascade.dimensions : [];
    const groups = { primary: [], key: [], advanced: [] };
    for (const dim of dims) {
      const field = String(dim?.field || "").trim();
      const hasValue = Boolean(String(dim?.selected ?? localSelected[field] ?? "").trim());
      // Povinná dimenze bez vyplněné hodnoty nesmí skončit schovaná v "Pokročilé dimenze"
      // (sbalené ve výchozím stavu) — uživatel pak nikdy nezjistí, proč se náhled nenačte
      // (viz "House price index - country weights": statinfo je required, ale bez výchozí
      // hodnoty a klasifikované jako advanced — dead-end bez zjevné příčiny).
      const isUnfilledRequired = Boolean(dim?.required) && !hasValue;
      const tier =
        dim?.tier === "primary" || dim?.tier === "key"
          ? dim.tier
          : isUnfilledRequired
            ? "key"
            : "advanced";
      groups[tier].push(dim);
    }
    return groups;
  }, [cascade, localSelected]);

  const orderedVisibleDims = useMemo(() => {
    const out = [...dimensionGroups.primary, ...dimensionGroups.key];
    if (showAdvanced) out.push(...dimensionGroups.advanced);
    return out;
  }, [dimensionGroups, showAdvanced]);

  const lastPrimaryKey = useMemo(() => {
    const keys = orderedVisibleDims.map((d) => d.field).filter(Boolean);
    return keys.length ? keys[keys.length - 1] : "";
  }, [orderedVisibleDims]);

  const handleDimensionChange = (field, value) => {
    const trimmed = String(value ?? "").trim();
    const next = { ...localSelected };
    if (trimmed) next[field] = trimmed;
    else delete next[field];

    const fieldIdx = orderedVisibleDims.findIndex((d) => d.field === field);
    if (fieldIdx >= 0) {
      for (const dim of orderedVisibleDims.slice(fieldIdx + 1)) {
        delete next[dim.field];
      }
    }
    setLocalSelected(next);
    refreshCascade(next);
    if (trimmed && onApply) onApply(next);
  };

  const applyRecommended = async () => {
    const sid = String(datasetId || "").trim();
    if (!sid) return;
    setLoading(true);
    setError("");
    try {
      const data = await fetchEurostatDimensionDefaults({
        datasetId: sid,
        userQuery,
        geoIntent,
      });
      const selection = normalizeSelected(data?.selectedDimensions || data?.selection || {});
      setLocalSelected(selection);
      setDefaultHint(
        data?.reason
          ? String(data.reason)
          : "Vybráno podle nejběžnější dostupné kombinace pro tento dataset.",
      );
      await refreshCascade(selection);
      if (selection && onApply) onApply(selection);
    } catch (err) {
      setError(err?.response?.data?.detail || err?.message || "Doporučený výběr se nepodařilo načíst.");
    } finally {
      setLoading(false);
    }
  };

  const resetFilters = () => {
    const empty = {};
    setLocalSelected(empty);
    setInvalidNotice("");
    setIncompleteNotice("");
    setDefaultHint("");
    refreshCascade(empty);
    onApply?.(empty);
  };

  const clearLastDimension = () => {
    if (!lastPrimaryKey) return;
    const next = { ...localSelected };
    delete next[lastPrimaryKey];
    setLocalSelected(next);
    refreshCascade(next);
    onApply?.(next);
  };

  const notice = "Zobrazeny jsou pouze hodnoty dostupné pro aktuální výběr.";
  const emptyCombination = Boolean(cascade?.empty_combination);
  const showIncomplete = Boolean(incompleteNotice) || shouldShowIncompleteAvailabilityWarning(cascade);
  const availabilityBannerLines = useMemo(() => buildAvailabilityBannerLines(cascade), [cascade]);
  const unfilledRequiredDims = useMemo(
    () =>
      [...dimensionGroups.primary, ...dimensionGroups.key, ...dimensionGroups.advanced].filter(
        (dim) => Boolean(dim?.required) && !String(dim?.selected ?? localSelected[dim?.field] ?? "").trim(),
      ),
    [dimensionGroups, localSelected],
  );

  const renderGroup = (tier, dims) => {
    if (!dims.length) return null;
    return (
      <div key={tier} className="space-y-2">
        <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate-600">
          {TIER_LABELS[tier] || tier}
        </div>
        {dims.map((dim) => (
          <label key={dim.field} className="block space-y-1">
            <span className="text-[11px] font-medium text-slate-700">
              {dim.label || dim.field}
              {dim.required ? <span className="text-amber-700 ml-1">*</span> : null}
              <span className="ml-1 text-[10px] font-mono text-slate-400">
                ({dim.available_count ?? dim.options?.length ?? 0})
              </span>
            </span>
            <SearchableDimensionSelect
              dim={dim}
              testId={dimensionSelectTestId(dim.field)}
              value={localSelected[dim.field] || dim.selected || ""}
              disabled={disabled || loading}
              onChange={(val) => handleDimensionChange(dim.field, val)}
            />
          </label>
        ))}
      </div>
    );
  };

  return (
    <div className={`space-y-3 ${className}`.trim()}>
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="text-sm font-semibold text-amber-950">Výběr dimenzí datasetu</div>
          <p className="mt-1 text-[11px] text-slate-600 leading-snug">{notice}</p>
        </div>
        {loading ? (
          <RefreshCw className="h-4 w-4 text-slate-400 animate-spin shrink-0 mt-0.5" aria-hidden />
        ) : null}
      </div>

      {invalidNotice ? (
        <div className="text-[11px] text-amber-900 bg-white/70 border border-amber-200 rounded-md px-2 py-1.5">
          {invalidNotice}
        </div>
      ) : null}

      {availabilityBannerLines.length ? (
        <div
          data-testid="eurostat-availability-banner"
          className="text-[11px] text-slate-700 bg-slate-50/90 border border-slate-200/90 rounded-md px-2.5 py-2 space-y-0.5 leading-snug"
        >
          {availabilityBannerLines.map((line) => (
            <p key={line}>{line}</p>
          ))}
        </div>
      ) : null}

      {unfilledRequiredDims.length ? (
        <div
          data-testid="eurostat-required-dimension-notice"
          className="text-[11px] text-amber-950 bg-amber-50/90 border border-amber-300 rounded-md px-2.5 py-2 leading-snug"
        >
          Náhled se nenačte, dokud nevyberete: {unfilledRequiredDims.map((d) => d.label || d.field).join(", ")}.
        </div>
      ) : null}

      {showIncomplete ? (
        <div className="text-[11px] text-slate-700 bg-slate-50 border border-slate-200 rounded-md px-2 py-1.5">
          {incompleteNotice ||
            cascade?.incomplete_notice_cs ||
            "Dostupné hodnoty jsou ověřené, ale výběr může být u tohoto velkého datasetu neúplný."}
        </div>
      ) : null}

      {defaultHint ? (
        <div className="text-[11px] text-blue-900 bg-blue-50/70 border border-blue-200 rounded-md px-2 py-1.5">
          <span className="font-medium">Doporučený výběr:</span> {defaultHint}
        </div>
      ) : null}

      {error ? (
        <div className="text-[11px] text-red-700 bg-red-50 border border-red-200 rounded-md px-2 py-1.5">
          {error}
        </div>
      ) : null}

      {emptyCombination ? (
        <div className="rounded-lg border border-amber-300 bg-white/80 p-3 space-y-2">
          <div className="text-[12px] text-amber-950 font-medium">
            Pro tuto kombinaci nejsou dostupná žádná data.
          </div>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={disabled || loading || !lastPrimaryKey}
              onClick={clearLastDimension}
              className="h-7 px-2.5 text-[11px] rounded-md border border-amber-300 bg-white hover:bg-amber-50 disabled:opacity-50"
            >
              Změnit poslední dimenzi
            </button>
            <button
              type="button"
              disabled={disabled || loading}
              onClick={resetFilters}
              className="h-7 px-2.5 text-[11px] rounded-md border border-amber-300 bg-white hover:bg-amber-50 disabled:opacity-50 inline-flex items-center gap-1"
            >
              <RotateCcw className="h-3 w-3" aria-hidden />
              Resetovat filtry
            </button>
            <button
              type="button"
              disabled={disabled || loading}
              onClick={applyRecommended}
              className="h-7 px-2.5 text-[11px] rounded-md bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 inline-flex items-center gap-1"
            >
              <Sparkles className="h-3 w-3" aria-hidden />
              Doporučený výběr
            </button>
          </div>
        </div>
      ) : null}

      <div className="space-y-3">
        {renderGroup("primary", dimensionGroups.primary)}
        {renderGroup("key", dimensionGroups.key)}
      </div>

      {dimensionGroups.advanced.length ? (
        <details
          className="rounded-lg border border-slate-200 bg-white/60"
          open={showAdvanced}
          onToggle={(e) => setShowAdvanced(e.currentTarget.open)}
        >
          <summary className="cursor-pointer list-none px-3 py-2 text-[11px] font-medium text-slate-700 flex items-center gap-1">
            <ChevronDown className={`h-3.5 w-3.5 transition-transform ${showAdvanced ? "rotate-180" : ""}`} />
            Pokročilé dimenze ({dimensionGroups.advanced.length})
          </summary>
          <div className="px-3 pb-3 space-y-2 border-t border-slate-100 pt-2">
            {renderGroup("advanced", dimensionGroups.advanced)}
          </div>
        </details>
      ) : null}

      <div className="flex flex-wrap items-center gap-2 pt-1">
        <div className="flex flex-col gap-0.5">
          <button
            type="button"
            disabled={disabled || loading}
            onClick={applyRecommended}
            className="h-7 px-2.5 text-[11px] rounded-md border border-border/70 bg-white hover:bg-slate-50 disabled:opacity-50 inline-flex items-center gap-1"
          >
            <Sparkles className="h-3 w-3" aria-hidden />
            Doporučený výběr
          </button>
          <span className="text-[10px] text-slate-500 leading-snug">
            Vybráno podle nejběžnější dostupné kombinace pro tento dataset.
          </span>
        </div>
        <button
          type="button"
          disabled={disabled || loading || !Object.keys(localSelected).length}
          onClick={resetFilters}
          className="h-7 px-2.5 text-[11px] rounded-md border border-border/70 bg-white hover:bg-slate-50 disabled:opacity-50"
        >
          Resetovat
        </button>
      </div>
    </div>
  );
}
