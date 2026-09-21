import { APIRequestContext, Page, expect } from '@playwright/test';

const IDENTITY_API_URL = 'https://identity.localhost';

export type TestRole = 'ADMIN' | 'TRAVEL_MANAGER' | 'TRAVELER';

export interface TestUser {
  id: string;
  email: string;
  password: string;
  role: TestRole;
}

/** Landing page of each role after login (see core/auth/roles.ts homeRouteFor). */
const HOME_PATH: Record<TestRole, RegExp> = {
  ADMIN: /\/users$/,
  TRAVEL_MANAGER: /\/manager\/travels$/,
  TRAVELER: /\/travels$/,
};

/**
 * Credentials of an ADMIN that already exists on the stack: the bootstrap
 * admin identity-service creates at startup from BOOTSTRAP_ADMIN_EMAIL /
 * BOOTSTRAP_ADMIN_PASSWORD (Vault `secret/identity/bootstrap-admin`, see
 * docs/lets-travel-architecture-decisions.md §1 addendum). Since POST /users
 * refuses to create an ADMIN for an anonymous caller, the e2e suite needs it
 * to mint the ADMIN accounts its specs use — the same legitimate route a real
 * admin would take (log in, then create the account with their token).
 *
 * Provide them to Playwright through the environment:
 *   E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... npm run e2e
 * (the dev-stack placeholders are in ansible/roles/vault/defaults/main.yml).
 */
function bootstrapAdminCredentials(): { email: string; password: string } {
  const email = process.env['E2E_ADMIN_EMAIL'];
  const password = process.env['E2E_ADMIN_PASSWORD'];
  if (!email || !password) {
    throw new Error(
      'E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD must be set: they are the credentials of the ' +
        'bootstrap admin (Vault secret/identity/bootstrap-admin), needed because POST /users no ' +
        'longer lets an anonymous caller create an ADMIN.',
    );
  }
  return { email, password };
}

/**
 * Creates a fresh account via the real identity-service API
 * (POST /users, see UserController#create) so each E2E run is independent
 * of any preexisting/fragile seeded account.
 *
 * TRAVELER and TRAVEL_MANAGER go through the public sign-up route, exactly as
 * the register screen does. ADMIN (the default, since the pre-Phase-1 specs
 * expect an account that can use every screen) is created the legitimate way:
 * log in as the bootstrap admin (see {@link bootstrapAdminCredentials}) and
 * POST /users with that token. Without a role in the body the backend would
 * now create a TRAVELER, so the role is always sent explicitly.
 */
export async function createTestUser(
  request: APIRequestContext,
  role: TestRole = 'ADMIN',
): Promise<TestUser> {
  const email = `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'TestPass1234';

  const headers: Record<string, string> = {};
  if (role === 'ADMIN') {
    const bootstrapAdmin = bootstrapAdminCredentials();
    const token = await apiLogin(request, { ...bootstrapAdmin, id: '', role: 'ADMIN' });
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await request.post(`${IDENTITY_API_URL}/users`, {
    data: { email, password, role },
    headers,
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
  await expect(page).toHaveURL(HOME_PATH[user.role]);
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
