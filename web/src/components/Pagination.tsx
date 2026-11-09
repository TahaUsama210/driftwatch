import { ChevronLeft, ChevronRight } from 'lucide-react';
import { formatCount } from '../lib/format';
import { Button } from './Button';

interface PaginationProps {
  /** Zero-based page index. */
  page: number;
  size: number;
  totalItems: number;
  noun: string;
  onPageChange: (page: number) => void;
}

export function Pagination({ page, size, totalItems, noun, onPageChange }: PaginationProps) {
  const pageCount = Math.max(1, Math.ceil(totalItems / size));
  const first = totalItems === 0 ? 0 : page * size + 1;
  const last = Math.min(totalItems, (page + 1) * size);
  return (
    <nav
      aria-label="Pagination"
      className="flex flex-wrap items-center justify-between gap-3 border-t border-border px-4 py-3"
    >
      <p className="text-xs text-ink-secondary tabular-nums">
        {formatCount(first)}–{formatCount(last)} of {formatCount(totalItems)} {noun}
      </p>
      <div className="flex items-center gap-2">
        <span className="text-xs text-ink-muted tabular-nums">
          Page {formatCount(page + 1)} of {formatCount(pageCount)}
        </span>
        <Button
          size="sm"
          aria-label="Previous page"
          disabled={page <= 0}
          onClick={() => {
            onPageChange(page - 1);
          }}
        >
          <ChevronLeft aria-hidden className="size-4" />
        </Button>
        <Button
          size="sm"
          aria-label="Next page"
          disabled={page >= pageCount - 1}
          onClick={() => {
            onPageChange(page + 1);
          }}
        >
          <ChevronRight aria-hidden className="size-4" />
        </Button>
      </div>
    </nav>
  );
}
