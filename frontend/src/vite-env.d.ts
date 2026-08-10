/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_EXPLORE_EXPERT_MODE?: string;
  readonly VITE_SYNC_RUNNING_STALE_UI_MS?: string;
  readonly REACT_APP_SENTRY_DSN?: string;
  readonly REACT_APP_TURNSTILE_SITE_KEY?: string;
  readonly REACT_APP_BACKEND_URL?: string;
  readonly REACT_APP_DEV_PROXY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare namespace NodeJS {
  interface ProcessEnv {
    readonly NODE_ENV: "development" | "production" | "test";
    readonly REACT_APP_SENTRY_DSN?: string;
    readonly REACT_APP_TURNSTILE_SITE_KEY?: string;
    readonly REACT_APP_BACKEND_URL?: string;
    readonly REACT_APP_DEV_PROXY?: string;
    readonly REACT_APP_PROD_SAME_ORIGIN_API?: string;
    readonly REACT_APP_API_TIMEOUT_MS?: string;
    readonly REACT_APP_EXPLORE_TIMEOUT_MS?: string;
    readonly REACT_APP_EXPLORE_POLL_MAX_MS?: string;
    readonly REACT_APP_DEEP_SEARCH_CHUNK_TIMEOUT_MS?: string;
    readonly REACT_APP_CATALOG_AI_QUICK_ESTIMATE_SEC?: string;
    readonly REACT_APP_CATALOG_AI_QUICK_TIMEOUT_MS?: string;
    readonly REACT_APP_DEEP_SEARCH_TIMEOUT_MS?: string;
    readonly REACT_APP_CATALOG_SOURCE_ROUTE_TIMEOUT_MS?: string;
  }
}

declare const process: {
  env: NodeJS.ProcessEnv;
};
