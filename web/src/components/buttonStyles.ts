import { cx } from '../lib/cx';

type Variant = 'secondary' | 'ghost';
type Size = 'sm' | 'md';

const VARIANTS: Record<Variant, string> = {
  secondary: 'border border-border-strong/60 bg-surface text-ink hover:bg-sunken',
  ghost: 'text-ink-secondary hover:bg-sunken hover:text-ink',
};

const SIZES: Record<Size, string> = {
  sm: 'h-7 gap-1.5 px-2 text-xs',
  md: 'h-8 gap-2 px-3 text-sm',
};

export function buttonClasses(variant: Variant = 'secondary', size: Size = 'md'): string {
  return cx(
    'inline-flex shrink-0 cursor-pointer items-center justify-center rounded-md font-medium whitespace-nowrap transition-colors disabled:cursor-not-allowed disabled:opacity-60',
    VARIANTS[variant],
    SIZES[size],
  );
}
