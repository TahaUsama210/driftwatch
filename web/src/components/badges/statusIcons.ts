import {
  Activity,
  ArrowRightLeft,
  CircleCheck,
  CircleMinus,
  CircleX,
  Clock,
  ShieldAlert,
  ShieldCheck,
  ShieldOff,
  ShieldX,
  TrendingDown,
  TrendingUp,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react';
import type { CheckStatus, DriftDirection, HostStatus } from '../../api/types';
import type { ScoreTier } from '../../domain/score';

export const CHECK_STATUS_ICONS: Record<CheckStatus, LucideIcon> = {
  PASS: CircleCheck,
  FAIL: CircleX,
  ERROR: TriangleAlert,
  NOT_APPLICABLE: CircleMinus,
};

export const HOST_STATUS_ICONS: Record<HostStatus, LucideIcon> = {
  ONLINE: Activity,
  STALE: Clock,
};

export const DIRECTION_ICONS: Record<DriftDirection, LucideIcon> = {
  REGRESSED: TrendingDown,
  RESOLVED: TrendingUp,
  CHANGED: ArrowRightLeft,
};

export const SCORE_TIER_ICONS: Record<ScoreTier, LucideIcon> = {
  good: ShieldCheck,
  warning: ShieldAlert,
  poor: ShieldX,
  unscored: ShieldOff,
};
