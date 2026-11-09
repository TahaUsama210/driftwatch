import { RefreshCw } from 'lucide-react';
import { useRouteError } from 'react-router';
import { Button } from '../components/Button';
import { ErrorState } from '../components/ErrorState';
import { PageHeader } from '../components/PageHeader';

/**
 * Catches render errors and failed route-chunk downloads (e.g. after a deploy replaced
 * the hashed assets) inside the shell, so navigation stays usable.
 */
export function RouteError() {
  const error = useRouteError();
  return (
    <>
      <PageHeader title="This page failed to load" />
      <div className="space-y-4">
        <ErrorState error={error} />
        <Button
          onClick={() => {
            window.location.reload();
          }}
        >
          <RefreshCw aria-hidden className="size-4" />
          Reload Driftwatch
        </Button>
      </div>
    </>
  );
}
