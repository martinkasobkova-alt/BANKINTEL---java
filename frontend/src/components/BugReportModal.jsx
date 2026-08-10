import React, { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useLocation } from "react-router-dom";
import { Bug, X } from "lucide-react";
import { LoadingSpinner } from "@/components/ui/loading";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import TurnstileField from "@/components/auth/TurnstileField";

const ACCEPT = "image/png,image/jpeg,image/jpg,image/webp,.png,.jpg,.jpeg,.webp";
const MAX_B = 5 * 1024 * 1024;

export default function BugReportModal({ open, onClose }) {
  const { user } = useAuth();
  const loc = useLocation();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [contactEmail, setContactEmail] = useState("");
  const [priority, setPriority] = useState("medium");
  const [file, setFile] = useState(null);
  const [preview, setPreview] = useState(null);
  const [sending, setSending] = useState(false);
  const [captchaToken, setCaptchaToken] = useState("");

  useEffect(() => {
    if (open && user && user !== false) {
      setContactEmail((prev) => prev || (user.email || ""));
    }
  }, [open, user]);

  useEffect(() => {
    if (!file) {
      setPreview(null);
      return;
    }
    const u = URL.createObjectURL(file);
    setPreview(u);
    return () => {
      URL.revokeObjectURL(u);
    };
  }, [file]);

  const onPickFile = (e) => {
    const f = e.target.files?.[0];
    e.target.value = "";
    if (!f) return;
    if (f.size > MAX_B) {
      toast.error("Soubor je větší než 5 MB.");
      return;
    }
    const t = f.type || "";
    const name = (f.name || "").toLowerCase();
    const ok =
      t === "image/png" ||
      t === "image/jpeg" ||
      t === "image/webp" ||
      (() => /\.(png|jpe?g|webp)$/.test(name))();
    if (!ok) {
      toast.error("Povolené formáty: PNG, JPG, WEBP.");
      return;
    }
    if (f.type === "image/svg+xml" || name.endsWith(".svg")) {
      toast.error("Formát SVG není podporován.");
      return;
    }
    setFile(f);
  };

  const clearFile = () => setFile(null);

  const send = useCallback(async () => {
    const t = title.trim();
    const d = description.trim();
    if (t.length < 3) {
      toast.error("Název musí mít alespoř 3 znaky.");
      return;
    }
    if (d.length < 10) {
      toast.error("Popis musí mít alespoř 10 znaků.");
      return;
    }
    setSending(true);
    try {
      const fd = new FormData();
      fd.append("title", t);
      fd.append("description", d);
      if (contactEmail.trim()) fd.append("contact_email", contactEmail.trim());
      fd.append("page_url", typeof window !== "undefined" ? window.location.href : "");
      fd.append("user_agent", typeof navigator !== "undefined" ? navigator.userAgent : "");
      fd.append("viewport", typeof window !== "undefined" ? `${window.innerWidth}x${window.innerHeight}` : "");
      fd.append("route", loc?.pathname + (loc?.search || "") || "");
      fd.append("priority", priority);
      fd.append("captcha_token", captchaToken || "");
      if (file) fd.append("screenshot", file, file.name);
      await api.post("/bug-reports", fd, {
        headers: { "Content-Type": false },
      });
      toast.success("Děkujeme, chyba byla nahlášena.");
      setTitle("");
      setDescription("");
      setContactEmail(user && user !== false && user.email ? user.email : "");
      setPriority("medium");
      setFile(null);
      setCaptchaToken("");
      onClose();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Odeslání se nepodařilo.");
    } finally {
      setSending(false);
    }
  }, [title, description, contactEmail, priority, file, loc, onClose, user, captchaToken]);

  if (!open) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="bug-report-title"
    >
      <div
        className="bankoapp-white-panels-scope w-full max-w-lg max-h-[min(90vh,720px)] overflow-y-auto rounded-2xl border border-border/80 bg-white text-slate-800 shadow-2xl p-5"
        style={{ background: "hsl(205 70% 99% / 0.98)" }}
      >
        <div className="flex items-start justify-between gap-3 mb-4">
          <div className="flex items-center gap-2">
            <div
              className="h-9 w-9 rounded-xl flex items-center justify-center"
              style={{ background: "hsl(205 70% 92% / 0.9)" }}
            >
              <Bug className="h-5 w-5 text-slate-700" strokeWidth={1.5} />
            </div>
            <h2 id="bug-report-title" className="text-lg font-semibold text-slate-800">
              Nahlásit chybu
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-slate-200/60 text-slate-500"
            aria-label="Zavřít"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="space-y-3 text-sm">
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">Název chyby / stručný popis *</label>
            <input
              className="w-full border border-border/70 rounded-xl px-3 py-2 bg-white/90 text-slate-900 placeholder:text-slate-500"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={200}
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">Popis problému *</label>
            <textarea
              className="w-full border border-border/70 rounded-xl px-3 py-2 min-h-[100px] bg-white/90 text-slate-900 placeholder:text-slate-500"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              maxLength={5000}
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">E-mail pro kontakt</label>
            <input
              type="email"
              className="w-full border border-border/70 rounded-xl px-3 py-2 bg-white/90 text-slate-900 placeholder:text-slate-500"
              value={contactEmail}
              onChange={(e) => setContactEmail(e.target.value)}
              placeholder="volitelné"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">Priorita</label>
            <select
              className="w-full border border-border/70 rounded-xl px-3 py-2 bg-white/90 text-slate-900"
              value={priority}
              onChange={(e) => setPriority(e.target.value)}
            >
              <option value="low">Nízká</option>
              <option value="medium">Střední</option>
              <option value="high">Vysoká</option>
            </select>
          </div>
          <div>
            <span className="block text-xs font-medium text-slate-600 mb-1">Přiložit screenshot</span>
            <label className="inline-flex items-center gap-2 cursor-pointer text-[hsl(var(--primary))] font-medium text-sm">
              <input type="file" accept={ACCEPT} className="hidden" onChange={onPickFile} />
              <span>Vybrat soubor…</span>
            </label>
            {file ? (
              <div className="mt-2 space-y-1">
                <p className="text-xs text-slate-600">{file.name}</p>
                {preview ? (
                  <img src={preview} alt="Náhled" className="max-h-40 rounded-lg border object-contain" />
                ) : null}
                <button type="button" onClick={clearFile} className="text-xs text-red-700 underline">
                  Odebrat screenshot
                </button>
              </div>
            ) : null}
            <p className="text-[11px] text-slate-500 mt-1">
              Maximální velikost screenshotu je 5 MB. Povolené formáty: PNG, JPG, WEBP.
            </p>
          </div>
          <TurnstileField onToken={setCaptchaToken} className="py-1" />
          <p className="text-[11px] text-slate-500 border-t border-border/50 pt-2">
            Technické údaje o stránce a prohlížeči se odešlou spolu s hlášením.
          </p>
        </div>

        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 rounded-xl text-sm text-slate-600 hover:bg-slate-100/80"
          >
            Zrušit
          </button>
          <button
            type="button"
            onClick={send}
            disabled={sending}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-medium text-white disabled:opacity-50"
            style={{ background: "hsl(208 55% 42%)" }}
          >
            {sending ? <LoadingSpinner suppressAria size="sm" aria-label="" /> : null}
            Odeslat hlášení
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
