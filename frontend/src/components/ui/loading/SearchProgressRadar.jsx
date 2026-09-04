import React from "react";

const SIZE = 160;
const CENTER = SIZE / 2;
const RING_INNER = 40;
const RING_OUTER = 62;
const SWEEP_RADIUS = (RING_INNER + RING_OUTER) / 2;

/**
 * Dva jemné soustředné kruhy, lupa obíhající po dráze mezi nimi (ne fixně uprostřed — na
 * přání uživatelky ať se appka viditelně "hýbe") a body zdrojů rozmístěné po vnějším kruhu,
 * rotující jako skupina. Barva/stav bodu (čeká/aktivní/hotovo) je jediná věc, co se mění
 * per-zdroj — pozice se odvozuje jen z pořadí, aby appka nikdy netvrdila nic o KONKRÉTNÍM
 * zdroji, co doopravdy neví (viz `mode="catalog"` v SearchProgressCard, kde je `state`
 * u všech zdrojů buď "pending" nebo "done", nikdy "active").
 */
export default function SearchProgressRadar({ sources = [], size = SIZE }) {
  return (
    <div
      className="relative shrink-0"
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} width={size} height={size}>
        <circle
          cx={CENTER}
          cy={CENTER}
          r={RING_INNER}
          className="fill-none stroke-[hsl(var(--primary)/0.16)]"
          strokeWidth={1.5}
        />
        <circle
          cx={CENTER}
          cy={CENTER}
          r={RING_OUTER}
          className="fill-none stroke-[hsl(var(--primary)/0.12)]"
          strokeWidth={1.5}
        />
        <circle cx={CENTER} cy={CENTER} r={2.5} className="fill-[hsl(var(--primary)/0.35)]" />

        {/* Lupa obíhá po dráze mezi oběma kruhy; vnitřní <g> se otáčí opačným směrem, aby
            samotná ikona zůstala vzpřímená (nesměřuje šikmo), jen mění pozici po kružnici. */}
        <g
          className="motion-safe:animate-[radar-spin_9s_linear_infinite]"
          style={{ transformOrigin: `${CENTER}px ${CENTER}px` }}
        >
          <g transform={`translate(${CENTER + SWEEP_RADIUS} ${CENTER})`}>
            <g className="motion-safe:animate-[radar-spin_9s_linear_infinite_reverse]">
              <circle
                r={9}
                className="fill-white stroke-[hsl(var(--primary)/0.22)]"
                strokeWidth={1}
              />
              <circle
                cx={-1.3}
                cy={-1.3}
                r={3.6}
                className="fill-none stroke-[hsl(var(--primary-deep))]"
                strokeWidth={1.6}
              />
              <line
                x1={1.1}
                y1={1.1}
                x2={3.3}
                y2={3.3}
                className="stroke-[hsl(var(--primary-deep))]"
                strokeWidth={1.6}
                strokeLinecap="round"
              />
            </g>
          </g>
        </g>

        <g
          className="motion-safe:animate-[radar-spin_22s_linear_infinite]"
          style={{ transformOrigin: `${CENTER}px ${CENTER}px` }}
        >
          {sources.map((source, index) => {
            const angle = (2 * Math.PI * index) / Math.max(sources.length, 1) - Math.PI / 2;
            const x = CENTER + RING_OUTER * Math.cos(angle);
            const y = CENTER + RING_OUTER * Math.sin(angle);
            return (
              <g key={source.id} transform={`translate(${x} ${y})`}>
                {source.state === "done" ? (
                  <>
                    <circle r={6} className="fill-emerald-400" />
                    <path
                      d="M-2.6 0.2 L-0.6 2.2 L3 -2.4"
                      className="stroke-white"
                      strokeWidth={1.6}
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      fill="none"
                    />
                  </>
                ) : source.state === "active" ? (
                  <circle
                    r={5}
                    className="fill-[hsl(var(--primary))] motion-safe:animate-[radar-breathe_1.8s_ease-in-out_infinite]"
                    style={{ transformOrigin: "0px 0px" }}
                  />
                ) : (
                  <circle r={4} className="fill-slate-300" />
                )}
              </g>
            );
          })}
        </g>
      </svg>
    </div>
  );
}
