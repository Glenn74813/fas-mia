import { expect, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { totp } from './totp';
import { signIn, signOut } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};
test.skip(!admin.username || !admin.password || !admin.totpSecret, 'Set E2E_ADMIN_* for an enrolled test administrator');

// Chromium's virtual authenticator stands in for a fingerprint-capable device (WebAuthn over CDP).
test('a user adds a passkey on their profile and signs in with it instead of the code', async ({ page, browserName }) => {
  test.skip(browserName !== 'chromium', 'Virtual authenticators are a Chromium DevTools feature');
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true,
    hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true } });

  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const user = await onboard(token, 'passkey', ['ACCOUNTANT']);
  await nextTotpWindow();
  await signIn(page, user);

  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'My profile' }).click();
  const section = page.getByRole('region', { name: 'Passkeys' });
  await section.getByLabel('Name for this passkey').fill('E2E laptop');
  await nextTotpWindow(); // the sign-in used this window's code
  await section.getByLabel('Current 6-digit code').fill(totp(user.secret));
  await section.getByRole('button', { name: 'Add a passkey' }).click();
  await expect(section.getByText('E2E laptop')).toBeVisible();
  await expect(section.getByText('not used yet')).toBeVisible();
  await signOut(page);

  // Password, then the passkey: no 6-digit code.
  await page.goto('/login');
  await page.getByLabel('Username or e-mail').fill(user.username);
  await page.getByLabel('Password').fill(user.password);
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByRole('button', { name: /Use a passkey/ }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();

  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'My profile' }).click();
  await expect(page.getByRole('region', { name: 'Passkeys' }).getByText(/last used/)).toBeVisible();
});
