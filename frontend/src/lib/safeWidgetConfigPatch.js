/** Uložení změn widgetu — nevyhazovat nezachycenou chybu (403 bez oprávnění apod.). */
export async function safeWidgetConfigPatch(patchFn, widgetId, payload) {
  if (typeof patchFn !== "function" || !widgetId) return false;
  try {
    const result = await patchFn(widgetId, payload);
    return result !== false;
  } catch {
    return false;
  }
}
