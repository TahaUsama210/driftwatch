import { useSyncExternalStore } from 'react';

/**
 * Theme preference store. "system" follows prefers-color-scheme; "light"/"dark" stamp
 * data-theme on <html>, which the CSS dark variant honours. The same key is read by
 * public/theme-init.js before first paint.
 */
export type ThemePreference = 'system' | 'light' | 'dark';

export const THEME_STORAGE_KEY = 'driftwatch:theme';

const listeners = new Set<() => void>();

function readStored(): ThemePreference {
  try {
    const saved = window.localStorage.getItem(THEME_STORAGE_KEY);
    return saved === 'light' || saved === 'dark' ? saved : 'system';
  } catch {
    // Storage can be unavailable (private mode, blocked site data): follow the system.
    return 'system';
  }
}

let current: ThemePreference = readStored();

function apply(preference: ThemePreference): void {
  const root = document.documentElement;
  if (preference === 'system') delete root.dataset.theme;
  else root.dataset.theme = preference;
}

export function setThemePreference(preference: ThemePreference): void {
  current = preference;
  apply(preference);
  try {
    if (preference === 'system') window.localStorage.removeItem(THEME_STORAGE_KEY);
    else window.localStorage.setItem(THEME_STORAGE_KEY, preference);
  } catch {
    // Not persisted when storage is unavailable; the choice still applies to this page.
  }
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useThemePreference(): [ThemePreference, (preference: ThemePreference) => void] {
  const preference = useSyncExternalStore(subscribe, () => current);
  return [preference, setThemePreference];
}
