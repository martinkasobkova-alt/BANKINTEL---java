import React from "react";

/**
 * Simulates landscape layout on a portrait phone by rotating the chart 90°.
 * Parent should fill the available viewport (fixed/absolute inset-0).
 */
export default function MobileChartLandscapeShell({ children, className = "" }) {
  return (
    <div
      className={`banko-mobile-chart-landscape-host absolute inset-0 overflow-hidden bg-[hsl(var(--background))] ${className}`}
    >
      <div
        className="banko-mobile-chart-landscape-inner absolute top-1/2 left-1/2 flex min-h-0 min-w-0 flex-col overflow-hidden"
        style={{
          width: "100dvh",
          height: "100dvw",
          maxWidth: "100dvh",
          maxHeight: "100dvw",
          transform: "translate(-50%, -50%) rotate(90deg)",
          transformOrigin: "center center",
        }}
      >
        {children}
      </div>
    </div>
  );
}
