import React, { useEffect, useMemo, useState } from "react";
import { Loader2, MessageCircle, Search, Users } from "lucide-react";
import { useNavigate } from "react-router-dom";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { buildCatalogChartMessagesShareUrl } from "@/lib/catalogChartShare";
import { toast } from "sonner";

function conversationLabel(conv, meId) {
  const explicit = String(conv?.title || "").trim();
  if (explicit) return explicit;
  const members = Array.isArray(conv?.participants) ? conv.participants : [];
  const others = members.filter((p) => p?.id && p.id !== meId);
  if (!others.length) return "Konverzace";
  if (others.length === 1) return others[0].name || others[0].email || "Konverzace";
  return others.map((p) => p.name || p.email || "Uživatel").slice(0, 3).join(", ");
}

/**
 * Výběr konverzace / uživatele před sdílením grafu do chatu.
 */
export default function ChartShareRecipientDialog({
  open,
  onOpenChange,
  title,
  sourceType,
  setId,
  pageUrl,
}) {
  const nav = useNavigate();
  const [loading, setLoading] = useState(false);
  const [meId, setMeId] = useState("");
  const [conversations, setConversations] = useState([]);
  const [userSearch, setUserSearch] = useState("");
  const [userSearchResults, setUserSearchResults] = useState([]);
  const [searchingUsers, setSearchingUsers] = useState(false);

  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    setLoading(true);
    Promise.all([api.get("/auth/me"), api.get("/chat/conversations")])
      .then(([meRes, convRes]) => {
        if (cancelled) return;
        setMeId(String(meRes.data?.id || ""));
        setConversations(Array.isArray(convRes.data) ? convRes.data : []);
      })
      .catch((e) => {
        if (!cancelled) toast.error(formatApiErrorFromAxios(e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  useEffect(() => {
    if (!open) {
      setUserSearch("");
      setUserSearchResults([]);
      return undefined;
    }
    const q = userSearch.trim();
    if (q.length < 2) {
      setUserSearchResults([]);
      return undefined;
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
  }, [open, userSearch]);

  const chartTitle = useMemo(
    () => String(title || setId || "Graf").trim(),
    [title, setId]
  );

  const goShare = ({ conversationId, userId } = {}) => {
    const url = buildCatalogChartMessagesShareUrl({
      title: chartTitle,
      sourceType,
      setId,
      pageUrl,
      conversationId,
      userId,
    });
    if (!url) {
      toast.error("Sdílení v chatu nelze sestavit.");
      return;
    }
    onOpenChange?.(false);
    nav(url);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md gap-0 p-0 overflow-hidden">
        <DialogHeader className="border-b border-border/60 px-5 py-4 text-left">
          <DialogTitle className="flex items-center gap-2 text-base">
            <MessageCircle className="h-4 w-4 text-indigo-600" />
            Sdílet graf v chatu
          </DialogTitle>
          <DialogDescription className="text-left text-xs leading-snug">
            Vyberte konverzaci nebo uživatele, kterému chcete poslat graf{" "}
            <span className="font-medium text-slate-800">„{chartTitle}"</span>.
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-[min(70vh,520px)] overflow-auto px-5 py-4 space-y-4">
          <div className="space-y-2">
            <label className="text-xs font-semibold text-indigo-900">Najít uživatele</label>
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-indigo-500" />
              <input
                className="input h-10 w-full border-indigo-300 bg-white pl-9 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                value={userSearch}
                onChange={(e) => setUserSearch(e.target.value)}
                placeholder="Jméno nebo e-mail"
                autoFocus
              />
            </div>
            {searchingUsers ? <div className="text-xs text-slate-500">Hledám…</div> : null}
            {userSearchResults.length > 0 ? (
              <div className="space-y-1 rounded-md border border-border/60 p-1">
                {userSearchResults.map((u) => (
                  <button
                    key={u.id}
                    type="button"
                    onClick={() => goShare({ userId: u.id })}
                    className="flex w-full items-center justify-between gap-2 rounded-md px-2 py-2 text-left text-xs hover:bg-indigo-50"
                  >
                    <div className="min-w-0">
                      <div className="truncate font-semibold text-slate-900">{u.name || u.email}</div>
                      <div className="truncate text-slate-500">{u.email}</div>
                    </div>
                    <span className="shrink-0 rounded-md border border-indigo-300 bg-white px-2 py-1 text-[10px] font-semibold text-indigo-700">
                      Chat
                    </span>
                  </button>
                ))}
              </div>
            ) : null}
          </div>

          <div className="space-y-2">
            <div className="flex items-center gap-1.5 text-xs font-semibold text-slate-800">
              <Users className="h-3.5 w-3.5 text-indigo-600" />
              Vaše konverzace
            </div>
            {loading ? (
              <div className="flex items-center gap-2 py-6 text-xs text-slate-500">
                <Loader2 className="h-4 w-4 animate-spin" />
                Načítám konverzace…
              </div>
            ) : conversations.length === 0 ? (
              <div className="rounded-md border border-dashed border-border/70 bg-slate-50 px-3 py-4 text-xs text-slate-600">
                Zatím nemáte žádnou konverzaci. Vyhledejte uživatele výše a začněte nový chat.
              </div>
            ) : (
              <div className="max-h-56 space-y-1 overflow-auto rounded-md border border-border/60 p-1">
                {conversations.map((conv) => (
                  <button
                    key={conv.id}
                    type="button"
                    onClick={() => goShare({ conversationId: conv.id })}
                    className="block w-full rounded-md px-2.5 py-2 text-left hover:bg-indigo-50"
                  >
                    <div className="truncate text-sm font-semibold text-slate-900">
                      {conversationLabel(conv, meId)}
                    </div>
                    <div className="truncate text-[11px] text-slate-500">
                      {conv.last_message_preview || "Bez zpráv"}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
