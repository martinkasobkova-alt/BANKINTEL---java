import React, { useRef, useState } from "react";
import {
  BarChart3,
  Bold,
  Heading1,
  Heading2,
  Heading3,
  Image as ImageIcon,
  Italic,
  List,
  Table2,
  Video,
} from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import ArchiveChartLinkPicker from "@/components/archive/ArchiveChartLinkPicker";
import {
  ARTICLE_TABLE_TEMPLATE,
  chartBlockSnippet,
  videoBlockSnippet,
} from "@/lib/articleBodyFormat";

/**
 * Rich markdown editor for article / zprávy body field.
 */
export default function ArticleRichEditor({ value, onChange, minHeight = 220 }) {
  const taRef = useRef(null);
  const imageInputRef = useRef(null);
  const [uploadingImage, setUploadingImage] = useState(false);
  const [chartPickerOpen, setChartPickerOpen] = useState(false);

  const focusTa = () => {
    requestAnimationFrame(() => taRef.current?.focus());
  };

  const insertAt = (snippet, cursorOffset = snippet.length) => {
    const ta = taRef.current;
    const text = ta?.value ?? value ?? "";
    const start = ta ? ta.selectionStart : text.length;
    const end = ta ? ta.selectionEnd : text.length;
    const next = text.slice(0, start) + snippet + text.slice(end);
    onChange(next);
    focusTa();
    requestAnimationFrame(() => {
      if (!taRef.current) return;
      const pos = start + cursorOffset;
      taRef.current.setSelectionRange(pos, pos);
    });
  };

  const wrap = (before, after = before) => {
    const ta = taRef.current;
    if (!ta) return;
    const start = ta.selectionStart;
    const end = ta.selectionEnd;
    const text = ta.value;
    const sel = text.slice(start, end) || "text";
    const next = text.slice(0, start) + before + sel + after + text.slice(end);
    onChange(next);
    requestAnimationFrame(() => {
      ta.focus();
      ta.setSelectionRange(start + before.length, start + before.length + sel.length);
    });
  };

  const prefixLine = (prefix) => {
    const ta = taRef.current;
    if (!ta) return;
    const start = ta.selectionStart;
    const text = ta.value;
    const lineStart = text.lastIndexOf("\n", start - 1) + 1;
    const lineEnd = text.indexOf("\n", start);
    const end = lineEnd === -1 ? text.length : lineEnd;
    const line = text.slice(lineStart, end);
    const stripped = line.replace(/^#{1,3}\s+/, "");
    const nextLine = `${prefix}${stripped}`;
    const next = text.slice(0, lineStart) + nextLine + text.slice(end);
    onChange(next);
    requestAnimationFrame(() => {
      ta.focus();
      const pos = lineStart + nextLine.length;
      ta.setSelectionRange(pos, pos);
    });
  };

  const uploadImage = async (file) => {
    if (!file) return;
    const formData = new FormData();
    formData.append("file", file);
    setUploadingImage(true);
    try {
      const { data } = await api.post("/media/upload", formData, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      if (!data?.url) throw new Error("Nahrání nevrátilo URL.");
      const alt = file.name?.replace(/\.[^.]+$/, "") || "Obrázek";
      insertAt(`\n\n![${alt}](${data.url})\n\n`);
      toast.success("Obrázek byl nahrán.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || e.message || "Obrázek se nepodařilo nahrát.");
    } finally {
      setUploadingImage(false);
    }
  };

  const insertVideo = () => {
    const url = window.prompt("URL videa (YouTube, Vimeo nebo MP4):", "https://");
    if (!url || url === "https://") return;
    insertAt(videoBlockSnippet(url));
  };

  const btnClass =
    "inline-flex h-7 min-w-[28px] items-center justify-center gap-1 rounded-md border border-slate-200 bg-white px-1.5 text-[11px] text-slate-700 hover:bg-slate-50 disabled:opacity-50";

  return (
    <div className="mt-1">
      <div className="flex flex-wrap items-center gap-1 rounded-t-lg border border-b-0 border-slate-300 bg-slate-50/80 p-1.5">
        <button type="button" className={btnClass} title="Nadpis" onClick={() => prefixLine("# ")}>
          <Heading1 className="h-3.5 w-3.5" />
        </button>
        <button type="button" className={btnClass} title="Podnadpis" onClick={() => prefixLine("## ")}>
          <Heading2 className="h-3.5 w-3.5" />
        </button>
        <button type="button" className={btnClass} title="Podpodnadpis" onClick={() => prefixLine("### ")}>
          <Heading3 className="h-3.5 w-3.5" />
        </button>
        <span className="mx-0.5 h-5 w-px bg-slate-200" />
        <button type="button" className={btnClass} title="Tučně (Ctrl+B)" onClick={() => wrap("**")}>
          <Bold className="h-3.5 w-3.5" />
        </button>
        <button type="button" className={btnClass} title="Kurzíva (Ctrl+I)" onClick={() => wrap("*")}>
          <Italic className="h-3.5 w-3.5" />
        </button>
        <span className="mx-0.5 h-5 w-px bg-slate-200" />
        <button
          type="button"
          className={btnClass}
          title="Odrážky"
          onClick={() => insertAt("- položka\n- položka\n")}
        >
          <List className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          className={btnClass}
          title="Tabulka"
          onClick={() => insertAt(`\n\n${ARTICLE_TABLE_TEMPLATE}\n`)}
        >
          <Table2 className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          className={btnClass}
          title="Obrázek"
          disabled={uploadingImage}
          onClick={() => imageInputRef.current?.click()}
        >
          <ImageIcon className="h-3.5 w-3.5" />
        </button>
        <button type="button" className={btnClass} title="Video" onClick={insertVideo}>
          <Video className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          className={`${btnClass} border-indigo-200 bg-indigo-50 text-indigo-800 hover:bg-indigo-100`}
          title="Vložit graf"
          onClick={() => setChartPickerOpen(true)}
        >
          <BarChart3 className="h-3.5 w-3.5" />
          <span className="hidden sm:inline">Graf</span>
        </button>
        <input
          ref={imageInputRef}
          type="file"
          accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml"
          className="hidden"
          onChange={(e) => {
            uploadImage(e.target.files?.[0]);
            e.target.value = "";
          }}
        />
      </div>
      <textarea
        ref={taRef}
        className="min-h-[200px] w-full rounded-b-lg rounded-t-none border border-slate-300 px-3 py-2 font-mono text-[13px] leading-relaxed"
        style={{ minHeight }}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "b") {
            e.preventDefault();
            wrap("**");
          } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "i") {
            e.preventDefault();
            wrap("*");
          }
        }}
        placeholder={`Nadpis: # Titulek, ## Podnadpis, ### Podpodnadpis\n\n**tučně**, *kurzíva*, odrážky (- položka), tabulka (| sloupce |)\n\nObrázek: ![popis](url) nebo tlačítko 🖼\n\nGraf: tlačítko Graf — vyhledání v katalogu, dashboardu nebo vlastních datech`}
      />
      <p className="mt-1 text-[11px] text-slate-500 leading-snug">
        Formátování v Markdownu. Graf se vloží jako živý náhled v článku. Video: YouTube, Vimeo nebo MP4 odkaz.
      </p>
      <ArchiveChartLinkPicker
        open={chartPickerOpen}
        onClose={() => setChartPickerOpen(false)}
        onPick={(chart) => insertAt(chartBlockSnippet(chart))}
      />
    </div>
  );
}
