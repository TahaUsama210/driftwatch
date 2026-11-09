import type { Tone } from '../../domain/tone';

/** Static class names per tone (kept literal so Tailwind can see them). */
export const TONE_CLASSES: Record<Tone, { soft: string; ink: string; fill: string }> = {
  good: { soft: 'bg-good-soft', ink: 'text-good-ink', fill: 'bg-good' },
  warning: { soft: 'bg-warning-soft', ink: 'text-warning-ink', fill: 'bg-warning' },
  serious: { soft: 'bg-serious-soft', ink: 'text-serious-ink', fill: 'bg-serious' },
  critical: { soft: 'bg-critical-soft', ink: 'text-critical-ink', fill: 'bg-critical' },
  neutral: { soft: 'bg-neutral-soft', ink: 'text-neutral-ink', fill: 'bg-neutral' },
};
