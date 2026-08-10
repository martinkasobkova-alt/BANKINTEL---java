import { useEffect, useState, useCallback } from "react";

export const AI_SEARCH_SCOPE_QUICK = "quick";
export const AI_SEARCH_SCOPE_EXTENDED = "extended";

export function useCatalogSearchState({
  initialCatalogQ,
  initialSelectedSet,
}) {
  const [selected, setSelected] = useState(() => new Set(initialSelectedSet || []));
  const [crossSearchQuery, setCrossSearchQuery] = useState(initialCatalogQ || "");
  /** Potvrzený dotaz — spouští klasické backend hledání (Enter / Hledat). */
  const [submittedCrossQuery, setSubmittedCrossQuery] = useState("");
  /** Inkrementuje se při každém Hledat — i stejný dotaz musí znovu spustit backend. */
  const [crossSearchSubmitNonce, setCrossSearchSubmitNonce] = useState(0);
  const [aiQuery, setAiQuery] = useState(initialCatalogQ || "");
  const [debouncedAi, setDebouncedAi] = useState("");
  const [useAiAssistant, setUseAiAssistant] = useState(true);
  const [aiSearchScope, setAiSearchScope] = useState(AI_SEARCH_SCOPE_QUICK);
  const [quickAiDbId, setQuickAiDbId] = useState("arad");

  const submitCrossSearch = useCallback((query) => {
    const q = String(query ?? crossSearchQuery ?? "").trim();
    setSubmittedCrossQuery(q);
    setCrossSearchSubmitNonce((n) => n + 1);
    return q;
  }, [crossSearchQuery]);

  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedAi(aiQuery), 380);
    return () => window.clearTimeout(t);
  }, [aiQuery]);

  return {
    selected,
    setSelected,
    crossSearchQuery,
    setCrossSearchQuery,
    submittedCrossQuery,
    setSubmittedCrossQuery,
    crossSearchSubmitNonce,
    submitCrossSearch,
    aiQuery,
    setAiQuery,
    debouncedAi,
    useAiAssistant,
    setUseAiAssistant,
    aiSearchScope,
    setAiSearchScope,
    quickAiDbId,
    setQuickAiDbId,
  };
}

