import { useEffect, useRef, useState } from "react";
import { Mic, MicOff } from "lucide-react";
import { toast } from "sonner";

function getSpeechRecognition() {
  if (typeof window === "undefined") return null;
  return window.SpeechRecognition || window.webkitSpeechRecognition || null;
}

function mergeTranscript(base, transcript) {
  const a = String(base || "").trim();
  const b = String(transcript || "").trim();
  if (!b) return a;
  if (!a) return b;
  return `${a} ${b}`;
}

export default function VoiceInputButton({
  value = "",
  onChange,
  disabled = false,
  className = "",
  lang = "cs-CZ",
  title = "Diktovat",
}) {
  const [listening, setListening] = useState(false);
  const recognitionRef = useRef(null);
  const baseTextRef = useRef("");

  useEffect(() => {
    return () => {
      try {
        recognitionRef.current?.stop?.();
      } catch {
        // Browser may already have stopped recognition.
      }
    };
  }, []);

  const stop = () => {
    try {
      recognitionRef.current?.stop?.();
    } catch {
      // Browser may already have stopped recognition.
    }
    setListening(false);
  };

  const start = () => {
    if (disabled) return;
    const Recognition = getSpeechRecognition();
    if (!Recognition) {
      toast.error("Hlasové zadávání není v tomto prohlížeči dostupné.");
      return;
    }
    if (listening) {
      stop();
      return;
    }

    try {
      const rec = new Recognition();
      recognitionRef.current = rec;
      baseTextRef.current = value;
      rec.lang = lang;
      rec.continuous = true;
      rec.interimResults = true;

      rec.onstart = () => setListening(true);
      rec.onend = () => setListening(false);
      rec.onerror = (event) => {
        setListening(false);
        const err = String(event?.error || "").trim();
        if (err && err !== "no-speech" && err !== "aborted") {
          toast.error(err === "not-allowed" ? "Povolte mikrofon v prohlížeči." : "Diktování se nepodařilo spustit.");
        }
      };
      rec.onresult = (event) => {
        let transcript = "";
        for (let i = 0; i < event.results.length; i += 1) {
          transcript += event.results[i][0]?.transcript || "";
        }
        onChange?.(mergeTranscript(baseTextRef.current, transcript));
      };
      rec.start();
    } catch {
      setListening(false);
      toast.error("Diktování se nepodařilo spustit.");
    }
  };

  return (
    <button
      type="button"
      onClick={start}
      disabled={disabled}
      className={`inline-flex shrink-0 items-center justify-center rounded-lg border text-xs transition disabled:opacity-45 ${
        listening
          ? "border-rose-300 bg-rose-50 text-rose-700 hover:bg-rose-100"
          : "border-sky-200 bg-white text-sky-800 hover:bg-sky-50"
      } ${className}`.trim()}
      title={listening ? "Zastavit diktování" : title}
      aria-label={listening ? "Zastavit diktování" : title}
      aria-pressed={listening}
    >
      {listening ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
    </button>
  );
}
