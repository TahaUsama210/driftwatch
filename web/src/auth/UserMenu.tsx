import { ChevronDown, LogOut } from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';
import { Button } from '../components/Button';
import { SegmentedControl } from '../components/SegmentedControl';
import { cx } from '../lib/cx';
import { useAuth, useCurrentUser } from './AuthContext';
import { hasRole, roleLabel } from './roles';
import type { MockRole } from './types';

const MOCK_ROLE_OPTIONS = [
  { value: 'admin', label: 'Admin' },
  { value: 'viewer', label: 'Viewer' },
] as const satisfies readonly { value: MockRole; label: string }[];

function initials(name: string): string {
  const letters = name
    .split(/\s+/)
    .filter(Boolean)
    .map((word) => word[0]?.toUpperCase() ?? '');
  return (letters.length > 1 ? `${letters[0] ?? ''}${letters.at(-1) ?? ''}` : letters[0]) ?? '?';
}

/**
 * The signed-in user's name and role, and sign out. A disclosure (button + panel)
 * rather than an ARIA menu: it holds a few controls, not a list of commands.
 */
export function UserMenu() {
  const auth = useAuth();
  const user = useCurrentUser();
  const [open, setOpen] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const panelId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const root = rootRef.current;
    if (!open || !root) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!root.contains(event.target as Node)) setOpen(false);
    };
    // Tabbing out of the panel closes it, like clicking elsewhere.
    const onFocusOut = (event: FocusEvent) => {
      if (!root.contains(event.relatedTarget as Node | null)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      buttonRef.current?.focus();
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    root.addEventListener('focusout', onFocusOut);
    return () => {
      root.removeEventListener('focusout', onFocusOut);
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const role = roleLabel(user);
  return (
    <div ref={rootRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => {
          setOpen((value) => !value);
        }}
        className="flex h-8 cursor-pointer items-center gap-2 rounded-md px-1 text-ink-secondary transition-colors hover:bg-sunken hover:text-ink sm:pr-2"
      >
        <span
          aria-hidden
          className="flex size-7 items-center justify-center rounded-full bg-accent-soft text-xs font-semibold text-ink"
        >
          {initials(user.displayName)}
        </span>
        <span className="sr-only sm:not-sr-only sm:max-w-40 sm:truncate sm:text-sm sm:font-medium">
          {user.displayName}
        </span>
        <span className="sr-only">, {role}. Account</span>
        <ChevronDown aria-hidden className="hidden size-4 sm:block" />
      </button>
      <div
        id={panelId}
        hidden={!open}
        className={cx(
          'absolute top-full right-0 z-50 mt-2 w-64 rounded-lg border border-border bg-surface p-3 shadow-lg',
        )}
      >
        <p className="truncate font-medium text-ink">{user.displayName}</p>
        <p className="truncate text-xs text-ink-muted">{user.username}</p>
        <p className="mt-2 text-xs text-ink-secondary">
          Role:{' '}
          <span
            className={cx(
              'rounded px-1.5 py-0.5 font-medium',
              hasRole(user, 'admin') ? 'bg-accent-soft text-ink' : 'bg-sunken text-ink',
            )}
          >
            {role}
          </span>
        </p>
        {auth.switchRole ? (
          <div className="mt-3 border-t border-border pt-3">
            <p className="mb-1.5 text-xs text-ink-muted">Mock mode: view the dashboard as</p>
            <SegmentedControl
              label="Demo role"
              options={MOCK_ROLE_OPTIONS}
              value={hasRole(user, 'admin') ? 'admin' : 'viewer'}
              onChange={(next) => auth.switchRole?.(next)}
            />
          </div>
        ) : null}
        <Button
          className="mt-3 w-full"
          disabled={signingOut}
          onClick={() => {
            setSigningOut(true);
            void auth.signOut().finally(() => {
              setSigningOut(false);
            });
          }}
        >
          <LogOut aria-hidden className="size-4" />
          {signingOut ? 'Signing out…' : 'Sign out'}
        </Button>
      </div>
    </div>
  );
}
