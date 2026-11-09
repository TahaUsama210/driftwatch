import type { Category } from '../api/types';

export const CATEGORY_LABELS: Record<Category, string> = {
  ssh: 'SSH',
  accounts: 'Accounts',
  network: 'Network',
  kernel: 'Kernel',
  filesystem: 'Filesystem',
};

export const CATEGORY_ORDER = [
  'ssh',
  'accounts',
  'network',
  'kernel',
  'filesystem',
] as const satisfies readonly Category[];

export function isCategory(value: string | null): value is Category {
  return value !== null && Object.hasOwn(CATEGORY_LABELS, value);
}
