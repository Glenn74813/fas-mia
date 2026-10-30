import { describe, expect, it } from 'vitest';
import { NAV_ITEMS } from '../layouts/navigation';
import { helpFor, MODULE_HELP } from '.';

describe('module help', () => {
  it('covers every sidebar module', () => {
    expect(NAV_ITEMS.map((n) => n.to).filter((to) => !MODULE_HELP[to])).toEqual([]);
  });

  it('links connections only to modules that have help', () => {
    const broken = Object.entries(MODULE_HELP).flatMap(([key, h]) =>
      h.connections.filter((c) => !MODULE_HELP[c.to] || c.to === key).map((c) => `${key} -> ${c.to}`));
    expect(broken).toEqual([]);
  });

  it('gives every module a summary and steps', () => {
    for (const [key, h] of Object.entries(MODULE_HELP)) {
      expect(h.summary, key).not.toBe('');
      expect(h.steps.length, key).toBeGreaterThan(0);
    }
  });

  it('finds the module for detail and form pages', () => {
    expect(helpFor('/journal-vouchers/abc/edit')?.key).toBe('/journal-vouchers');
    expect(helpFor('/fixed-assets/setup')?.key).toBe('/fixed-assets/setup');
    expect(helpFor('/fixed-assets/123')?.key).toBe('/fixed-assets');
    expect(helpFor('/tickets/sla')?.key).toBe('/tickets/sla');
    expect(helpFor('/')?.key).toBe('/');
    expect(helpFor('/journal-vouchersX')).toBeNull();
    expect(helpFor('/no-such-page')).toBeNull();
  });
});
