import { APIRequestContext, Page, expect } from '@playwright/test';

const IDENTITY_API_URL = 'https://identity.localhost';

export type TestRole = 'ADMIN' | 'TRAVEL_MANAGER' | 'TRAVELER';

export interface TestUser {
  id: string;
  email: string;
  password: string;
  /** `undefined` = created without a role (the backend then defaults to ADMIN). */
  role?: TestRole;
}

/** Landing page of each role after login (see core/auth/roles.ts homeRouteFor). */
const HOME_PATH: Record<TestRole, RegExp> = {
  ADMIN: /\/users$/,
  TRAVEL_MANAGER: /\/manager\/travels$/,
  TRAVELER: /\/travels$/,
};

/**
 * Creates a fresh account via the real identity-service API
 * (POST /users, see UserController#create) so each E2E run is independent
 * of any preexisting/fragile seeded account.
 *
 * Without `role` the account is created with no role in the body, which the
 * backend defaults to ADMIN (backward compatibility, and the reason the
 * pre-Phase-1 specs need no role): those accounts can use every screen.
 * Pass `role` for the role-specific specs. Creating an ADMIN explicitly works
 * because POST /users is currently public and accepts any role — a known
 * backend caveat tracked separately; this helper relies on it, the UI never
 * offers ADMIN.
 */
export async function createTestUser(
  request: APIRequestContext,
  role?: TestRole,
): Promise<TestUser> {
  const email = `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'TestPass1234';

  const response = await request.post(`${IDENTITY_API_URL}/users`, {
    data: role ? { email, password, role } : { email, password },
  });

  if (!response.ok()) {
    throw new Error(
      `Failed to create test user via ${IDENTITY_API_URL}/users: ${response.status()} ${await response.text()}`,
    );
  }

  const body = (await response.json()) as { id: string };
  return { id: body.id, email, password, role };
}

/**
 * Logs in through the real login screen (not a storage-state shortcut) so
 * every spec that needs an authenticated session also exercises the actual
 * login flow against identity-service, and checks the role-specific landing
 * page.
 */
export async function loginAsTestUser(page: Page, user: TestUser): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email').fill(user.email);
  await page.getByLabel('Mot de passe').fill(user.password);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(HOME_PATH[user.role ?? 'ADMIN']);
}

/** Direct API login, for arranging data without going through the UI. */
export async function apiLogin(request: APIRequestContext, user: TestUser): Promise<string> {
  const response = await request.post(`${IDENTITY_API_URL}/login`, {
    data: { email: user.email, password: user.password },
  });
  if (!response.ok()) {
    throw new Error(`API login failed for ${user.email}: ${response.status()} ${await response.text()}`);
  }
  return ((await response.json()) as { token: string }).token;
}
