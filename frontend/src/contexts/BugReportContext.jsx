import React, { createContext, useCallback, useContext, useMemo, useState } from "react";
import BugReportModal from "@/components/BugReportModal";

const BugReportContext = createContext(null);

export function BugReportProvider({ children }) {
  const [open, setOpen] = useState(false);
  const openBugReport = useCallback(() => setOpen(true), []);
  const closeBugReport = useCallback(() => setOpen(false), []);
  const value = useMemo(
    () => ({ openBugReport, closeBugReport, bugReportOpen: open }),
    [open, openBugReport, closeBugReport]
  );
  return (
    <BugReportContext.Provider value={value}>
      {children}
      <BugReportModal open={open} onClose={closeBugReport} />
    </BugReportContext.Provider>
  );
}

export function useBugReport() {
  const ctx = useContext(BugReportContext);
  if (!ctx) {
    throw new Error("useBugReport must be used within BugReportProvider");
  }
  return ctx;
}
