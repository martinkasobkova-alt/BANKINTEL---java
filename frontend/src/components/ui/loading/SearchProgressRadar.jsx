import React, { useId } from "react";

const SIZE = 160;
const CENTER = SIZE / 2;
const RING_INNER = 40;
const RING_OUTER = 62;
const SWEEP_ANGLE_DEG = 50;

function sweepWedgePath(radius, angleDeg) {
  const rad = (angleDeg * Math.PI) / 180;
  const x = radius * Math.cos(rad);
  const y = radius * Math.sin(rad);
  return `M 0 0 L ${radius} 0 A ${radius} ${radius} 0 0 1 ${x.toFixed(2)} ${y.toFixed(2)} Z`;
}

/**
 * Dva jemné soustředné kruhy, měkký rotující "sweep" výsek na pozadí (čistě dekorativní — ať
 * karta nepůsobí prázdně, i když je zdrojů málo/žádné), lupa bloumající po vnitřní ploše (ne
 * po dráze kruhu — na přání "ať se pohne, ne ať se točí do kolečka") a body zdrojů rozmístěné
 * po vnějším kruhu, rotující jako skupina. Barva/stav bodu (čeká/aktivní/hotovo) je jediná věc,
 * co se mění per-zdroj — pozice se odvozuje jen z pořadí, aby appka nikdy netvrdila nic o
 * KONKRÉTNÍM zdroji, co doopravdy neví (viz `mode="catalog"` v SearchProgressCard, kde je
 * `state` u všech zdrojů buď "pending" nebo "done", nikdy "active").
 */
export default function SearchProgressRadar({ sources = [], size = SIZE }) {
  const gradientId = useId();
  return (
    <div
      className="relative shrink-0"
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} width={size} height={size}>
        <defs>
          <radialGradient id={gradientId} cx="0" cy="0" r={RING_OUTER} gradientUnits="userSpaceOnUse">
            <stop offset="0%" stopColor="hsl(var(--primary))" stopOpacity="0.3" />
            <stop offset="100%" stopColor="hsl(var(--primary))" stopOpacity="0" />
          </radialGradient>
        </defs>
        <g
          className="motion-safe:animate-[radar-spin_6s_linear_infinite]"
          style={{ transformOrigin: `${CENTER}px ${CENTER}px` }}
        >
          <g transform={`translate(${CENTER} ${CENTER})`}>
            <path d={sweepWedgePath(RING_OUTER, SWEEP_ANGLE_DEG)} fill={`url(#${gradientId})`} />
          </g>
        </g>
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

        {/* Lupa se přesouvá mezi několika body uvnitř kruhu (doprava, doleva, dolů, ...) -
            translate, ne rotace po dráze, ať to vypadá jako hledání, ne jako hodinový strojek. */}
        <g transform={`translate(${CENTER} ${CENTER})`}>
          <g className="motion-safe:animate-[radar-wander_7s_ease-in-out_infinite]">
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
