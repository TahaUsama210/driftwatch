import { Terminal } from 'lucide-react';
import { CodeBlock } from '../../components/CodeBlock';

const SERVER_URL = 'http://<api-host>:8480';
const TOKEN = '<enrollment-token>';

const BINARY_SNIPPET = `export DRIFTWATCH_SERVER_URL=${SERVER_URL}
export DRIFTWATCH_ENROLLMENT_TOKEN=${TOKEN}
sudo -E driftwatch-agent run`;

// No registry image is published yet, so the snippet builds it from a checkout first.
const DOCKER_SNIPPET = `docker build -t driftwatch-agent agent
docker run -d --name driftwatch-agent --restart unless-stopped \\
  --network host \\
  -v /:/host:ro \\
  -v driftwatch-agent-state:/var/lib/driftwatch \\
  -e DRIFTWATCH_SERVER_URL=${SERVER_URL} \\
  -e DRIFTWATCH_ENROLLMENT_TOKEN=${TOKEN} \\
  -e DRIFTWATCH_ROOT=/host \\
  -e DRIFTWATCH_PROC=/host/proc \\
  driftwatch-agent run`;

const ENV_VARS: readonly { name: string; meaning: string }[] = [
  { name: 'DRIFTWATCH_SERVER_URL', meaning: 'Base URL of the Driftwatch API.' },
  {
    name: 'DRIFTWATCH_ENROLLMENT_TOKEN',
    meaning: 'The shared secret configured on the API; used only for the first enrollment.',
  },
  { name: 'DRIFTWATCH_INTERVAL', meaning: 'Time between scans, as a Go duration. Default 5m.' },
  {
    name: 'DRIFTWATCH_STATE_DIR',
    meaning: 'Where the agent keeps its host ID and token. Default /var/lib/driftwatch.',
  },
];

/** Shown instead of fleet data while no host has enrolled. */
export function EnrollAgentPanel() {
  return (
    <section
      aria-labelledby="enroll-heading"
      className="rounded-lg border border-border bg-surface p-4 sm:p-6"
    >
      <div className="flex items-start gap-3">
        <span className="rounded-md bg-accent-soft p-2 text-accent">
          <Terminal aria-hidden className="size-5" />
        </span>
        <div className="min-w-0 space-y-1">
          <h2 id="enroll-heading" className="text-base font-semibold text-ink">
            No hosts enrolled yet
          </h2>
          <p className="max-w-2xl text-ink-secondary">
            Run the Driftwatch agent on each Linux host you want to track. On first start it enrolls
            with the shared enrollment token, then submits a scan every interval. Hosts appear here
            as soon as they enroll.
          </p>
        </div>
      </div>

      <div className="mt-6 grid gap-4 lg:grid-cols-2">
        <div className="min-w-0 space-y-2">
          <h3 className="font-medium text-ink">On the host</h3>
          <p className="text-xs text-ink-secondary">
            The agent reads files such as <code className="font-mono">/etc/shadow</code>, so it runs
            as root.
          </p>
          <CodeBlock label="Shell commands" code={BINARY_SNIPPET} />
        </div>
        <div className="min-w-0 space-y-2">
          <h3 className="font-medium text-ink">With Docker</h3>
          <p className="text-xs text-ink-secondary">
            Build the image from the repository root, then mount the host filesystem read-only and
            point the agent&apos;s root and procfs at it.
          </p>
          <CodeBlock label="Docker command" code={DOCKER_SNIPPET} />
        </div>
      </div>

      <dl className="mt-6 grid gap-x-6 gap-y-3 border-t border-border pt-4 sm:grid-cols-2">
        {ENV_VARS.map(({ name, meaning }) => (
          <div key={name} className="min-w-0">
            <dt className="font-mono text-xs break-all text-ink">{name}</dt>
            <dd className="text-xs text-ink-secondary">{meaning}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
