import { useCallback, useEffect, useRef, useState } from "react";

function isPanBlockedTarget(target) {
  if (!target || typeof target.closest !== "function") return false;
  return Boolean(
    target.closest(
      "button, a, input, textarea, select, label, [data-archive-chart-link], [contenteditable='true']"
    )
  );
}

/**
 * Tažení (ručka) pro posun zoomnutého PDF — jako Hand tool v Acrobat Reader.
 * Volitelný callback onPinchZoom(scaleFactor) pro zoom dvěma prsty na dotykových zařízeních.
 */
export function useArchiveReaderPan(containerRef, enabled, onPinchZoom) {
  const panRef = useRef({
    active: false,
    pointerId: null,
    startX: 0,
    startY: 0,
    scrollLeft: 0,
    scrollTop: 0,
  });
  const pinchRef = useRef({ active: false, lastDist: 0 });
  const touchPanRef = useRef({
    active: false,
    startX: 0,
    startY: 0,
    scrollLeft: 0,
    scrollTop: 0,
  });
  const [panning, setPanning] = useState(false);

  const endPan = useCallback(() => {
    const el = containerRef.current;
    const pan = panRef.current;
    if (!pan.active) return;
    if (el && pan.pointerId != null) {
      try {
        el.releasePointerCapture(pan.pointerId);
      } catch {
        /* ignore */
      }
    }
    pan.active = false;
    pan.pointerId = null;
    setPanning(false);
  }, [containerRef]);

  const onPointerDown = useCallback(
    (event) => {
      if (!enabled) return;
      if (event.button !== 0) return;
      if (isPanBlockedTarget(event.target)) return;
      // Don't start single-finger pan while pinch is active
      if (pinchRef.current.active) return;

      const el = containerRef.current;
      if (!el) return;

      panRef.current = {
        active: true,
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        scrollLeft: el.scrollLeft,
        scrollTop: el.scrollTop,
      };
      setPanning(true);
      try {
        el.setPointerCapture(event.pointerId);
      } catch {
        /* ignore */
      }
    },
    [containerRef, enabled]
  );

  const onPointerMove = useCallback(
    (event) => {
      const el = containerRef.current;
      const pan = panRef.current;
      if (!enabled || !pan.active || !el || pan.pointerId !== event.pointerId) return;

      const dx = event.clientX - pan.startX;
      const dy = event.clientY - pan.startY;
      if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
        event.preventDefault();
      }
      el.scrollLeft = pan.scrollLeft - dx;
      el.scrollTop = pan.scrollTop - dy;
    },
    [containerRef, enabled]
  );

  const onPointerUp = useCallback(
    (event) => {
      const pan = panRef.current;
      if (!pan.active) return;
      if (pan.pointerId !== event.pointerId) return;
      endPan();
    },
    [endPan]
  );

  // Pinch-to-zoom via native touch/gesture events. iOS Safari must be stopped
  // already on touchstart/gesturestart, otherwise it zooms the whole page.
  useEffect(() => {
    const el = containerRef.current;
    if (!el || !onPinchZoom) return;

    const onTouchStart = (e) => {
      if (e.touches.length === 2) {
        e.preventDefault();
        e.stopPropagation();
        const t0 = e.touches[0];
        const t1 = e.touches[1];
        const dist = Math.hypot(t1.clientX - t0.clientX, t1.clientY - t0.clientY);
        pinchRef.current = { active: true, lastDist: dist };
        // End any ongoing single-finger pan
        endPan();
        touchPanRef.current.active = false;
      } else if (enabled && e.touches.length === 1 && !isPanBlockedTarget(e.target)) {
        const t0 = e.touches[0];
        touchPanRef.current = {
          active: true,
          startX: t0.clientX,
          startY: t0.clientY,
          scrollLeft: el.scrollLeft,
          scrollTop: el.scrollTop,
        };
      } else {
        pinchRef.current.active = false;
        touchPanRef.current.active = false;
      }
    };

    const onTouchMove = (e) => {
      if (e.touches.length === 2) {
        e.preventDefault();
        e.stopPropagation();
      }
      if (!pinchRef.current.active || e.touches.length !== 2) return;
      const t0 = e.touches[0];
      const t1 = e.touches[1];
      const dist = Math.hypot(t1.clientX - t0.clientX, t1.clientY - t0.clientY);
      const lastDist = pinchRef.current.lastDist || dist;
      if (lastDist > 0) {
        const scale = dist / lastDist;
        onPinchZoom(scale);
      }
      pinchRef.current.lastDist = dist;
    };

    const onTouchPanMove = (e) => {
      if (!enabled || pinchRef.current.active || e.touches.length !== 1 || !touchPanRef.current.active) return;
      const t0 = e.touches[0];
      const dx = t0.clientX - touchPanRef.current.startX;
      const dy = t0.clientY - touchPanRef.current.startY;
      if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
        e.preventDefault();
        e.stopPropagation();
      }
      el.scrollLeft = touchPanRef.current.scrollLeft - dx;
      el.scrollTop = touchPanRef.current.scrollTop - dy;
    };

    const onTouchEnd = () => {
      pinchRef.current.active = false;
      touchPanRef.current.active = false;
    };

    const preventNativeGesture = (e) => {
      e.preventDefault();
      e.stopPropagation();
    };

    el.addEventListener("touchstart", onTouchStart, { passive: false });
    el.addEventListener("touchmove", onTouchMove, { passive: false });
    el.addEventListener("touchmove", onTouchPanMove, { passive: false });
    el.addEventListener("touchend", onTouchEnd, { passive: true });
    el.addEventListener("touchcancel", onTouchEnd, { passive: true });
    el.addEventListener("gesturestart", preventNativeGesture, { passive: false });
    el.addEventListener("gesturechange", preventNativeGesture, { passive: false });
    el.addEventListener("gestureend", preventNativeGesture, { passive: false });

    return () => {
      el.removeEventListener("touchstart", onTouchStart);
      el.removeEventListener("touchmove", onTouchMove);
      el.removeEventListener("touchmove", onTouchPanMove);
      el.removeEventListener("touchend", onTouchEnd);
      el.removeEventListener("touchcancel", onTouchEnd);
      el.removeEventListener("gesturestart", preventNativeGesture);
      el.removeEventListener("gesturechange", preventNativeGesture);
      el.removeEventListener("gestureend", preventNativeGesture);
    };
  }, [containerRef, enabled, onPinchZoom, endPan]);

  const viewportProps = enabled
    ? {
        onPointerDown,
        onPointerMove,
        onPointerUp,
        onPointerCancel: endPan,
        onLostPointerCapture: endPan,
      }
    : {};

  const viewportClassName = enabled
    ? ` archive-reader-pan${panning ? " archive-reader-pan-active" : ""}`
    : "";

  return { panning, viewportProps, viewportClassName };
}
