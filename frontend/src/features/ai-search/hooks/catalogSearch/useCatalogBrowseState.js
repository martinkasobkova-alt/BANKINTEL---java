import { useState } from "react";

export function useCatalogBrowseState(initialBrowseCatalogId = "") {
  const [browseCatalogId, setBrowseCatalogId] = useState(() => String(initialBrowseCatalogId || "").trim());
  const [browsePanelFilter, setBrowsePanelFilter] = useState("");
  const [browseLocalBranchOnly, setBrowseLocalBranchOnly] = useState(false);
  const [debouncedBrowsePanelFilter, setDebouncedBrowsePanelFilter] = useState("");
  const [openPaths, setOpenPaths] = useState(() => new Set());
  return {
    browseCatalogId,
    setBrowseCatalogId,
    browsePanelFilter,
    setBrowsePanelFilter,
    browseLocalBranchOnly,
    setBrowseLocalBranchOnly,
    debouncedBrowsePanelFilter,
    setDebouncedBrowsePanelFilter,
    openPaths,
    setOpenPaths,
  };
}

