import { Monitor, Moon, Sun, type LucideIcon } from 'lucide-react';
import { cx } from '../lib/cx';
import { useThemePreference, type ThemePreference } from '../lib/theme';

const OPTIONS: readonly { value: ThemePreference; label: string; icon: LucideIcon }[] = [
  { value: 'system', label: 'System theme', icon: Monitor },
  { value: 'light', label: 'Light theme', icon: Sun },
  { value: 'dark', label: 'Dark theme', icon: Moon },
];

export function ThemeToggle() {
  const [preference, setPreference] = useThemePreference();
  return (
    <fieldset>
      <legend className="sr-only">Theme</legend>
      <div className="flex rounded-md border border-border bg-sunken p-0.5">
        {OPTIONS.map(({ value, label, icon: Icon }) => {
          const selected = preference === value;
          return (
            <label
              key={value}
              title={label}
              className={cx(
                'flex size-7 cursor-pointer items-center justify-center rounded transition-colors',
                'has-focus-visible:outline-2 has-focus-visible:outline-offset-1 has-focus-visible:outline-focus',
                selected
                  ? 'bg-surface text-ink shadow-xs ring-1 ring-border'
                  : 'text-ink-muted hover:text-ink',
              )}
            >
              <input
                type="radio"
                name="theme"
                value={value}
                checked={selected}
                onChange={() => {
                  setPreference(value);
                }}
                className="sr-only"
              />
              <Icon aria-hidden className="size-4" />
              <span className="sr-only">{label}</span>
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
