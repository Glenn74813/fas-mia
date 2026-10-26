import { describe, expect, it } from 'vitest';
import { visibleNav } from './navigation';

describe('navigation', () => {
  it('shows only items the user may open', () => {
    const labels = (perms: string[]) => visibleNav((p) => perms.includes(p)).map((i) => i.label);
    expect(labels([])).toEqual(['Dashboard', 'Inbox']);
    // Security-event reviewers can export the security log from My reports.
    expect(labels(['USER_VIEW', 'SECURITY_EVENT_VIEW'])).toEqual(['Dashboard', 'Inbox', 'My reports', 'Users', 'Organisation', 'Audit trail']);
    // A system administrator never sees accounting screens.
    expect(labels(['USER_VIEW', 'USER_ADMINISTER', 'ROLE_VIEW'])).not.toContain('Journal vouchers');
    expect(labels(['LEDGER_VIEW', 'JOURNAL_CREATE'])).toContain('Trial balance');
  });
});
