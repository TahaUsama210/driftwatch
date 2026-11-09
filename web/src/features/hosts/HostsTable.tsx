import { ArrowDown, ArrowUp, ChevronsUpDown } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { Host, HostSortField, SortDirection } from '../../api/types';
import { HostStatusBadge } from '../../components/badges/HostStatusBadge';
import { ScoreBadge } from '../../components/badges/ScoreBadge';
import { RelativeTime } from '../../components/RelativeTime';
import { StatusCounts } from '../../components/StatusCounts';

interface HostsTableProps {
  hosts: readonly Host[];
  sort: HostSortField;
  direction: SortDirection;
  onSort: (sort: HostSortField, direction: SortDirection) => void;
}

const TH = 'px-4 py-2.5 text-left text-xs font-medium whitespace-nowrap text-ink-secondary';

export function HostsTable({ hosts, sort, direction, onSort }: HostsTableProps) {
  const sortable = (field: HostSortField, label: string) => (
    <SortableHeader
      label={label}
      active={sort === field}
      direction={direction}
      onClick={() => {
        onSort(field, sort === field && direction === 'asc' ? 'desc' : 'asc');
      }}
    />
  );

  return (
    <div className="relative overflow-x-auto">
      <table className="w-full min-w-[46rem] border-collapse">
        <caption className="sr-only">Hosts with their latest scan result</caption>
        <thead className="bg-sunken">
          <tr>
            {sortable('hostname', 'Host')}
            <th scope="col" className={TH}>
              OS
            </th>
            <th scope="col" className={TH}>
              Status
            </th>
            {sortable('score', 'Score')}
            <th scope="col" className={TH}>
              Checks
            </th>
            {sortable('lastSeenAt', 'Last seen')}
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {hosts.map((host) => (
            <tr key={host.id} className="hover:bg-sunken/60">
              <Cell>
                <Link
                  to={`/hosts/${host.id}`}
                  className="font-medium text-ink hover:text-accent hover:underline"
                >
                  {host.hostname}
                </Link>
              </Cell>
              <Cell muted>{host.os.prettyName}</Cell>
              <Cell>
                <HostStatusBadge status={host.status} />
              </Cell>
              <Cell>
                <ScoreBadge score={host.latestScan?.score} />
              </Cell>
              <Cell>
                {host.latestScan ? (
                  <StatusCounts counts={host.latestScan.counts} />
                ) : (
                  <span className="text-ink-muted">No scans yet</span>
                )}
              </Cell>
              <Cell muted>
                <RelativeTime value={host.lastSeenAt} />
              </Cell>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Cell({ children, muted = false }: { children: ReactNode; muted?: boolean }) {
  return (
    <td className={`px-4 py-2.5 whitespace-nowrap ${muted ? 'text-ink-secondary' : 'text-ink'}`}>
      {children}
    </td>
  );
}

interface SortableHeaderProps {
  label: string;
  active: boolean;
  direction: SortDirection;
  onClick: () => void;
}

function SortableHeader({ label, active, direction, onClick }: SortableHeaderProps) {
  const Icon = !active ? ChevronsUpDown : direction === 'asc' ? ArrowUp : ArrowDown;
  return (
    <th
      scope="col"
      aria-sort={active ? (direction === 'asc' ? 'ascending' : 'descending') : undefined}
      className={TH}
    >
      <button
        type="button"
        onClick={onClick}
        className="-mx-1 inline-flex cursor-pointer items-center gap-1 rounded px-1 hover:text-ink"
      >
        {label}
        <Icon aria-hidden className={active ? 'size-3.5 text-ink' : 'size-3.5 text-ink-muted'} />
        <span className="sr-only">
          {active ? `, sorted ${direction === 'asc' ? 'ascending' : 'descending'}` : ', not sorted'}
        </span>
      </button>
    </th>
  );
}
