import { expect, test, type Page } from '@playwright/test';
import { select } from './ui';
import { totp } from './totp';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
// Several waits for a fresh 30-second code window.
test.setTimeout(180_000);

async function signIn(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Username or e-mail').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Continue' }).click();
}

async function waitForFreshStep() {
  // A TOTP step can be used once; wait for the next 30-second window when re-using an authenticator.
  await new Promise((r) => setTimeout(r, 30_000 - (Date.now() % 30_000) + 500));
}

test('administrator onboards a user who enrols MFA and sets a password', async ({ page }) => {
  const username = `e2e.${Date.now()}`;
  const temporary = 'Temp-E2E-Passw0rd!';
  const final = 'Final-E2E-Passw0rd!';

  await signIn(page, admin.username, admin.password);
  // The dev profile may skip the administrator's second factor (cas.mfa.dev-bypass-users); everywhere else it is asked.
  const mfa = page.getByRole('heading', { name: 'Two-step verification' });
  const welcome = page.getByRole('heading', { name: /Welcome/ });
  await expect(mfa.or(welcome)).toBeVisible();
  if (await mfa.isVisible()) {
    // A code works once: a fresh window, so an earlier run's or spec's sign-in cannot have used it.
    await waitForFreshStep();
    await page.getByLabel('6-digit code').fill(totp(admin.totpSecret));
    await page.getByRole('button', { name: 'Verify' }).click();
  }
  await expect(welcome).toBeVisible();

  // Segregation of duties is enforced by the API and explained in the form.
  await page.goto('/admin/users/new');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('E-mail').fill(`${username}@miaa.local`);
  await page.getByLabel('Display name').fill('E2E Accountant');
  await page.getByLabel('Temporary password').fill(temporary);
  await page.getByRole('checkbox', { name: 'Accountant', exact: true }).check();
  await page.getByRole('checkbox', { name: 'Chief Accountant' }).check();
  await page.getByRole('button', { name: 'Create user' }).click();
  await expect(page.getByRole('alert')).toContainText('JOURNAL_CREATE + JOURNAL_POST');

  await page.getByRole('checkbox', { name: 'Chief Accountant' }).uncheck();
  await page.getByRole('button', { name: 'Create user' }).click();
  await expect(page.getByText('PASSWORD CHANGE PENDING')).toBeVisible();

  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);

  // The new user must enrol an authenticator, then replace the temporary password.
  await signIn(page, username, temporary);
  await expect(page.getByRole('heading', { name: 'Set up your authenticator' })).toBeVisible();
  const manual = await page.getByText(/Manual key:/).textContent();
  const secret = manual!.replace('Manual key:', '').trim();
  await page.getByLabel('6-digit code').fill(totp(secret));
  await page.getByRole('button', { name: 'Verify' }).click();
  // Recovery codes are shown once; the app opens only after the user confirms saving them.
  await expect(page.getByRole('heading', { name: 'Save your recovery codes' })).toBeVisible();
  const recoveryCodes = await page.getByRole('list', { name: 'Recovery codes' }).getByRole('listitem').allTextContents();
  expect(recoveryCodes).toHaveLength(10);
  await expect(page.getByRole('button', { name: 'Continue' })).toBeDisabled();
  await page.getByLabel('I have saved these codes somewhere safe.').check();
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('heading', { name: 'Choose a new password' })).toBeVisible();
  await page.getByLabel('Current password').fill(temporary);
  await page.getByLabel('New password', { exact: true }).fill(final);
  await page.getByLabel('Confirm new password').fill(final);
  await page.getByRole('button', { name: 'Save password' }).click();
  await expect(page.getByRole('heading', { name: 'Welcome, E2E Accountant' })).toBeVisible();

  // An accountant has no administration screens.
  await expect(page.getByRole('link', { name: 'Users' })).toHaveCount(0);
  await page.goto('/admin/users');
  await expect(page.getByRole('alert')).toContainText('Access restricted');

  // The session survives a reload via the HttpOnly refresh cookie, then sign-out ends it.
  await page.goto('/');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Welcome, E2E Accountant' })).toBeVisible();
  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);

  // Sign in again with the final password and a fresh code.
  await waitForFreshStep();
  await signIn(page, username, final);
  await page.getByLabel('6-digit code').fill(totp(secret));
  await page.getByRole('button', { name: 'Verify' }).click();
  await expect(page.getByRole('heading', { name: 'Welcome, E2E Accountant' })).toBeVisible();

  // Lost authenticator: a saved recovery code leads straight to setting up a new one.
  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await signIn(page, username, final);
  await page.getByRole('button', { name: /Use a recovery code/ }).click();
  await page.getByLabel('Recovery code').fill(recoveryCodes[0]!);
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('heading', { name: 'Set up your authenticator' })).toBeVisible();
  const replacement = (await page.getByText(/Manual key:/).textContent())!.replace('Manual key:', '').trim();
  expect(replacement).not.toBe(secret);
  await page.getByLabel('6-digit code').fill(totp(replacement));
  await page.getByRole('button', { name: 'Verify' }).click();
  await expect(page.getByRole('heading', { name: 'Save your recovery codes' })).toBeVisible();
  await page.getByLabel('I have saved these codes somewhere safe.').check();
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('heading', { name: 'Welcome, E2E Accountant' })).toBeVisible();
});

test('an external auditor is created with an access expiry, which the administrator can extend', async ({ page }) => {
  const username = `e2e.coa.${Date.now()}`;
  const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

  await waitForFreshStep();
  await signIn(page, admin.username, admin.password);
  const mfa = page.getByRole('heading', { name: 'Two-step verification' });
  const welcome = page.getByRole('heading', { name: /Welcome/ });
  await expect(mfa.or(welcome)).toBeVisible();
  if (await mfa.isVisible()) {
    // A code works once: a fresh window, so an earlier run's or spec's sign-in cannot have used it.
    await waitForFreshStep();
    await page.getByLabel('6-digit code').fill(totp(admin.totpSecret));
    await page.getByRole('button', { name: 'Verify' }).click();
  }
  await expect(welcome).toBeVisible();

  await page.goto('/admin/users/new');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('E-mail').fill(`${username}@auditor.test`);
  await page.getByLabel('Display name').fill('E2E COA Auditor');
  await select(page.getByLabel('User type'), 'EXTERNAL_AUDITOR');
  await expect(page.getByLabel('Access expires')).toHaveAttribute('required', '');
  await page.getByLabel('Access expires').fill(day(30));
  await page.getByLabel('Temporary password').fill('Temp-E2E-Passw0rd!');
  await page.getByRole('checkbox', { name: 'External Auditor' }).check();
  await page.getByRole('button', { name: 'Create user' }).click();
  await expect(page.getByRole('heading', { name: 'E2E COA Auditor' })).toBeVisible();
  await expect(page.getByText('23:59:59').first()).toBeVisible();

  // Extending the expiry from the Profile section.
  const profile = page.getByRole('form', { name: 'Profile' });
  await expect(profile.getByLabel('Access expires')).toHaveValue(day(30));
  await profile.getByLabel('Access expires').fill(day(60));
  await profile.getByRole('button', { name: 'Save profile' }).click();
  await expect(page.getByRole('status')).toContainText('Profile saved.');
  await expect(profile.getByLabel('Access expires')).toHaveValue(day(60));
});
