import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import * as Sentry from "@sentry/react";

import "@/index.css";
import "@/i18n";
import App from "@/App";

const dsn =
  typeof process.env.REACT_APP_SENTRY_DSN === "string"
    ? process.env.REACT_APP_SENTRY_DSN.trim()
    : "";
if (dsn) {
  Sentry.init({
    dsn,
    integrations: [],
    tracesSampleRate: 0.1,
    environment: import.meta.env.PROD ? "production" : "development",
  });
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
