import { expect, test } from '@playwright/test';

import { createTestUser, loginAsTestUser } from './support/test-user';

/**
 * Role-aware navigation and route guards, against the real identity-service:
 * each role sees only its own entries (ADMIN sees all, hierarchy), and a URL
 * the role may not use is redirected to that role's home page.
 */
test.describe('Role navigation', () => {
  test('a traveler is kept out of manager and admin screens', async ({ page, request }) => {
    const traveler = await createTestUser(request, 'TRAVELER');
    await loginAsTestUser(page, traveler);

    for (const forbidden of ['/users', '/payments', '/destinations', '/admin/reports', '/manager/travels']) {
      await page.goto(forbidden);
      await expect(page).toHaveURL(/\/travels$/);
    }
  });

  test('a travel manager reaches traveler and manager screens but not admin ones', async ({
    page,
    request,
  }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    await loginAsTestUser(page, manager);

    await page.goto('/travels');
    await expect(page).toHaveURL(/\/travels$/);
    await page.goto('/my-subscriptions');
    await expect(page).toHaveURL(/\/my-subscriptions$/);

    await page.goto('/admin/reports');
    await expect(page).toHaveURL(/\/manager\/travels$/);
    await page.goto('/users');
    await expect(page).toHaveURL(/\/manager\/travels$/);
  });

  test('an admin sees every navigation entry and can open traveler and manager screens', async ({
    page,
    request,
  }) => {
    const admin = await createTestUser(request, 'ADMIN');
    await loginAsTestUser(page, admin);

    const nav = page.getByRole('navigation', { name: 'Navigation principale' });
    for (const label of [
      'Voyages',
      'Mes abonnements',
      'Mes voyages organisés',
      'Signalements',
      'Utilisateurs',
      'Paiements',
      'Destinations',
    ]) {
      await expect(nav.getByRole('link', { name: label, exact: true })).toBeVisible();
    }

    await nav.getByRole('link', { name: 'Signalements' }).click();
    await expect(page).toHaveURL(/\/admin\/reports$/);
    await nav.getByRole('link', { name: 'Mes voyages organisés' }).click();
    await expect(page).toHaveURL(/\/manager\/travels$/);
  });

  test('the root URL sends each role to its own home page', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    await loginAsTestUser(page, manager);

    await page.goto('/');
    await expect(page).toHaveURL(/\/manager\/travels$/);
    await page.goto('/does-not-exist');
    await expect(page).toHaveURL(/\/manager\/travels$/);
  });

  test.describe('on a phone', () => {
    test.use({ viewport: { width: 375, height: 812 } });

    test('the navigation collapses behind a menu button and stays usable', async ({ page, request }) => {
      const traveler = await createTestUser(request, 'TRAVELER');
      await loginAsTestUser(page, traveler);

      const menu = page.getByRole('button', { name: 'Menu' });
      await expect(menu).toBeVisible();
      await expect(page.getByRole('link', { name: 'Mes abonnements' })).toBeHidden();

      await menu.click();
      await expect(page.getByRole('link', { name: 'Mes abonnements' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Se déconnecter' })).toBeVisible();

      await page.getByRole('link', { name: 'Mes abonnements' }).click();
      await expect(page).toHaveURL(/\/my-subscriptions$/);
      // Following a link closes the menu again.
      await expect(page.getByRole('link', { name: 'Mes abonnements' })).toBeHidden();

      // No horizontal scroll on the page itself.
      const overflows = await page.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
      );
      expect(overflows).toBe(false);
    });
  });
});
