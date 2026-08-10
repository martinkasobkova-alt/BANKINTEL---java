import React from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/contexts/AuthContext";

function LoadingGate() {
  const { t } = useTranslation();
  return (
    <div className="min-h-screen grid place-items-center text-slate-500 font-mono text-sm">
      {t("protected.loading")}
    </div>
  );
}

export function AdminRoute({ children, fallbackTo = "/", preserveSearch = false }) {
  const { ready, isAdmin } = useAuth();
  const { search } = useLocation();
  if (!ready) return <LoadingGate />;
  if (!isAdmin) {
    const target = preserveSearch ? mergeSearchParams(fallbackTo, search) : fallbackTo;
    return <Navigate to={target} replace />;
  }
  return children;
}

function mergeSearchParams(target, currentSearch) {
  const parsed = new URL(String(target || "/"), "http://bankintel.local");
  const current = new URLSearchParams(currentSearch);
  current.forEach((value, key) => {
    if (!parsed.searchParams.has(key)) parsed.searchParams.append(key, value);
  });
  return `${parsed.pathname}${parsed.search}${parsed.hash}`;
}

/** Admin nebo editor — zprávy a widgety na přehledu / v sekcích. */
export function EditorRoute({ children }) {
  const { ready, canEditContent } = useAuth();
  if (!ready) return <LoadingGate />;
  if (!canEditContent) return <Navigate to="/" replace />;
  return children;
}

export function AdminOnly({ children, fallback = null }) {
  const { isAdmin } = useAuth();
  return isAdmin ? children : fallback;
}

export function EditorOnly({ children, fallback = null }) {
  const { canEditContent } = useAuth();
  return canEditContent ? children : fallback;
}

export function AuthRoute({ children }) {
  const { ready, user } = useAuth();
  if (!ready) return <LoadingGate />;
  if (!user || user === false) return <Navigate to="/" replace />;
  return children;
}

/** Předplatitelské stránky (Moje data, osobní dashboard) – není to správa globálních zdrojů. */
export function SubscriberRoute({ children }) {
  const { ready, user, isSubscriber, isAdmin } = useAuth();
  if (!ready) return <LoadingGate />;
  if (!user || user === false) return <Navigate to="/" replace />;
  if (!isSubscriber && !isAdmin) return <Navigate to="/predplatne" replace />;
  return children;
}
