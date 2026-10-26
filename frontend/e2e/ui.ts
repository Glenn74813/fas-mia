import { expect, type Page } from '@playwright/test';
import type { Member } from './team';
import { totp } from './totp';

export async function signIn(page: Page, m: Member) {
  // A host network change (net::ERR_NETWORK_CHANGED) can abort the page's loads; navigate again rather than wait forever.
  const identifier = page.getByLabel('Username or e-mail');
  for (let attempt = 1; ; attempt++) {
    await page.goto('/login');
    try {
      await identifier.waitFor({ state: 'visible', timeout: 15_000 });
      break;
    } catch (e) {
      if (attempt === 3) throw e;
    }
  }
  await identifier.fill(m.username);
  await page.getByLabel('Password').fill(m.password);
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByLabel('6-digit code').fill(totp(m.secret));
  await page.getByRole('button', { name: 'Verify' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}

export async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
}

export function nav(page: Page, label: string) {
  return page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: label, exact: true }).click();
}

/** Clicks a header action button, then the confirm button inside the dialog. */
export async function act(page: Page, button: string | RegExp, confirmLabel: string, comment?: string) {
  await page.getByRole('main').getByRole('button', { name: button }).first().click();
  const dialog = page.getByRole('dialog');
  if (comment) await dialog.getByRole('textbox').last().fill(comment);
  await dialog.getByRole('button', { name: confirmLabel, exact: true }).click();
  await expect(dialog).toBeHidden();
}

export const PDF = { name: 'support.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n% e2e\n%%EOF\n') };
