import type { ReactNode } from 'react';

interface PageHeaderProps {
  title: ReactNode;
  /** Plain-text title for the browser tab; defaults to `title` when that is a string. */
  documentTitle?: string;
  description?: ReactNode;
  actions?: ReactNode;
  breadcrumbs?: ReactNode;
}

export function PageHeader({
  title,
  documentTitle,
  description,
  actions,
  breadcrumbs,
}: PageHeaderProps) {
  const tabTitle = documentTitle ?? (typeof title === 'string' ? title : undefined);
  return (
    <div className="mb-6 space-y-2">
      {tabTitle ? <PageTitle title={tabTitle} /> : null}
      {breadcrumbs}
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold tracking-tight break-words text-ink">{title}</h1>
          {description ? <div className="mt-1 text-ink-secondary">{description}</div> : null}
        </div>
        {actions ? <div className="flex flex-wrap items-center gap-3">{actions}</div> : null}
      </div>
    </div>
  );
}

/** React 19 hoists <title> into <head>. */
export function PageTitle({ title }: { title: string }) {
  return <title>{`${title} · Driftwatch`}</title>;
}
