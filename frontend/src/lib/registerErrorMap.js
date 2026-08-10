import { formatApiError } from "./api";

/** Mapování odpovědí POST /auth/register na krátké češtiny pro UI. */
export function mapRegisterHttpError(e) {
  const status = e?.response?.status;
  const raw = e?.response?.data?.detail;
  const detail = typeof raw === "string" ? raw : formatApiError(raw) || "";
  if (status === 403) {
    return "Registrační kód není správný.";
  }
  if (
    status === 400 &&
    (detail.includes("Invalid subscriber registration code") || detail.includes("registration code"))
  ) {
    return "Registrační kód není správný.";
  }
  if (status === 400 && (detail.includes("Email already") || detail.includes("already in use"))) {
    return "Účet s tímto e-mailem už existuje.";
  }
  if (status === 400 && (detail.includes("captcha") || detail.includes("Captch"))) {
    return "Dokončete ověření captcha (ochrana proti robotům).";
  }
  if (status === 400 && (detail.includes("proti spamu") || detail.includes("Turnstile"))) {
    return detail.includes("Ověření proti spamu")
      ? detail
      : "Ověření proti spamu se nezdařilo. Zkuste to prosím znovu.";
  }
  if (status === 429) {
    return "Příliš mnoho pokusů. Počkejte chvíli a zkuste to znovu.";
  }
  if (status === 503 && detail && String(detail).toLowerCase().includes("captcha")) {
    return "Server nemá nakonfigurovanou captcha. Kontaktujte provozovatele.";
  }
  return formatApiError(raw) || "Registrace se nepodařila. Zkuste to znovu.";
}
