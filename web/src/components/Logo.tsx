/** The Driftwatch mark: a trace leaving its baseline. Same artwork as public/favicon.svg. */
export function Logo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={className}>
      <rect width="32" height="32" rx="7" className="fill-brand" />
      <path
        d="M6 22h20"
        className="stroke-on-brand"
        strokeOpacity={0.45}
        strokeWidth={2}
        strokeLinecap="round"
      />
      <path
        d="M6 22h6c3 0 4-11 8-11h6"
        fill="none"
        className="stroke-on-brand"
        strokeWidth={2.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
