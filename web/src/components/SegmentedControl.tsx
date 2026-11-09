import { useId, type ReactNode } from 'react';
import { cx } from '../lib/cx';
import { formatCount } from '../lib/format';

export interface SegmentOption<T extends string> {
  value: T;
  label: ReactNode;
  count?: number;
}

interface SegmentedControlProps<T extends string> {
  /** Accessible group name, e.g. "Time range". */
  label: string;
  options: readonly SegmentOption<T>[];
  value: T;
  onChange: (value: T) => void;
}

/** A radio group styled as segments; native radios give arrow-key navigation for free. */
export function SegmentedControl<T extends string>({
  label,
  options,
  value,
  onChange,
}: SegmentedControlProps<T>) {
  const name = useId();
  return (
    <fieldset className="min-w-0">
      <legend className="sr-only">{label}</legend>
      <div className="inline-flex flex-wrap gap-0.5 rounded-md border border-border bg-sunken p-0.5">
        {options.map((option) => {
          const selected = option.value === value;
          return (
            <label
              key={option.value}
              className={cx(
                'flex cursor-pointer items-center gap-1.5 rounded px-2.5 py-1 text-xs font-medium whitespace-nowrap transition-colors',
                'has-focus-visible:outline-2 has-focus-visible:outline-offset-1 has-focus-visible:outline-focus',
                selected
                  ? 'bg-surface text-ink shadow-xs ring-1 ring-border'
                  : 'text-ink-secondary hover:text-ink',
              )}
            >
              <input
                type="radio"
                name={name}
                value={option.value}
                checked={selected}
                onChange={() => {
                  onChange(option.value);
                }}
                className="sr-only"
              />
              {option.label}
              {option.count === undefined ? null : (
                <span className="text-ink-muted tabular-nums">{formatCount(option.count)}</span>
              )}
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
