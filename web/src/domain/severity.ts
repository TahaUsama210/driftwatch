import type { Severity } from '../api/types';

interface SeverityMeta {
  label: string;
  /** Scoring weight from the contract. */
  weight: number;
  /** 1-4, drives the pip indicator. */
  level: number;
}

export const SEVERITIES: Record<Severity, SeverityMeta> = {
  low: { label: 'Low', weight: 1, level: 1 },
  medium: { label: 'Medium', weight: 2, level: 2 },
  high: { label: 'High', weight: 3, level: 3 },
  critical: { label: 'Critical', weight: 5, level: 4 },
};

export const SEVERITY_LEVELS = 4;
