import { Badge } from './Badge';

const tones: Record<string, 'green' | 'red' | 'amber' | 'slate' | 'blue'> = {
  DRAFT: 'slate', SUBMITTED: 'blue', APPROVED: 'amber', POSTED: 'green', RETURNED: 'amber', REJECTED: 'red',
  CANCELLED: 'slate', OPEN: 'green', SOFT_CLOSED: 'amber', CLOSED: 'slate', FUTURE: 'blue', ACTIVE: 'green',
  INACTIVE: 'slate', PAUSED: 'amber', ENDED: 'slate', PENDING: 'amber',
};

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={tones[status] ?? 'slate'}>{status.replace('_', ' ')}</Badge>;
}
