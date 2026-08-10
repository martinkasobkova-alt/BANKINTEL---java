import React, { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { BookOpen, FolderSearch, HardDrive, LayoutDashboard, Loader2, Paperclip, Plus, Search, Send, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { useSearchParams } from "react-router-dom";
import AppShell from "@/components/layout/AppShell";
import ArchiveInlineChartPanel from "@/components/archive/ArchiveInlineChartPanel";
import SharedChartMessagePreview from "@/components/chat/SharedChartMessagePreview";
import api, { formatApiErrorFromAxios, postFormData } from "@/lib/api";
import {
  CATALOG_PICKER_OPTIONS,
  catalogPickerLabel,
  searchCatalogPickerItems,
} from "@/lib/catalogChartPickerSearch";
import { normalizeSharedChart } from "@/lib/sharedChartLink";

function formatWhen(iso) {
  if (!iso) return "";
  try {
    return new Date(iso).toLocaleString("cs-CZ", { dateStyle: "short", timeStyle: "short" });
  } catch {
    return "";
  }
}

function isImageAttachment(att) {
  const ct = String(att?.content_type || "").toLowerCase();
  if (ct.startsWith("image/")) return true;
  const name = String(att?.file_name || "").toLowerCase();
  return [".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif"].some((ext) => name.endsWith(ext));
}

function conversationTitle(conv, meId, t) {
  const fallback = t("pages.messages.conversation");
  const explicit = String(conv?.title || "").trim();
  if (explicit) return explicit;
  const members = Array.isArray(conv?.participants) ? conv.participants : [];
  const others = members.filter((p) => p?.id && p.id !== meId);
  if (!others.length) return fallback;
  if (others.length === 1) return others[0].name || others[0].email || fallback;
  const names = others.map((p) => p.name || p.email || t("pages.messages.userFallback")).slice(0, 3);
  return names.join(", ");
}

const DASHBOARD_SHAREABLE_WIDGET_TYPES = new Set([
  "external_catalog_chart",
  "chart",
  "computed_chart",
  "user_upload_chart",
  "uploaded_data_chart",
]);

export default function MessagesPage() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [me, setMe] = useState(null);
  const [loadingConversations, setLoadingConversations] = useState(false);
  const [conversations, setConversations] = useState([]);
  const [activeConversationId, setActiveConversationId] = useState("");
  const [messages, setMessages] = useState([]);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [composerText, setComposerText] = useState("");
  const [pendingAttachments, setPendingAttachments] = useState([]);
  const [pendingSharedChart, setPendingSharedChart] = useState(null);
  const [userSearch, setUserSearch] = useState("");
  const [userSearchResults, setUserSearchResults] = useState([]);
  const [searchingUsers, setSearchingUsers] = useState(false);
  const [inviting, setInviting] = useState(false);
  const [uploadingAttachment, setUploadingAttachment] = useState(false);
  const [groupTitleDraft, setGroupTitleDraft] = useState("");
  const [savingGroupTitle, setSavingGroupTitle] = useState(false);
  const [removingParticipantId, setRemovingParticipantId] = useState("");
  const [prefillHandled, setPrefillHandled] = useState(false);
  const [shareMenuOpen, setShareMenuOpen] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerSource, setPickerSource] = useState("");
  const [pickerCatalogFilter, setPickerCatalogFilter] = useState("");
  const [pickerQuery, setPickerQuery] = useState("");
  const [pickerLoading, setPickerLoading] = useState(false);
  const [pickerItems, setPickerItems] = useState([]);
  const pickerHasQuery = pickerSource === "catalog" && pickerQuery.trim().length >= 2;
  const pickerCatalogLabel = pickerCatalogFilter
    ? CATALOG_PICKER_OPTIONS.find((o) => o.id === pickerCatalogFilter)?.label || pickerCatalogFilter
    : "všech katalozích";
  const [expandedSharedChartLink, setExpandedSharedChartLink] = useState(null);
  const userSearchInputRef = useRef(null);
  const fileInputRef = useRef(null);
  const shareMenuRef = useRef(null);

  const activeConversation = useMemo(
    () => conversations.find((c) => c.id === activeConversationId) || null,
    [conversations, activeConversationId]
  );
  const prefillUserId = String(searchParams.get("user") || "").trim();
  const prefillConversationId = String(searchParams.get("conversation") || "").trim();
  const prefillContext = String(searchParams.get("context") || "").trim();
  const prefillChartTitle = String(searchParams.get("chart_title") || "").trim();
  const prefillChartSetId = String(searchParams.get("chart_set_id") || "").trim();
  const prefillChartSourceType = String(searchParams.get("chart_source_type") || "").trim();
  const prefillChartUrl = String(searchParams.get("chart_url") || "").trim();
  const prefillShareChart = String(searchParams.get("share") || "").trim() === "chart";

  const loadConversations = async ({ keepActive = true, autoSelectFirst = true } = {}) => {
    setLoadingConversations(true);
    try {
      const [meRes, convRes] = await Promise.all([api.get("/auth/me"), api.get("/chat/conversations")]);
      const convs = Array.isArray(convRes.data) ? convRes.data : [];
      setMe(meRes.data || null);
      setConversations(convs);
      if (
        autoSelectFirst
        && (!keepActive || !convs.some((c) => c.id === activeConversationId))
      ) {
        setActiveConversationId(convs[0]?.id || "");
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setLoadingConversations(false);
    }
  };

  const loadMessages = async (conversationId) => {
    if (!conversationId) {
      setMessages([]);
      return;
    }
    setLoadingMessages(true);
    try {
      const { data } = await api.get(`/chat/conversations/${conversationId}/messages`);
      setMessages(Array.isArray(data) ? data : []);
      await api.post(`/chat/conversations/${conversationId}/read`);
      await loadConversations({ keepActive: true, autoSelectFirst: true });
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
      setMessages([]);
    } finally {
      setLoadingMessages(false);
    }
  };

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const autoSelectFirst = params.get("share") !== "chart";
    loadConversations({ keepActive: false, autoSelectFirst });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    loadMessages(activeConversationId);
    setPendingAttachments([]);
  }, [activeConversationId]);

  useEffect(() => {
    setGroupTitleDraft(activeConversation?.type === "group" ? String(activeConversation?.title || "") : "");
  }, [activeConversation?.id, activeConversation?.title, activeConversation?.type]);

  useEffect(() => {
    const q = userSearch.trim();
    if (q.length < 2) {
      setUserSearchResults([]);
      return;
    }
    let cancelled = false;
    setSearchingUsers(true);
    api
      .get("/chat/users", { params: { q, limit: 8 } })
      .then(({ data }) => {
        if (!cancelled) setUserSearchResults(Array.isArray(data) ? data : []);
      })
      .catch(() => {
        if (!cancelled) setUserSearchResults([]);
      })
      .finally(() => {
        if (!cancelled) setSearchingUsers(false);
      });
    return () => {
      cancelled = true;
    };
  }, [userSearch]);

  const createDirectConversation = async (targetUserId, contextText = "") => {
    try {
      const { data } = await api.post("/chat/conversations/direct", { user_id: targetUserId });
      setConversations((prev) => {
        const without = prev.filter((c) => c.id !== data.id);
        return [data, ...without];
      });
      setActiveConversationId(data.id);
      if (contextText.trim()) setComposerText(contextText.trim());
      setUserSearch("");
      setUserSearchResults([]);
      return data;
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
      return null;
    }
  };

  const inviteUser = async (targetUserId) => {
    if (!activeConversationId || !targetUserId) return;
    setInviting(true);
    try {
      const { data } = await api.post(`/chat/conversations/${activeConversationId}/participants`, {
        user_id: targetUserId,
      });
      setConversations((prev) => prev.map((c) => (c.id === data.id ? data : c)));
      toast.success("Uživatel byl přidán do konverzace.");
      setUserSearch("");
      setUserSearchResults([]);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setInviting(false);
    }
  };

  const saveGroupTitle = async () => {
    if (!activeConversationId || activeConversation?.type !== "group") return;
    setSavingGroupTitle(true);
    try {
      const { data } = await api.patch(`/chat/conversations/${activeConversationId}`, {
        title: groupTitleDraft.trim() || null,
      });
      setConversations((prev) => prev.map((c) => (c.id === data.id ? { ...c, ...data } : c)));
      toast.success("Název skupiny byl uložen.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setSavingGroupTitle(false);
    }
  };

  const removeParticipant = async (participantUserId) => {
    if (!activeConversationId || !participantUserId) return;
    setRemovingParticipantId(participantUserId);
    try {
      const { data } = await api.delete(
        `/chat/conversations/${activeConversationId}/participants/${encodeURIComponent(participantUserId)}`
      );
      setConversations((prev) => prev.map((c) => (c.id === data.id ? { ...c, ...data } : c)));
      toast.success("Účastník odebrán.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setRemovingParticipantId("");
    }
  };

  const focusInviteUser = () => {
    if (!userSearchInputRef.current) return;
    userSearchInputRef.current.focus();
    userSearchInputRef.current.select?.();
  };

  useEffect(() => {
    if (!shareMenuOpen) return undefined;
    const onDocClick = (e) => {
      if (shareMenuRef.current && !shareMenuRef.current.contains(e.target)) {
        setShareMenuOpen(false);
      }
    };
    const onEsc = (e) => {
      if (e.key === "Escape") setShareMenuOpen(false);
    };
    document.addEventListener("mousedown", onDocClick);
    document.addEventListener("keydown", onEsc);
    return () => {
      document.removeEventListener("mousedown", onDocClick);
      document.removeEventListener("keydown", onEsc);
    };
  }, [shareMenuOpen]);

  const onAttachmentPicked = async (file) => {
    if (!file || !activeConversationId) return;
    const formData = new FormData();
    formData.append("file", file);
    setUploadingAttachment(true);
    try {
      const { data } = await postFormData(`/chat/conversations/${activeConversationId}/attachments`, formData);
      setPendingAttachments((prev) => [...prev, data]);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setUploadingAttachment(false);
    }
  };

  const sendMessage = async (e) => {
    e.preventDefault();
    if (!activeConversationId) return;
    const text = composerText.trim();
    const attachmentIds = pendingAttachments.map((a) => a.id);
    if (!text && attachmentIds.length === 0 && !pendingSharedChart) return;
    try {
      const { data } = await api.post(`/chat/conversations/${activeConversationId}/messages`, {
        text,
        attachment_ids: attachmentIds,
        shared_chart: pendingSharedChart || undefined,
      });
      setMessages((prev) => [...prev, data]);
      setComposerText("");
      setPendingAttachments([]);
      setPendingSharedChart(null);
      loadConversations({ keepActive: true, autoSelectFirst: true });
    } catch (e2) {
      toast.error(formatApiErrorFromAxios(e2));
    }
  };

  const downloadAttachment = async (att) => {
    try {
      const response = await api.get(`/chat/attachments/${att.id}/download`, { responseType: "blob" });
      const url = URL.createObjectURL(response.data);
      const a = document.createElement("a");
      a.href = url;
      a.download = att.file_name || "attachment";
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
  };

  const sharePickLinks = [
    { id: "catalog", label: "Vybrat z katalogu", icon: FolderSearch },
    { id: "dashboard", label: "Vybrat z mého dashboardu", icon: LayoutDashboard },
    { id: "my-data", label: "Vybrat z mých dat", icon: HardDrive },
    { id: "reader", label: "Vybrat z čtečky", icon: BookOpen },
  ];

  const openSourcePicker = (sourceId) => {
    setShareMenuOpen(false);
    setPickerSource(sourceId);
    setPickerCatalogFilter("");
    setPickerQuery("");
    setPickerItems([]);
    setPickerOpen(true);
  };

  const sharePickedItem = (item) => {
    setPendingSharedChart(
      normalizeSharedChart({
        title: item.title,
        source_type: item.source_type,
        set_id: item.set_id,
        link_url: item.link_url,
      })
    );
    setPickerOpen(false);
    setPickerItems([]);
    setPickerQuery("");
  };

  useEffect(() => {
    if (!pickerOpen) return;
    let cancelled = false;
    const load = async () => {
      setPickerLoading(true);
      try {
        if (pickerSource === "dashboard") {
          const { data: pages } = await api.get("/me/dashboard/pages");
          if (cancelled) return;
          const pageList = Array.isArray(pages) ? pages : [];
          const widgetsByPage = await Promise.all(
            pageList.map(async (p) => {
              const pid = String(p?.id || "").trim();
              if (!pid) return [];
              try {
                const { data: widgets } = await api.get(`/me/dashboard/pages/${encodeURIComponent(pid)}/widgets`);
                const arr = Array.isArray(widgets) ? widgets : [];
                return arr
                  .filter((w) => DASHBOARD_SHAREABLE_WIDGET_TYPES.has(String(w?.type || "").trim().toLowerCase()))
                  .map((w) => {
                    const wid = String(w?.id || "").trim();
                    if (!wid) return null;
                    const pageTitle = String(p?.title || "Můj dashboard").trim();
                    const widgetTitle = String(w?.title || "").trim();
                    return {
                      id: `dashboard_widget:${wid}`,
                      title: widgetTitle ? `${widgetTitle} (${pageTitle})` : pageTitle,
                      source_type: "dashboard_widget",
                      set_id: `dashboard_widget:${wid}`,
                      link_url: `/my-dashboard?page=${encodeURIComponent(pid)}#widget-${encodeURIComponent(wid)}`,
                    };
                  })
                  .filter(Boolean);
              } catch {
                return [];
              }
            })
          );
          const list = widgetsByPage.flat();
          setPickerItems(list);
          return;
        }
        if (pickerSource === "my-data") {
          const [uploadChartsRes, seriesRes] = await Promise.all([api.get("/me/upload-charts"), api.get("/my-series")]);
          if (cancelled) return;
          const uploadCharts = (Array.isArray(uploadChartsRes.data) ? uploadChartsRes.data : []).map((c) => ({
            id: `upload_chart:${c.id}`,
            title: c.title || c.upload_name || `Graf ${c.id}`,
            source_type: "my_upload_chart",
            set_id: `my_upload_chart:${c.id}`,
            link_url: c.page_id ? `/my-dashboard?page=${encodeURIComponent(String(c.page_id))}` : "/my-dashboard",
          }));
          const series = (Array.isArray(seriesRes.data) ? seriesRes.data : []).map((s) => ({
            id: `series:${s.id}`,
            title: s.title || s.name || `Řada ${s.id}`,
            source_type: "my_series",
            set_id: `my_series:${s.id}`,
            link_url: `/my-data?series=${encodeURIComponent(String(s.id || ""))}`,
          }));
          setPickerItems([...uploadCharts, ...series]);
          return;
        }
        if (pickerSource === "reader") {
          const { data } = await api.get("/magazines");
          if (cancelled) return;
          const mags = (Array.isArray(data) ? data : []).map((m) => ({
            id: `mag:${m.id}`,
            title: m.title || "Článek/číslo",
            source_type: "reader",
            set_id: `magazine:${m.id}`,
            link_url: `/archive/${encodeURIComponent(String(m.id || ""))}`,
          }));
          setPickerItems(mags);
          return;
        }
        setPickerItems([]);
      } catch (e) {
        if (!cancelled) toast.error(formatApiErrorFromAxios(e));
      } finally {
        if (!cancelled) setPickerLoading(false);
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [pickerOpen, pickerSource]);

  useEffect(() => {
    if (!pickerOpen || pickerSource !== "catalog") return undefined;
    const q = pickerQuery.trim();
    if (q.length < 2) {
      setPickerLoading(false);
      setPickerItems([]);
      return undefined;
    }
    let cancelled = false;
    setPickerLoading(true);
    const timer = window.setTimeout(() => {
      searchCatalogPickerItems(q, { catalogId: pickerCatalogFilter })
        .then((items) => {
          if (!cancelled) setPickerItems(items);
        })
        .catch((e) => {
          if (!cancelled) {
            toast.error(formatApiErrorFromAxios(e));
            setPickerItems([]);
          }
        })
        .finally(() => {
          if (!cancelled) setPickerLoading(false);
        });
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [pickerOpen, pickerSource, pickerQuery, pickerCatalogFilter]);

  useEffect(() => {
    if (prefillHandled) return;
    const hasChartPrefill =
      prefillChartTitle || prefillChartSetId || prefillChartSourceType || prefillChartUrl;
    if (!prefillUserId && !(prefillShareChart && hasChartPrefill)) return;
    if (!me) return;
    let cancelled = false;
    (async () => {
      if (prefillUserId) {
        const conv = await createDirectConversation(prefillUserId, prefillContext);
        if (cancelled || !conv) return;
      } else if (prefillConversationId) {
        setActiveConversationId(prefillConversationId);
      } else if (prefillShareChart) {
        setActiveConversationId("");
      }
      const shared = normalizeSharedChart({
        title: prefillChartTitle,
        source_type: prefillChartSourceType,
        set_id: prefillChartSetId,
        link_url: prefillChartUrl,
      });
      if (shared) {
        setPendingSharedChart(shared);
        if (prefillShareChart && !prefillUserId && !prefillConversationId) {
          toast.info("Graf je připraven — vyberte konverzaci vlevo nebo vyhledejte příjemce.");
        } else if (prefillShareChart) {
          toast.info("Graf je připraven — odešlete zprávu níže.");
        }
      }
      setPrefillHandled(true);
      const sp = new URLSearchParams(searchParams);
      sp.delete("user");
      sp.delete("conversation");
      sp.delete("context");
      sp.delete("share");
      sp.delete("chart_title");
      sp.delete("chart_set_id");
      sp.delete("chart_source_type");
      sp.delete("chart_url");
      setSearchParams(sp, { replace: true });
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    prefillHandled,
    prefillUserId,
    prefillConversationId,
    prefillContext,
    prefillShareChart,
    prefillChartTitle,
    prefillChartSetId,
    prefillChartSourceType,
    prefillChartUrl,
    me,
  ]);

  return (
    <AppShell title={t("pages.messages.title")} subtitle={t("pages.messages.subtitle")}>
      <div className="grid gap-4 lg:grid-cols-[320px_minmax(0,1fr)]">
        <aside className="soft-card p-3 space-y-3">
          <div className="text-sm font-semibold text-slate-900">Konverzace</div>
          <div className="space-y-2 rounded-xl border border-indigo-200/80 bg-indigo-50/50 p-2.5">
            <label className="text-xs font-semibold text-indigo-900">Najít uživatele</label>
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-indigo-500" />
              <input
                ref={userSearchInputRef}
                className="input h-10 w-full border-indigo-300 bg-white pl-9 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                value={userSearch}
                onChange={(e) => setUserSearch(e.target.value)}
                placeholder="Jméno nebo e-mail"
              />
            </div>
            {searchingUsers ? <div className="text-xs text-slate-500">Hledám…</div> : null}
            {userSearchResults.length > 0 ? (
              <div className="max-h-44 space-y-1 overflow-auto rounded-md border border-border/60 p-1">
                {userSearchResults.map((u) => (
                  <div key={u.id} className="flex items-center justify-between gap-2 rounded-md px-2 py-1.5 text-xs hover:bg-slate-50">
                    <div className="min-w-0">
                      <div className="truncate font-semibold text-slate-900">{u.name || u.email}</div>
                      <div className="truncate text-slate-500">{u.email}</div>
                    </div>
                    <button
                      type="button"
                      className="btn-ghost h-7 px-2 text-[11px]"
                      onClick={() => (activeConversation ? inviteUser(u.id) : createDirectConversation(u.id))}
                      disabled={inviting}
                      title={activeConversation ? "Přizvat do konverzace" : "Začít chat"}
                    >
                      {activeConversation ? <UserPlus className="h-3.5 w-3.5" /> : "Chat"}
                    </button>
                  </div>
                ))}
              </div>
            ) : null}
          </div>
          <div className="divide-y divide-border/50 rounded-md border border-border/60">
            {loadingConversations ? (
              <div className="p-3 text-xs text-slate-500">Načítám konverzace…</div>
            ) : conversations.length === 0 ? (
              <div className="p-3 text-xs text-slate-500">Zatím nemáte žádnou konverzaci.</div>
            ) : (
              conversations.map((conv) => (
                <button
                  key={conv.id}
                  type="button"
                  onClick={() => setActiveConversationId(conv.id)}
                  className={`block w-full px-3 py-2 text-left transition ${
                    activeConversationId === conv.id ? "bg-primary/10" : "hover:bg-slate-50"
                  }`}
                >
                  <div className="truncate text-sm font-semibold text-slate-900">
                    {conversationTitle(conv, me?.id, t)}
                  </div>
                  <div className="flex items-center gap-1.5">
                    <div className="truncate text-xs text-slate-500">
                      {conv.last_message_preview || "Bez zpráv"}
                    </div>
                    {Number(conv.unread_count || 0) > 0 ? (
                      <span className="inline-flex h-5 min-w-[1.3rem] items-center justify-center rounded-full bg-rose-600 px-1 text-[10px] font-semibold text-white">
                        {conv.unread_count}
                      </span>
                    ) : null}
                  </div>
                </button>
              ))
            )}
          </div>
        </aside>

        <section className="soft-card min-w-0 flex min-h-[70vh] flex-col p-3">
          <div className="mb-3 border-b border-border/60 pb-2">
            <div className="text-sm font-semibold text-slate-900">
              {activeConversation ? conversationTitle(activeConversation, me?.id, t) : t("pages.messages.selectConversation")}
            </div>
            {activeConversation ? (
              <>
                {activeConversation.type === "group" ? (
                  <div className="mt-2 flex items-center gap-2">
                    <input
                      className="input h-9 w-full border-indigo-300 bg-indigo-50/40 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                      value={groupTitleDraft}
                      onChange={(e) => setGroupTitleDraft(e.target.value)}
                      placeholder="Název skupiny"
                    />
                    <button
                      type="button"
                      className="btn-ghost h-8 px-2 text-xs"
                      onClick={saveGroupTitle}
                      disabled={savingGroupTitle}
                    >
                      Uložit
                    </button>
                  </div>
                ) : null}
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {(activeConversation.participants || []).map((p) => {
                    const pid = String(p.id || "");
                    const isMe = pid && pid === String(me?.id || "");
                    const canRemove = activeConversation.type === "group" && !isMe;
                    return (
                      <span key={pid || p.email} className="inline-flex items-center gap-1 rounded-md border border-border/70 bg-slate-50 px-2 py-1 text-[11px] text-slate-700">
                        {p.name || p.email || "Uživatel"}
                        {canRemove ? (
                          <button
                            type="button"
                            className="rounded px-1 text-rose-700 hover:bg-rose-100"
                            onClick={() => removeParticipant(pid)}
                            disabled={removingParticipantId === pid}
                            title="Odebrat účastníka"
                          >
                            ×
                          </button>
                        ) : null}
                      </span>
                    );
                  })}
                </div>
              </>
            ) : null}
          </div>
          <div className="flex-1 space-y-2 overflow-auto rounded-md border border-border/60 bg-slate-50/50 p-2">
            {!activeConversation ? (
              <div className="text-xs text-slate-500">
                {pendingSharedChart
                  ? "Vyberte konverzaci v seznamu vlevo, nebo vyhledejte uživatele / skupinu."
                  : "Vyhledejte uživatele vlevo a začněte konverzaci."}
              </div>
            ) : loadingMessages ? (
              <div className="text-xs text-slate-500">Načítám zprávy…</div>
            ) : messages.length === 0 ? (
              <div className="text-xs text-slate-500">Zatím žádné zprávy.</div>
            ) : (
              messages.map((msg) => {
                const mine = msg.sender_id === me?.id;
                return (
                  <div
                    key={msg.id}
                    className={`max-w-[88%] rounded-lg border px-3 py-2 text-sm ${
                      mine ? "ml-auto border-primary/25 bg-primary/10" : "border-border/70 bg-white"
                    }`}
                  >
                    <div className="mb-1 flex items-center justify-between gap-3 text-[11px] text-slate-500">
                      <span className="font-semibold text-slate-700">{msg.sender?.name || "Uživatel"}</span>
                      <span>{formatWhen(msg.created_at)}</span>
                    </div>
                    {msg.text ? <div className="whitespace-pre-wrap text-slate-800">{msg.text}</div> : null}
                    {msg.shared_chart ? (
                      <SharedChartMessagePreview
                        sharedChart={msg.shared_chart}
                        onExpand={setExpandedSharedChartLink}
                      />
                    ) : null}
                    {Array.isArray(msg.attachments) && msg.attachments.length > 0 ? (
                      <div className="mt-2 space-y-1">
                        {msg.attachments.map((att) => (
                          <div key={att.id} className="space-y-1">
                            {isImageAttachment(att) ? (
                              <img
                                src={`/api/chat/attachments/${encodeURIComponent(att.id)}/download`}
                                alt={att.file_name || "Obrázek"}
                                className="max-h-48 rounded-md border border-border/60 bg-white object-contain"
                                loading="lazy"
                              />
                            ) : null}
                            <button
                              type="button"
                              onClick={() => downloadAttachment(att)}
                              className="inline-flex items-center gap-1 rounded-md border border-border/70 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
                            >
                              <Paperclip className="h-3.5 w-3.5" />
                              {att.file_name}
                            </button>
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </div>
                );
              })
            )}
          </div>
          <form onSubmit={sendMessage} className="mt-3 space-y-2 rounded-xl border-2 border-indigo-200/90 bg-indigo-50/70 p-2.5 shadow-sm">
            {pendingSharedChart ? (
              <div className="rounded-md border border-indigo-300 bg-white px-3 py-2 text-xs text-indigo-900 leading-snug">
                {activeConversation ? (
                  <>
                    Sdílíte graf do konverzace{" "}
                    <strong>{conversationTitle(activeConversation, me?.id, t)}</strong>.
                    {" "}Pro jiného příjemce klikněte jinou konverzaci vlevo.
                  </>
                ) : (
                  <>
                    Graf je připraven ke sdílení.{" "}
                    <strong>Vyberte konverzaci</strong> vlevo, nebo vyhledejte studenty / skupinu.
                  </>
                )}
              </div>
            ) : null}
            {pendingAttachments.length > 0 ? (
              <div className="flex flex-wrap gap-1.5 rounded-md border border-border/60 bg-slate-50 p-2">
                {pendingAttachments.map((att) => (
                  <span key={att.id} className="inline-flex items-center gap-1 rounded-md border border-border/70 bg-white px-2 py-1 text-xs">
                    <Paperclip className="h-3.5 w-3.5" />
                    {att.file_name}
                  </span>
                ))}
              </div>
            ) : null}
            {pendingSharedChart ? (
              <div className="space-y-2">
                <div className="flex items-center justify-end">
                  <button
                    type="button"
                    onClick={() => setPendingSharedChart(null)}
                    className="h-7 rounded-md border border-indigo-300 bg-white px-2 text-[11px] text-indigo-700 hover:bg-indigo-50"
                  >
                    Odebrat graf
                  </button>
                </div>
                <SharedChartMessagePreview
                  sharedChart={pendingSharedChart}
                  onExpand={setExpandedSharedChartLink}
                />
              </div>
            ) : null}
            <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
              <div className="min-w-0 flex-1">
                <label className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-indigo-900">
                  Napsat zprávu
                </label>
                <textarea
                  className="input w-full min-h-[92px] border-2 border-indigo-300 bg-white shadow-md focus:border-indigo-600 focus:ring-4 focus:ring-indigo-200"
                  placeholder="Napište zprávu…"
                  value={composerText}
                  onChange={(e) => setComposerText(e.target.value)}
                  disabled={!activeConversation}
                />
              </div>
              <div className="flex shrink-0 flex-wrap items-center justify-end gap-2">
                <input
                  ref={fileInputRef}
                  type="file"
                  className="hidden"
                  accept="image/*,.pdf,.doc,.docx,.xls,.xlsx,.csv,.txt,.ppt,.pptx,.heic,.heif"
                  disabled={!activeConversation || uploadingAttachment}
                  onChange={(e) => onAttachmentPicked(e.target.files?.[0])}
                />
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  className="inline-flex h-10 w-10 items-center justify-center rounded-md border border-indigo-300 bg-white text-indigo-700 hover:bg-indigo-50"
                  disabled={!activeConversation || uploadingAttachment}
                  title="Přidat soubor"
                  aria-label="Přidat soubor"
                >
                  <Paperclip className="h-4 w-4" />
                </button>
                <div className="relative" ref={shareMenuRef}>
                  <button
                    type="button"
                    onClick={() => setShareMenuOpen((v) => !v)}
                    className="inline-flex h-10 w-10 items-center justify-center rounded-md border border-indigo-300 bg-white text-indigo-700 hover:bg-indigo-50"
                    title="Sdílet obsah"
                    aria-label="Sdílet obsah"
                  >
                    <Plus className="h-4 w-4" />
                  </button>
                  {shareMenuOpen ? (
                    <div className="absolute bottom-12 right-0 z-20 w-56 rounded-lg border border-border/70 bg-white p-1.5 shadow-lg">
                      {sharePickLinks.map((item) => (
                        <button
                          key={item.id}
                          type="button"
                          className="flex h-9 w-full items-center gap-2 rounded-md px-2 text-left text-xs text-slate-700 hover:bg-slate-50"
                        onClick={() => openSourcePicker(item.id)}
                        >
                          <item.icon className="h-4 w-4 text-indigo-600" />
                          {item.label}
                        </button>
                      ))}
                    </div>
                  ) : null}
                </div>
                <button
                  type="button"
                  onClick={focusInviteUser}
                  className="inline-flex h-10 items-center gap-1.5 rounded-md border border-indigo-300 bg-white px-2.5 text-xs font-semibold text-indigo-700 hover:bg-indigo-50"
                  title="Přizvat uživatele"
                  aria-label="Přizvat uživatele"
                >
                  <UserPlus className="h-4 w-4" />
                  Přizvat
                </button>
                <button type="submit" className="btn-primary h-10 px-3" disabled={!activeConversation}>
                  <Send className="h-4 w-4" />
                </button>
              </div>
            </div>
            {uploadingAttachment ? <div className="text-xs text-slate-500">Nahrávám přílohu…</div> : null}
          </form>
        </section>
      </div>
      {pickerOpen ? (
        <div className="fixed inset-0 z-[250] flex items-end justify-center bg-slate-900/30 p-3 sm:items-center">
          <div className="w-full max-w-xl rounded-xl border border-border/70 bg-white p-3 shadow-2xl">
            <div className="mb-2 flex items-center justify-between gap-2">
              <div className="text-sm font-semibold text-slate-900">
                {pickerSource === "catalog"
                  ? "Vybrat z katalogu"
                  : pickerSource === "dashboard"
                    ? "Vybrat z mého dashboardu"
                    : pickerSource === "my-data"
                      ? "Vybrat z mých dat"
                      : "Vybrat z čtečky"}
              </div>
              <button type="button" className="btn-ghost h-8 px-2 text-xs" onClick={() => setPickerOpen(false)}>
                Zavřít
              </button>
            </div>
            {pickerSource === "catalog" ? (
              <div className="space-y-2">
                <select
                  className="input h-9 w-full border-indigo-300 bg-white text-sm shadow-sm"
                  value={pickerCatalogFilter}
                  onChange={(e) => {
                    setPickerCatalogFilter(e.target.value);
                    setPickerItems([]);
                  }}
                  aria-label="Vybrat typ katalogu"
                >
                  {CATALOG_PICKER_OPTIONS.map((opt) => (
                    <option key={opt.id || "all"} value={opt.id}>
                      {opt.label}
                    </option>
                  ))}
                </select>
                <input
                  className="input h-9 w-full border-indigo-300 bg-white shadow-sm"
                  placeholder={
                    pickerCatalogFilter
                      ? `Hledat v katalogu ${pickerCatalogLabel}…`
                      : "Hledat ve všech katalozích (ARAD, ČSÚ, Eurostat…)"
                  }
                  value={pickerQuery}
                  onChange={(e) => setPickerQuery(e.target.value)}
                />
                <p className="text-[11px] text-slate-500 leading-snug">
                  {pickerCatalogFilter
                    ? `Hledání jen v katalogu ${pickerCatalogLabel} — zadejte alespoň 2 znaky.`
                    : "Vyberte konkrétní katalog nebo hledejte napříč všemi zdroji."}
                </p>
              </div>
            ) : null}
            <div className="mt-2 max-h-[48vh] space-y-1 overflow-auto rounded-md border border-border/60 p-1">
              {pickerLoading ? (
                <div className="flex items-center gap-2 p-2 text-xs text-slate-500">
                  <Loader2 className="h-3.5 w-3.5 animate-spin text-indigo-600" />
                  <span>
                    {pickerSource === "catalog" && pickerCatalogFilter
                      ? `Hledám v katalogu ${pickerCatalogLabel}…`
                      : "Hledám v katalozích…"}
                  </span>
                </div>
              ) : pickerItems.length === 0 ? (
                <div className="p-2 text-xs text-slate-500">
                  {pickerSource === "catalog"
                    ? (pickerHasQuery
                      ? (pickerCatalogFilter
                        ? `V katalogu ${pickerCatalogLabel} jsme nic nenašli. Zkuste jiné klíčové slovo.`
                        : "Nic jsme nenašli. Zkuste jiné klíčové slovo nebo vyberte konkrétní katalog.")
                      : "Zadejte aspoň 2 znaky pro vyhledání.")
                    : "Nebyly nalezeny žádné položky."}
                </div>
              ) : (
                pickerItems.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => sharePickedItem(item)}
                    className="block w-full rounded-md px-2 py-2 text-left hover:bg-slate-50"
                  >
                    <div className="truncate text-sm font-semibold text-slate-900">{item.title}</div>
                    <div className="truncate text-xs text-slate-500">{catalogPickerLabel(item.source_type)}</div>
                  </button>
                ))
              )}
            </div>
          </div>
        </div>
      ) : null}
      {expandedSharedChartLink ? (
        <ArchiveInlineChartPanel
          link={expandedSharedChartLink}
          onClose={() => setExpandedSharedChartLink(null)}
        />
      ) : null}
    </AppShell>
  );
}
