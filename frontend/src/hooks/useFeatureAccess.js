import { useMemo } from "react";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccessContextOptional } from "@/contexts/FeatureAccessContext";

const LOADING_MSG = "Kontrola oprávnění…";

/**
 * @param {string} featureKey
 * @param {{ user?: object | null | false, accessLevel: string, featureKey: string }} opts
 */
function buildLockMessage({ user, accessLevel, featureKey }) {
  if (accessLevel === "admin") {
    return "Tato funkce je dostupná pouze administrátorům.";
  }
  if (accessLevel === "registered") {
    return "Tato funkce je dostupná po přihlášení.";
  }
  if (accessLevel === "subscriber") {
    if (featureKey === "export_data") {
      if (user && user !== false) {
        return "Export dat je dostupný pouze pro předplatitele časopisu Bankovnictví.";
      }
    }
    if (featureKey === "chart_image_export") {
      return "Stažení grafu jako PNG/JPG je dostupné pouze pro předplatitele časopisu Bankovnictví.";
    }
    return "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.";
  }
  return "Tato funkce není k dispozici.";
}

/**
 * Fail-closed: dokud není access mapa načtená (`ready === false`), `allowed` je false.
 * Chybějící klíč v mapě nebo selhání endpointu (prázdná mapa) = zamčeno.
 *
 * @param {string} featureKey
 * @returns {{
 *   allowed: boolean,
 *   access_level: string,
 *   loading: boolean,
 *   ready: boolean,
 *   message: string | null,
 *   error: Error | null
 * }}
 */
export function useFeatureAccess(featureKey) {
  const { user, isAdmin } = useAuth();
  const ctx = useFeatureAccessContextOptional();
  const effective = ctx?.effective;
  const accessMapReady = ctx?.accessMapReady;
  const error = ctx?.error ?? null;
  const hasProvider = ctx != null;

  return useMemo(() => {
    if (isAdmin) {
      return {
        allowed: true,
        access_level: "admin",
        loading: false,
        ready: true,
        message: null,
        error: null,
      };
    }

    const ready = hasProvider && accessMapReady === true;
    const entry =
      ready && featureKey && !String(featureKey).startsWith("_")
        ? effective?.[featureKey]
        : undefined;
    const hasEntry = entry != null && typeof entry === "object";
    const allowed = ready && hasEntry && entry.allowed === true;
    const access_level = (hasEntry && entry.access_level) || "subscriber";
    const loading = !ready;

    let message = null;
    if (loading) {
      message = LOADING_MSG;
    } else if (ready && !allowed) {
      if (!hasEntry) {
        message = "Tato funkce není v přístupové mapě k dispozici. Zkuste obnovit stránku.";
      } else {
        message = buildLockMessage({ user, accessLevel: access_level, featureKey });
      }
    }

    return {
      allowed,
      access_level,
      loading,
      ready,
      message,
      error,
    };
  }, [isAdmin, hasProvider, effective, accessMapReady, featureKey, error, user]);
}
