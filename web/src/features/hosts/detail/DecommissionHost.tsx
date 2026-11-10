import { useQueryClient } from '@tanstack/react-query';
import { CircleAlert, Trash2, TriangleAlert } from 'lucide-react';
import { useId, useRef, useState, type SyntheticEvent, type RefObject } from 'react';
import { useNavigate } from 'react-router';
import { isApiError, toProblem } from '../../../api/client';
import { queryKeys } from '../../../api/queryKeys';
import type { Host, HostPage } from '../../../api/types';
import { useCurrentUser } from '../../../auth/AuthContext';
import { hasRole } from '../../../auth/roles';
import { Button } from '../../../components/Button';
import { useToast } from '../../../components/toastContext';
import { useDecommissionHost } from '../api';

/**
 * Admin-only. Hidden for everyone else, but only as a convenience: the API checks the
 * `admin` role on the DELETE itself.
 */
export function DecommissionHost({ host }: { host: Host }) {
  const user = useCurrentUser();
  const dialogRef = useRef<HTMLDialogElement>(null);
  if (!hasRole(user, 'admin')) return null;
  return (
    <>
      <Button
        onClick={() => {
          dialogRef.current?.showModal();
        }}
      >
        <Trash2 aria-hidden className="size-4 text-critical-ink" />
        Decommission host
      </Button>
      <DecommissionDialog host={host} dialogRef={dialogRef} />
    </>
  );
}

function DecommissionDialog({
  host,
  dialogRef,
}: {
  host: Host;
  dialogRef: RefObject<HTMLDialogElement | null>;
}) {
  const [confirmation, setConfirmation] = useState('');
  const decommission = useDecommissionHost();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const titleId = useId();
  const descriptionId = useId();
  const inputId = useId();

  const confirmed = confirmation === host.hostname;
  const pending = decommission.isPending;
  // A 404 means someone else already removed it: the outcome the admin wanted.
  const alreadyGone = isApiError(decommission.error) && decommission.error.status === 404;

  const finish = async (detail: string) => {
    dialogRef.current?.close();
    // Drop the host from cached lists first, so the hosts page never shows it again.
    queryClient.setQueriesData<HostPage>({ queryKey: queryKeys.hosts.lists() }, (page) =>
      page
        ? {
            ...page,
            items: page.items.filter((item) => item.id !== host.id),
            totalItems: Math.max(0, page.totalItems - 1),
          }
        : page,
    );
    await navigate('/hosts');
    toast({ title: `${host.hostname} decommissioned`, detail });
    // Only now that its page is gone can the host's own queries go without a refetch.
    queryClient.removeQueries({ queryKey: queryKeys.hosts.detail(host.id) });
    await Promise.all(
      [queryKeys.hosts.all, queryKeys.fleet.all, queryKeys.checks.all, queryKeys.events.all].map(
        (queryKey) => queryClient.invalidateQueries({ queryKey }),
      ),
    );
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!confirmed || pending) return;
    decommission.mutate(host.id, {
      onSuccess: () => void finish('Its scans, results and drift events were deleted.'),
      onError: (error) => {
        if (isApiError(error) && error.status === 404) {
          void finish('It had already been removed.');
        }
      },
    });
  };

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onCancel={(event) => {
        // Escape must not hide a request that is still running.
        if (pending) event.preventDefault();
      }}
      onClose={() => {
        setConfirmation('');
        decommission.reset();
      }}
      className="m-auto w-[min(30rem,calc(100vw-2rem))] rounded-lg border border-border bg-surface p-0 text-ink shadow-xl backdrop:bg-scrim"
    >
      <form onSubmit={submit} className="space-y-4 p-5">
        <div className="flex gap-3">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-critical-soft">
            <TriangleAlert aria-hidden className="size-5 text-critical-ink" />
          </span>
          <div className="min-w-0 space-y-1.5">
            <h2 id={titleId} className="text-base font-semibold break-words">
              Decommission {host.hostname}?
            </h2>
            <div id={descriptionId} className="space-y-1.5 text-ink-secondary">
              <p>
                This permanently deletes the host with its scan history, results and drift events,
                and revokes its agent token. It cannot be undone.
              </p>
              <p>
                Stop the agent first: an agent that is still running enrolls again on its next scan
                and comes back as a new host.
              </p>
            </div>
          </div>
        </div>

        <div className="space-y-1.5">
          <label htmlFor={inputId} className="block text-ink-secondary">
            Type <code className="font-mono text-xs font-semibold text-ink">{host.hostname}</code>{' '}
            to confirm
          </label>
          <input
            id={inputId}
            value={confirmation}
            onChange={(event) => {
              setConfirmation(event.target.value);
            }}
            disabled={pending}
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            className="h-8 w-full rounded-md border border-border-strong bg-surface px-2.5 font-mono text-sm text-ink"
          />
        </div>

        {decommission.isError && !alreadyGone ? (
          <DecommissionError error={decommission.error} />
        ) : null}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button
            disabled={pending}
            onClick={() => {
              dialogRef.current?.close();
            }}
          >
            Cancel
          </Button>
          <Button type="submit" variant="danger" disabled={!confirmed || pending}>
            <Trash2 aria-hidden className="size-4" />
            {pending ? 'Decommissioning…' : 'Decommission host'}
          </Button>
        </div>
      </form>
    </dialog>
  );
}

function DecommissionError({ error }: { error: unknown }) {
  const status = isApiError(error) ? error.status : undefined;
  const problem = toProblem(error);
  const message =
    status === 403
      ? 'Your account is not allowed to decommission hosts. This needs the admin role.'
      : status === 401
        ? 'Your session is no longer valid. Sign in again, then retry.'
        : (problem.detail ?? problem.title);
  return (
    <div
      role="alert"
      className="flex gap-2.5 rounded-md border border-critical/30 bg-critical-soft p-3"
    >
      <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-critical-ink" />
      <div className="min-w-0">
        <p className="font-medium text-ink">
          {status === 403 ? 'Insufficient permissions' : 'The host was not decommissioned'}
        </p>
        <p className="text-ink-secondary">{message}</p>
      </div>
    </div>
  );
}
