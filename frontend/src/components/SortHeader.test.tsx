import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { SortHeader } from './SortHeader';

function header(sort: string, descFirst = false) {
  const onSort = vi.fn();
  render(<table><thead><tr><SortHeader label="Date" field="date" sort={sort} onSort={onSort} descFirst={descFirst} /></tr></thead></table>);
  return onSort;
}

describe('SortHeader', () => {
  it('sorts by its column, ascending unless told dates go newest first', async () => {
    let onSort = header('name');
    expect(screen.getByRole('columnheader')).toHaveAttribute('aria-sort', 'none');
    await userEvent.click(screen.getByRole('button', { name: 'Date' }));
    expect(onSort).toHaveBeenCalledWith('date');
    document.body.innerHTML = '';
    onSort = header('name', true);
    await userEvent.click(screen.getByRole('button', { name: 'Date' }));
    expect(onSort).toHaveBeenCalledWith('-date');
  });

  it('reverses the active column and announces the direction', async () => {
    let onSort = header('date');
    expect(screen.getByRole('columnheader')).toHaveAttribute('aria-sort', 'ascending');
    await userEvent.click(screen.getByRole('button', { name: 'Date' }));
    expect(onSort).toHaveBeenCalledWith('-date');
    document.body.innerHTML = '';
    onSort = header('-date');
    expect(screen.getByRole('columnheader')).toHaveAttribute('aria-sort', 'descending');
    await userEvent.click(screen.getByRole('button', { name: 'Date' }));
    expect(onSort).toHaveBeenCalledWith('date');
  });
});
