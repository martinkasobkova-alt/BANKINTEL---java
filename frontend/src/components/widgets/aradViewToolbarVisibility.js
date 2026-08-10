/** Pure visibility rules for AradView chart toolbar groups. */

export function resolveChartCompareToolbarVisible({
  catalogLivePreview = false,
  canEditChartCompare = false,
  canEditUploadSeries = false,
  isMultiSeries = false,
} = {}) {
  if (catalogLivePreview) return false;
  return Boolean(canEditChartCompare || canEditUploadSeries || !isMultiSeries);
}

export function resolveChartTransformToolbarVisible({
  allowedTransformCount = 0,
  hasDates = false,
  latestDataMode = false,
} = {}) {
  return allowedTransformCount > 1 && hasDates && !latestDataMode;
}

export function resolveChartActionsInSidePanel({
  showMobileChrome = false,
  controlsInOptionsPanel = false,
  showInteractiveControls = true,
  fsExpand = false,
} = {}) {
  return !showMobileChrome && controlsInOptionsPanel && showInteractiveControls && !fsExpand;
}
