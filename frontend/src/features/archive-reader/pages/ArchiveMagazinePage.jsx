import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { Loader2, Search, Upload } from "lucide-react";
import { toast } from "sonner";
import AppShell from "@/components/layout/AppShell";
import { ArchiveHitResultButton } from "@/components/archive/ArchiveReaderSearchWidgets";
import ArticleCoverImageField from "@/components/editor/ArticleCoverImageField";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { getIssueCoverImageUrl } from "@/lib/articleCover";
import { archiveHitIssueId, normalizeArchiveHits } from "@/lib/archiveReader";
import { useAuth } from "@/contexts/AuthContext";

const ARCHIVE_REQUEST_TIMEOUT_MS = 12000;

export default function ArchiveMagazinePage() {
  const { magazineId } = useParams();
  const navigate = useNavigate();
  const { isAdmin } = useAuth();
  // Admin spravuje čísla (nahrát/nahradit PDF) i na veřejné čtecí trase /archive/:magazineId,
  // ne jen na /admin/archive/:magazineId. Mutace na backendu stejně gate-uje AdminAccess.requireAdmin().
  const showManage = isAdmin;
  const [magazines, setMagazines] = useState([]);
  const [issues, setIssues] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");

  const [issueLabel, setIssueLabel] = useState("");
  const [issueTitle, setIssueTitle] = useState("");
  const [publishedAt, setPublishedAt] = useState("");
  const [description, setDescription] = useState("");
  const [coverImageUrl, setCoverImageUrl] = useState("");
  const [file, setFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [replacingId, setReplacingId] = useState("");
  const [savingCoverId, setSavingCoverId] = useState("");
  const coverSaveTimersRef = useRef({});

  const [searchQuery, setSearchQuery] = useState("");
  const [searching, setSearching] = useState(false);
  const [searchHits, setSearchHits] = useState([]);
  const [searchSearched, setSearchSearched] = useState(false);

  const magazine = useMemo(
    () => magazines.find((m) => String(m.id) === String(magazineId)) || null,
    [magazines, magazineId]
  );

  const load = useCallback(async ({ silent = false } = {}) => {
    if (!magazineId) return;
    if (!silent) {
      setLoading(true);
      setErr("");
    }
    try {
      const [mRes, iRes] = await Promise.all([
        api.get("/magazines", { timeout: ARCHIVE_REQUEST_TIMEOUT_MS }),
        api.get(`/magazines/${magazineId}/issues`, { timeout: ARCHIVE_REQUEST_TIMEOUT_MS }),
      ]);
      setMagazines(Array.isArray(mRes.data) ? mRes.data : []);
      setIssues(Array.isArray(iRes.data) ? iRes.data : []);
    } catch (e) {
      setErr(formatApiErrorFromAxios(e));
      if (!silent) setIssues([]);
    } finally {
      if (!silent) setLoading(false);
    }
  }, [magazineId]);

  const displayIssues = useMemo(() => {
    if (showManage) return issues;
    return issues.filter((it) => it.ingest_status === "ready");
  }, [issues, showManage]);

  const hasIndexingIssue = useMemo(
    () =>
      showManage &&
      issues.some((it) => it.ingest_status !== "ready" && it.ingest_status !== "error"),
    [issues, showManage]
  );

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!magazineId || !hasIndexingIssue) return undefined;
    const timer = setInterval(() => {
      void load({ silent: true });
    }, 3500);
    return () => clearInterval(timer);
  }, [hasIndexingIssue, load, magazineId]);

  useEffect(() => {
    if (!searchQuery.trim()) {
      setSearchHits([]);
      setSearchSearched(false);
    }
  }, [searchQuery]);

  const runMagazineSearch = async (e) => {
    e.preventDefault();
    const q = searchQuery.trim();
    if (!q || !magazineId) return;
    setSearching(true);
    setErr("");
    setSearchSearched(true);
    try {
      const { data } = await api.get(`/magazines/${magazineId}/search`, { params: { q, limit: 40 } });
      setSearchHits(normalizeArchiveHits(data?.hits));
    } catch (e2) {
      setErr(formatApiErrorFromAxios(e2));
      setSearchHits([]);
    } finally {
      setSearching(false);
    }
  };

  const jumpToSearchHit = (hit) => {
    const targetIssueId = archiveHitIssueId(hit);
    const targetPage = Number(hit?.page) || 1;
    if (!targetIssueId) return;
    navigate(`/archive/${magazineId}/${targetIssueId}`, { state: { readerPage: targetPage } });
  };

  const clearSearchResults = () => {
    setSearchHits([]);
    setSearchSearched(false);
    setSearchQuery("");
  };

  const saveIssueCover = async (issueId, url) => {
    setSavingCoverId(issueId);
    try {
      const cover = String(url || "").trim();
      await api.patch(`/magazines/issues/${issueId}`, { cover_image_url: cover || null });
      setIssues((prev) =>
        prev.map((it) => (it.id === issueId ? { ...it, cover_image_url: cover || null } : it))
      );
      toast.success("Náhled čísla byl uložen.");
    } catch (e2) {
      toast.error(formatApiErrorFromAxios(e2) || "Náhled se nepodařilo uložit.");
    } finally {
      setSavingCoverId("");
    }
  };

  const queueIssueCoverSave = (issueId, url) => {
    const timers = coverSaveTimersRef.current;
    if (timers[issueId]) window.clearTimeout(timers[issueId]);
    const trimmed = String(url || "").trim();
    if (!trimmed) {
      void saveIssueCover(issueId, "");
      return;
    }
    timers[issueId] = window.setTimeout(() => {
      delete timers[issueId];
      void saveIssueCover(issueId, trimmed);
    }, 700);
  };

  useEffect(() => {
    const timers = coverSaveTimersRef.current;
    return () => {
      Object.values(timers).forEach((timer) => window.clearTimeout(timer));
    };
  }, []);

  const uploadIssue = async (e) => {
    e.preventDefault();
    if (!magazineId || !file || !issueLabel.trim()) return;
    setUploading(true);
    setErr("");
    try {
      const fd = new FormData();
      fd.append("issue_label", issueLabel.trim());
      fd.append("title", issueTitle.trim());
      fd.append("published_at", publishedAt.trim());
      fd.append("description", description.trim());
      const cover = coverImageUrl.trim();
      if (cover) fd.append("cover_image_url", cover);
      fd.append("file", file);
      await api.post(`/magazines/${magazineId}/issues/upload`, fd, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      setIssueLabel("");
      setIssueTitle("");
      setPublishedAt("");
      setDescription("");
      setCoverImageUrl("");
      setFile(null);
      await load();
    } catch (e2) {
      setErr(formatApiErrorFromAxios(e2));
    } finally {
      setUploading(false);
    }
  };

  // Nahradí PDF u JIŽ EXISTUJÍCÍHO čísla (např. seed číslo bez souboru nebo oprava vadného PDF).
  const replaceIssueFile = async (issueId, selectedFile) => {
    if (!issueId || !selectedFile) return;
    setReplacingId(issueId);
    setErr("");
    try {
      const fd = new FormData();
      fd.append("file", selectedFile);
      await api.post(`/magazines/issues/${issueId}/file`, fd, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      toast.success("PDF nahrazeno. Probíhá indexace.");
      await load();
    } catch (e2) {
      const msg = formatApiErrorFromAxios(e2);
      setErr(msg);
      toast.error(msg || "Nahrazení PDF selhalo.");
    } finally {
      setReplacingId("");
    }
  };

  return (
    <AppShell
      title={magazine?.title || "Archiv PDF"}
      subtitle={
        showManage
          ? "Nahrávání čísel, náhledy a stav indexace."
          : magazine?.description || "Seznam čísel k prohlížení a fulltextové vyhledávání."
      }
    >
      <div className="max-w-5xl space-y-5">
        <div className="text-sm">
          <Link
            to={showManage ? "/admin/archive" : "/archive"}
            className="text-[hsl(var(--primary))] underline font-medium"
          >
            ← Zpět na přehled časopisů
          </Link>
        </div>

        <form onSubmit={runMagazineSearch} className="soft-card p-4 space-y-3">
          <div className="text-sm font-semibold text-slate-900 inline-flex items-center gap-2">
            <Search className="h-4 w-4" /> Hledat napříč čísly
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <input
              className="input h-10 flex-1 min-w-[14rem]"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Fulltext ve všech indexovaných číslech…"
            />
            <button type="submit" className="btn-primary h-10 px-4 text-sm" disabled={searching}>
              {searching ? "Hledám…" : "Najít"}
            </button>
          </div>
          {searchHits.length > 0 ? (
            <div className="rounded-lg border border-border/60 p-2 max-h-64 overflow-auto space-y-1">
              <div className="mb-1 flex items-center justify-between px-1">
                <span className="text-[11px] font-medium text-slate-500">Nalezeno: {searchHits.length}</span>
                <button
                  type="button"
                  onClick={clearSearchResults}
                  className="text-[11px] font-medium text-slate-500 hover:text-slate-800"
                >
                  Zavřít výsledky
                </button>
              </div>
              {searchHits.map((h, i) => (
                <ArchiveHitResultButton
                  key={`${h.chunk_id || i}-${archiveHitIssueId(h) || "issue"}-${h.page}`}
                  hit={h}
                  currentIssueId=""
                  onJump={jumpToSearchHit}
                  variant="inline"
                />
              ))}
            </div>
          ) : null}
          {searchSearched && !searching && searchHits.length === 0 ? (
            <p className="text-xs text-slate-500">K dotazu nebylo nic nalezeno.</p>
          ) : null}
        </form>

        {showManage ? (
          <form onSubmit={uploadIssue} className="soft-card p-4 space-y-3">
            <div className="text-sm font-semibold text-slate-900 flex items-center gap-2">
              <Upload className="h-4 w-4" /> Nahrát nové číslo (PDF)
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              <input
                className="input w-full"
                placeholder="Označení čísla (např. 05/2026)"
                value={issueLabel}
                onChange={(e) => setIssueLabel(e.target.value)}
                required
              />
              <input
                className="input w-full"
                placeholder="Název čísla (volitelně)"
                value={issueTitle}
                onChange={(e) => setIssueTitle(e.target.value)}
              />
              <input
                className="input w-full"
                placeholder="Datum vydání (volitelně)"
                value={publishedAt}
                onChange={(e) => setPublishedAt(e.target.value)}
              />
              <div className="rounded-lg border border-border/70 bg-white px-3 py-2.5">
                <label className="text-[10px] uppercase tracking-[0.14em] font-medium text-slate-500">
                  PDF soubor
                </label>
                <div className="mt-2 flex items-center gap-2">
                  <label
                    htmlFor="magazine-issue-file-input"
                    className="inline-flex h-9 cursor-pointer items-center rounded-md border border-[hsl(var(--primary)/0.35)] bg-[hsl(var(--primary-soft)/0.55)] px-3 text-xs font-semibold text-[hsl(var(--primary-deep))] hover:bg-[hsl(var(--primary-soft))]"
                  >
                    Zvolit PDF
                  </label>
                  <span className="min-w-0 truncate text-xs text-slate-600">
                    {file ? file.name : "Nevybrán žádný soubor"}
                  </span>
                </div>
                <input
                  id="magazine-issue-file-input"
                  type="file"
                  accept="application/pdf,.pdf"
                  className="sr-only"
                  onChange={(e) => setFile(e.target.files?.[0] || null)}
                />
              </div>
            </div>
            <textarea
              className="input w-full min-h-[84px]"
              placeholder="Popis čísla (volitelně)"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
            <ArticleCoverImageField value={coverImageUrl} onChange={setCoverImageUrl} />
            <button type="submit" className="btn-primary h-10 px-4 text-sm" disabled={uploading}>
              {uploading ? "Nahrávám…" : "Nahrát a indexovat"}
            </button>
          </form>
        ) : null}

        {err ? (
          <div className="chip-rose rounded-md p-3 text-sm">
            <div>{err}</div>
            <button type="button" onClick={() => load()} className="mt-2 btn-ghost h-8 px-3 text-xs">
              Zkusit načíst znovu
            </button>
          </div>
        ) : null}

        {loading ? (
          <div className="text-sm text-slate-600">Načítám čísla…</div>
        ) : displayIssues.length === 0 ? (
          <div className="soft-card p-6 text-sm text-slate-600">
            {showManage ? "Zatím nejsou nahraná žádná čísla." : "Zatím nejsou k dispozici žádná čísla k čtení."}
          </div>
        ) : (
          <div className="space-y-3">
            {displayIssues.map((it) => {
              const coverUrl = getIssueCoverImageUrl(it);
              return (
                <div key={it.id} className="soft-card overflow-hidden">
                  <Link
                    to={`/archive/${magazineId}/${it.id}`}
                    className="block hover:border-[hsl(var(--primary)/0.45)] transition"
                  >
                    {coverUrl ? (
                      <div className="h-40 w-full overflow-hidden border-b border-slate-100 bg-slate-100 sm:h-44">
                        <img
                          src={coverUrl}
                          alt=""
                          className="h-full w-full object-cover"
                          loading="lazy"
                          referrerPolicy="no-referrer"
                        />
                      </div>
                    ) : null}
                    <div className="p-4">
                      <div className="flex items-center justify-between gap-3">
                        <div className="min-w-0">
                          <div className="font-semibold text-slate-900 truncate">{it.issue_label}</div>
                          <div className="text-xs text-slate-500 mt-1 truncate">
                            {it.title || it.description || "Bez titulku"}
                          </div>
                        </div>
                        {showManage ? (
                          <span
                            className={`inline-flex items-center gap-1.5 text-[10px] uppercase tracking-wide px-2 py-1 rounded-md border shrink-0 ${
                              it.ingest_status === "ready"
                                ? "border-emerald-300 bg-emerald-50 text-emerald-900"
                                : it.ingest_status === "error"
                                  ? "border-rose-300 bg-rose-50 text-rose-900"
                                  : "border-amber-300 bg-amber-50 text-amber-900"
                            }`}
                          >
                            {it.ingest_status !== "ready" && it.ingest_status !== "error" ? (
                              <Loader2 className="h-3 w-3 animate-spin" />
                            ) : null}
                            {it.ingest_status === "ready"
                              ? "Připraveno"
                              : it.ingest_status === "error"
                                ? "Chyba"
                                : "Indexuje se"}
                          </span>
                        ) : null}
                      </div>
                      <div className="text-xs text-slate-500 mt-2">
                        {showManage
                          ? `Stran: ${it.page_count || 0} · chunků: ${it.chunk_count || 0}`
                          : it.page_count
                            ? `${it.page_count} stran`
                            : null}
                        {it.published_at ? ` · vydání: ${it.published_at}` : ""}
                      </div>
                      {showManage && it.ingest_status === "error" && it.ingest_error ? (
                        <div className="mt-2 text-xs text-rose-700 line-clamp-2">
                          Chyba indexace: {it.ingest_error}
                        </div>
                      ) : null}
                    </div>
                  </Link>
                  {showManage ? (
                    <div className="border-t border-slate-100 bg-slate-50/60 px-4 py-3 space-y-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <label
                          className={`inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 ${
                            replacingId === it.id ? "pointer-events-none opacity-60" : ""
                          }`}
                        >
                          {replacingId === it.id ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : (
                            <Upload className="h-3.5 w-3.5" />
                          )}
                          {replacingId === it.id ? "Nahrávám…" : "Nahradit PDF"}
                          <input
                            type="file"
                            accept="application/pdf,.pdf"
                            className="sr-only"
                            disabled={replacingId === it.id}
                            onChange={(e) => {
                              const f = e.target.files?.[0] || null;
                              e.target.value = "";
                              if (f) void replaceIssueFile(it.id, f);
                            }}
                          />
                        </label>
                        <span className="text-[11px] text-slate-400">Nahradí PDF soubor u tohoto čísla</span>
                      </div>
                      <ArticleCoverImageField
                        value={coverUrl}
                        onChange={(url) => {
                          if (savingCoverId === it.id) return;
                          queueIssueCoverSave(it.id, url);
                        }}
                      />
                    </div>
                  ) : null}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </AppShell>
  );
}
