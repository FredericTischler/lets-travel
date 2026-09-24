import AxeBuilder from '@axe-core/playwright';
import { Page, expect, test } from '@playwright/test';

import { createTravelViaApi } from './support/travel-api';
import { TestUser, createTestUser, loginAsTestUser } from './support/test-user';

/**
 * Automated accessibility sweep (axe-core, WCAG 2.1 A/AA rules) of every
 * screen reachable from each role's navigation, against the real stack —
 * same "no mocks" philosophy as the rest of this e2e suite. This complements,
 * it does not replace, the ARIA roles/labels already reviewed by hand
 * (services/admin-dashboard/README.md "Accessibilité") — axe catches
 * mechanical violations (contrast, missing labels, invalid ARIA), not
 * genuine usability with a screen reader, which still needs a human.
 *
 * Each page is scanned once, listing every violation found (not just
 * asserting zero) so a failure is immediately actionable — see the
 * `describe.each`-style loop below for the exact page list per role.
 */
test.describe('Accessibility', () => {
  async function expectNoViolations(page: Page, screenName: string): Promise<void> {
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    const summary = results.violations.map(
      (v) => `[${v.impact}] ${v.id}: ${v.description} (${v.nodes.length} node(s))`,
    );
    expect(summary, `Accessibility violations on ${screenName}`).toEqual([]);
  }

  test('the login screen has no violations', async ({ page }) => {
    await page.goto('/login');
    await expectNoViolations(page, '/login');
  });

  test('the sign-up screen has no violations', async ({ page }) => {
    await page.goto('/register');
    await expectNoViolations(page, '/register');
  });

  test('traveler screens have no violations', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager, { price: 0 });
    const traveler = await createTestUser(request, 'TRAVELER');
    await loginAsTestUser(page, traveler);
    await expectNoViolations(page, '/travels');

    await page.goto(`/travels/${travel.id}`);
    await expectNoViolations(page, '/travels/:id');

    await page.getByRole('link', { name: 'Mes abonnements' }).click();
    await expectNoViolations(page, '/my-subscriptions');

    await page.getByRole('link', { name: 'Mes statistiques' }).click();
    await expectNoViolations(page, '/my-stats');
  });

  test('travel manager screens have no violations', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    await createTravelViaApi(request, manager, { price: 0 });
    await loginAsTestUser(page, manager);
    await expectNoViolations(page, '/manager/travels');

    await page.getByRole('link', { name: 'Tableau de bord organisateur' }).click();
    await expectNoViolations(page, '/manager/dashboard');
  });

  test('admin screens have no violations', async ({ page, request }) => {
    const admin = await createTestUser(request, 'ADMIN');
    await loginAsTestUser(page, admin);
    await expectNoViolations(page, '/users');

    await page.getByRole('link', { name: 'Tableau de bord admin' }).click();
    await expectNoViolations(page, '/admin/dashboard');

    await page.getByRole('link', { name: 'Avis', exact: true }).click();
    await expectNoViolations(page, '/admin/feedback');

    await page.getByRole('link', { name: 'Signalements' }).click();
    await expectNoViolations(page, '/admin/reports');

    await page.getByRole('link', { name: 'Paiements' }).click();
    await expectNoViolations(page, '/payments');

    await page.getByRole('link', { name: 'Destinations' }).click();
    await expectNoViolations(page, '/destinations');
  });

  test('dark mode has no violations either', async ({ page, request }) => {
    // Every color token above is checked in both themes when it's darkened for
    // AA (see styles.css comments), but only light mode is the OS/browser
    // default here — dark mode gets its own pass rather than assumed-safe.
    await page.addInitScript(() => localStorage.setItem('admin-dashboard.theme', 'dark'));

    await page.goto('/login');
    await expectNoViolations(page, '/login (dark)');

    const admin = await createTestUser(request, 'ADMIN');
    await loginAsTestUser(page, admin);
    await expectNoViolations(page, '/users (dark)');

    await page.getByRole('link', { name: 'Tableau de bord admin' }).click();
    await expectNoViolations(page, '/admin/dashboard (dark)');
  });
});
