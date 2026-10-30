import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  // Against a live docker-compose stack + `npm run dev` — see scripts/e2e-test.sh's
  // step 14 for the equivalent backend-only check and README.md's "Frontend" section
  // for the exact commands.
  use: {
    baseURL: process.env['PLAYWRIGHT_BASE_URL'] ?? 'http://localhost:5173',
  },
});
