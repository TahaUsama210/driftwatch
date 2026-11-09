import { Compass } from 'lucide-react';
import { Link, useLocation } from 'react-router';
import { buttonClasses } from '../../components/buttonStyles';
import { PageTitle } from '../../components/PageHeader';

interface NotFoundProps {
  title?: string;
  detail?: string;
}

/** Unknown routes, and resources (host, check) the API reports as missing. */
export function NotFound({ title = 'Page not found', detail }: NotFoundProps) {
  const { pathname } = useLocation();
  return (
    <div className="flex flex-col items-center gap-3 py-16 text-center">
      <PageTitle title={title} />
      <Compass aria-hidden className="size-8 text-ink-muted" />
      <h1 className="text-xl font-semibold text-ink">{title}</h1>
      <p className="max-w-md text-ink-secondary">
        {detail ?? (
          <>
            Nothing lives at <code className="font-mono break-all">{pathname}</code>.
          </>
        )}
      </p>
      <div className="mt-2 flex gap-2">
        <Link to="/" className={buttonClasses()}>
          Go to overview
        </Link>
        <Link to="/hosts" className={buttonClasses('ghost')}>
          Browse hosts
        </Link>
      </div>
    </div>
  );
}

export function NotFoundPage() {
  return <NotFound />;
}
