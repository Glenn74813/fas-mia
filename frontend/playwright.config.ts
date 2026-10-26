import { defineConfig, devices } from '@playwright/test';

// Runs against an already running stack (Vite dev server or the NGINX container). Credentials for the
// pre-provisioned test administrator come from the environment, never from the repository.
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
