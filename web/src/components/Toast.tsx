import { CircleCheck, X } from 'lucide-react';
import { useCallback, useRef, useState, type ReactNode } from 'react';
import { ToastContext, type ShowToast, type ToastMessage } from './toastContext';

interface Toast extends ToastMessage {
  id: number;
}

export const TOAST_DURATION_MS = 6000;

/**
 * Confirmation messages that outlive the page that raised them (e.g. "host
 * decommissioned" shown on the hosts list). Announced politely by a live region that
 * is always in the DOM, so screen readers pick up each new message.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const show = useCallback<ShowToast>(
    (toast) => {
      const id = ++nextId.current;
      setToasts((current) => [...current.slice(-2), { ...toast, id }]);
      setTimeout(() => {
        dismiss(id);
      }, TOAST_DURATION_MS);
    },
    [dismiss],
  );

  return (
    <ToastContext value={show}>
      {children}
      <div
        role="status"
        aria-live="polite"
        className="pointer-events-none fixed inset-x-0 bottom-0 z-50 flex flex-col items-center gap-2 p-4 sm:items-end"
      >
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className="pointer-events-auto flex w-full max-w-sm gap-3 rounded-lg border border-border bg-surface p-3 shadow-lg"
          >
            <CircleCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-good-ink" />
            <div className="min-w-0 flex-1">
              <p className="font-medium break-words text-ink">{toast.title}</p>
              {toast.detail ? <p className="mt-0.5 text-ink-secondary">{toast.detail}</p> : null}
            </div>
            <button
              type="button"
              onClick={() => {
                dismiss(toast.id);
              }}
              className="-m-1 flex size-6 shrink-0 cursor-pointer items-center justify-center rounded text-ink-muted hover:bg-sunken hover:text-ink"
            >
              <X aria-hidden className="size-4" />
              <span className="sr-only">Dismiss</span>
            </button>
          </div>
        ))}
      </div>
    </ToastContext>
  );
}
