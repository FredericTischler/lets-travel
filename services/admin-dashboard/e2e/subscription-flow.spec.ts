import { expect, test } from '@playwright/test';

import { createTravelViaApi } from './support/travel-api';
import { createTestUser, loginAsTestUser } from './support/test-user';

/**
 * Subscription lifecycle across two roles, against the real identity- and
 * travel-service (no mocks): a manager's travel is found and joined by a
 * traveler, shows up in "Mes abonnements", and the manager sees the subscriber
 * and can force-unsubscribe them. The travels are arranged through the API so
 * these specs only exercise the screens involved.
 *
 * Needs the travel-service image built with the subscription endpoints (the
 * Docker stack must be rebuilt after the Phase 3 backend changes).
 */
test.describe('Subscription flow', () => {
  test('a traveler subscribes and unsubscribes from a travel', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager, { startInDays: 60 });
    const traveler = await createTestUser(request, 'TRAVELER');
    await loginAsTestUser(page, traveler);

    // Browse -> detail.
    await expect(page.getByRole('heading', { name: 'Voyages', exact: true })).toBeVisible();
    await page.getByRole('link', { name: travel.name }).click();
    await expect(page).toHaveURL(/\/travels\/[0-9a-f-]+$/);
    await expect(page.getByRole('heading', { name: travel.name })).toBeVisible();
    await expect(page.getByText('499.00 €')).toBeVisible();

    // Subscribe.
    await page.getByRole('button', { name: "S'inscrire" }).click();
    await expect(page.getByRole('button', { name: 'Se désinscrire' })).toBeVisible();
    await expect(page.getByText('Vous pouvez annuler jusqu')).toBeVisible();

    // It appears in "Mes abonnements" as an upcoming travel.
    await page.getByRole('link', { name: 'Mes abonnements' }).click();
    await expect(page).toHaveURL(/\/my-subscriptions$/);
    await expect(page.getByTestId('count-upcoming')).toHaveText('1');
    await expect(page.getByRole('link', { name: travel.name })).toBeVisible();

    // Unsubscribe (60 days out: well before the 3-day cutoff).
    await page.getByRole('link', { name: travel.name }).click();
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: 'Se désinscrire' }).click();
    await expect(page.getByRole('button', { name: "S'inscrire" })).toBeVisible();

    await page.getByRole('link', { name: 'Mes abonnements' }).click();
    await expect(page.getByTestId('count-cancelled')).toHaveText('1');
    await expect(page.getByTestId('count-upcoming')).toHaveText('0');
  });

  test('unsubscribing less than 3 days before departure is refused with an explicit message', async ({
    page,
    request,
  }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager, { startInDays: 1 });
    const traveler = await createTestUser(request, 'TRAVELER');
    await loginAsTestUser(page, traveler);

    await page.getByRole('link', { name: travel.name }).click();
    await page.getByRole('button', { name: "S'inscrire" }).click();
    await expect(page.getByRole('button', { name: 'Se désinscrire' })).toBeVisible();
    await expect(page.getByText("Le délai d'annulation (3 jours avant le départ) est dépassé")).toBeVisible();

    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: 'Se désinscrire' }).click();

    await expect(page.getByRole('alert')).toContainText('Désinscription refusée');
    await expect(page.getByRole('alert')).toContainText('moins de 3 jours');
    // Still subscribed.
    await expect(page.getByRole('button', { name: 'Se désinscrire' })).toBeVisible();
  });

  test('the manager sees the subscriber of their travel and can force-unsubscribe them', async ({
    browser,
    request,
  }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager, { startInDays: 60 });
    const traveler = await createTestUser(request, 'TRAVELER');

    // Traveler subscribes in their own browser context.
    const travelerContext = await browser.newContext({ ignoreHTTPSErrors: true });
    const travelerPage = await travelerContext.newPage();
    await loginAsTestUser(travelerPage, traveler);
    await travelerPage.getByRole('link', { name: travel.name }).click();
    await travelerPage.getByRole('button', { name: "S'inscrire" }).click();
    await expect(travelerPage.getByRole('button', { name: 'Se désinscrire' })).toBeVisible();

    // Manager: my travels -> subscribers.
    const managerContext = await browser.newContext({ ignoreHTTPSErrors: true });
    const managerPage = await managerContext.newPage();
    await loginAsTestUser(managerPage, manager);
    const row = managerPage.locator('tr.table-row', { hasText: travel.name });
    await expect(row).toBeVisible();
    await row.getByRole('link', { name: 'Abonnés' }).click();
    await expect(managerPage).toHaveURL(/\/manager\/travels\/[0-9a-f-]+\/subscribers$/);

    await expect(managerPage.getByTestId('active-count')).toContainText('1 abonné(s) actif(s) sur 10 places');
    const subscriber = managerPage.locator('tr.table-row', { hasText: traveler.id });
    await expect(subscriber).toContainText('Active');

    managerPage.once('dialog', (dialog) => dialog.accept());
    await subscriber.getByRole('button', { name: 'Désinscrire' }).click();
    await expect(managerPage.getByTestId('active-count')).toContainText('0 abonné(s) actif(s)');
    await expect(subscriber).toContainText('Annulée');

    // The traveler now sees the cancellation.
    await travelerPage.getByRole('link', { name: 'Mes abonnements' }).click();
    await expect(travelerPage.getByTestId('count-cancelled')).toHaveText('1');

    await travelerContext.close();
    await managerContext.close();
  });
});
