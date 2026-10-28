import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { LineInput } from '../../api/types';
import { emptyLine, LinesEditor, normaliseLines } from './LinesEditor';

vi.mock('../../hooks/useAccounting', () => ({
  useChart: () => ({ data: { accounts: [
    { id: 'a1', code: 'PLC-5201', name: 'Utilities', postable: true, status: 'ACTIVE', requiresCostCenter: true, subsidiaryType: null },
    { id: 'a2', code: 'PLC-1102', name: 'Cash in bank', postable: true, status: 'ACTIVE', requiresCostCenter: false, subsidiaryType: null },
    { id: 'h1', code: 'PLC-1', name: 'Assets', postable: false, status: 'ACTIVE', requiresCostCenter: false, subsidiaryType: null },
  ], activePlaceholderAccounts: 3 } }),
  useCostCenters: () => ({ data: [{ id: 'cc1', code: 'PLC-CC-FIN', status: 'ACTIVE' }] }),
  postableAccounts: (a: { postable: boolean }[]) => a.filter((x) => x.postable),
}));

function Harness({ onLines }: { onLines?: (l: LineInput[]) => void }) {
  const [lines, setLines] = useState<LineInput[]>([emptyLine(), emptyLine()]);
  return <LinesEditor lines={lines} onChange={(l) => { setLines(l); onLines?.(l); }} />;
}

describe('LinesEditor', () => {
  it('totals exactly and reports balance', async () => {
    render(<Harness />);
    await userEvent.type(screen.getByLabelText('Line 1 debit'), '0.10');
    await userEvent.type(screen.getByLabelText('Line 2 credit'), '0.1');
    expect(screen.getByTestId('total-debit')).toHaveTextContent('0.10');
    expect(screen.getByTestId('total-credit')).toHaveTextContent('0.10');
    expect(screen.getByRole('status')).toHaveTextContent('Balanced');
  });

  it('flags invalid amounts and offers only postable accounts', async () => {
    render(<Harness />);
    await userEvent.type(screen.getByLabelText('Line 1 debit'), '1.005');
    expect(screen.getByLabelText('Line 1 debit')).toHaveAttribute('aria-invalid', 'true');
    await userEvent.click(screen.getByLabelText('Line 1 account'));
    const options = screen.getAllByRole('option').map((o) => o.textContent);
    expect(options).toContain('PLC-5201 — Utilities');
    expect(options.join()).not.toContain('Assets');
  });

  it('finds an account by typing any part of its code or name', async () => {
    let last: LineInput[] = [];
    render(<Harness onLines={(l) => { last = l; }} />);
    const field = screen.getByLabelText('Line 1 account');
    await userEvent.type(field, 'cash bank');
    expect(within(screen.getByRole('listbox')).getAllByRole('option').map((o) => o.textContent)).toEqual(['PLC-1102 — Cash in bank']);
    await userEvent.keyboard('{Enter}');
    expect(last[0]?.accountId).toBe('a2');
    expect(field).toHaveValue('PLC-1102 — Cash in bank');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('sends blank amounts as zero', () => {
    expect(normaliseLines([{ ...emptyLine(), accountId: 'a1', debit: '5' }])[0]).toMatchObject({ debit: '5', credit: '0' });
  });
});
