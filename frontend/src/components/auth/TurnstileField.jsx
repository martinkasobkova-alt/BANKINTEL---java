import React from "react";
import { Turnstile } from "@marsidev/react-turnstile";

const SITE_KEY = (process.env.REACT_APP_TURNSTILE_SITE_KEY || "").trim();

/**
 * Když není site key, widget se nerenderuje — backend může povolit odeslání
 * (vývoj / CAPTCHA_BYPASS). V produkci musí být klíč nastaven.
 */
export default function TurnstileField({ onToken, className = "" }) {
  if (!SITE_KEY) {
    return null;
  }
  return (
    <div className={className} data-testid="turnstile-container">
      <Turnstile
        siteKey={SITE_KEY}
        onSuccess={(token) => onToken?.(token)}
        onExpire={() => onToken?.("")}
        onError={() => onToken?.("")}
      />
    </div>
  );
}
