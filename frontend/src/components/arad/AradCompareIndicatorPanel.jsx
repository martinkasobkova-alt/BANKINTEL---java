import React, { useCallback, useEffect, useMemo, useState } from "react";
import { ChevronRight, ChevronDown, Folder, FileBarChart2, Search, ArrowLeft } from "lucide-react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { LoadingSpinner } from "@/components/ui/loading";
import {
  flattenCatalogCategories,
  buildPathIndex,
  buildFilteredPaths,
  parseSearchKeywords,
  MAX_CATALOG_FILTER_ROWS,
} from "@/lib/catalogTree";

/**
 * Výběr ARAD indikátoru pro porovnávací řadu: plochý sloučený seznam nebo strom katalogu sestav.
 */
export default function AradCompareIndicatorPanel({
  row,
  mergedIndicators,
  mergedLoading,
  onChange,
  /** např. `input w-full text-xs` (modal) nebo `input` (editor) */
  inputClassName = "input w-full text-xs",
  listMaxHeight = 320,
  treeMaxHeight = 240,
  /** Volitelný nadpis nad přepínačem (v editoru už je label z `Field`) */
  heading = null,
  /** Widget z katalogu ARAD — načte ukazatele sestavy přímo z ČNB (bez UUID zdroje v aplikaci). */
  fixedSetId = "",
  fixedSetName = "",
}) {
  const [pickerMode, setPickerMode] = useState("list");
  const [filter, setFilter] = useState("");
  const sourceId = row.source_id || "";

  const filtered = mergedIndicators.filter((i) => {
    if (sourceId && i.source_id !== sourceId) return false;
    if (!filter) return true;
    const q = filter.toLowerCase();
    const src = (i._aradSourceLabel || "").toLowerCase();
    return (
      (i.indicator_id || "").toLowerCase().includes(q) ||
      (i.name || "").toLowerCase().includes(q) ||
      src.includes(q)
    );
  });

  const catalogSetId = String(fixedSetId || "").trim();

  const [tree, setTree] = useState(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [treeErr, setTreeErr] = useState("");
  const [treeSearch, setTreeSearch] = useState("");
  const [openPaths, setOpenPaths] = useState(() => new Set());
  const [treeBrowse, setTreeBrowse] = useState(null);
  const [setPack, setSetPack] = useState(null);
  const [setLoading, setSetLoading] = useState(false);
  const [setErr, setSetErr] = useState("");

  const selectedVybrano = useMemo(() => {
    const iid = String(row.indicator_id || "").trim();
    if (!iid) return null;
    const sid = String(row.source_id || "").trim();
    if (catalogSetId && setPack) {
      const hit = (Array.isArray(setPack.indicators) ? setPack.indicators : []).find(
        (it) => String(it.indicator_id || "").trim() === iid
      );
      if (hit) {
        const name = (hit.name || "").trim();
        const unit = (hit.unit || "").trim();
        return {
          title: name ? (unit ? `${name} (${unit})` : name) : iid,
          subtitle: fixedSetName || catalogSetId || null,
        };
      }
    }
    const hit = mergedIndicators.find((i) => {
      if (String(i.indicator_id || "").trim() !== iid) return false;
      if (!sid) return true;
      return String(i.source_id || "").trim() === sid;
    });
    const name = (hit?.name || "").trim();
    const unit = (hit?.unit || "").trim();
    const sourceLabel = (hit?._aradSourceLabel || "").trim();
    const title =
      name ? (unit ? `${name} (${unit})` : name) : iid;
    return { title, subtitle: sourceLabel || null };
  }, [row.indicator_id, row.source_id, mergedIndicators, catalogSetId, setPack, fixedSetName]);

  const loadFixedSet = useCallback(async () => {
    if (!catalogSetId) return;
    setSetErr("");
    setSetLoading(true);
    try {
      const { data } = await api.get("/arad/catalog/set-indicators", { params: { set_id: catalogSetId } });
      setSetPack(data);
    } catch (e) {
      setSetPack(null);
      setSetErr(formatApiErrorFromAxios(e) || "Ukazatele se nepodařilo načíst.");
    } finally {
      setSetLoading(false);
    }
  }, [catalogSetId]);

  useEffect(() => {
    if (!catalogSetId) return;
    void loadFixedSet();
  }, [catalogSetId, loadFixedSet]);

  const loadTree = useCallback(async () => {
    setTreeLoading(true);
    setTreeErr("");
    try {
      const { data } = await api.get("/arad/catalog");
      setTree(data);
      const top = new Set((data.categories || []).map((c) => c.path));
      setOpenPaths(top);
    } catch (e) {
      setTree(null);
      setTreeErr(formatApiErrorFromAxios(e) || "Katalog se nepodařilo načíst.");
    } finally {
      setTreeLoading(false);
    }
  }, []);

  useEffect(() => {
    if (pickerMode !== "tree") return;
    if (tree || treeLoading) return;
    void loadTree();
  }, [pickerMode, tree, treeLoading, loadTree]);

  useEffect(() => {
    if (catalogSetId) return;
    if (mergedLoading) return;
    if (mergedIndicators.length > 0) return;
    setPickerMode("tree");
  }, [catalogSetId, mergedLoading, mergedIndicators.length]);

  useEffect(() => {
    if (catalogSetId) return;
    if (pickerMode !== "tree") {
      setTreeBrowse(null);
      setSetPack(null);
      setSetErr("");
    }
  }, [pickerMode, catalogSetId]);

  const allRows = useMemo(() => (tree ? flattenCatalogCategories(tree.categories || []) : []), [tree]);
  const rowIndex = useMemo(() => buildPathIndex(allRows), [allRows]);
  const keywords = useMemo(() => parseSearchKeywords(treeSearch), [treeSearch]);
  const filteredPaths = useMemo(
    () => buildFilteredPaths(allRows, rowIndex, keywords),
    [allRows, rowIndex, keywords]
  );

  const visibleTreeRows = useMemo(() => {
    if (!allRows.length) return [];
    if (filteredPaths) {
      return allRows.filter((r) => filteredPaths.has(r.path)).slice(0, MAX_CATALOG_FILTER_ROWS);
    }
    const result = [];
    for (const r of allRows) {
      if (r.depth === 0) {
        result.push(r);
        continue;
      }
      const parentSegments =
        r.kind === "set" ? r.parentPath.split(" > ") : r.path.split(" > ").slice(0, -1);
      let allOpen = true;
      const acc = [];
      for (const seg of parentSegments) {
        acc.push(seg);
        if (!openPaths.has(acc.join(" > "))) {
          allOpen = false;
          break;
        }
      }
      if (allOpen) result.push(r);
    }
    return result;
  }, [allRows, openPaths, filteredPaths]);

  const togglePath = (path) => {
    setOpenPaths((s) => {
      const n = new Set(s);
      if (n.has(path)) n.delete(path);
      else n.add(path);
      return n;
    });
  };

  const openSet = async (setRow) => {
    const sid = String(setRow.set_id || "").trim();
    if (!sid) return;
    setTreeBrowse({ set_id: sid, name: (setRow.name || "").trim() || `Sestava ${sid}` });
    setSetPack(null);
    setSetErr("");
    setSetLoading(true);
    try {
      const { data } = await api.get("/arad/catalog/set-indicators", { params: { set_id: sid } });
      setSetPack(data);
    } catch (e) {
      setSetPack(null);
      setSetErr(formatApiErrorFromAxios(e) || "Ukazatele se nepodařilo načíst.");
    } finally {
      setSetLoading(false);
    }
  };

  const indFilter = catalogSetId
    ? (filter || "").trim().toLowerCase()
    : pickerMode === "tree" && setPack
      ? (treeSearch || "").trim().toLowerCase()
      : "";
  const filteredSetIndicators = useMemo(() => {
    const list = Array.isArray(setPack?.indicators) ? setPack.indicators : [];
    if (!indFilter) return list;
    return list.filter((it) => {
      const id = String(it.indicator_id || "").toLowerCase();
      const nm = String(it.name || "").toLowerCase();
      return id.includes(indFilter) || nm.includes(indFilter);
    });
  }, [setPack, indFilter]);

  if (catalogSetId) {
    return (
      <div className="space-y-1.5">
        {heading ? (
          <div className="text-[10px] font-medium text-slate-600">{heading}</div>
        ) : null}
        <div className="text-[10px] text-slate-700">
          <span className="font-medium">{fixedSetName || "ARAD sestava"}</span>
          <span className="ml-1 font-mono text-slate-500">set_id {catalogSetId}</span>
        </div>
        <p className="text-[9px] text-slate-600 bg-slate-50 border border-border/60 rounded px-2 py-1 leading-snug">
          Ukazatele načítáme přímo z katalogu ČNB — sestava nemusí být mezi vašimi synchronizovanými zdroji.
        </p>
        <input
          className={`${inputClassName} mb-1`}
          placeholder="Filtr podle kódu nebo názvu…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          disabled={setLoading}
          aria-label="Filtrovat ukazatele"
        />
        <div
          className="border border-border rounded-sm bg-white overflow-y-auto relative"
          style={{ maxHeight: listMaxHeight, minHeight: 120 }}
        >
          {setLoading && (
            <div className="absolute inset-0 bg-white/70 flex items-center justify-center z-10">
              <LoadingSpinner suppressAria size="xs" aria-label="" />
            </div>
          )}
          {setErr ? <div className="px-2 py-3 text-[10px] text-rose-700">{setErr}</div> : null}
          {!setLoading && !setErr && filteredSetIndicators.length === 0 ? (
            <div className="px-2 py-3 text-[10px] text-slate-500 text-center">Žádné ukazatele.</div>
          ) : null}
          {!setLoading &&
            !setErr &&
            filteredSetIndicators.map((it) => {
              const iid = String(it.indicator_id || "").trim();
              const sel = String(row.indicator_id || "").trim() === iid;
              return (
                <button
                  type="button"
                  key={iid}
                  onClick={() =>
                    onChange({
                      indicator_id: iid,
                      label: String(it.name || "").trim(),
                      source_id: String(setPack?.source_id || "").trim() || undefined,
                    })
                  }
                  className={`w-full text-left px-2 py-1.5 border-b border-border/40 text-[10px] leading-snug flex gap-1.5 items-start ${
                    sel ? "bg-emerald-50 font-medium" : "hover:bg-slate-50 text-slate-800"
                  }`}
                >
                  <span className="shrink-0 w-6 text-center tabular-nums text-slate-500">[{it.frequency_code || "?"}]</span>
                  <span className="shrink-0 w-[5.5rem] tabular-nums font-mono text-[9px]">{iid}</span>
                  <span className="flex-1 min-w-0 break-words">{it.name || "(bez názvu)"}</span>
                </button>
              );
            })}
        </div>
        {row.indicator_id && selectedVybrano ? (
          <div className="rounded-md border border-emerald-200 bg-emerald-50/90 px-2 py-1.5 text-[10px] text-emerald-950">
            <span className="font-medium text-emerald-900">Vybráno:</span>{" "}
            <span className="font-medium break-words">{selectedVybrano.title}</span>
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <div className="space-y-1.5">
      {heading ? (
        <div className="text-[10px] font-medium text-slate-600">{heading}</div>
      ) : null}
      <div className="flex flex-wrap gap-1.5">
        <button
          type="button"
          onClick={() => setPickerMode("list")}
          className={`rounded-md px-2 py-1 text-[10px] font-medium border ${
            pickerMode === "list"
              ? "border-emerald-300 bg-emerald-50 text-emerald-950"
              : "border-border/70 bg-white text-slate-600 hover:bg-slate-50"
          }`}
        >
          Rychlý seznam
        </button>
        <button
          type="button"
          onClick={() => setPickerMode("tree")}
          className={`rounded-md px-2 py-1 text-[10px] font-medium border ${
            pickerMode === "tree"
              ? "border-emerald-300 bg-emerald-50 text-emerald-950"
              : "border-border/70 bg-white text-slate-600 hover:bg-slate-50"
          }`}
        >
          Strom sestav
        </button>
      </div>

      {pickerMode === "list" ? (
        <div className="block text-[10px] text-slate-600 space-y-0.5">
          <input
            className={`${inputClassName} mb-1`}
            placeholder="Filtr podle kódu, názvu nebo názvu zdroje…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            disabled={mergedLoading || mergedIndicators.length === 0}
            aria-label="Filtrovat ukazatele"
          />
          <div
            className="border border-border rounded-sm bg-white overflow-y-auto relative"
            style={{ maxHeight: listMaxHeight, minHeight: 120 }}
          >
            {mergedLoading && (
              <div className="absolute inset-0 bg-white/70 flex items-center justify-center z-10">
                <LoadingSpinner suppressAria size="xs" aria-label="" />
              </div>
            )}
            {!mergedLoading && mergedIndicators.length === 0 ? (
              <div className="px-2 py-3 text-[10px] text-slate-500 text-center leading-snug">
                Žádné uložené ukazatele. V administraci u ARAD zdroje spusťte synchronizaci nebo obnovení metadat řad.
              </div>
            ) : filtered.length === 0 ? (
              <div className="px-2 py-3 text-[10px] text-slate-400 text-center font-mono">— nic nevyhovuje filtru —</div>
            ) : (
              filtered.map((i) => {
                const sel = i.indicator_id === row.indicator_id && i.source_id === row.source_id;
                return (
                  <button
                    type="button"
                    key={`${i.source_id}:${i.indicator_id}`}
                    onClick={() =>
                      onChange({
                        source_id: i.source_id,
                        indicator_id: i.indicator_id,
                      })
                    }
                    className={`w-full text-left px-2 py-1.5 border-b border-border/40 text-[10px] leading-snug flex gap-1.5 items-start ${
                      sel ? "bg-emerald-50 font-medium" : "hover:bg-slate-50 text-slate-800"
                    }`}
                  >
                    <span className="shrink-0 w-6 text-center tabular-nums text-slate-500">[{i.frequency_code || "?"}]</span>
                    <span className="shrink-0 w-[5.5rem] tabular-nums font-mono text-[9px]">{i.indicator_id}</span>
                    <span className="flex-1 min-w-0 break-words">
                      {i.name || "(bez názvu)"}
                      {i._aradSourceLabel ? (
                        <span className="block text-[9px] text-slate-500 mt-0.5 truncate" title={i._aradSourceLabel}>
                          {i._aradSourceLabel}
                        </span>
                      ) : null}
                    </span>
                  </button>
                );
              })
            )}
          </div>
        </div>
      ) : (
        <>
          <p className="text-[9px] text-slate-500 leading-snug">
            Stejný katalog jako v sekci Katalog ARAD. Po výběru sestavy načteme ukazatele z ČNB (vyžaduje uložený ARAD klíč
            nebo proměnnou prostředí). Pokud sestava ještě není mezi zdroji, řadu uložíte až po jejím přidání.
          </p>
          {!treeBrowse ? (
            <>
              <div className="relative">
                <Search className="h-3.5 w-3.5 absolute left-2 top-1/2 -translate-y-1/2 text-slate-400" />
                <input
                  type="text"
                  className={`${inputClassName} pl-7`}
                  placeholder="Hledat v katalogu (název, set_id, cesta…)…"
                  value={treeSearch}
                  onChange={(e) => setTreeSearch(e.target.value)}
                  disabled={treeLoading}
                />
              </div>
              <div
                className="border border-border rounded-sm bg-white overflow-y-auto relative text-[10px]"
                style={{ maxHeight: treeMaxHeight, minHeight: 140 }}
              >
                {treeLoading && (
                  <div className="absolute inset-0 bg-white/80 flex items-center justify-center z-10">
                    <LoadingSpinner suppressAria size="xs" aria-label="" />
                  </div>
                )}
                {treeErr ? <div className="px-2 py-3 text-rose-700">{treeErr}</div> : null}
                {!treeLoading && !treeErr && visibleTreeRows.length === 0 ? (
                  <div className="px-2 py-3 text-slate-500 text-center">Žádné řádky.</div>
                ) : null}
                {!treeLoading &&
                  !treeErr &&
                  visibleTreeRows.map((r) => {
                    if (r.kind === "cat") {
                      const isOpen = openPaths.has(r.path) || Boolean(filteredPaths);
                      return (
                        <button
                          key={r.path}
                          type="button"
                          onClick={() => togglePath(r.path)}
                          className={`w-full flex items-center gap-1.5 text-left px-2 py-1.5 border-b border-border/40 hover:bg-slate-50 ${
                            r.depth === 0 ? "bg-slate-50/80 font-medium" : ""
                          }`}
                          style={{ paddingLeft: `${8 + r.depth * 14}px` }}
                        >
                          {isOpen ? (
                            <ChevronDown className="h-3.5 w-3.5 text-slate-500 shrink-0" />
                          ) : (
                            <ChevronRight className="h-3.5 w-3.5 text-slate-500 shrink-0" />
                          )}
                          <Folder className="h-3.5 w-3.5 text-slate-500 shrink-0" />
                          <span className="truncate text-slate-800">{r.name}</span>
                          <span className="ml-auto shrink-0 text-[9px] uppercase text-slate-400">{r.count}</span>
                        </button>
                      );
                    }
                    return (
                      <button
                        key={r.path}
                        type="button"
                        onClick={() => void openSet(r)}
                        className="w-full flex items-center gap-1.5 text-left px-2 py-1.5 border-b border-border/40 hover:bg-emerald-50/50"
                        style={{ paddingLeft: `${22 + r.depth * 14}px` }}
                      >
                        <FileBarChart2 className="h-3.5 w-3.5 text-slate-500 shrink-0" />
                        <span className="min-w-0 flex-1 truncate text-slate-800">{r.name}</span>
                        <span className="shrink-0 font-mono text-[9px] text-slate-500">{r.set_id}</span>
                      </button>
                    );
                  })}
              </div>
            </>
          ) : (
            <>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setTreeBrowse(null);
                    setSetPack(null);
                    setSetErr("");
                  }}
                  className="inline-flex items-center gap-1 rounded-md border border-border/70 px-2 py-1 text-[10px] text-slate-700 hover:bg-slate-50"
                >
                  <ArrowLeft className="h-3.5 w-3.5" />
                  Zpět
                </button>
                <div className="min-w-0 flex-1">
                  <div className="text-[10px] font-medium text-slate-800 truncate" title={treeBrowse.name}>
                    {treeBrowse.name}
                  </div>
                  <div className="text-[9px] text-slate-500 font-mono">set_id {treeBrowse.set_id}</div>
                </div>
              </div>
              {setPack && !setPack.has_source ? (
                <p className="text-[9px] text-slate-600 bg-slate-50 border border-border/60 rounded px-2 py-1 leading-snug">
                  Sestava zatím není mezi synchronizovanými zdroji — pro srovnání v tomto grafu ji můžete vybrat, data
                  načteme z katalogu ČNB.
                </p>
              ) : null}
              <div
                className="border border-border rounded-sm bg-white overflow-y-auto relative"
                style={{ maxHeight: listMaxHeight, minHeight: 120 }}
              >
                {setLoading && (
                  <div className="absolute inset-0 bg-white/70 flex items-center justify-center z-10">
                    <LoadingSpinner suppressAria size="xs" aria-label="" />
                  </div>
                )}
                {setErr ? <div className="px-2 py-3 text-[10px] text-rose-700">{setErr}</div> : null}
                {!setLoading && !setErr && setPack && filteredSetIndicators.length === 0 ? (
                  <div className="px-2 py-3 text-[10px] text-slate-500 text-center">Žádné ukazatele.</div>
                ) : null}
                {!setLoading &&
                  !setErr &&
                  setPack &&
                  filteredSetIndicators.map((it) => {
                    const sid = String(setPack.source_id || "").trim();
                    const iid = String(it.indicator_id || "").trim();
                    const sel =
                      String(row.indicator_id || "").trim() === iid &&
                      (!sid || String(row.source_id || "").trim() === sid);
                    return (
                      <button
                        type="button"
                        key={iid}
                        onClick={() =>
                          onChange({
                            ...(sid ? { source_id: sid } : {}),
                            indicator_id: iid,
                            label: String(it.name || "").trim(),
                          })
                        }
                        className={`w-full text-left px-2 py-1.5 border-b border-border/40 text-[10px] leading-snug flex gap-1.5 items-start ${
                          sel ? "bg-emerald-50 font-medium" : "hover:bg-slate-50 text-slate-800"
                        }`}
                      >
                        <span className="shrink-0 w-6 text-center tabular-nums text-slate-500">[{it.frequency_code || "?"}]</span>
                        <span className="shrink-0 w-[5.5rem] tabular-nums font-mono text-[9px]">{iid}</span>
                        <span className="flex-1 min-w-0 break-words">{it.name || "(bez názvu)"}</span>
                      </button>
                    );
                  })}
              </div>
            </>
          )}
        </>
      )}

      {row.indicator_id && selectedVybrano ? (
        <div className="rounded-md border border-emerald-200 bg-emerald-50/90 px-2 py-1.5 text-[10px] text-emerald-950">
          <span className="font-medium text-emerald-900">Vybráno:</span>{" "}
          <span className="font-medium break-words">{selectedVybrano.title}</span>
          {selectedVybrano.subtitle ? (
            <div className="mt-0.5 text-[9px] text-emerald-800/90 leading-snug">{selectedVybrano.subtitle}</div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
