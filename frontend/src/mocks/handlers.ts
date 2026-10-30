import type { HttpHandler } from 'msw';

/** Empty by default — each test registers the specific handlers it needs via
 *  `server.use(...)`, scoped to that test only (reset after every test in setupTests.ts). */
export const handlers: HttpHandler[] = [];
