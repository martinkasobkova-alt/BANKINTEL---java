import React, { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import api, { formatApiErrorFromAxios } from "@/lib/api";

/**
 * Zpracuje odkaz /verify-email?token= (SPA) a přesměrování z GET /api/auth/verify-email?verified=1|error=1
 */
export default function VerifyEmailPage() {
  const [search, setSearch] = useSearchParams();
  const [status, setStatus] = useState("init"); // init | working | ok | err
  const [message, setMessage] = useState("");

  useEffect(() => {
    const v = search.get("verified");
    const e = search.get("error");
    if (v === "1") {
      setStatus("ok");
      setMessage("E-mail byl potvrzen. Nyní se můžete přihlásit.");
      return;
    }
    if (e === "1") {
      setStatus("err");
      setMessage("Odkaz je neplatný nebo vypršel. Požádejte o nový e-mail s ověřením.");
      return;
    }

    const token = (search.get("token") || "").trim();
    if (!token) {
      setStatus("err");
      setMessage(
        "Otevřete prosím celý odkaz z ověřovacího e-mailu, nebo v přihlášení znovu požádejte o zaslání ověřovacího e-mailu.",
      );
      return;
    }

    setStatus("working");
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.post("/auth/verify-email", { token });
        if (cancelled) return;
        setStatus("ok");
        setMessage(data?.message || "E-mail byl potvrzen. Nyní se můžete přihlásit.");
        setSearch({ verified: "1" }, { replace: true });
      } catch (err) {
        if (cancelled) return;
        setStatus("err");
        setMessage(formatApiErrorFromAxios(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [search, setSearch]);

  return (
    <div className="min-h-[50vh] flex items-center justify-center p-6">
      <div className="soft-card max-w-lg w-full p-6 sm:p-8 space-y-4">
        <h1 className="font-serif text-2xl text-slate-900">Ověření e-mailu</h1>
        {status === "working" && <p className="text-slate-600">Probíhá ověření…</p>}
        {status === "ok" && <p className="text-slate-700 leading-relaxed">{message}</p>}
        {status === "err" && <p className="text-rose-800 leading-relaxed">{message}</p>}
        <div className="pt-2">
          <Link
            to="/"
            className="inline-flex items-center text-[hsl(var(--primary))] font-medium underline"
          >
            Zpět na úvod
          </Link>
        </div>
      </div>
    </div>
  );
}
