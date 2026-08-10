import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { Search, ChevronLeft, ChevronRight, Maximize2, Minimize2, Plus, Minus, X, Link2, PanelRightClose, PanelRightOpen, MousePointer2, Volume2, Pause, Play, Square, Loader2 } from "lucide-react";
import { toast } from "sonner";
import AppShell from "@/components/layout/AppShell";
import { useAuth } from "@/contexts/AuthContext";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import ArchiveReaderAside from "@/components/archive/ArchiveReaderAside";
import ArchivePdfPageLinksBar from "@/components/archive/ArchivePdfPageLinksBar";
import ArchivePdfPane from "@/components/archive/ArchivePdfPane";
import { getArchivePdfPageText, preloadArchivePdfDocument } from "@/components/archive/ArchivePdfJsViewer";
import ArchiveInlineChartPanel from "@/components/archive/ArchiveInlineChartPanel";
import { useArchiveReaderPan } from "@/components/archive/useArchiveReaderPan";
import { archiveHitIssueId, normalizeArchiveHits } from "@/lib/archiveReader";
import {
  ARCHIVE_SEARCH_SCOPE_ISSUE,
  ARCHIVE_SEARCH_SCOPE_MAGAZINE,
  ArchiveHitResultButton,
  ArchiveSearchScopeToggle,
} from "@/components/archive/ArchiveReaderSearchWidgets";

const PDF_PAGE_ASPECT_RATIO = 1.42;
const DESKTOP_READER_CHROME_HEIGHT = 168;
const FULLSCREEN_READER_CHROME_HEIGHT = 56;
const SPREAD_MIN_VIEWPORT_WIDTH = 1024;
const SPREAD_PAGE_GAP = 16;

const normalizeArchiveAiQuery = (value) =>
  String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();

const looksLikeArchiveAiQuestion = (value) => {
  const q = normalizeArchiveAiQuery(value);
  return (
    q.includes("o cem") ||
    q.includes("shrn") ||
    q.includes("souhrn") ||
    q.includes("tema") ||
    q.includes("co resi") ||
    q.includes("co se pise") ||
    q.includes("vysvetli") ||
    q.includes("porovnej") ||
    q.includes("?")
  );
};

