/**
 * Pravidla hesla — musí odpovídat backendu `password_policy.password_strength_error`.
 * Min. 8 znaků, písmeno, číslice, speciální znak (ASCII interpunkce).
 */

const SPECIAL_RE = /[!"#$%&'()*+,\-./:;<=>?@[\\\]^_`{|}~]/;

/**
 * @param {string} password
 * @returns {{ ok: boolean, message: string }}
 */
export function validatePasswordClient(password) {
  const p = password == null ? "" : String(password);
  if (p.length < 8) {
    return { ok: false, message: "Heslo musí mít alespoň 8 znaků." };
  }
  if (!/[A-Za-z]/.test(p)) {
    return { ok: false, message: "Heslo musí obsahovat alespoň jedno písmeno." };
  }
  if (!/\d/.test(p)) {
    return { ok: false, message: "Heslo musí obsahovat alespoň jednu číslici." };
  }
  if (!SPECIAL_RE.test(p)) {
    return { ok: false, message: "Heslo musí obsahovat alespoň jeden speciální znak (např. !@#$%)." };
  }
  return { ok: true, message: "" };
}

/** Krátký text do UI (registrace / nastavení). */
export const PASSWORD_POLICY_HINT =
  "Heslo: min. 8 znaků, alespoň jedno písmeno, jedna číslice a jeden speciální znak (!\"#$%…).";
