import { useCallback } from "react";

/**
 * Drag handle pro vertikální split — táhlo nahoru zmenší spodní panel, dolů zvětší.
 */
export function useVerticalSplitDrag({
  containerRef,
  height,
  onHeightChange,
  minHeight,
  minOppositeHeight,
}) {
  const onPointerDown = useCallback(
    (e) => {
      if (e.button !== 0) return;
      e.preventDefault();
      const container = containerRef.current;
      if (!container) return;

      const startY = e.clientY;
      const startHeight = height;
      const containerHeight = container.getBoundingClientRect().height;
      const maxHeight = Math.max(minHeight, containerHeight - minOppositeHeight);

      const onMove = (ev) => {
        const delta = ev.clientY - startY;
        const next = Math.min(maxHeight, Math.max(minHeight, startHeight - delta));
        onHeightChange(next);
      };

      const onUp = () => {
        document.removeEventListener("pointermove", onMove);
        document.removeEventListener("pointerup", onUp);
        document.body.style.cursor = "";
        document.body.style.userSelect = "";
        document.body.style.touchAction = "";
      };

      document.body.style.cursor = "ns-resize";
      document.body.style.userSelect = "none";
      document.body.style.touchAction = "none";
      document.addEventListener("pointermove", onMove);
      document.addEventListener("pointerup", onUp);
    },
    [containerRef, height, minHeight, minOppositeHeight, onHeightChange],
  );

  return { onPointerDown };
}
