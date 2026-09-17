import { APIRequestContext, Page, expect } from '@playwright/test';

const IDENTITY_API_URL = 'https://identity.localhost';

export interface TestUser {
  email: string;
  password: string;
}

/**
 * Creates a fresh account via the real identity-service API
 * (POST /users, see UserController#create) so each E2E run is independent
 * of any preexisting/fragile seeded account. Every account created this way
 * is ADMIN by default (single-role system, see JwtService), so it can
 * immediately use the admin-only /users, /payments and /destinations
 * endpoints the dashboard depends on after login.
 */
export async function createTestUser(request: APIRequestContext): Promise<TestUser> {
  const email = `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'TestPass1234';

  const response = await request.post(`${IDENTITY_API_URL}/users`, {
    data: { email, password },
  });

  if (!response.ok()) {
    throw new Error(
      `Failed to create test user via ${IDENTITY_API_URL}/users: ${response.status()} ${await response.text()}`,
    );
  }

  return { email, password };
}

/**
 * Logs in through the real login screen (not a storage-state shortcut) so
 * every spec that needs an authenticated session also exercises the actual
 * login flow against identity-service.
 */
export async function loginAsTestUser(page: Page, user: TestUser): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email').fill(user.email);
  await page.getByLabel('Mot de passe').fill(user.password);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/users$/);
}
