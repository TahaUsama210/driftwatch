import { SearchX } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { Button } from '../../components/Button';
import { Panel } from '../../components/Card';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { SearchField, SelectField } from '../../components/fields';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { SkeletonRows } from '../../components/Skeleton';
import { HOST_STATUS_ORDER, HOST_STATUSES } from '../../domain/hostStatus';
import { EnrollAgentPanel } from '../enroll/EnrollAgentPanel';
import { useHosts } from './api';
import { HostsTable } from './HostsTable';
import {
  DEFAULT_HOST_LIST_PARAMS,
  hasActiveFilters,
  HOSTS_PAGE_SIZE,
  toApiQuery,
  useHostListParams,
} from './hostListParams';

const SEARCH_DEBOUNCE_MS = 300;

export function HostsPage() {
  const [params, update] = useHostListParams();
  const hosts = useHosts(toApiQuery(params));
  const filtered = hasActiveFilters(params);

  const commitQuery = useCallback(
    (query: string) => {
      update({ query }, { replace: true });
    },
    [update],
  );

  const fleetIsEmpty = hosts.data?.totalItems === 0 && !filtered;

  return (
    <>
      <PageHeader
        title="Hosts"
        description="Every enrolled host and the result of its latest scan."
      />
      {fleetIsEmpty ? (
        <EnrollAgentPanel />
      ) : (
        <Panel>
          <div className="flex flex-wrap items-end gap-3 border-b border-border p-4">
            <HostSearch value={params.query} onCommit={commitQuery} />
            <SelectField
              label="Status"
              value={params.status ?? 'ALL'}
              onChange={(value) => {
                update({ status: value === 'ALL' ? undefined : value });
              }}
            >
              <option value="ALL">All statuses</option>
              {HOST_STATUS_ORDER.map((status) => (
                <option key={status} value={status}>
                  {HOST_STATUSES[status].label}
                </option>
              ))}
            </SelectField>
          </div>

          {hosts.isPending ? <SkeletonRows rows={6} label="Loading hosts" /> : null}

          {hosts.isError ? (
            <div className="p-4">
              <ErrorState
                error={hosts.error}
                retrying={hosts.isFetching}
                onRetry={() => void hosts.refetch()}
              />
            </div>
          ) : null}

          {hosts.data?.items.length === 0 && hosts.data.totalItems > 0 ? (
            // A shared or bookmarked URL can point past the last page once hosts are removed.
            <EmptyState
              icon={SearchX}
              title="No hosts on this page"
              action={
                <Button
                  onClick={() => {
                    update({ page: 0 });
                  }}
                >
                  Go to the first page
                </Button>
              }
            />
          ) : null}

          {hosts.data?.items.length === 0 && hosts.data.totalItems === 0 ? (
            <EmptyState
              icon={SearchX}
              title="No hosts match these filters"
              action={
                <Button
                  onClick={() => {
                    update({ query: '', status: DEFAULT_HOST_LIST_PARAMS.status });
                  }}
                >
                  Clear filters
                </Button>
              }
            />
          ) : null}

          {hosts.data && hosts.data.items.length > 0 ? (
            <>
              <div
                className={hosts.isPlaceholderData ? 'opacity-60 transition-opacity' : undefined}
              >
                <HostsTable
                  hosts={hosts.data.items}
                  sort={params.sort}
                  direction={params.direction}
                  onSort={(sort, direction) => {
                    update({ sort, direction });
                  }}
                />
              </div>
              <Pagination
                page={params.page}
                size={HOSTS_PAGE_SIZE}
                totalItems={hosts.data.totalItems}
                noun="hosts"
                onPageChange={(page) => {
                  update({ page });
                }}
              />
            </>
          ) : null}
        </Panel>
      )}
    </>
  );
}

/** Debounced search box: the URL (and so the request) updates once typing pauses. */
function HostSearch({ value, onCommit }: { value: string; onCommit: (value: string) => void }) {
  const [draft, setDraft] = useState(value);
  const [syncedValue, setSyncedValue] = useState(value);

  // Adopt outside changes (back/forward, "Clear filters") without an effect round-trip.
  if (value !== syncedValue) {
    setSyncedValue(value);
    setDraft(value);
  }

  useEffect(() => {
    if (draft === value) return;
    const timer = setTimeout(() => {
      onCommit(draft);
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [draft, value, onCommit]);

  return (
    <SearchField
      label="Search"
      value={draft}
      onChange={setDraft}
      placeholder="Hostname contains…"
    />
  );
}
