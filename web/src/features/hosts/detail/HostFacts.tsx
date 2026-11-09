import type { ReactNode } from 'react';
import type { Host } from '../../../api/types';
import { Card } from '../../../components/Card';
import { RelativeTime } from '../../../components/RelativeTime';
import { formatDateTime } from '../../../lib/format';

export function HostFacts({ host }: { host: Host }) {
  return (
    <Card title="Host facts" description="As reported with the latest scan">
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2.5">
        <Fact label="Operating system">{host.os.prettyName}</Fact>
        <Fact label="OS ID">
          <code className="font-mono text-xs">
            {host.os.id}
            {host.os.version ? ` ${host.os.version}` : ''}
          </code>
        </Fact>
        <Fact label="Kernel">
          <code className="font-mono text-xs break-all">{host.kernel}</code>
        </Fact>
        <Fact label="Architecture">
          <code className="font-mono text-xs">{host.arch}</code>
        </Fact>
        <Fact label="Agent">
          <code className="font-mono text-xs">{host.agentVersion}</code>
        </Fact>
        <Fact label="Last seen">
          <RelativeTime value={host.lastSeenAt} />
        </Fact>
        <Fact label="Enrolled">
          <time dateTime={host.enrolledAt}>{formatDateTime(host.enrolledAt)}</time>
        </Fact>
        <Fact label="Host ID">
          <code className="font-mono text-xs break-all text-ink-secondary">{host.id}</code>
        </Fact>
      </dl>
    </Card>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-xs text-ink-muted">{label}</dt>
      <dd className="min-w-0 text-ink">{children}</dd>
    </>
  );
}
