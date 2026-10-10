import { defineConfig } from '@playwright/test';

const port = 5179;

/**
 * Screen tests: the real page in a browser, driven like a user. Runs against the dev server, whose debug hooks
 * (`window.__slice` and friends) let a test find where a block cell is on screen.
 *
 * CI uses Playwright's own Chromium (`npx playwright install chromium`); on a PC the installed Chrome is used, so
 * nothing has to be downloaded. Set PW_CHANNEL to pick another one (for example `msedge`).
 */
export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: `http://localhost:${port}/`,
    channel: process.env.PW_CHANNEL ?? (process.env.CI ? undefined : 'chrome'),
    locale: 'en-US',
    viewport: { width: 1400, height: 900 },
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npm run dev -- --port ${port} --strictPort`,
    url: `http://localhost:${port}/`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
