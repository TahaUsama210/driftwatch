import { LoadingRegion, Skeleton } from '../components/Skeleton';

/** Shown while the first route's code chunk downloads. */
export function PageFallback() {
  return (
    <LoadingRegion label="Loading Driftwatch">
      <div className="mx-auto max-w-7xl space-y-4 px-4 py-6">
        <Skeleton className="h-7 w-48" />
        <Skeleton className="h-32 w-full" />
      </div>
    </LoadingRegion>
  );
}
