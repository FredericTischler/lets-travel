import { expect, test } from '@playwright/test';

/**
 * Route guard: an unauthenticated visitor trying to reach the dashboard is
 * redirected to /login (see core/guards/auth.guard.ts). No backend call is
 * even needed for this scenario — the guard only checks for a stored token.
 */
test.describe('Auth guard', () => {
  test('an unauthenticated user hitting the dashboard is redirected to /login', async ({
    page,
  }) => {
    await page.goto('/users');

    await expect(page).toHaveURL(/\/login$/);
  });
});
