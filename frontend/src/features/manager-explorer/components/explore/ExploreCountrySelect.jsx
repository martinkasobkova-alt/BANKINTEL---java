import { useMemo } from "react";
import { groupExploreCountryOptions } from "@/lib/exploreGeoOptions";

/**
 * Dropdown zemí pro Manager Explorer — seskupeno podle kontinentu (optgroup bold).
 */
export default function ExploreCountrySelect({
  countryGroups,
  countries,
  value = "",
  onChange,
  placeholder = "Vyberte zemi…",
  disabled = false,
  className = "",
  id,
}) {
  const groups = useMemo(() => {
    if (Array.isArray(countryGroups) && countryGroups.length) {
      return countryGroups.filter((g) => (g.countries || []).length);
    }
    return groupExploreCountryOptions(countries || []);
  }, [countryGroups, countries]);

  return (
    <select
      id={id}
      className={
        className ||
        "w-full h-10 px-3 rounded-xl border border-border bg-card text-sm [&_optgroup]:font-semibold [&_optgroup]:text-foreground"
      }
      value={value}
      disabled={disabled || !groups.length}
      onChange={(e) => onChange?.(e.target.value)}
    >
      <option value="">{placeholder}</option>
      {groups.map((group) => (
        <optgroup key={group.id} label={group.label_cs}>
          {(group.countries || []).map((country) => (
            <option key={country.code} value={country.code}>
              {country.label_cs}
            </option>
          ))}
        </optgroup>
      ))}
    </select>
  );
}
