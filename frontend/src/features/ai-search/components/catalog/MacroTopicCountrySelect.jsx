import React, { useMemo } from "react";
import { countryDisplayLabel, isAbbreviationCountry } from "@/lib/macroGeoLabels";

const CONTINENT_LABELS = {
  aggregates: "Agregáty a regiony",
  europe: "Evropa",
  north_america: "Severní Amerika",
  south_america: "Jižní Amerika",
  asia: "Asie",
  africa: "Afrika",
  oceania: "Australie a Oceánie",
  other: "Ostatní",
};

const CONTINENT_ORDER = [
  "aggregates",
  "europe",
  "north_america",
  "south_america",
  "asia",
  "africa",
  "oceania",
  "other",
];

function buildGroupsFromFlat(countries, comparisonPanelSize) {
  const buckets = new Map();
  for (const raw of countries || []) {
    if (isAbbreviationCountry(raw)) continue;
    const label_cs = countryDisplayLabel(raw);
    if (!label_cs) continue;
    const cid = String(raw.continent_id || "other").trim() || "other";
    if (!buckets.has(cid)) buckets.set(cid, []);
    buckets.get(cid).push({
      ...raw,
      label_cs,
      comparison_topic_count: raw.comparison_topic_count ?? comparisonPanelSize ?? raw.topic_count,
    });
  }
  return CONTINENT_ORDER.map((id) => ({
    id,
    label_cs: CONTINENT_LABELS[id] || id,
    countries: (buckets.get(id) || []).sort((a, b) =>
      String(a.label_cs || "").localeCompare(String(b.label_cs || ""), "cs", { sensitivity: "base" })
    ),
  })).filter((g) => g.countries.length);
}

/**
 * Dropdown zemí seskupených podle kontinentu (data z /catalog/macro-topics).
 * V UI vždy celé české názvy — ISO kód jen interně v option value.
 */
export default function MacroTopicCountrySelect({
  countryGroups,
  countries,
  comparisonPanelSize,
  value = "",
  onChange,
  placeholder = "Vyberte zemi nebo region…",
  disabled = false,
  className = "",
  id,
}) {
  const groups = useMemo(() => {
    if (Array.isArray(countryGroups) && countryGroups.length) {
      return countryGroups
        .map((g) => ({
          ...g,
          countries: (g.countries || [])
            .filter((c) => !isAbbreviationCountry(c))
            .map((c) => ({ ...c, label_cs: countryDisplayLabel(c) })),
        }))
        .filter((g) => g.countries.length);
    }
    return buildGroupsFromFlat(countries, comparisonPanelSize);
  }, [countryGroups, countries, comparisonPanelSize]);

  const panelSize = comparisonPanelSize ?? groups[0]?.countries?.[0]?.comparison_topic_count;

  return (
    <select
      id={id}
      className={
        className ||
        "w-full max-w-md h-10 px-3 rounded-xl border border-border bg-card text-sm text-foreground [&_optgroup]:font-semibold"
      }
      value={value}
      disabled={disabled || !groups.length}
      onChange={(e) => {
        const code = e.target.value;
        if (!code) {
          onChange?.(null);
          return;
        }
        const found = groups.flatMap((g) => g.countries || []).find((c) => c.code === code);
        if (!found) {
          onChange?.(null);
          return;
        }
        onChange?.(found);
      }}
    >
      <option value="">{placeholder}</option>
      {groups.map((group) => (
        <optgroup key={group.id} label={group.label_cs}>
          {(group.countries || []).map((country) => (
            <option key={country.code} value={country.code}>
              {countryDisplayLabel(country)}
              {panelSize
                ? ` · ${panelSize} srovnatelných ukazatelů`
                : country.comparison_topic_count
                  ? ` · ${country.comparison_topic_count} ukazatelů`
                  : ""}
            </option>
          ))}
        </optgroup>
      ))}
    </select>
  );
}
