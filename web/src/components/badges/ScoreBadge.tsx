import { SCORE_TIERS, scoreTier } from '../../domain/score';
import { formatScore } from '../../lib/format';
import { Badge } from './Badge';
import { SCORE_TIER_ICONS } from './statusIcons';

export function ScoreBadge({ score }: { score: number | null | undefined }) {
  const tier = scoreTier(score);
  const meta = SCORE_TIERS[tier];
  return (
    <Badge
      tone={meta.tone}
      icon={SCORE_TIER_ICONS[tier]}
      title={`${meta.label}: ${meta.description}`}
    >
      <span className="tabular-nums">{formatScore(score)}</span>
      <span className="sr-only">, {meta.label}</span>
    </Badge>
  );
}
