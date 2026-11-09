import { Inbox } from 'lucide-react';
import { Link } from 'react-router';
import { Card } from '../../../components/Card';
import { EmptyState } from '../../../components/EmptyState';
import { QueryState } from '../../../components/QueryState';
import { useDriftEvents } from '../../events/api';
import { DriftEventList, DriftEventListSkeleton } from '../../events/DriftEventList';

const HOST_EVENTS_LIMIT = 10;

export function HostEvents({ hostId }: { hostId: string }) {
  const events = useDriftEvents({ hostId, limit: HOST_EVENTS_LIMIT });
  return (
    <Card
      title="Drift events"
      description="This host, newest first"
      actions={
        <Link
          to={`/events?hostId=${encodeURIComponent(hostId)}`}
          className="text-xs font-medium text-accent hover:underline"
        >
          All for this host
        </Link>
      }
      flush
    >
      <QueryState query={events} loading={<DriftEventListSkeleton rows={4} />}>
        {(items) =>
          items.length === 0 ? (
            <EmptyState icon={Inbox} title="No drift recorded">
              Every check has kept its status between consecutive scans.
            </EmptyState>
          ) : (
            <DriftEventList events={items} showHost={false} />
          )
        }
      </QueryState>
    </Card>
  );
}
