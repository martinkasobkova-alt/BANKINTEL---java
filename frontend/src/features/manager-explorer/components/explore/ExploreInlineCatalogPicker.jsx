import React, { useEffect, useMemo, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  FileBarChart2,
  Folder,
  Loader2,
  X as XIcon,
} from "lucide-react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { CATALOGS, WB_DEFAULT_COUNTRY } from "@/lib/catalogDefinitions";
import {
  buildFilteredPaths,
  buildPathIndex,
  flattenCatalogCategoriesBestEffort,
  MAX_CATALOG_FILTER_ROWS,
  parseSearchKeywords,
} from "@/lib/catalogTree";

function useDebounced(value, ms) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

function treeRowToRef(row, catalogDef, wbCountry) {
  const qp = {};
  if (catalogDef?.needsCountry && wbCountry) {
    qp.country = wbCountry;
  }
  return {
    catalog_id: catalogDef.id,
    source_type: String(catalogDef.sourceType || catalogDef.id).trim().toLowerCase(),
    set_id: String(row.set_id || "").trim(),
    title: String(row.name || row.set_id || "").trim(),
    query_params: qp,
  };
}

/**
 * Inline procházení katalogu (stejný princip jako při přidávání grafu na dashboard).
 */
export default function ExploreInlineCatalogPicker({
  catalogId,
  onCatalogIdChange,
  allowedCatalogIds = null,
  pickedKeys,
  onToggleRef,
  seriesRefKey,
  onClose,
}) {
  const catalogOptions = useMemo(() => {
    const allow = Array.isArray(allowedCatalogIds) && allowedCatalogIds.length
      ? new Set(allowedCatalogIds.map((x) => String(x).toLowerCase()))
      : null;
    return CATALOGS.filter((c) => !allow || allow.has(String(c.id).toLowerCase()));
  }, [allowedCatalogIds]);

  const catalogDef = useMemo(
    () => catalogOptions.find((c) => c.id === catalogId) || catalogOptions[0] || CATALOGS[0],
    [catalogId, catalogOptions],
  );

  const [wbCountries, setWbCountries] = useState([]);
  const [wbCountry, setWbCountry] = useState(WB_DEFAULT_COUNTRY);
  const [catalogRows, setCatalogRows] = useState([]);
  const [openPaths, setOpenPaths] = useState(() => new Set());
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");
  const [q, setQ] = useState("");
  const dq = useDebounced(q, 200);

  useEffect(() => {
    if (!catalogDef?.catalogPath) return undefined;
    let cancel = false;
    (async () => {
      setLoading(true);
      setErr("");
      setCatalogRows([]);
      try {
        const { data } = await api.get(catalogDef.catalogPath);
        if (cancel) return;
        if (catalogDef.needsCountry) {
          setWbCountries(Array.isArray(data?.countries) ? data.countries : []);
        } else {
          setWbCountries([]);
        }
        const flat = await flattenCatalogCategoriesBestEffort(data?.categories || []);
        const allRows = flat || [];
        setCatalogRows(allRows);
        const topPaths = new Set(
          allRows.filter((r) => r.kind === "cat" && r.depth === 0).map((r) => r.path),
        );
        setOpenPaths(topPaths);
      } catch (e) {
        if (!cancel) setErr(formatApiErrorFromAxios(e) || e?.message || "Chyba načtení katalogu");
      } finally {
        if (!cancel) setLoading(false);
      }
    })();
    return () => {
      cancel = true;
    };
  }, [catalogDef?.catalogPath, catalogDef?.id, catalogDef?.needsCountry]);

  const keywords = useMemo(() => parseSearchKeywords(dq), [dq]);
  const rowIndex = useMemo(() => buildPathIndex(catalogRows), [catalogRows]);
  const filteredPaths = useMemo(
    () => buildFilteredPaths(catalogRows, rowIndex, keywords),
    [catalogRows, rowIndex, keywords],
  );

  const visibleRows = useMemo(() => {
    if (!catalogRows.length) return [];
    if (filteredPaths) {
      return catalogRows.filter((r) => filteredPaths.has(r.path)).slice(0, MAX_CATALOG_FILTER_ROWS);
    }
    const result = [];
    for (const r of catalogRows) {
      if (r.depth === 0) {
        result.push(r);
        continue;
      }
      const segs =
        r.kind === "set" ? r.parentPath.split(" > ") : r.path.split(" > ").slice(0, -1);
      let allOpen = true;
      const acc = [];
      for (const seg of segs) {
        acc.push(seg);
        if (!openPaths.has(acc.join(" > "))) {
          allOpen = false;
          break;
        }
      }
      if (allOpen) result.push(r);
    }
    return result;
  }, [catalogRows, openPaths, filteredPaths]);

  const toggleFolder = (path) => {
    setOpenPaths((s) => {
      const n = new Set(s);
      if (n.has(path)) n.delete(path);
      else n.add(path);
      return n;
    });
  };

  return (
    <div className="rounded-lg border border-teal-200/80 bg-card p-2.5 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11px] font-medium text-teal-900">Procházet katalog zde</span>
        {onClose ? (
          <button
            type="button"
            className="h-7 w-7 inline-flex items-center justify-center rounded-md border border-border/70 hover:bg-muted/50"
            onClick={onClose}
            aria-label="Zavřít procházení katalogu"
          >
            <XIcon className="h-3.5 w-3.5" />
          </button>
        ) : null}
      </div>

      <div className="flex flex-wrap gap-2">
        <select
          className="h-8 rounded-md border border-border bg-background text-xs px-2 min-w-[8rem]"
          value={catalogDef?.id || ""}
          onChange={(e) => onCatalogIdChange?.(e.target.value)}
        >
          {catalogOptions.map((c) => (
            <option key={c.id} value={c.id}>
              {c.label}
            </option>
          ))}
        </select>
        {catalogDef?.needsCountry ? (
          <select
            className="h-8 rounded-md border border-border bg-background text-xs px-2 min-w-[8rem]"
            value={wbCountry}
            onChange={(e) => setWbCountry(e.target.value)}
          >
            {wbCountries.length === 0 ? (
              <option value={WB_DEFAULT_COUNTRY}>{WB_DEFAULT_COUNTRY}</option>
            ) : (
              wbCountries.map((c) => (
                <option key={c.code} value={c.code}>
                  {(c.label && `${c.label} (${c.code})`) || c.code}
                </option>
              ))
            )}
          </select>
        ) : null}
      </div>

      <input
        type="search"
        className="w-full h-8 rounded-md border border-border bg-background text-xs px-2"
        placeholder="Hledat v názvu nebo cestě…"
        value={q}
        onChange={(e) => setQ(e.target.value)}
      />

      {loading ? (
        <div className="flex items-center gap-2 text-xs text-muted-foreground py-4 justify-center">
          <Loader2 className="h-4 w-4 animate-spin" />
          Načítám katalog…
        </div>
      ) : null}
      {err ? <p className="text-[11px] text-rose-800">{err}</p> : null}

      {!loading && !err ? (
        <div className="max-h-52 overflow-y-auto border border-border/60 rounded-md bg-background text-xs">
          {visibleRows.length === 0 ? (
            <div className="p-3 text-muted-foreground">{dq ? "Žádná shoda." : "Katalog je prázdný."}</div>
          ) : (
            visibleRows.map((row) => {
              if (row.kind === "cat") {
                const isOpen = openPaths.has(row.path) || Boolean(filteredPaths);
                return (
                  <button
                    key={row.path}
                    type="button"
                    onClick={() => toggleFolder(row.path)}
                    className={`w-full flex items-center gap-1.5 text-left py-1.5 border-b border-border/30 hover:bg-muted/40 ${
                      row.depth === 0 ? "bg-muted/50 font-semibold" : "font-medium"
                    }`}
                    style={{ paddingLeft: `${8 + row.depth * 14}px` }}
                  >
                    {isOpen ? (
                      <ChevronDown className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                    ) : (
                      <ChevronRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                    )}
                    <Folder className="h-3.5 w-3.5 shrink-0 text-amber-600/80" />
                    <span className="truncate">{row.name}</span>
                  </button>
                );
              }
              if (row.kind !== "set" || !row.set_id) return null;
              const ref = treeRowToRef(row, catalogDef, wbCountry);
              const key = seriesRefKey(ref);
              const checked = pickedKeys?.has(key);
              return (
                <label
                  key={row.path}
                  className={`flex items-start gap-2 py-1.5 border-b border-border/30 last:border-0 cursor-pointer hover:bg-muted/30 ${
                    checked ? "bg-teal-50/80" : ""
                  }`}
                  style={{ paddingLeft: `${10 + row.depth * 14}px` }}
                >
                  <input
                    type="checkbox"
                    className="mt-0.5"
                    checked={Boolean(checked)}
                    onChange={() => onToggleRef?.(ref)}
                  />
                  <FileBarChart2 className="h-3.5 w-3.5 shrink-0 text-muted-foreground mt-0.5" />
                  <span className="min-w-0">
                    <span className="font-medium text-slate-800 block truncate">{ref.title}</span>
                    <span className="text-[10px] text-muted-foreground">{ref.source_type}</span>
                  </span>
                </label>
              );
            })
          )}
        </div>
      ) : null}
      <p className="text-[10px] text-muted-foreground leading-snug">
        Zaškrtněte řady, které chcete přidat k doplňujícímu dotazu. Stejný katalog jako při přidání grafu na dashboard.
      </p>
    </div>
  );
}
