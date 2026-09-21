import { expect, test } from '@playwright/test';

import { createTestUser, loginAsTestUser } from './support/test-user';

/**
 * The admin "Utilisateurs" form now has a role selector (it used to create
 * accounts without a role). An ADMIN token is what lets POST /users create every
 * role, ADMIN included; the created account can then log in with that role.
 */
test.describe('Admin creates users with a role', () => {
  test('creating a TRAVEL_MANAGER account, who then lands on the manager screens', async ({ browser, page, request }) => {
    const admin = await createTestUser(request, 'ADMIN');
    await loginAsTestUser(page, admin);

    const email = `e2e-role-${Date.now()}@example.com`;
    const password = 'TestPass1234';
    await page.getByLabel('Email').first().fill(email);
    await page.getByLabel('Mot de passe').first().fill(password);
    // Angular's [ngValue] makes the option values opaque ("1: TRAVEL_MANAGER"): pick by label.
    await page.getByLabel('Rôle').selectOption({ label: 'Organisateur (TRAVEL_MANAGER)' });
    await page.getByRole('button', { name: 'Créer' }).click();

    const row = page.locator('tr.table-row', { hasText: email });
    await expect(row).toBeVisible();
    await expect(row).toContainText('TRAVEL_MANAGER');

    // The new account really has that role: its landing page is the manager one.
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const managerPage = await context.newPage();
    await loginAsTestUser(managerPage, { id: '', email, password, role: 'TRAVEL_MANAGER' });
    await expect(managerPage.getByRole('link', { name: 'Tableau de bord organisateur' })).toBeVisible();
    await expect(managerPage.getByRole('link', { name: 'Utilisateurs' })).toHaveCount(0);
    await context.close();
  });

  test('the selector offers ADMIN and defaults to the safest role', async ({ page, request }) => {
    const admin = await createTestUser(request, 'ADMIN');
    await loginAsTestUser(page, admin);

    const select = page.getByLabel('Rôle');
    await expect(select).toHaveValue(/TRAVELER/);
    await expect(select.locator('option')).toHaveText([
      'Administrateur (ADMIN)',
      'Organisateur (TRAVEL_MANAGER)',
      'Voyageur (TRAVELER)',
    ]);
  });
});
