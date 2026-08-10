import React from "react";
import { useTranslation } from "react-i18next";

/**
 * Dvojjazyčný pár polí pro CMS obsah (CS povinný obsah + volitelný EN).
 */
export default function LocalizedTextFields({
  labelCs,
  labelEn,
  hintEn,
  valueCs = "",
  valueEn = "",
  onChangeCs,
  onChangeEn,
  placeholderCs = "",
  placeholderEn = "",
  multiline = false,
  rows = 3,
  className = "",
  inputClassName = "input",
}) {
  const { t } = useTranslation();
  const enLabel = labelEn || t("cms.labelEnOptional");
  const enHint = hintEn ?? t("cms.enFallbackHint");
  const InputTag = multiline ? "textarea" : "input";
  const sharedProps = multiline ? { rows } : {};

  return (
    <div className={`space-y-2 ${className}`}>
      <div>
        <label className="mb-1 block text-[11px] font-semibold text-foreground/90">{labelCs}</label>
        <InputTag
          className={`${inputClassName} w-full ${multiline ? "min-h-[72px]" : ""}`}
          value={valueCs}
          onChange={(e) => onChangeCs?.(e.target.value)}
          placeholder={placeholderCs}
          {...sharedProps}
        />
      </div>
      <div>
        <label className="mb-1 block text-[11px] font-semibold text-foreground/80">{enLabel}</label>
        <InputTag
          className={`${inputClassName} w-full ${multiline ? "min-h-[72px]" : ""}`}
          value={valueEn}
          onChange={(e) => onChangeEn?.(e.target.value)}
          placeholder={placeholderEn || placeholderCs}
          {...sharedProps}
        />
        <p className="mt-1 text-[10px] text-muted-foreground leading-snug">{enHint}</p>
      </div>
    </div>
  );
}
