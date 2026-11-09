import { Search } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { cx } from '../lib/cx';

const CONTROL =
  'h-8 rounded-md border border-border-strong bg-surface px-2.5 text-sm text-ink placeholder:text-ink-muted';

interface SelectFieldProps<T extends string> {
  label: string;
  value: T;
  onChange: (value: T) => void;
  children: ReactNode;
  /** Keep the label for screen readers only. */
  hideLabel?: boolean;
  className?: string;
}

/** Native select; `T` is the union of the option values rendered as children. */
export function SelectField<T extends string>({
  label,
  value,
  onChange,
  children,
  hideLabel = false,
  className,
}: SelectFieldProps<T>) {
  const id = useId();
  return (
    <div className={cx('flex min-w-0 flex-col gap-1', className)}>
      <label
        htmlFor={id}
        className={hideLabel ? 'sr-only' : 'text-xs font-medium text-ink-secondary'}
      >
        {label}
      </label>
      <select
        id={id}
        value={value}
        onChange={(event) => {
          onChange(event.target.value as T);
        }}
        className={cx(CONTROL, 'w-full min-w-0 pr-8')}
      >
        {children}
      </select>
    </div>
  );
}

interface SearchFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
}

export function SearchField({ label, value, onChange, placeholder }: SearchFieldProps) {
  const id = useId();
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <label htmlFor={id} className="text-xs font-medium text-ink-secondary">
        {label}
      </label>
      <div className="relative">
        <Search
          aria-hidden
          className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-ink-muted"
        />
        <input
          id={id}
          type="search"
          value={value}
          placeholder={placeholder}
          autoComplete="off"
          spellCheck={false}
          onChange={(event) => {
            onChange(event.target.value);
          }}
          className={cx(CONTROL, 'w-full pl-8 sm:w-64')}
        />
      </div>
    </div>
  );
}
