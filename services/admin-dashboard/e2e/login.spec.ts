import { expect, test } from '@playwright/test';

import { createTestUser } from './support/test-user';

/**
 * Login screen, against the real identity-service (no mocks). A fresh
 * account is created via POST /users in beforeEach so each test is
 * independent of any preexisting account.
 */
test.describe('Login', () => {
  test('an existing account can log in and lands on the dashboard', async ({ page, request }) => {
    const user = await createTestUser(request);

    await page.goto('/login');
    await page.getByLabel('Email').fill(user.email);
    await page.getByLabel('Mot de passe').fill(user.password);
    await page.getByRole('button', { name: 'Se connecter' }).click();

    await expect(page).toHaveURL(/\/users$/);
    await expect(page.getByRole('link', { name: 'Utilisateurs' })).toBeVisible();
  });

  test('a wrong password shows an error and does not redirect', async ({ page, request }) => {
    const user = await createTestUser(request);

    await page.goto('/login');
    await page.getByLabel('Email').fill(user.email);
    await page.getByLabel('Mot de passe').fill('WrongPassword123');
    await page.getByRole('button', { name: 'Se connecter' }).click();

    await expect(page.getByRole('alert')).toHaveText('Identifiants invalides.');
    await expect(page).toHaveURL(/\/login$/);
  });
});
