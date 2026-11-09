import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import type { Tone } from '../../domain/tone';
import { cx } from '../../lib/cx';
import { TONE_CLASSES } from './toneStyles';

interface BadgeProps {
  tone: Tone;
  icon: LucideIcon;
  children: ReactNode;
  title?: string;
  className?: string;
}

/** A tinted label whose meaning is carried by icon shape and text, never colour alone. */
export function Badge({ tone, icon: Icon, children, title, className }: BadgeProps) {
  return (
    <span
      title={title}
      className={cx(
        'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-medium whitespace-nowrap text-ink',
        TONE_CLASSES[tone].soft,
        className,
      )}
    >
      <Icon
        aria-hidden
        className={cx('size-3.5 shrink-0', TONE_CLASSES[tone].ink)}
        strokeWidth={2.25}
      />
      {children}
    </span>
  );
}
