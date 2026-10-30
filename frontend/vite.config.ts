/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    // :8087 is api-gateway's real REST listener (docker-compose.yml's
    // "8087:8080" mapping); :8088 is its actuator-only management port and has
    // no business routes at all — proxying there would 404/connection-refuse
    // every staff request.
    proxy: {
      '/api': { target: 'http://localhost:8087', changeOrigin: true },
      '/dev': { target: 'http://localhost:9000', changeOrigin: true },
    },
  },
  define: {
    // Empty string by default: RTK Query then issues relative paths
    // (e.g. "/api/v1/rooms/board"), same-origin against this dev server, which
    // the proxy above forwards server-side — the browser never makes a
    // cross-origin request, so api-gateway (which defines no CORS policy)
    // never needs one. A real deployment overrides this to the gateway's
    // actual public URL, where CORS is a deployment concern, not a dev one.
    //
    // Under Vitest specifically, a relative baseUrl doesn't work the same
    // way: MSW's Node interceptor constructs a `Request` directly from the
    // raw string before anything gets a chance to resolve it against a
    // document location (unlike a real browser's fetch) and throws "Invalid
    // URL". Vitest always sets process.env.VITEST, so tests get a concrete
    // dummy origin instead — dev/prod behavior above is unaffected.
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify(
      process.env['VITE_API_BASE_URL'] ?? (process.env['VITEST'] ? 'http://localhost:3000' : ''),
    ),
  },
  test: {
    // e2e/ is Playwright-only (a different test API — `test`/`expect` from
    // @playwright/test, not vitest's own globals/fixtures) — excluded here so
    // vitest doesn't try to run it with its own runner. .tsc-build/ is tsc's
    // own output directory (tsconfig.node.json), never test source.
    exclude: ['**/node_modules/**', '**/dist/**', 'e2e/**', '.tsc-build/**'],
    environment: 'jsdom',
    environmentOptions: {
      jsdom: {
        // Gives a concrete origin so a relative fetchBaseQuery baseUrl (the
        // real, dev-proxy-friendly default — see the VITE_API_BASE_URL note
        // above) resolves the same way it would against a real browser's
        // document location, instead of throwing "Invalid URL" under Node's
        // undici-backed global fetch.
        url: 'http://localhost:3000/',
      },
    },
    setupFiles: ['./src/setupTests.ts'],
    globals: true,
    coverage: {
      // Scoped to this layer's own new files, not the whole repo — the
      // existing owner-dashboard/payment-submission prototype code predates
      // this gate and is untouched by this layer (Constitution Principle II
      // applied the same way JaCoCo applies it on the backend: to the code a
      // change actually touches).
      thresholds: {
        'src/features/staff-session/**': { lines: 100, branches: 100, functions: 100, statements: 100 },
        'src/features/room-board/**': { lines: 100, branches: 100, functions: 100, statements: 100 },
        'src/features/booking/**': { lines: 100, branches: 100, functions: 100, statements: 100 },
        'src/components/RetryBanner.tsx': { lines: 100, branches: 100, functions: 100, statements: 100 },
      },
    },
  },
});
