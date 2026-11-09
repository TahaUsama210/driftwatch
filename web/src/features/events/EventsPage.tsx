import { Inbox, X } from 'lucide-react';
import { Link, useSearchParams } from 'react-router';
import type { DriftDirection } from '../../api/types';
import { buttonClasses } from '../../components/buttonStyles';
import { Card } from '../../components/Card';
import { EmptyState } from '../../components/EmptyState';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { SegmentedControl, type SegmentOption } from '../../components/SegmentedControl';
import { DRIFT_DIRECTION_ORDER, DRIFT_DIRECTIONS } from '../../domain/drift';
import { pluralize } from '../../lib/format';
import { useHost } from '../hosts/api';
import { useDriftEvents } from './api';
import { DriftEventList, DriftEventListSkeleton } from './DriftEventList';

const EVENTS_LIMIT = 100;

type DirectionFilter = DriftDirection | 'ALL';

const DIRECTION_OPTIONS: readonly SegmentOption<DirectionFilter>[] = [
  { value: 'ALL', label: 'All' },
  ...DRIFT_DIRECTION_ORDER.map((direction) => ({
    value: direction,
    label: DRIFT_DIRECTIONS[direction].label,
  })),
];

function parseDirection(value: string | null): DriftDirection | undefined {
  return DRIFT_DIRECTION_ORDER.find((direction) => direction === value);
}

export function EventsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const direction = parseDirection(searchParams.get('direction'));
  const hostId = searchParams.get('hostId') ?? undefined;
  const events = useDriftEvents({ direction, hostId, limit: EVENTS_LIMIT });

  const update = (key: 'direction' | 'hostId', value: string | undefined) => {
    setSearchParams((params) => {
      if (value) params.set(key, value);
      else params.delete(key);
      return params;
    });
  };

  return (
    <>
      <PageHeader
        title="Drift events"
        description="Checks that changed status between two consecutive scans of a host."
      />
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <SegmentedControl
          label="Direction"
          options={DIRECTION_OPTIONS}
          value={direction ?? 'ALL'}
          onChange={(value) => {
            update('direction', value === 'ALL' ? undefined : value);
          }}
        />
        {hostId ? (
          <HostFilterChip
            hostId={hostId}
            onClear={() => {
              update('hostId', undefined);
            }}
          />
        ) : null}
      </div>
      <Card
        title="Events"
        description={
          events.data ? `${pluralize(events.data.length, 'event')}, newest first` : undefined
        }
        flush
      >
        <QueryState query={events} loading={<DriftEventListSkeleton rows={8} />}>
          {(items) =>
            items.length === 0 ? (
              <EmptyState icon={Inbox} title="No drift events">
                {direction || hostId
                  ? 'Nothing matches these filters.'
                  : 'Events appear when a check changes status between two scans of a host.'}
              </EmptyState>
            ) : (
              <div
                className={events.isPlaceholderData ? 'opacity-60 transition-opacity' : undefined}
              >
                <DriftEventList events={items} showHost={!hostId} />
                {items.length === EVENTS_LIMIT ? (
                  <p className="border-t border-border px-4 py-3 text-xs text-ink-muted">
                    Showing the {EVENTS_LIMIT} most recent events.
                  </p>
                ) : null}
              </div>
            )
          }
        </QueryState>
      </Card>
    </>
  );
}

function HostFilterChip({ hostId, onClear }: { hostId: string; onClear: () => void }) {
  const host = useHost(hostId);
  const name = host.data?.hostname ?? 'selected host';
  return (
    <span className="inline-flex items-center gap-1 rounded-md border border-border bg-surface py-0.5 pr-0.5 pl-2 text-xs text-ink-secondary">
      Host:{' '}
      <Link to={`/hosts/${hostId}`} className="font-mono text-ink hover:underline">
        {name}
      </Link>
      <button
        type="button"
        onClick={onClear}
        className={buttonClasses('ghost', 'sm')}
        aria-label={`Clear host filter (${name})`}
      >
        <X aria-hidden className="size-3.5" />
      </button>
    </span>
  );
}
