import React, { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  ChevronRight,
  ChevronDown,
  Search,
  RefreshCw,
  Plus,
  Check,
  ArrowLeft,
  Folder,
  FileBarChart2,
  ExternalLink,
} from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import {
  flattenCatalogCategories,
  buildPathIndex,
  buildFilteredPaths,
  parseSearchKeywords,
  MAX_CATALOG_FILTER_ROWS,
  browseCategoryCountNode,
} from "@/lib/catalogTree";
import { LoadingBlock, LoadingSpinner } from "@/components/ui/loading";

export default function CsuCatalogPage() {
  const nav = useNavigate();
  const [tree, setTree] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [search, setSearch] = useState("");
  const [existing, setExisting] = useState(new Set());
  const [adding, setAdding] = useState({});
  const [openPaths, setOpenPaths] = useState(new Set());

  const load = async (force = false) => {
    if (force) setRefreshing(true);
    else setLoading(true);
    try {
      const url = force ? "/csu/catalog/refresh" : "/csu/catalog";
      const resp = force ? await api.post(url) : await api.get(url);
      setTree(resp.data);
      // Open just the top-level topic groups by default — 21 groups stay readable.
      const top = new Set((resp.data.categories || []).map((c) => c.path));
      setOpenPaths(top);
      const { data: srcs } = await api.get("/sources/catalog-stubs");
      // Existing CSU sources are matched by selection code.
      setExisting(
        new Set(
          (srcs || [])
            .filter((s) => s.source_type === "csu")
            .map(
              (s) =>
                s.csu_selection_code ||
                ((s.endpoint || "").split("/").pop() || "").trim()
            )
            .filter(Boolean)
        )
      );
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setLoading(false);
    setRefreshing(false);
  };

  useEffect(() => {
    load(false);
  }, []);

  const allRows = useMemo(() => (tree ? flattenCatalogCategories(tree.categories || []) : []), [tree]);
  const rowIndex = useMemo(() => buildPathIndex(allRows), [allRows]);
  const keywords = useMemo(() => parseSearchKeywords(search), [search]);
  const filteredPaths = useMemo(
    () => buildFilteredPaths(allRows, rowIndex, keywords),
    [allRows, rowIndex, keywords]
  );

  const visibleRows = useMemo(() => {
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
      let parentSegments =
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

  const toggle = (path) => {
    setOpenPaths((s) => {
      const n = new Set(s);
      if (n.has(path)) n.delete(path);
      else n.add(path);
      return n;
    });
  };

  const addSource = async (set_id) => {
    setAdding((a) => ({ ...a, [set_id]: true }));
    try {
      const { data } = await api.post("/csu/catalog/add-source", { set_id });
      toast.success(`Přidáno: ${data.name}`);
      setExisting((s) => new Set([...s, String(set_id)]));
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setAdding((a) => ({ ...a, [set_id]: false }));
  };

  return (
    <AppShell
      title="Katalog ČSÚ"
      subtitle={`Procházejte všech ${
        tree?.total_sets?.toLocaleString("cs-CZ") || "—"
      } předdefinovaných výběrů z databáze ČSÚ DataStat · jedním klikem přidáte jako zdroj`}
      actions={
        <div className="flex items-center gap-2">
          <a
            href="https://data.csu.gov.cz/"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
            title="Otevřít originální databázi ČSÚ DataStat v novém okně"
          >
            <ExternalLink className="h-4 w-4" /> data.csu.gov.cz
          </a>
          <button
            onClick={() => nav("/sources")}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
          >
            <ArrowLeft className="h-4 w-4" /> Zpět na zdroje
          </button>
          <button
            onClick={() => load(true)}
            disabled={refreshing}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))] disabled:opacity-50"
          >
            <RefreshCw className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} />
            {refreshing ? "Stahuji…" : "Obnovit z ČSÚ"}
          </button>
        </div>
      }
    >
      <div className="mb-4 max-w-xl relative">
        <Search className="h-4 w-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
        <input
          type="text"
          className="w-full h-10 pl-9 pr-3 border border-[hsl(var(--border)/0.75)] rounded-xl text-sm bg-card shadow-sm"
          placeholder="Hledat výběr (např. „inflace“, „mzdy“, kód CEN0101HT01)…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          data-testid="csu-catalog-search"
        />
      </div>

      {loading ? (
        <LoadingBlock
          label="Načítám katalog ČSÚ…"
          sublabel="Přibližně 1 500 výběrů — prvotní načtení zabere zpravidla 3–10 sekund."
          minHeightClass="min-h-[180px]"
          showSkeletonLines
          skeletonRows={5}
        />
      ) : visibleRows.length === 0 ? (
        <div className="border border-dashed border-border bg-muted/25 rounded-2xl p-12 text-center text-sm text-muted-foreground font-mono">
          {search ? "Žádný výsledek pro zadaný filtr." : "Katalog je prázdný."}
        </div>
      ) : (
        <div className="bg-card border border-border rounded-2xl overflow-hidden shadow-sm">
          {visibleRows.map((row) => {
            if (row.kind === "cat") {
              const isOpen = openPaths.has(row.path) || Boolean(filteredPaths);
              const browseCountLabel = browseCategoryCountNode(row, isOpen);
              return (
                <button
                  key={row.path}
                  onClick={() => toggle(row.path)}
                  className={`w-full flex items-center gap-2 text-left px-4 py-2.5 hover:bg-muted/50 border-t border-border/60 ${
                    row.depth === 0 ? "bg-muted/30 font-medium" : ""
                  }`}
                  style={{ paddingLeft: `${16 + row.depth * 20}px` }}
                >
                  {isOpen ? (
                    <ChevronDown className="h-4 w-4 text-muted-foreground shrink-0" />
                  ) : (
                    <ChevronRight className="h-4 w-4 text-muted-foreground shrink-0" />
                  )}
                  <Folder className="h-4 w-4 text-muted-foreground shrink-0" />
                  <span className="text-sm text-foreground truncate">{row.name}</span>
                  {browseCountLabel != null ? (
                    <span className="text-[10px] uppercase tracking-wider text-muted-foreground ml-auto pr-2">
                      {browseCountLabel}
                    </span>
                  ) : null}
                </button>
              );
            }
            const isAdded = existing.has(String(row.set_id));
            const isAdding = Boolean(adding[row.set_id]);
            const meta = [];
            if (row.period) meta.push(`období: ${row.period}`);
            if (row.territory) meta.push(`oblast: ${row.territory}`);
            return (
              <div
                key={row.path}
                className="flex items-center gap-3 py-2 pr-3 border-t border-border/60 hover:bg-muted/50"
                style={{ paddingLeft: `${36 + row.depth * 20}px` }}
              >
                <FileBarChart2 className="h-4 w-4 text-muted-foreground shrink-0" />
                <div className="min-w-0 flex-1">
                  <div className="text-sm text-foreground truncate" title={row.name}>
                    {row.name}
                  </div>
                  <div className="text-[11px] text-muted-foreground font-mono">
                    kód: {row.set_id}
                    {meta.length ? `  ·  ${meta.join("  ·  ")}` : ""}
                  </div>
                </div>
                {isAdded ? (
                  <span
                    data-testid={`csu-added-${row.set_id}`}
                    className="flex items-center gap-1.5 px-2.5 h-7 text-[11px] uppercase tracking-wider rounded-lg chip-mint border border-[hsl(215_45%_82%)]"
                  >
                    <Check className="h-3 w-3" /> přidáno
                  </span>
                ) : (
                  <button
                    onClick={() => addSource(row.set_id)}
                    disabled={isAdding}
                    data-testid={`csu-add-${row.set_id}`}
                    className="btn-mint flex items-center gap-1.5 px-3 h-7 text-xs disabled:opacity-50"
                  >
                    {isAdding ? (
                      <LoadingSpinner suppressAria size="xs" aria-label="" />
                    ) : (
                      <Plus className="h-3 w-3" />
                    )}
                    Přidat
                  </button>
                )}
              </div>
            );
          })}

          {filteredPaths && allRows.filter((r) => filteredPaths.has(r.path)).length > 800 && (
            <div className="px-4 py-3 text-[11px] text-muted-foreground border-t border-border/60 font-mono">
              Zobrazuji prvních 800 výsledků — zpřesněte hledání pro užší výběr.
            </div>
          )}
        </div>
      )}
    </AppShell>
  );
}
