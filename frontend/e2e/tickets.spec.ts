import { expect, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { act, nav, PDF, signIn, signOut, select } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(240_000);

test('an incident is raised, worked against its SLA and closed by the requester', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const requester = await onboard(token, 'tkreq', ['ACCOUNTANT']);
  const agent = await onboard(token, 'tkagent', ['SUPPORT_AGENT']);
  await nextTotpWindow();
  const title = `Trial balance export fails ${Date.now().toString(36)}`;

  await signIn(page, requester);
  await nav(page, 'Tickets');
  await page.getByRole('button', { name: 'New ticket' }).click();
  await select(page.getByLabel('Priority'), 'P2');
  await select(page.getByLabel(/^Severity/), 'S2');
  await select(page.getByLabel(/^Category/), { label: '[PLACEHOLDER] Reports and printing' });
  await page.getByLabel('Title').fill(title);
  await page.getByLabel('Description').fill('Clicking Export XLSX on the trial balance shows an error for October.');
  await page.getByRole('button', { name: 'Submit ticket' }).click();
  await expect(page.getByRole('heading', { name: new RegExp(`TKT-\\d{4}-\\d{6} — ${title}`) })).toBeVisible();
  await expect(page.getByText(/respond 60 min, resolve 480 min \(business hours\) \[PLACEHOLDER\]/)).toBeVisible();
  const ticketUrl = page.url();
  await expect(page.getByText(/S2 · \[PLACEHOLDER\] Reports and printing/)).toBeVisible();
  await page.getByLabel('Choose supporting document').setInputFiles(PDF);
  await expect(page.getByRole('button', { name: PDF.name })).toBeVisible();
  await expect(page.getByRole('main').getByRole('button', { name: 'Acknowledge' })).toHaveCount(0);
  await signOut(page);

  await signIn(page, agent);
  await page.goto(ticketUrl);
  await act(page, 'Acknowledge', 'Acknowledge');
  await page.getByLabel('Assign to username').fill(agent.username);
  await page.getByRole('button', { name: 'Assign' }).click();
  await expect(page.getByText(`Assignee: ${agent.username}`)).toBeVisible();
  await page.getByLabel('Comment').fill('Reproduced; the export job lacks a period parameter.');
  await page.getByLabel('Internal note (hidden from the requester)').check();
  await page.getByRole('button', { name: 'Add comment' }).click();
  await expect(page.getByText('Reproduced; the export job lacks a period parameter.')).toBeVisible();
  await act(page, 'Start work', 'Start');
  await act(page, 'Resolve', 'Resolve', 'Fixed the period parameter; please retry the export.');
  await expect(page.getByRole('main').getByText('RESOLVED', { exact: true }).first()).toBeVisible();
  // Only the requester confirms and closes.
  await expect(page.getByRole('main').getByRole('button', { name: 'Confirm and close' })).toHaveCount(0);
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, requester);
  await page.goto(ticketUrl);
  await expect(page.getByText('Reproduced; the export job lacks a period parameter.')).toHaveCount(0);
  await expect(page.getByText('Fixed the period parameter; please retry the export.')).toBeVisible();
  await page.getByRole('main').getByRole('button', { name: 'Confirm and close' }).click();
  await select(page.getByRole('dialog').getByLabel('Satisfaction (optional)'), '4');
  await page.getByRole('dialog').getByRole('button', { name: 'Close ticket', exact: true }).click();
  await expect(page.getByRole('main').getByText('CLOSED', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('CONFIRMED BY REQUESTER')).toBeVisible();
  await expect(page.getByText('Satisfaction 4/5')).toBeVisible();
});

test('a change request carries a rollback plan, is validated on staging and released with notes', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const requester = await onboard(token, 'crreq', ['ACCOUNTANT']);
  const agent = await onboard(token, 'cragent', ['SUPPORT_AGENT']);
  const approver = await onboard(token, 'crfm', ['FINANCE_MANAGER']);
  await nextTotpWindow();
  const title = `Add TIN to AP aging ${Date.now().toString(36)}`;

  await signIn(page, requester);
  await nav(page, 'Tickets');
  await page.getByRole('button', { name: 'New ticket' }).click();
  await select(page.getByLabel(/^Type/), 'CHANGE_REQUEST');
  await select(page.getByLabel(/^Category/), { label: '[PLACEHOLDER] Reports and printing' });
  await page.getByLabel('Title').fill(title);
  await page.getByLabel('Description').fill('Show the supplier TIN column on the AP aging report.');
  await page.getByRole('button', { name: 'Submit ticket' }).click();
  await expect(page.getByRole('heading', { name: new RegExp(title) })).toBeVisible();
  const ticketUrl = page.url();
  await signOut(page);

  await signIn(page, agent);
  await page.goto(ticketUrl);
  await act(page, 'Acknowledge', 'Acknowledge');
  await page.getByLabel('Assign to username').fill(agent.username);
  await page.getByRole('button', { name: 'Assign' }).click();
  await expect(page.getByText(`Assignee: ${agent.username}`)).toBeVisible();
  await page.getByRole('main').getByRole('button', { name: 'Submit for approval' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('button', { name: 'Submit', exact: true })).toBeDisabled();
  await dialog.getByLabel('Rollback plan').fill('Redeploy the previous report template from release 1.4.2.');
  await dialog.getByLabel(/Proposed change and impact/).fill('Report layout only; about two hours.');
  await dialog.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('region', { name: 'Change control' }).getByText('Redeploy the previous report template')).toBeVisible();
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, approver);
  await page.goto(ticketUrl);
  await act(page, 'Approve', 'Approve', 'Approved for the next release.');
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, agent);
  await page.goto(ticketUrl);
  await act(page, 'Start work', 'Start');
  // Not releasable until validated on staging, and the assignee cannot validate it.
  await expect(page.getByRole('main').getByRole('button', { name: 'Resolve' })).toHaveCount(0);
  await expect(page.getByRole('main').getByRole('button', { name: 'Validate on staging' })).toHaveCount(0);
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, requester);
  await page.goto(ticketUrl);
  await act(page, 'Validate on staging', 'Record validation', 'Checked on staging with the March data; the TIN column shows.');
  await expect(page.getByRole('region', { name: 'Change control' }).getByText('VALIDATED')).toBeVisible();
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, agent);
  await page.goto(ticketUrl);
  await page.getByRole('main').getByRole('button', { name: 'Resolve' }).click();
  await page.getByRole('dialog').getByLabel('Release notes').fill('The AP aging report now shows the supplier TIN column.');
  await page.getByRole('dialog').getByLabel('Release reference (optional)').fill('1.4.3');
  await page.getByRole('dialog').getByLabel(/Resolution/).fill('Deployed to production.');
  await page.getByRole('dialog').getByRole('button', { name: 'Resolve', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  await expect(page.getByRole('region', { name: 'Change control' }).getByText(/1\.4\.3: The AP aging report now shows/)).toBeVisible();
});
