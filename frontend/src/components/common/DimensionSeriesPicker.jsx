import { useMemo } from "react";

/**
 * Výběr „podle čeho graf rozdělit a které hodnoty ukázat".
 *
 * Proč to je zvlášť: v aplikaci už byly čtyři ručně psané varianty téhož výběru (chipy zemí,
 * zaškrtávátka řad na třech místech v náhledu, řádky srovnání, mřížka ukazatelů) a pátá kopie
 * do formuláře widgetu by rozdíly mezi cestami jen prohloubila. Formulář „+ Přidat datový
 * widget" totiž u Eurostatu/ČSÚ nenabízel výběr dimenze vůbec, takže z něj nešel vyrobit
 * sloupec za každou zemi — přestože náhled v katalogu to uměl.
 *
 * Prázdný `selectedValues` znamená „prvních `defaultCount`", stejně jako v katalogovém
 * náhledu. První zaškrtnutí ten výchozí stav zhmotní do konkrétního seznamu.
 */
export default function DimensionSeriesPicker({
  dimensions = [],
  dimension = "",
  onDimensionChange,
  selectedValues = [],
  onSelectedValuesChange,
  defaultCount = 12,
  disabled = false,
  label = "Porovnat podle",
  noneLabel = "— jedna řada —",
  hint = "",
}) {
  const active = useMemo(
    () => dimensions.find((dim) => dim.field === dimension) || null,
    [dimensions, dimension]
  );
  const values = active?.values || [];

  // Co je fakticky zaškrtnuté — s prázdným výběrem to je výchozích `defaultCount`.
  const effective = useMemo(
    () => (selectedValues.length > 0 ? selectedValues : values.slice(0, defaultCount).map((v) => v.code)),
    [selectedValues, values, defaultCount]
  );
  const effectiveSet = useMemo(() => new Set(effective), [effective]);

  if (dimensions.length === 0) return null;

  const toggle = (code, checked) => {
    const next = checked ? [...effective, code] : effective.filter((c) => c !== code);
    onSelectedValuesChange?.(next);
  };

  return (
    <div className="space-y-1.5">
      <label className="block text-[11px] text-slate-600">
        {label}
        <select
          value={dimension}
          disabled={disabled}
          onChange={(e) => {
            onDimensionChange?.(e.target.value);
            // Hodnoty patří k dimenzi — po přepnutí by starý výběr ukazoval na kódy,
            // které v nové dimenzi neexistují.
            onSelectedValuesChange?.([]);
          }}
          className="mt-0.5 h-7 w-full border border-border rounded-md px-1.5 text-xs bg-card"
        >
          <option value="">{noneLabel}</option>
          {dimensions.map((dim) => (
            <option key={dim.field} value={dim.field}>
              {dim.label} ({dim.values.length})
            </option>
          ))}
        </select>
      </label>

      {active && values.length > 0 ? (
        <div className="border-t border-border/60 pt-1.5">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-[10px] font-semibold text-slate-500 uppercase tracking-wide">
              Hodnoty ({effective.length}/{values.length})
            </span>
            <button
              type="button"
              disabled={disabled}
              onClick={() => onSelectedValuesChange?.(values.map((v) => v.code))}
              className="text-[10px] text-blue-600 hover:underline disabled:opacity-50"
            >
              Vše
            </button>
            <button
              type="button"
              disabled={disabled}
              onClick={() => onSelectedValuesChange?.([])}
              className="text-[10px] text-slate-500 hover:underline disabled:opacity-50"
            >
              Výchozí
            </button>
          </div>
          <div className="flex flex-wrap gap-1 max-h-28 overflow-y-auto">
            {values.map((value) => (
              <label
                key={value.code}
                className="flex items-center gap-1 cursor-pointer bg-card rounded px-1.5 py-0.5 border border-border/60 text-[10px]"
              >
                <input
                  type="checkbox"
                  disabled={disabled}
                  checked={effectiveSet.has(value.code)}
                  onChange={(ev) => toggle(value.code, ev.target.checked)}
                  className="w-3 h-3"
                />
                <span className="max-w-[150px] truncate" title={`${value.label} · ${value.code}`}>
                  {value.label}
                </span>
              </label>
            ))}
          </div>
          {hint ? <p className="mt-1 text-[10px] text-slate-500">{hint}</p> : null}
        </div>
      ) : null}
    </div>
  );
}
