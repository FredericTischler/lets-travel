import { defineConfig, devices } from '@playwright/test';

/**
 * E2E config: runs against the real dev server + the real backend stack
 * (identity/travel/payment-service behind Traefik at https://*.localhost),
 * no mocks. See e2e/README-equivalent notes in each spec file for details.
 *
 * `ignoreHTTPSErrors` is required because the backend APIs are served over
 * HTTPS by Traefik with a self-signed certificate in this local dev setup;
 * without it, every fetch/XHR the app makes to https://*.localhost from
 * within the page would be rejected by Chromium.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:4200',
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
