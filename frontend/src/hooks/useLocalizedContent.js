import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import {
  isEnglishLocale,
  localizedConfigText,
  localizedHomepageSubtitle,
  localizedHomepageTitle,
  localizedKpiTitle,
  localizedRichTextContent,
  localizedRichTextHeading,
  localizedRichTextSubheading,
  localizedSectionName,
  localizedSectionSubtitle,
  localizedSubpageTitle,
  localizedWidgetCaption,
  localizedWidgetTitle,
  normalizeAppLocale,
  pickLocalized,
} from "@/lib/localizedContent";

export function useLocalizedContent() {
  const { i18n } = useTranslation();
  const locale = normalizeAppLocale(i18n.language);

  return useMemo(
    () => ({
      locale,
      isEn: isEnglishLocale(locale),
      pick: (obj, field) => pickLocalized(obj, field, locale),
      homepageTitle: (page) => localizedHomepageTitle(page, locale),
      homepageSubtitle: (page) => localizedHomepageSubtitle(page, locale),
      sectionName: (section) => localizedSectionName(section, locale),
      sectionSubtitle: (section) => localizedSectionSubtitle(section, locale),
      subpageTitle: (page) => localizedSubpageTitle(page, locale),
      widgetTitle: (widget) => localizedWidgetTitle(widget, locale),
      widgetCaption: (widget) => localizedWidgetCaption(widget, locale),
      configText: (config, field) => localizedConfigText(config, field, locale),
      kpiTitle: (kpi) => localizedKpiTitle(kpi, locale),
      richTextHeading: (widget) => localizedRichTextHeading(widget, locale),
      richTextSubheading: (widget) => localizedRichTextSubheading(widget, locale),
      richTextContent: (widget) => localizedRichTextContent(widget, locale),
    }),
    [locale]
  );
}
