import React from "react";
import CatalogSeriesFullscreenDetail from "@/components/catalog/search/CatalogSeriesFullscreenDetail";
import SeriesDetailPreview from "@/components/catalog/search/SeriesDetailPreview";

/**
 * Sjednocený detail řady — fullscreen na notebooku, overlay / inline na širokém desktopu.
 */
export default function CatalogDetailView({
  presentation = "fullscreen",
  useFullscreenShell = true,
  ...props
}) {
  if (useFullscreenShell && (presentation === "fullscreen" || presentation === "overlay")) {
    return <CatalogSeriesFullscreenDetail presentation={presentation} {...props} />;
  }

  return (
    <SeriesDetailPreview
      open={props.open}
      onClose={props.onClose}
      title={props.title}
      code={props.code}
      catalogLabel={props.catalogLabel}
      metadata={props.metadata}
      previewContent={props.previewContent}
      relatedItems={props.relatedItems}
      onPreview={props.onRetryPreview}
      onDownload={props.onDownload}
      onSave={props.onSave}
      autoPreview
      previewLoading={props.previewLoading}
      previewError={props.previewError}
      placement={presentation === "drawer" ? "drawer" : "bottom"}
    />
  );
}
