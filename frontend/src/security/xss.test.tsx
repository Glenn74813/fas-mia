import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DocumentInfo } from '../api/types';
import { Badge } from '../components/Badge';
import { DocumentsPanel } from '../components/DocumentsPanel';
import { StatusBadge } from '../components/StatusBadge';

/** Cross-site scripting (MS §9, §18; OWASP A03): the client renders server data as text only. Backend side: XssIT. */
const PAYLOAD = `<script>window.__xss = 1</script><img src=x onerror="window.__xss = 2"><svg onload="window.__xss = 3">`;

function assertInert(container: HTMLElement) {
  expect(container.querySelector('script, img[onerror], svg[onload], iframe, object')).toBeNull();
  expect(container.textContent).toContain('<script>');
  expect((window as unknown as { __xss?: number }).__xss).toBeUndefined();
}

describe('stored markup is rendered as text', () => {
  it('in badges', () => {
    assertInert(render(<><Badge>{PAYLOAD}</Badge><StatusBadge status={PAYLOAD} /></>).container);
  });

  it('in uploaded file names', () => {
    const doc = { id: 'd1', fileName: PAYLOAD, contentType: 'application/pdf', sizeBytes: 10, sha256: 'x', scanStatus: 'CLEAN',
      uploadedBy: PAYLOAD, uploadedAt: '2026-01-01T00:00:00Z', removedAt: null } as unknown as DocumentInfo;
    assertInert(render(<DocumentsPanel documents={[doc]} canUpload={false} onUpload={() => {}} uploading={false} error={null} />).container);
  });
});

describe('the source never opts out of escaping', () => {
  const sources = import.meta.glob(['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}'], { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

  it('finds the sources', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(50);
  });

  it.each([
    ['dangerouslySetInnerHTML', /dangerouslySetInnerHTML/],
    ['innerHTML / outerHTML assignment', /\.(inner|outer)HTML\s*=/],
    ['insertAdjacentHTML', /insertAdjacentHTML/],
    ['document.write', /document\.write/],
    ['eval or new Function', /\beval\(|new Function\(/],
    ['javascript: URLs', /['"`]javascript:/i],
  ])('no %s', (_name, pattern) => {
    const offenders = Object.entries(sources).filter(([, text]) => pattern.test(text)).map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});