export default function ArchiveIssueReaderPage() {
  const { magazineId, issueId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const { isAdmin } = useAuth();
  const [issue, setIssue] = useState(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");
  const [readerFullscreen, setReaderFullscreen] = useState(false);
  const [isMobile, setIsMobile] = useState(() => (typeof window !== "undefined" ? window.innerWidth < 768 : false));
  const [mobileSearchOpen, setMobileSearchOpen] = useState(false);
  const [mobileReaderMode, setMobileReaderMode] = useState(true);
  const [mobilePanel, setMobilePanel] = useState("reader");
  const [asideCollapsed, setAsideCollapsed] = useState(false);
  const [zoom, setZoom] = useState(() => (typeof window !== "undefined" && window.innerWidth < 768 ? "page-width" : "page-fit"));
  const pdfContainerRef = useRef(null);
  const zoomAnchorRef = useRef(null);
  const [pdfContainerWidth, setPdfContainerWidth] = useState(0);
  const [pdfContainerHeight, setPdfContainerHeight] = useState(0);
  const [viewportWidth, setViewportWidth] = useState(() => (typeof window !== "undefined" ? window.innerWidth : 0));
  const [viewportHeight, setViewportHeight] = useState(() => (typeof window !== "undefined" ? window.innerHeight : 0));

  const [page, setPage] = useState(1);
  const [docQuery, setDocQuery] = useState("");
  const [docSearching, setDocSearching] = useState(false);
  const [docHits, setDocHits] = useState([]);
  const [searchScope, setSearchScope] = useState(ARCHIVE_SEARCH_SCOPE_ISSUE);

  const [asideTab, setAsideTab] = useState("search");
  const [linkDraft, setLinkDraft] = useState(null);
  const [draftBbox, setDraftBbox] = useState(null);
  const [draftBboxPage, setDraftBboxPage] = useState(null);
  const [regionMarkActive, setRegionMarkActive] = useState(false);
  const [textSelectMode, setTextSelectMode] = useState(false);
  const [linksRevision, setLinksRevision] = useState(0);
  const [activeChartLink, setActiveChartLink] = useState(null);
  const [pagePreviewStatus, setPagePreviewStatus] = useState({
    loading: true,
    ready: false,
    failed: false,
    error: "",
  });
  const [aiQuery, setAiQuery] = useState("");
  const [aiLoading, setAiLoading] = useState(false);
  const [aiSearchHits, setAiSearchHits] = useState([]);
  const [aiTurns, setAiTurns] = useState([]);
  const [readerPageTexts, setReaderPageTexts] = useState({});
  const [speechStatus, setSpeechStatus] = useState("idle");
  const [speechLoading, setSpeechLoading] = useState(false);
  const speechUtteranceRef = useRef(null);

  const maxPage = useMemo(() => {
    const n = Number(issue?.page_count || 0);
    return Number.isFinite(n) && n > 0 ? Math.floor(n) : null;
  }, [issue?.page_count]);

  const isSpreadMode = !isMobile && viewportWidth >= SPREAD_MIN_VIEWPORT_WIDTH;

  const spreadStartPage = useMemo(() => {
    const current = Number(page || 1);
    const safeCurrent = Number.isFinite(current) && current > 0 ? Math.floor(current) : 1;
    if (!isSpreadMode || safeCurrent <= 1) return safeCurrent;
    return safeCurrent % 2 === 0 ? safeCurrent : safeCurrent - 1;
  }, [isSpreadMode, page]);

  const visiblePdfPages = useMemo(() => {
    if (!isSpreadMode) return [Number(page || 1) || 1];
    if (spreadStartPage <= 1) return [1];
    const pages = [spreadStartPage];
    const next = spreadStartPage + 1;
    if (!maxPage || next <= maxPage) pages.push(next);
    return pages;
  }, [isSpreadMode, maxPage, page, spreadStartPage]);

  const layoutContainerWidth = useMemo(() => {
    if (pdfContainerWidth > 0) return pdfContainerWidth;
    const asideReserve = isMobile || readerFullscreen || asideCollapsed ? 0 : 372;
    const chromeReserve = isMobile ? 24 : 48;
    return Math.max(320, viewportWidth - asideReserve - chromeReserve);
  }, [pdfContainerWidth, asideCollapsed, isMobile, readerFullscreen, viewportWidth]);

  const layoutContainerHeight = useMemo(() => {
    if (pdfContainerHeight > 0) return pdfContainerHeight;
    const chrome = readerFullscreen ? FULLSCREEN_READER_CHROME_HEIGHT : DESKTOP_READER_CHROME_HEIGHT;
    return Math.max(320, viewportHeight - chrome);
  }, [pdfContainerHeight, readerFullscreen, viewportHeight]);

  const effectivePdfWidth = useMemo(() => {
    const pagesInView = isSpreadMode ? visiblePdfPages.length : 1;
    const horizontalPadding = isMobile && !readerFullscreen ? 8 : 0;
    const spreadGap = isSpreadMode ? SPREAD_PAGE_GAP * Math.max(0, pagesInView - 1) : 0;
    const containerFit = Math.max(
      220,
      Math.floor((layoutContainerWidth - horizontalPadding - spreadGap) / pagesInView)
    );
    let baseFit = containerFit;
    if (!isMobile && (zoom === "page-fit" || typeof zoom === "number")) {
      // Výšku pro „fit" stropujeme viewportem. V ne-fullscreen režimu stránka scrolluje
      // uvnitř app shellu, takže změřená výška kontejneru roste s vykreslenou stránkou —
      // stránka tím ovlivňuje svou vlastní velikost (zpětná vazba). Bez capu má pak výpočet
      // dva stabilní stavy (malý/obří) a 10% krok zoomu mezi nimi přeskočí. Cap to přetrhne.
      const viewportAvailable =
        viewportHeight - (readerFullscreen ? FULLSCREEN_READER_CHROME_HEIGHT : DESKTOP_READER_CHROME_HEIGHT);
      const measuredAvailable = layoutContainerHeight > 120 ? layoutContainerHeight - 16 : viewportAvailable;
      const availableHeight = Math.max(220, Math.min(measuredAvailable, viewportAvailable));
      const heightFit = Math.max(220, Math.floor(availableHeight / PDF_PAGE_ASPECT_RATIO));
      baseFit = Math.min(containerFit, heightFit);
    } else if (isMobile && viewportWidth) {
      const viewportFit = Math.max(220, Math.floor(viewportWidth - (readerFullscreen ? 0 : 24)));
      baseFit = Math.min(containerFit, viewportFit);
    }
    if (typeof zoom === "number" && Number.isFinite(zoom) && zoom > 0) {
      return Math.min(2400, Math.max(220, Math.round(baseFit * (zoom / 100))));
    }
    return baseFit;
  }, [
    isMobile,
    isSpreadMode,
    layoutContainerWidth,
    layoutContainerHeight,
    readerFullscreen,
    viewportHeight,
    viewportWidth,
    visiblePdfPages.length,
    zoom,
  ]);

  useEffect(() => {
    if (!issueId) return;
    void preloadArchivePdfDocument(issueId);
    setLoading(true);
    setErr("");
    api
      .get(`/magazines/issues/${issueId}`)
      .then(({ data }) => setIssue(data || null))
      .catch((e) => {
        setErr(formatApiErrorFromAxios(e));
        setIssue(null);
      })
      .finally(() => setLoading(false));
    setAiTurns([]);
  }, [issueId]);

  useEffect(() => {
    const readerPage = Number(location.state?.readerPage);
    if (Number.isFinite(readerPage) && readerPage > 0) {
      setPage(Math.floor(readerPage));
    }
  }, [issueId, location.state?.readerPage]);

  useEffect(() => {
    setDraftBbox(null);
    setDraftBboxPage(null);
    setRegionMarkActive(false);
    setActiveChartLink(null);
    setPagePreviewStatus({ loading: true, ready: false, failed: false, error: "" });
  }, [page, issueId]);

  useEffect(() => {
    setDocHits([]);
    setAiSearchHits([]);
    setAiTurns([]);
  }, [searchScope]);

  useEffect(() => {
    if (typeof window === "undefined") return undefined;
    const onResize = () => {
      setIsMobile(window.innerWidth < 768);
      setViewportWidth(window.innerWidth);
      setViewportHeight(window.innerHeight);
    };
    onResize();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  useEffect(() => {
    if (!isMobile) {
      setMobileReaderMode(false);
      setMobilePanel("reader");
      return;
    }
    setMobileReaderMode(true);
    setMobilePanel("reader");
  }, [isMobile]);

  useEffect(() => {
    setZoom((prev) => {
      if (typeof prev === "number") return prev;
      if (isMobile && prev === "page-fit") return "page-width";
      if (!isMobile && prev === "page-width") return "page-fit";
      return prev;
    });
  }, [isMobile]);

  useEffect(() => {
    if (!readerFullscreen || typeof document === "undefined") return undefined;
    const previousBodyOverflow = document.body.style.overflow;
    const previousHtmlOverflow = document.documentElement.style.overflow;
    const scrollPane = document.getElementById("app-main-scroll-pane");
    const previousPaneOverflow = scrollPane ? scrollPane.style.overflow : "";
    document.body.style.overflow = "hidden";
    document.documentElement.style.overflow = "hidden";
    if (scrollPane) scrollPane.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousBodyOverflow;
      document.documentElement.style.overflow = previousHtmlOverflow;
      if (scrollPane) scrollPane.style.overflow = previousPaneOverflow;
    };
  }, [readerFullscreen]);

  useEffect(() => {
    if (typeof window === "undefined") return undefined;
    let cancelled = false;
    let observer = null;
    const updateSize = () => {
      const target = pdfContainerRef.current;
      if (!target) return;
      const nextWidth = Math.floor(target.clientWidth || 0);
      const nextHeight = Math.floor(target.clientHeight || 0);
      setPdfContainerWidth((prev) => (prev === nextWidth ? prev : nextWidth));
      setPdfContainerHeight((prev) => (prev === nextHeight ? prev : nextHeight));
    };
    const attach = () => {
      if (cancelled) return;
      const target = pdfContainerRef.current;
      if (!target) {
        requestAnimationFrame(attach);
        return;
      }
      updateSize();
      observer = new ResizeObserver(updateSize);
      observer.observe(target);
      window.addEventListener("resize", updateSize);
    };
    attach();
    return () => {
      cancelled = true;
      observer?.disconnect();
      window.removeEventListener("resize", updateSize);
    };
  }, [readerFullscreen, isMobile, mobilePanel, mobileSearchOpen, loading, issueId]);

  // Synchronous measurement before first paint to avoid initial-load flicker.
  // useEffect fires after paint; useLayoutEffect fires before it, so setPdfContainer*
  // here triggers a synchronous re-render and the browser paints only once.
  useLayoutEffect(() => {
    if (loading) return;
    const target = pdfContainerRef.current;
    if (!target) return;
    const nextWidth = Math.floor(target.clientWidth || 0);
    const nextHeight = Math.floor(target.clientHeight || 0);
    if (nextWidth > 0) setPdfContainerWidth((prev) => (prev === nextWidth ? prev : nextWidth));
    if (nextHeight > 0) setPdfContainerHeight((prev) => (prev === nextHeight ? prev : nextHeight));
  }, [loading]);

  const captureZoomAnchor = useCallback(() => {
    const el = pdfContainerRef.current;
    if (!el) return null;
    return {
      leftRatio: (el.scrollLeft + el.clientWidth / 2) / Math.max(1, el.scrollWidth),
      topRatio: (el.scrollTop + el.clientHeight / 2) / Math.max(1, el.scrollHeight),
    };
  }, []);

  const updateZoom = useCallback((updater) => {
    zoomAnchorRef.current = captureZoomAnchor();
    setZoom(updater);
  }, [captureZoomAnchor]);

  const speechSupported =
    typeof window !== "undefined" &&
    "speechSynthesis" in window &&
    typeof window.SpeechSynthesisUtterance === "function";

  const stopSpeech = useCallback(() => {
    if (typeof window !== "undefined" && window.speechSynthesis) {
      window.speechSynthesis.cancel();
    }
    speechUtteranceRef.current = null;
    setSpeechStatus("idle");
    setSpeechLoading(false);
  }, []);

  const handlePageTextReady = useCallback((textPage, text) => {
    const pageNum = Number(textPage);
    const cleaned = String(text || "").replace(/\s+/g, " ").trim();
    if (!Number.isFinite(pageNum) || pageNum < 1) return;
    setReaderPageTexts((prev) => (prev[pageNum] === cleaned ? prev : { ...prev, [pageNum]: cleaned }));
  }, []);

  const loadSpeechTextForPages = useCallback(
    async (pages) => {
      const safePages = Array.from(
        new Set(
          (Array.isArray(pages) && pages.length ? pages : [page])
            .map((p) => Number(p))
            .filter((p) => Number.isFinite(p) && p > 0)
            .map((p) => Math.floor(p))
        )
      );
      if (!issueId || !safePages.length) return "";

      const entries = await Promise.all(
        safePages.map(async (textPage) => {
          const cached = readerPageTexts[textPage];
          if (cached) return [textPage, cached];
          const loaded = await getArchivePdfPageText(issueId, textPage);
          return [textPage, String(loaded || "").replace(/\s+/g, " ").trim()];
        })
      );

      setReaderPageTexts((prev) => {
        let changed = false;
        const next = { ...prev };
        entries.forEach(([textPage, text]) => {
          if (text && next[textPage] !== text) {
            next[textPage] = text;
            changed = true;
          }
        });
        return changed ? next : prev;
      });

      return entries
        .map(([textPage, text]) => (text ? `Strana ${textPage}. ${text}` : ""))
        .filter(Boolean)
        .join("\n\n");
    },
    [issueId, page, readerPageTexts]
  );

  const pickSpeechVoice = useCallback(() => {
    if (!speechSupported) return null;
    const voices = window.speechSynthesis?.getVoices?.() || [];
    return (
      voices.find((voice) => /^cs(-|_)?/i.test(voice.lang)) ||
      voices.find((voice) => /czech|cesky|češt/i.test(`${voice.name} ${voice.lang}`)) ||
      null
    );
  }, [speechSupported]);

  const handleSpeechToggle = useCallback(async () => {
    if (!speechSupported) {
      toast.error("Čtení nahlas tento prohlížeč nepodporuje.");
      return;
    }
    const synth = window.speechSynthesis;
    if (speechStatus === "speaking") {
      synth.pause();
      setSpeechStatus("paused");
      return;
    }
    if (speechStatus === "paused") {
      synth.resume();
      setSpeechStatus("speaking");
      return;
    }

    setSpeechLoading(true);
    try {
      const text = await loadSpeechTextForPages(visiblePdfPages);
      if (!text.trim()) {
        toast.error("Tato stránka nemá čitelný text. Pokud je PDF sken, je potřeba OCR.");
        setSpeechStatus("idle");
        return;
      }

      synth.cancel();
      const utterance = new window.SpeechSynthesisUtterance(text.slice(0, 30000));
      const voice = pickSpeechVoice();
      utterance.lang = voice?.lang || "cs-CZ";
      if (voice) utterance.voice = voice;
      utterance.rate = 0.95;
      utterance.pitch = 1;
      utterance.onend = () => {
        if (speechUtteranceRef.current === utterance) {
          speechUtteranceRef.current = null;
          setSpeechStatus("idle");
        }
      };
      utterance.onerror = (event) => {
        if (speechUtteranceRef.current === utterance) {
          speechUtteranceRef.current = null;
          setSpeechStatus("idle");
        }
        if (event?.error && !["interrupted", "canceled"].includes(event.error)) {
          toast.error("Čtení nahlas se nepodařilo spustit.");
        }
      };

      speechUtteranceRef.current = utterance;
      setSpeechStatus("speaking");
      synth.speak(utterance);
    } catch {
      toast.error("Text z PDF se nepodařilo načíst pro čtení nahlas.");
      setSpeechStatus("idle");
    } finally {
      setSpeechLoading(false);
    }
  }, [loadSpeechTextForPages, pickSpeechVoice, speechStatus, speechSupported, visiblePdfPages]);

  useEffect(() => {
    setReaderPageTexts({});
  }, [issueId]);

  useEffect(() => {
    stopSpeech();
  }, [issueId, page, stopSpeech]);

  useEffect(() => {
    return () => {
      if (typeof window !== "undefined" && window.speechSynthesis) {
        window.speechSynthesis.cancel();
      }
    };
  }, []);

  const isMagazineScope = searchScope === ARCHIVE_SEARCH_SCOPE_MAGAZINE;
  const searchScopeLabel = isMagazineScope ? "Hledat v časopise" : "Hledat v čísle";

  const runDocSearch = async (e) => {
    e.preventDefault();
    const q = docQuery.trim();
    if (!q) return;
    if (isMagazineScope && !magazineId) return;
    if (!isMagazineScope && !issueId) return;
    setDocSearching(true);
    setErr("");
    try {
      const { data } = isMagazineScope
        ? await api.get(`/magazines/${magazineId}/search`, { params: { q, limit: 40 } })
        : await api.get(`/magazines/issues/${issueId}/search`, { params: { q, limit: 40 } });
      setDocHits(normalizeArchiveHits(data?.hits));
    } catch (e2) {
      setErr(formatApiErrorFromAxios(e2));
      setDocHits([]);
    } finally {
      setDocSearching(false);
    }
  };

  useEffect(() => {
    if (!docQuery.trim() && docHits.length) setDocHits([]);
  }, [docQuery, docHits.length]);

  const runAiSearch = async () => {
    if (!aiQuery.trim()) return;
    setAiLoading(true);
    setErr("");
    try {
      const payload = {
        query: aiQuery.trim(),
        magazine_id: magazineId,
        limit: 12,
      };
      if (!isMagazineScope) payload.issue_id = issueId;
      const { data } = await api.post("/magazines/ai/search", payload);
      setAiSearchHits(normalizeArchiveHits(data?.hits));
    } catch (e2) {
      setErr(formatApiErrorFromAxios(e2));
      setAiSearchHits([]);
    } finally {
      setAiLoading(false);
    }
  };

  const runAiChat = async () => {
    const q = aiQuery.trim();
    if (!q) return;
    setAiLoading(true);
    setErr("");
    setAiSearchHits([]);
    const turnId = `archive-turn-${aiTurns.length}-${q.length}`;
    const priorTurns = aiTurns;
    setAiTurns((prev) => [...prev, { id: turnId, question: q, answer: "", citations: [], error: "" }]);
    setAiQuery("");
    try {
      const conversationHistory = priorTurns.flatMap((t) => [
        { role: "user", content: t.question || "" },
        ...(t.answer ? [{ role: "assistant", content: t.answer }] : []),
      ]);
      const payload = {
        query: q,
        magazine_id: magazineId,
        top_k: 10,
        conversation_history: conversationHistory,
      };
      if (!isMagazineScope) {
        payload.issue_id = issueId;
        if (page) payload.page = page;
      }
      const { data } = await api.post("/magazines/ai/chat", payload);
      const answer = String(data?.answer || "").trim();
      const citations = Array.isArray(data?.citations) ? data.citations : [];
      setAiTurns((prev) => prev.map((t) => (t.id === turnId ? { ...t, answer, citations } : t)));
    } catch (e2) {
      const msg = formatApiErrorFromAxios(e2);
      setErr(msg);
      setAiTurns((prev) => prev.map((t) => (t.id === turnId ? { ...t, error: msg } : t)));
    } finally {
      setAiLoading(false);
    }
  };

  const runAiAction = async (e) => {
    e.preventDefault();
    if (asideTab === "chat" || looksLikeArchiveAiQuestion(aiQuery)) {
      setAsideTab("chat");
      await runAiChat();
    } else {
      await runAiSearch();
    }
  };

  const jumpToPage = useCallback((p) => {
    const np = Number(p || 1);
    if (Number.isFinite(np) && np > 0) {
      const bounded = maxPage ? Math.min(Math.floor(np), maxPage) : Math.floor(np);
      setPage(bounded);
    }
  }, [maxPage]);

  const jumpToHit = useCallback(
    (hit) => {
      const targetIssueId = archiveHitIssueId(hit);
      const targetPage = Number(hit?.page) || 1;
      if (!targetIssueId || targetIssueId === String(issueId || "")) {
        jumpToPage(targetPage);
        if (isMobile) setMobileSearchOpen(false);
        return;
      }
      navigate(`/archive/${magazineId}/${targetIssueId}`, { state: { readerPage: targetPage } });
    },
    [issueId, magazineId, navigate, isMobile, jumpToPage]
  );

  const linkDraftFromHit = useCallback(
    (hit) => {
      if (!isAdmin) return;
      const snippet = String(hit?.snippet || "").trim();
      const label = snippet.slice(0, 80) || `Strana ${hit?.page || page}`;
      setLinkDraft({ label, anchorText: snippet, page: Number(hit?.page) || page, ts: Date.now() });
      setDraftBbox(null);
      setDraftBboxPage(null);
      setRegionMarkActive(false);
      setAsideTab("links");
      if (isMobile) {
        setMobilePanel("ai");
        setMobileSearchOpen(false);
      }
      const targetPage = Number(hit?.page) || page;
      if (targetPage && targetPage !== page) jumpToPage(targetPage);
    },
    [isAdmin, isMobile, page, jumpToPage]
  );

  const linkDraftFromText = useCallback(
    (text) => {
      if (!isAdmin) return;
      const snippet = String(text || "")
        .replace(/\s+/g, " ")
        .trim();
      if (snippet.length < 2) return;
      setLinkDraft({ label: snippet.slice(0, 80), anchorText: snippet, page: Number(page) || 1, ts: Date.now() });
      setDraftBbox(null);
      setDraftBboxPage(null);
      setRegionMarkActive(false);
      setAsideTab("links");
      if (isMobile) setMobilePanel("ai");
      toast.success("Označený text převzat — klikněte „Vybrat graf z katalogu a uložit“.");
    },
    [isAdmin, isMobile, page]
  );

  const handleRegionDrawn = useCallback((bbox, bboxPage) => {
    if (!isAdmin) return;
    const targetPage = Number(bboxPage) > 0 ? Math.floor(Number(bboxPage)) : page;
    setDraftBbox(bbox);
    setDraftBboxPage(targetPage);
    setPage(targetPage);
    setRegionMarkActive(false);
    setLinkDraft(null);
    setAsideTab("links");
    toast.success("Oblast označena — vyberte graf z katalogu a uložte.");
  }, [isAdmin, page]);

  const clearLinkDraft = useCallback(() => {
    if (!isAdmin) return;
    setLinkDraft(null);
    setDraftBbox(null);
    setDraftBboxPage(null);
    setRegionMarkActive(false);
  }, [isAdmin]);

  useEffect(() => {
    if (!isAdmin) {
      setRegionMarkActive(false);
      setDraftBbox(null);
      setDraftBboxPage(null);
      setLinkDraft(null);
    }
  }, [isAdmin]);

  const handlePagePreviewStatus = useCallback((status) => {
    setPagePreviewStatus((prev) => {
      const next = status || {};
      if (
        prev.loading === next.loading &&
        prev.ready === next.ready &&
        prev.failed === next.failed &&
        prev.error === next.error
      ) {
        return prev;
      }
      return {
        loading: Boolean(next.loading),
        ready: Boolean(next.ready),
        failed: Boolean(next.failed),
        error: String(next.error || ""),
      };
    });
    if (status?.failed) {
      setRegionMarkActive(false);
    }
  }, []);

  useEffect(() => {
    if (asideTab !== "links") setTextSelectMode(false);
  }, [asideTab]);

  const startRegionMark = useCallback(() => {
    if (!isAdmin) return;
    if (pagePreviewStatus.failed) {
      toast.error(pagePreviewStatus.error || "Náhled stránky není k dispozici.");
      return;
    }
    setTextSelectMode(false);
    setRegionMarkActive(true);
    setDraftBbox(null);
    setDraftBboxPage(null);
    setLinkDraft(null);
    setActiveChartLink(null);
    setAsideTab("links");
    if (isMobile) setMobilePanel("reader");
    requestAnimationFrame(() => {
      const el =
        document.querySelector("[data-archive-page-preview]") ||
        pdfContainerRef.current;
      el?.scrollIntoView?.({ behavior: "smooth", block: "center" });
    });
  }, [isAdmin, isMobile, pagePreviewStatus]);

  const openChartLink = useCallback((link) => {
    if (!link) return;
    setActiveChartLink(link);
    setRegionMarkActive(false);
  }, []);

  const readerAsideProps = {
    asideTab,
    setAsideTab,
    isMobile,
    mobileReaderMode,
    onCloseMobile: () => setMobilePanel("reader"),
    searchScope,
    setSearchScope,
    isMagazineScope,
    aiQuery,
    setAiQuery,
    aiLoading,
    runAiAction,
    aiSearchHits,
    aiTurns,
    issueId,
    page: draftBboxPage || page,
    isAdmin,
    linkDraft: isAdmin ? linkDraft : null,
    onLinkDraftFromHit: isAdmin ? linkDraftFromHit : undefined,
    onLinkDraftFromText: isAdmin ? linkDraftFromText : undefined,
    onLinkDraftConsumed: isAdmin ? clearLinkDraft : undefined,
    draftBbox: isAdmin ? draftBbox : null,
    regionMarkActive: isAdmin && regionMarkActive,
    previewReady: pagePreviewStatus.ready,
    previewLoading: pagePreviewStatus.loading,
    previewFailed: pagePreviewStatus.failed,
    previewError: pagePreviewStatus.error,
    onStartRegionMark: isAdmin ? startRegionMark : undefined,
    onClearRegionBbox: isAdmin ? () => {
      setDraftBbox(null);
      setDraftBboxPage(null);
    } : undefined,
    onLinksChanged: () => setLinksRevision((v) => v + 1),
    onOpenChartLink: openChartLink,
    jumpToHit,
  };

  const pageRenderWidth = effectivePdfWidth;

  useLayoutEffect(() => {
    if (typeof window === "undefined") return undefined;
    const anchor = zoomAnchorRef.current;
    const el = pdfContainerRef.current;
    if (!anchor || !el) return undefined;
    const frame = window.requestAnimationFrame(() => {
      el.scrollLeft = Math.max(0, anchor.leftRatio * el.scrollWidth - el.clientWidth / 2);
      el.scrollTop = Math.max(0, anchor.topRatio * el.scrollHeight - el.clientHeight / 2);
      zoomAnchorRef.current = null;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [pageRenderWidth, zoom, visiblePdfPages.length]);

  const spreadContentWidth = useMemo(() => {
    if (!pageRenderWidth) return null;
    const pages = visiblePdfPages.length;
    if (pages < 1) return null;
    const gap = isSpreadMode ? SPREAD_PAGE_GAP * Math.max(0, pages - 1) : 0;
    return pages * pageRenderWidth + gap;
  }, [isSpreadMode, pageRenderWidth, visiblePdfPages.length]);

  const needsHorizontalScroll = useMemo(() => {
    const contentWidth = spreadContentWidth || pageRenderWidth;
    if (!contentWidth) return false;
    return contentWidth > layoutContainerWidth - 8;
  }, [layoutContainerWidth, spreadContentWidth, pageRenderWidth]);

  const isReaderPanMode =
    !regionMarkActive &&
    !textSelectMode &&
    !(isAdmin && asideTab === "links") &&
    (typeof zoom === "number" || needsHorizontalScroll);

  const handlePinchZoom = useCallback((scale) => {
    updateZoom((z) => {
      const base = typeof z === "number" ? z : 100;
      return Math.min(250, Math.max(60, Math.round(base * scale)));
    });
  }, [updateZoom]);

  const { viewportProps: readerPanProps, viewportClassName: readerPanClassName } = useArchiveReaderPan(
    pdfContainerRef,
    isReaderPanMode,
    handlePinchZoom,
  );

  // Ctrl+Scroll zoom on PDF container
  useEffect(() => {
    const el = pdfContainerRef.current;
    if (!el) return undefined;
    const onWheel = (e) => {
      if (!e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      updateZoom((z) => {
        const base = typeof z === "number" ? z : 100;
        const delta = e.deltaY > 0 ? -10 : 10;
        return Math.min(250, Math.max(60, Math.round(base + delta)));
      });
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [loading, issueId, updateZoom]);

  // Keyboard shortcuts: Ctrl++/−/0
  useEffect(() => {
    const onKeyDown = (e) => {
      if (!e.ctrlKey && !e.metaKey) return;
      if (e.key === "+" || e.key === "=") {
        e.preventDefault();
        updateZoom((z) => Math.min(250, (typeof z === "number" ? z : 100) + 10));
      } else if (e.key === "-") {
        e.preventDefault();
        updateZoom((z) => Math.max(60, (typeof z === "number" ? z : 100) - 10));
      } else if (e.key === "0") {
        e.preventDefault();
        updateZoom(isMobile ? "page-width" : "page-fit");
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [isMobile, updateZoom]);

  const pdfPaneProps = {
    issueId,
    page,
    zoom,
    effectivePdfWidth: pageRenderWidth,
    isAdmin,
    regionMarkActive: isAdmin && regionMarkActive,
    draftBbox: null,
    linksRevision,
    onRegionDrawn: isAdmin ? handleRegionDrawn : undefined,
    onCancelRegionMark: isAdmin ? () => setRegionMarkActive(false) : undefined,
    onStartRegionMark: isAdmin ? startRegionMark : undefined,
    onOpenChartLink: openChartLink,
    onPreviewStatusChange: handlePagePreviewStatus,
    onTextSelected: isAdmin ? linkDraftFromText : undefined,
    onPageTextReady: handlePageTextReady,
    preferTextSelectMode: isAdmin && textSelectMode && asideTab === "links",
    allowHorizontalScroll: !needsHorizontalScroll,
  };

  const pdfLinksBar = (
    <ArchivePdfPageLinksBar
      issueId={issueId}
      page={page}
      linksRevision={linksRevision}
      onOpenChartLink={openChartLink}
    />
  );

  const pdfPagesEl = (
    <div
      className={
        isSpreadMode
          ? "inline-flex flex-row items-start justify-center shrink-0 py-2"
          : "flex w-full flex-col items-center px-2 py-1"
      }
      style={
        isSpreadMode && spreadContentWidth
          ? { width: spreadContentWidth, minWidth: spreadContentWidth, gap: SPREAD_PAGE_GAP }
          : undefined
      }
    >
      {visiblePdfPages.map((pdfPage) => (
        <div
          key={pdfPage}
          className="flex shrink-0 justify-center"
          style={pageRenderWidth ? { width: pageRenderWidth, minWidth: pageRenderWidth } : undefined}
        >
          <ArchivePdfPane
            {...pdfPaneProps}
            page={pdfPage}
            draftBbox={isAdmin && draftBboxPage === pdfPage ? draftBbox : null}
          />
        </div>
      ))}
    </div>
  );

  const inlineChartPanel = activeChartLink ? (
    <ArchiveInlineChartPanel link={activeChartLink} onClose={() => setActiveChartLink(null)} />
  ) : null;
  const readerGridClass = `grid h-full w-full max-w-full min-w-0 gap-2 ${
    asideCollapsed ? "xl:grid-cols-[minmax(0,1fr)]" : "xl:grid-cols-[minmax(0,1fr)_360px]"
  }`;
  const asideToggleEl = !isMobile ? (
    <button
      type="button"
      onClick={() => setAsideCollapsed((v) => !v)}
      className="fixed right-2 top-1/2 z-[380] inline-flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full border border-border/70 bg-white/95 text-slate-700 shadow-lg hover:bg-slate-50"
      title={asideCollapsed ? "Zobrazit AI panel" : "Schovat AI panel"}
      aria-label={asideCollapsed ? "Zobrazit AI panel" : "Schovat AI panel"}
    >
      {asideCollapsed ? <PanelRightOpen className="h-5 w-5" /> : <PanelRightClose className="h-5 w-5" />}
    </button>
  ) : null;

  const prevPageTarget = isSpreadMode
    ? Math.max(1, spreadStartPage <= 2 ? 1 : spreadStartPage - 2)
    : Math.max(1, Number(page || 1) - 1);
  const nextPageTarget = isSpreadMode
    ? (spreadStartPage <= 1 ? 2 : spreadStartPage + 2)
    : Number(page || 1) + 1;
  const canGoPrev = isSpreadMode ? spreadStartPage > 1 : page > 1;
  const canGoNext = maxPage ? nextPageTarget <= maxPage : true;
  const pageLabel = isSpreadMode && visiblePdfPages.length > 1
    ? `${visiblePdfPages[0]}–${visiblePdfPages[visiblePdfPages.length - 1]}`
    : `${visiblePdfPages[0] || page}`;
  const SpeechToggleIcon = speechLoading ? Loader2 : speechStatus === "speaking" ? Pause : speechStatus === "paused" ? Play : Volume2;
  const speechButtonTitle = !speechSupported
    ? "Čtení nahlas není v tomto prohlížeči dostupné"
    : speechStatus === "speaking"
      ? "Pozastavit čtení nahlas"
      : speechStatus === "paused"
        ? "Pokračovat ve čtení nahlas"
        : "Přečíst stránku nahlas";
  const speechActive = speechStatus !== "idle" || speechLoading;

  const pdfContainerClassName = `archive-reader-pinch-surface archive-reader-scrollbars-hidden h-full w-full max-w-full ${
    isReaderPanMode || needsHorizontalScroll ? "overflow-x-auto" : "overflow-x-hidden"
  } overflow-y-auto rounded-lg border border-border/70 bg-white box-border flex flex-col ${
    needsHorizontalScroll ? "items-start" : "items-stretch"
  }${readerPanClassName} ${
    readerFullscreen ? "archive-reader-pinch-surface--contain min-h-0 rounded-none border-0" : "min-h-[52vh]"
  }`;

  const pdfScrollBodyClassName = `flex shrink-0 ${
    needsHorizontalScroll ? "min-w-max justify-start" : "w-full justify-center"
  }`;

  const pdfViewportShellClassName = `relative flex-1 w-full max-w-full overflow-x-hidden box-border ${
    readerFullscreen ? "min-h-0 h-full" : "min-h-[52vh]"
  } ${isMobile && mobileReaderMode ? "pb-12" : ""} ${
    isMobile && mobileReaderMode && !readerFullscreen ? "archive-reader-mobile-viewport" : ""
  }`;

  const mobileReaderToolbarClassName =
    "archive-reader-mobile-toolbar fixed inset-x-2 bottom-[calc(env(safe-area-inset-bottom,0px)+0.55rem)] z-[420] w-auto max-w-[calc(100vw-1rem)] overflow-x-hidden rounded-xl border border-border/70 bg-white/95 p-0.5 shadow-lg backdrop-blur";

  const goPrevPage = () => jumpToPage(prevPageTarget);
  const goNextPage = () => {
    jumpToPage(maxPage ? Math.min(nextPageTarget, maxPage) : nextPageTarget);
  };

  const clearDocResults = () => {
    setDocHits([]);
  };

  const docSearchFormEl = (
    <form
      onSubmit={runDocSearch}
      className="flex flex-col gap-2 rounded-lg border border-border/70 bg-slate-50/75 p-2"
    >
      <div className="flex flex-wrap items-center gap-2">
        <label className="text-xs font-semibold text-slate-700 inline-flex items-center gap-1.5 shrink-0">
          <Search className="h-3.5 w-3.5" /> {searchScopeLabel}
        </label>
        <ArchiveSearchScopeToggle value={searchScope} onChange={setSearchScope} compact={isMobile} />
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <input
          className="input h-9 flex-1 min-w-[12rem] border-slate-300 bg-white shadow-sm"
          value={docQuery}
          onChange={(e) => setDocQuery(e.target.value)}
          placeholder={isMagazineScope ? "Hledat ve všech číslech časopisu…" : "Napište výraz a potvrďte Enter"}
        />
        <button
          type="submit"
          className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
          title={searchScopeLabel}
          aria-label={searchScopeLabel}
          disabled={docSearching}
        >
          <Search className="h-4 w-4" />
        </button>
        <button type="submit" className="btn-ghost h-9 px-3 text-xs" disabled={docSearching}>
          {docSearching ? "Hledám…" : "Najít"}
        </button>
        {isMobile ? (
          <button
            type="button"
            onClick={() => setMobileSearchOpen(false)}
            className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
            title="Zavřít hledání"
            aria-label="Zavřít hledání"
          >
            <X className="h-4 w-4" />
          </button>
        ) : null}
      </div>
    </form>
  );

  const docHitsEl =
    docHits.length > 0 ? (
      <div className="rounded-lg border border-border/60 p-2 max-h-36 overflow-auto space-y-1">
        <div className="mb-1 flex items-center justify-between px-1">
          <span className="text-[11px] font-medium text-slate-500">Nalezeno: {docHits.length}</span>
          <button
            type="button"
            onClick={clearDocResults}
            className="text-[11px] font-medium text-slate-500 hover:text-slate-800"
          >
            Zavřít výsledky
          </button>
        </div>
        {docHits.map((h, i) => (
          <div key={`${h.chunk_id || i}-${archiveHitIssueId(h) || "issue"}-${h.page}`} className="space-y-0.5">
            <ArchiveHitResultButton hit={h} currentIssueId={issueId} onJump={jumpToHit} onAfterJump={clearDocResults} />
            {isAdmin && archiveHitIssueId(h) === String(issueId || "") ? (
              <button
                type="button"
                onClick={() => linkDraftFromHit(h)}
                className="text-[11px] font-medium text-[hsl(var(--primary-deep))] hover:underline px-2"
              >
                Propojit s grafem…
              </button>
            ) : null}
          </div>
        ))}
      </div>
    ) : null;

  const zoomIn = () =>
    updateZoom((z) => {
      const base = typeof z === "number" ? z : 100;
      return Math.min(250, base + 10);
    });
  const zoomOut = () =>
    updateZoom((z) => {
      const base = typeof z === "number" ? z : 100;
      return Math.max(60, base - 10);
    });
  const resetZoom = () => updateZoom(isMobile ? "page-width" : "page-fit");

  const desktopReaderToolbarEl = (
    <div className={`flex flex-wrap items-center gap-2 text-xs text-slate-500 ${isMobile ? "hidden" : ""}`}>
      <Link
        to={`/archive/${magazineId}`}
        className="inline-flex h-8 shrink-0 items-center rounded-md border border-border/70 bg-white px-2.5 text-[11px] font-medium text-[hsl(var(--primary))] hover:bg-slate-50"
      >
        ← Čísla
      </Link>
      <form onSubmit={runDocSearch} className="flex min-w-[18rem] flex-1 items-center gap-1.5">
        <input
          className="input h-8 min-w-0 flex-1 border-slate-300 bg-white shadow-sm"
          value={docQuery}
          onChange={(e) => setDocQuery(e.target.value)}
          placeholder={isMagazineScope ? "Hledat ve všech číslech…" : "Hledat v čísle…"}
        />
        <button
          type="submit"
          className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
          title={searchScopeLabel}
          aria-label={searchScopeLabel}
          disabled={docSearching}
        >
          <Search className="h-4 w-4" />
        </button>
        {isAdmin ? (
          <>
            <button
              type="button"
              onClick={() => {
                setTextSelectMode(false);
                setAsideTab("links");
                setAsideCollapsed(false);
              }}
              className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-md border border-border/70 bg-white px-2.5 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
              title="Otevřít grafy a odkazy k článku"
            >
              <Link2 className="h-3.5 w-3.5" />
              Grafy
            </button>
            <button
              type="button"
              onClick={() => {
                setRegionMarkActive(false);
                setTextSelectMode(true);
                setAsideTab("links");
                setAsideCollapsed(false);
                toast.message("Označte text v PDF myší — objeví se v panelu propojení.");
              }}
              className={`inline-flex h-8 shrink-0 items-center gap-1.5 rounded-md border px-2.5 text-[11px] font-medium ${
                textSelectMode && asideTab === "links"
                  ? "border-[hsl(var(--primary)/0.45)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]"
                  : "border-border/70 bg-white text-slate-700 hover:bg-slate-50"
              }`}
              title="Označit text v PDF a propojit ho s grafem"
            >
              <MousePointer2 className="h-3.5 w-3.5" />
              Označit text
            </button>
          </>
        ) : null}
      </form>
      <div className="inline-flex shrink-0 items-center gap-1.5">
        <button
          type="button"
          onClick={goPrevPage}
          className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
          title="Předchozí stránka"
          aria-label="Předchozí stránka"
          disabled={!canGoPrev}
        >
          <ChevronLeft className="h-4 w-4" />
        </button>
        <span className="inline-flex items-center whitespace-nowrap">
          Strana:
          <input
            type="number"
            min={1}
            max={maxPage || undefined}
            className="ml-1.5 input h-8 w-20"
            value={isSpreadMode ? spreadStartPage : page}
            onChange={(e) => jumpToPage(e.target.value)}
          />
          {maxPage ? <span className="ml-1">/ {maxPage}</span> : null}
        </span>
        <button
          type="button"
          onClick={goNextPage}
          className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
          title="Další stránka"
          aria-label="Další stránka"
          disabled={!canGoNext}
        >
          <ChevronRight className="h-4 w-4" />
        </button>
      </div>
      <div className="inline-flex shrink-0 items-center gap-2">
        <div className="inline-flex items-center rounded-md border border-border/70 bg-white">
          <button
            type="button"
            onClick={zoomOut}
            className="inline-flex h-8 w-8 items-center justify-center text-slate-700 hover:bg-slate-50"
            title="Oddálit"
            aria-label="Oddálit"
          >
            <Minus className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={resetZoom}
            className="h-8 min-w-[3.3rem] border-x border-border/70 px-1.5 text-[11px] font-semibold text-slate-700 hover:bg-slate-50"
            title="Reset zoomu"
            aria-label="Reset zoomu"
          >
            {typeof zoom === "number" ? `${Math.round(zoom)}%` : "Fit"}
          </button>
          <button
            type="button"
            onClick={zoomIn}
            className="inline-flex h-8 w-8 items-center justify-center text-slate-700 hover:bg-slate-50"
            title="Přiblížit"
            aria-label="Přiblížit"
          >
            <Plus className="h-4 w-4" />
          </button>
        </div>
        <div className="inline-flex items-center rounded-md border border-border/70 bg-white">
          <button
            type="button"
            onClick={handleSpeechToggle}
            className={`inline-flex h-8 w-8 items-center justify-center hover:bg-slate-50 disabled:opacity-45 ${
              speechActive ? "text-[hsl(var(--primary-deep))]" : "text-slate-700"
            }`}
            title={speechButtonTitle}
            aria-label={speechButtonTitle}
            disabled={speechLoading || !speechSupported}
          >
            <SpeechToggleIcon className={`h-4 w-4 ${speechLoading ? "animate-spin" : ""}`} />
          </button>
          {speechActive ? (
            <button
              type="button"
              onClick={stopSpeech}
              className="inline-flex h-8 w-8 items-center justify-center border-l border-border/70 text-slate-700 hover:bg-slate-50"
              title="Zastavit čtení nahlas"
              aria-label="Zastavit čtení nahlas"
            >
              <Square className="h-3.5 w-3.5" />
            </button>
          ) : null}
        </div>
        {isReaderPanMode ? (
          <span className="hidden shrink-0 text-[10px] text-slate-500 xl:inline" title="Tažením posunete zoomnutou stránku">
            Tahem posunout
          </span>
        ) : null}
        <button
          type="button"
          onClick={() => setReaderFullscreen((v) => !v)}
          className="inline-flex h-8 items-center gap-1.5 rounded-md border border-border/70 bg-white px-2.5 text-slate-700 hover:bg-slate-50"
          title={readerFullscreen ? "Ukončit celou obrazovku" : "Celá obrazovka"}
          aria-label={readerFullscreen ? "Ukončit celou obrazovku" : "Celá obrazovka"}
        >
          {readerFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
          <span className="text-[11px] font-medium">{readerFullscreen ? "Zmenšit" : "Celá obrazovka"}</span>
        </button>
      </div>
    </div>
  );

  return (
    <AppShell
      title={issue?.issue_label || "Čtečka PDF"}
      subtitle={issue?.title || issue?.description || "Archivní číslo s fulltextem a AI."}
    >
      {/* Full-bleed přes boční padding app-shellu + bílé pozadí: jinak po stranách
          čtečky prosvítá levandulové pozadí motivu jako svislé „pruhy". Záporné
          marginy ruší padding scroll-pane, stejný padding vrátíme zpět jako bílý
          okraj, takže vedle stránky už není barevný pruh, ale čistá bílá. */}
      <div className="space-y-2 -mx-4 px-4 sm:-mx-6 sm:px-6 md:-mx-8 md:px-8 xl:-mx-12 xl:px-12 bg-[hsl(var(--card))]">
        <div className="hidden">
          <Link to={`/archive/${magazineId}`} className="text-[hsl(var(--primary))] underline font-medium">
            ← Zpět na čísla
          </Link>
        </div>

        {err ? <div className="chip-rose rounded-md p-3 text-sm">{err}</div> : null}

        {loading ? (
          <div className="text-sm text-slate-600">Načítám číslo…</div>
        ) : (
          <>
          {(readerFullscreen && typeof document !== "undefined"
            ? createPortal(
                <div className={`fixed inset-0 z-[320] h-[100dvh] overflow-hidden bg-white ${isMobile ? "p-0" : "p-1 md:p-2"}`}>
                  <div className={readerGridClass}>
                    <section
                      className={`soft-card w-full max-w-full min-w-0 overflow-x-hidden box-border p-2 min-h-[70vh] flex flex-col gap-2 ${
                        readerFullscreen ? "h-full min-h-0 rounded-none border-0 shadow-none" : ""
                      } ${
                        isMobile && mobileReaderMode ? (readerFullscreen ? "p-0 min-h-0" : "p-2 min-h-[78vh]") : ""
                      }`}
                    >
                      {isMobile && (mobileSearchOpen || !mobileReaderMode) ? docSearchFormEl : null}

                      {(!isMobile || !mobileReaderMode || mobileSearchOpen) ? docHitsEl : null}

                      {desktopReaderToolbarEl}

                      <div className={`hidden items-center justify-between gap-2 text-xs text-slate-500 ${isMobile && mobileReaderMode ? "hidden" : ""}`}>
                        <div className="inline-flex items-center gap-2">
                          <button
                            type="button"
                            onClick={goPrevPage}
                            className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
                            title="Předchozí stránka"
                            aria-label="Předchozí stránka"
                            disabled={!canGoPrev}
                          >
                            <ChevronLeft className="h-4 w-4" />
                          </button>
                          <span>
                            Strana:
                            <input
                              type="number"
                              min={1}
                              max={maxPage || undefined}
                              className="ml-2 input h-8 w-24"
                              value={isSpreadMode ? spreadStartPage : page}
                              onChange={(e) => jumpToPage(e.target.value)}
                            />
                            {maxPage ? ` / ${maxPage}` : ""}
                          </span>
                          <button
                            type="button"
                            onClick={goNextPage}
                            className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
                            title="Další stránka"
                            aria-label="Další stránka"
                            disabled={!canGoNext}
                          >
                            <ChevronRight className="h-4 w-4" />
                          </button>
                        </div>
                        <div className="inline-flex items-center gap-2">
                          <div className="inline-flex items-center rounded-md border border-border/70 bg-white">
                            <button
                              type="button"
                              onClick={zoomOut}
                              className="inline-flex h-8 w-8 items-center justify-center text-slate-700 hover:bg-slate-50"
                              title="Oddálit"
                              aria-label="Oddálit"
                            >
                              <Minus className="h-4 w-4" />
                            </button>
                            <button
                              type="button"
                              onClick={resetZoom}
                              className="h-8 min-w-[3.3rem] border-x border-border/70 px-1.5 text-[11px] font-semibold text-slate-700 hover:bg-slate-50"
                              title="Reset zoomu"
                              aria-label="Reset zoomu"
                            >
                              {typeof zoom === "number" ? `${Math.round(zoom)}%` : "Fit"}
                            </button>
                            <button
                              type="button"
                              onClick={zoomIn}
                              className="inline-flex h-8 w-8 items-center justify-center text-slate-700 hover:bg-slate-50"
                              title="Přiblížit"
                              aria-label="Přiblížit"
                            >
                              <Plus className="h-4 w-4" />
                            </button>
                          </div>
                          <button
                            type="button"
                            onClick={() => setReaderFullscreen((v) => !v)}
                            className="inline-flex h-8 items-center gap-1.5 rounded-md border border-border/70 bg-white px-2.5 text-slate-700 hover:bg-slate-50"
                            title={readerFullscreen ? "Ukončit celou obrazovku" : "Celá obrazovka"}
                            aria-label={readerFullscreen ? "Ukončit celou obrazovku" : "Celá obrazovka"}
                          >
                            {readerFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
                            <span className="text-[11px] font-medium">{readerFullscreen ? "Zmenšit" : "Celá obrazovka"}</span>
                          </button>
                        </div>
                      </div>

                      <div className={pdfViewportShellClassName}>
                        <button
                          type="button"
                          onClick={goPrevPage}
                          className={`absolute left-2 top-1/2 z-10 -translate-y-1/2 inline-flex items-center justify-center rounded-full border border-border/70 bg-white/95 text-slate-700 shadow-sm hover:bg-white disabled:opacity-45 ${
                            isMobile && mobileReaderMode ? "h-12 w-12" : "h-10 w-10"
                          } ${isMobile && mobileReaderMode ? "hidden" : ""}`}
                          title="Předchozí stránka"
                          aria-label="Předchozí stránka"
                          disabled={!canGoPrev}
                        >
                          <ChevronLeft className={`${isMobile && mobileReaderMode ? "h-6 w-6" : "h-5 w-5"}`} />
                        </button>
                        <div
                          ref={pdfContainerRef}
                          className={pdfContainerClassName}
                          title={isReaderPanMode ? "Tažením myší posunete stránku" : undefined}
                          {...readerPanProps}
                        >
                          {pdfLinksBar}
                          <div className={pdfScrollBodyClassName}>{pdfPagesEl}</div>
                        </div>
                        <button
                          type="button"
                          onClick={goNextPage}
                          className={`absolute right-2 top-1/2 z-10 -translate-y-1/2 inline-flex items-center justify-center rounded-full border border-border/70 bg-white/95 text-slate-700 shadow-sm hover:bg-white disabled:opacity-45 ${
                            isMobile && mobileReaderMode ? "h-12 w-12" : "h-10 w-10"
                          } ${isMobile && mobileReaderMode ? "hidden" : ""}`}
                          title="Další stránka"
                          aria-label="Další stránka"
                          disabled={!canGoNext}
                        >
                          <ChevronRight className={`${isMobile && mobileReaderMode ? "h-6 w-6" : "h-5 w-5"}`} />
                        </button>
                      </div>
                      {isMobile && mobileReaderMode ? (
                        <div className={mobileReaderToolbarClassName}>
                          <div className="flex flex-nowrap items-center justify-center gap-0.5 px-0.5">
                            <button
                              type="button"
                              onClick={goPrevPage}
                              className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                              disabled={!canGoPrev}
                            >
                              <ChevronLeft className="h-3.5 w-3.5" />
                            </button>
                            <button
                              type="button"
                              onClick={() => setMobileSearchOpen((v) => !v)}
                              className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                              title="Hledání"
                            >
                              <Search className="h-3 w-3" />
                            </button>
                            <button
                              type="button"
                              onClick={handleSpeechToggle}
                              className={`inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border ${
                                speechActive
                                  ? "border-[hsl(var(--primary)/0.4)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]"
                                  : "border-border/70 text-slate-700"
                              } disabled:opacity-45`}
                              title={speechButtonTitle}
                              aria-label={speechButtonTitle}
                              disabled={speechLoading || !speechSupported}
                            >
                              <SpeechToggleIcon className={`h-3 w-3 ${speechLoading ? "animate-spin" : ""}`} />
                            </button>
                            {speechActive ? (
                              <button
                                type="button"
                                onClick={stopSpeech}
                                className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                                title="Zastavit čtení nahlas"
                                aria-label="Zastavit čtení nahlas"
                              >
                                <Square className="h-3 w-3" />
                              </button>
                            ) : null}
                            <div className="shrink-0 px-0.5 text-[8px] font-semibold tabular-nums text-slate-700">
                              {pageLabel}{maxPage ? ` / ${maxPage}` : ""}
                            </div>
                            <button
                              type="button"
                              onClick={zoomOut}
                              className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                              title="Oddálit"
                            >
                              <Minus className="h-3 w-3" />
                            </button>
                            <button
                              type="button"
                              onClick={zoomIn}
                              className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                              title="Přiblížit"
                            >
                              <Plus className="h-3 w-3" />
                            </button>
                            <button
                              type="button"
                              onClick={() => {
                                setAsideTab("links");
                                setMobilePanel("ai");
                              }}
                              className={`inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border text-slate-700 ${
                                mobilePanel === "ai" && asideTab === "links"
                                  ? "border-[hsl(var(--primary)/0.4)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]"
                                  : "border-border/70"
                              }`}
                              title="Propojení s grafy"
                            >
                              <Link2 className="h-3 w-3" />
                            </button>
                            <button
                              type="button"
                              onClick={() => {
                                setAsideTab((t) => (t === "links" ? "search" : t));
                                setMobilePanel((v) => (v === "ai" ? "reader" : "ai"));
                              }}
                              className={`inline-flex h-6 min-w-[1.65rem] shrink-0 items-center justify-center rounded border text-[8px] font-semibold leading-none ${
                                mobilePanel === "ai" && asideTab !== "links"
                                  ? "border-[hsl(var(--primary)/0.4)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]"
                                  : "border-border/70 text-slate-700"
                              }`}
                              title={mobilePanel === "ai" ? "Přepnout na čtení" : "Otevřít AI"}
                            >
                              AI
                            </button>
                            <button
                              type="button"
                              onClick={() => setReaderFullscreen((v) => !v)}
                              className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                              title={readerFullscreen ? "Ukončit celou obrazovku" : "Celá obrazovka"}
                            >
                              {readerFullscreen ? <Minimize2 className="h-3 w-3" /> : <Maximize2 className="h-3 w-3" />}
                            </button>
                            <button
                              type="button"
                              onClick={goNextPage}
                              className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                              disabled={!canGoNext}
                            >
                              <ChevronRight className="h-3.5 w-3.5" />
                            </button>
                          </div>
                        </div>
                      ) : null}
                    </section>

                    <aside
                      className={`soft-card p-3 space-y-3 ${
                        asideCollapsed ? "hidden" : ""
                      } ${
                        isMobile && mobileReaderMode
                          ? mobilePanel === "ai"
                            ? "fixed inset-x-2 top-20 bottom-[calc(env(safe-area-inset-bottom,0px)+3.75rem)] z-[370] overflow-auto rounded-2xl border border-border/70 bg-white shadow-2xl pointer-events-auto"
                            : "hidden"
                          : ""
                      }`}
                    >
                      <ArchiveReaderAside {...readerAsideProps} />
                    </aside>
                    {asideToggleEl}
                  </div>
                </div>,
                document.body
              )
            : <div className={readerGridClass}>
            <section
              className={`soft-card w-full max-w-full min-w-0 overflow-x-hidden box-border p-2 min-h-[70vh] flex flex-col gap-2 ${
                readerFullscreen ? "rounded-none border-0 shadow-none" : ""
              } ${
                isMobile && mobileReaderMode ? (readerFullscreen ? "p-2 min-h-0" : "p-2 min-h-[78vh]") : ""
              }`}
            >
              {isMobile && (mobileSearchOpen || !mobileReaderMode) ? docSearchFormEl : null}

              {(!isMobile || !mobileReaderMode || mobileSearchOpen) ? docHitsEl : null}

              {desktopReaderToolbarEl}

              <div className={`hidden items-center justify-between gap-2 text-xs text-slate-500 ${isMobile && mobileReaderMode ? "hidden" : ""}`}>
                <div className="inline-flex items-center gap-2">
                  <button
                    type="button"
                    onClick={goPrevPage}
                    className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
                    title="Předchozí stránka"
                    aria-label="Předchozí stránka"
                    disabled={!canGoPrev}
                  >
                    <ChevronLeft className="h-4 w-4" />
                  </button>
                  <span>
                    Strana:
                    <input
                      type="number"
                      min={1}
                      max={maxPage || undefined}
                      className="ml-2 input h-8 w-24"
                      value={isSpreadMode ? spreadStartPage : page}
                      onChange={(e) => jumpToPage(e.target.value)}
                    />
                    {maxPage ? ` / ${maxPage}` : ""}
                  </span>
                  <button
                    type="button"
                    onClick={goNextPage}
                    className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
                    title="Další stránka"
                    aria-label="Další stránka"
                    disabled={!canGoNext}
                  >
                    <ChevronRight className="h-4 w-4" />
                  </button>
                </div>
                <div className="inline-flex items-center gap-2">
                  <div className="inline-flex items-center rounded-md border border-border/70 bg-white">
                    <button
                      type="button"
                      onClick={zoomOut}
                      className="inline-flex h-8 w-8 items-center justify-center text-slate-700 hover:bg-slate-50"
                      title="Oddálit"
                      aria-label="Oddálit"
                    >
                      <Minus className="h-4 w-4" />
                    </button>
                    <button
                      type="button"
                      onClick={resetZoom}
                      className="h-8 min-w-[3.3rem] border-x border-border/70 px-1.5 text-[11px] font-semibold text-slate-700 hover:bg-slate-50"
                      title="Reset zoomu"
                      aria-label="Reset zoomu"
                    >
                      {typeof zoom === "number" ? `${Math.round(zoom)}%` : "Fit"}
                    </button>
                    <button
                      type="button"
                      onClick={zoomIn}
                      className="inline-flex h-8 w-8 items-center justify-center text-slate-700 hover:bg-slate-50"
                      title="Přiblížit"
                      aria-label="Přiblížit"
                    >
                      <Plus className="h-4 w-4" />
                    </button>
                  </div>
                  <button
                    type="button"
                    onClick={() => setReaderFullscreen((v) => !v)}
                    className="inline-flex h-8 items-center gap-1.5 rounded-md border border-border/70 bg-white px-2.5 text-slate-700 hover:bg-slate-50"
                    title={readerFullscreen ? "Ukončit celou obrazovku" : "Celá obrazovka"}
                    aria-label={readerFullscreen ? "Ukončit celou obrazovku" : "Celá obrazovka"}
                  >
                    {readerFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
                    <span className="text-[11px] font-medium">{readerFullscreen ? "Zmenšit" : "Celá obrazovka"}</span>
                  </button>
                </div>
              </div>

              <div className={pdfViewportShellClassName}>
                <button
                  type="button"
                  onClick={goPrevPage}
                  className={`absolute left-2 top-1/2 z-10 -translate-y-1/2 inline-flex items-center justify-center rounded-full border border-border/70 bg-white/95 text-slate-700 shadow-sm hover:bg-white disabled:opacity-45 ${
                    isMobile && mobileReaderMode ? "h-12 w-12" : "h-10 w-10"
                  } ${isMobile && mobileReaderMode ? "hidden" : ""}`}
                  title="Předchozí stránka"
                  aria-label="Předchozí stránka"
                  disabled={!canGoPrev}
                >
                  <ChevronLeft className={`${isMobile && mobileReaderMode ? "h-6 w-6" : "h-5 w-5"}`} />
                </button>
                <div
                  ref={pdfContainerRef}
                  className={pdfContainerClassName}
                  title={isReaderPanMode ? "Tažením myší posunete stránku" : undefined}
                  {...readerPanProps}
                >
                  {pdfLinksBar}
                  <div className={pdfScrollBodyClassName}>{pdfPagesEl}</div>
                </div>
                <button
                  type="button"
                  onClick={goNextPage}
                  className={`absolute right-2 top-1/2 z-10 -translate-y-1/2 inline-flex items-center justify-center rounded-full border border-border/70 bg-white/95 text-slate-700 shadow-sm hover:bg-white disabled:opacity-45 ${
                    isMobile && mobileReaderMode ? "h-12 w-12" : "h-10 w-10"
                  } ${isMobile && mobileReaderMode ? "hidden" : ""}`}
                  title="Další stránka"
                  aria-label="Další stránka"
                  disabled={!canGoNext}
                >
                  <ChevronRight className={`${isMobile && mobileReaderMode ? "h-6 w-6" : "h-5 w-5"}`} />
                </button>
              </div>
              {isMobile && mobileReaderMode ? (
                <div className={mobileReaderToolbarClassName}>
                  <div className="flex flex-nowrap items-center justify-center gap-0.5 px-0.5">
                    <button
                      type="button"
                      onClick={goPrevPage}
                      className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                      disabled={!canGoPrev}
                    >
                      <ChevronLeft className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      onClick={() => setMobileSearchOpen((v) => !v)}
                      className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                      title="Hledání"
                    >
                      <Search className="h-3 w-3" />
                    </button>
                    <button
                      type="button"
                      onClick={handleSpeechToggle}
                      className={`inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border ${
                        speechActive
                          ? "border-[hsl(var(--primary)/0.4)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]"
                          : "border-border/70 text-slate-700"
                      } disabled:opacity-45`}
                      title={speechButtonTitle}
                      aria-label={speechButtonTitle}
                      disabled={speechLoading || !speechSupported}
                    >
                      <SpeechToggleIcon className={`h-3 w-3 ${speechLoading ? "animate-spin" : ""}`} />
                    </button>
                    {speechActive ? (
                      <button
                        type="button"
                        onClick={stopSpeech}
                        className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                        title="Zastavit čtení nahlas"
                        aria-label="Zastavit čtení nahlas"
                      >
                        <Square className="h-3 w-3" />
                      </button>
                    ) : null}
                    <div className="shrink-0 px-0.5 text-[8px] font-semibold tabular-nums text-slate-700">
                      {pageLabel}{maxPage ? ` / ${maxPage}` : ""}
                    </div>
                    <button
                      type="button"
                      onClick={zoomOut}
                      className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                      title="Oddálit"
                    >
                      <Minus className="h-3 w-3" />
                    </button>
                    <button
                      type="button"
                      onClick={zoomIn}
                      className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                      title="Přiblížit"
                    >
                      <Plus className="h-3 w-3" />
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setAsideTab("links");
                        setMobilePanel("ai");
                      }}
                      className={`inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border text-slate-700 ${
                        mobilePanel === "ai" && asideTab === "links"
                          ? "border-[hsl(var(--primary)/0.4)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]"
                          : "border-border/70"
                      }`}
                      title="Propojení s grafy"
                    >
                      <Link2 className="h-3 w-3" />
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setAsideTab((t) => (t === "links" ? "search" : t));
                        setMobilePanel((v) => (v === "ai" ? "reader" : "ai"));
                      }}
                      className={`inline-flex h-6 min-w-[1.65rem] shrink-0 items-center justify-center rounded border text-[8px] font-semibold leading-none ${
                        mobilePanel === "ai" && asideTab !== "links"
                          ? "border-[hsl(var(--primary)/0.4)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]"
                          : "border-border/70 text-slate-700"
                      }`}
                      title={mobilePanel === "ai" ? "Přepnout na čtení" : "Otevřít AI"}
                    >
                      AI
                    </button>
                    <button
                      type="button"
                      onClick={() => setReaderFullscreen((v) => !v)}
                      className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                      title={readerFullscreen ? "Ukončit celou obrazovku" : "Celá obrazovka"}
                    >
                      {readerFullscreen ? <Minimize2 className="h-3 w-3" /> : <Maximize2 className="h-3 w-3" />}
                    </button>
                    <button
                      type="button"
                      onClick={goNextPage}
                      className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-700"
                      disabled={!canGoNext}
                    >
                      <ChevronRight className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              ) : null}
            </section>

            <aside
              className={`soft-card p-3 space-y-3 ${
                readerFullscreen || asideCollapsed ? "hidden" : ""
              } ${
                isMobile && mobileReaderMode
                  ? mobilePanel === "ai"
                    ? "fixed inset-x-2 top-20 bottom-[calc(env(safe-area-inset-bottom,0px)+3.75rem)] z-[370] overflow-auto rounded-2xl border border-border/70 bg-white shadow-2xl pointer-events-auto"
                    : "hidden"
                  : ""
              }`}
            >
              <ArchiveReaderAside {...readerAsideProps} />
            </aside>
            {asideToggleEl}
          </div>)}
          </>
        )}
      </div>
      {inlineChartPanel}
    </AppShell>
  );
}
