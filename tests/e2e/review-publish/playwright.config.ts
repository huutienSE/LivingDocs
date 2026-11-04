import { defineConfig, devices } from '@playwright/test';

/**
 * Runs against the real Compose stack. The frontend is served by Vite (proxy /api → Gateway),
 * started automatically unless LD_UI_URL points to an already running UI.
 */
const UI_URL = process.env.LD_UI_URL ?? 'http://127.0.0.1:5173';

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.spec\.ts/,
  fullyParallel: false, // tests mutate seeded versions; keep order deterministic
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }], ['junit', { outputFile: 'results/junit.xml' }]],
  use: {
    baseURL: UI_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: process.env.LD_PW_VIDEO === 'off' ? 'off' : 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Optional: use a locally installed Chrome instead of `npx playwright install chromium`.
        launchOptions: process.env.LD_CHROME_PATH ? { executablePath: process.env.LD_CHROME_PATH } : {},
      },
    },
  ],
  webServer: process.env.LD_UI_URL
    ? undefined
    : {
        command: 'npm --prefix ../../../frontend run dev -- --host 127.0.0.1',
        url: UI_URL,
        reuseExistingServer: true,
        timeout: 120_000,
      },
});
