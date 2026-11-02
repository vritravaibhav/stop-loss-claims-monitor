import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    // Integration tests each create (and drop) their own Postgres schema, so files can run in parallel.
    testTimeout: 60_000,
    hookTimeout: 60_000,
  },
});
