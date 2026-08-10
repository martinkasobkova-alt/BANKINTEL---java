import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import api from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";

export const FeatureAccessContext = createContext(null);

/**
 * Fetches GET /api/feature-access/effective once (and on auth user change) and
 * provides a map: featureKey -> { access_level, allowed }.
 */
export function FeatureAccessProvider({ children }) {
  const { user } = useAuth();
  /** null = ještě žádná odpověď; objekt = mapa (i prázdná po chybě) */
  const [effective, setEffective] = useState(null);
  /** true během každého fetchu (včetně refetche) — pro placené akce fail-closed. */
  const [inFlight, setInFlight] = useState(true);
  const [error, setError] = useState(null);

  const userKey = user?.id || user?.email || "anon";

  const refetch = useCallback(async () => {
    setInFlight(true);
    setError(null);
    try {
      const { data } = await api.get("/feature-access/effective");
      setEffective(data && typeof data === "object" ? data : {});
    } catch (e) {
      setError(e);
      setEffective({});
    } finally {
      setInFlight(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (cancelled) return;
      await refetch();
    })();
    return () => {
      cancelled = true;
    };
  }, [refetch, userKey]);

  const accessMapReady = !inFlight && effective != null;

  const value = useMemo(
    () => ({
      effective,
      inFlight,
      accessMapReady,
      error,
      refetch,
    }),
    [effective, inFlight, accessMapReady, error, refetch]
  );

  return <FeatureAccessContext.Provider value={value}>{children}</FeatureAccessContext.Provider>;
}

export function useFeatureAccessContext() {
  const ctx = useContext(FeatureAccessContext);
  if (!ctx) {
    throw new Error("useFeatureAccessContext must be used within FeatureAccessProvider");
  }
  return ctx;
}

/** Optional: no throw when used outside provider (e.g. tests). */
export function useFeatureAccessContextOptional() {
  return useContext(FeatureAccessContext);
}
