import { expect, test } from '@playwright/test';

import { createTestUser, loginAsTestUser } from './support/test-user';
import { catalogueLink, createTravelViaApi } from './support/travel-api';

/**
 * The role dashboards and the traveler pages that read statistics, against the
 * real stack. The data is arranged through the API: a manager with one free
 * travel and one traveler subscribed to it. Income stays at zero (no completed
 * payment) — the point is that every screen renders and is reachable from the
 * navigation; the numbers themselves are asserted by the Vitest component specs.
 */
test.describe('Dashboards and statistics', () => {
  test('the manager dashboard shows the KPI tiles, the income chart and their travel', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager, { startInDays: 30, price: 0 });
    await loginAsTestUser(page, manager);

    await page.getByRole('link', { name: 'Tableau de bord organisateur' }).click();
    await expect(page).toHaveURL(/\/manager\/dashboard$/);

    await expect(page.getByTestId('kpi-trips')).toContainText('1');
    await expect(page.getByTestId('kpi-travelers')).toBeVisible();
    await expect(page.getByTestId('kpi-rating')).toContainText('—');
    await expect(page.getByTestId('kpi-income')).toBeVisible();
    await expect(page.getByTestId('travel-row').filter({ hasText: travel.name })).toBeVisible();

    // Feedback of one travel is reachable from the table (empty for a fresh travel).
    await page.getByTestId('travel-row').filter({ hasText: travel.name }).getByRole('link', { name: 'Voir les avis' }).click();
    await expect(page).toHaveURL(/\/manager\/travels\/[0-9a-f-]+\/feedback$/);
    await expect(page.getByTestId('feedback-empty')).toBeVisible();
  });

  test('the admin dashboard lists the manager in the ranking, with a report count', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    await createTravelViaApi(request, manager, { startInDays: 30, price: 0 });
    const admin = await createTestUser(request, 'ADMIN');
    await loginAsTestUser(page, admin);

    await page.getByRole('link', { name: 'Tableau de bord admin' }).click();
    await expect(page).toHaveURL(/\/admin\/dashboard$/);

    await expect(page.getByTestId('kpi-travels')).toBeVisible();
    await expect(page.getByTestId('kpi-managers')).toBeVisible();

    // The ranking is paginated (20/page) and the dev database accumulates managers
    // across every test run: a fresh, unscored manager can land on any page, so page
    // through until it's found instead of assuming page 1. Each click re-fetches the
    // page asynchronously, so wait for the paginator's own summary to confirm the page
    // actually advanced before checking for the row (an immediate isVisible() check
    // would race the fetch and always see the previous page's content).
    const row = page.getByTestId('ranking-row').filter({ hasText: manager.email });
    const nextPageButton = page.getByRole('button', { name: 'Suivant' });
    const paginatorSummary = page.getByTestId('paginator-summary');
    for (let pageNumber = 1; !(await row.isVisible()) && (await nextPageButton.isEnabled()); pageNumber++) {
      await nextPageButton.click();
      await expect(paginatorSummary).toContainText(`Page ${pageNumber + 1} sur`);
    }
    await expect(row).toBeVisible();
    await expect(row.getByTestId('ranking-reports')).toHaveText('0');
    await expect(row.getByTestId('ranking-score')).not.toHaveText('');

    // The global feedback page is reachable from the navigation.
    await page.getByRole('link', { name: 'Avis', exact: true }).click();
    await expect(page).toHaveURL(/\/admin\/feedback$/);
    await expect(page.getByRole('heading', { name: 'Avis des voyageurs' })).toBeVisible();
  });

  test('a traveler sees suggestions, personal statistics and the public page of an organiser', async ({
    page,
    request,
  }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager, { startInDays: 30, price: 0 });
    const traveler = await createTestUser(request, 'TRAVELER');
    await loginAsTestUser(page, traveler);

    // Recommendations block on the home: a brand-new traveler gets the popularity ranking,
    // and each suggestion carries its reasons.
    await expect(page.getByRole('heading', { name: 'Suggestions pour vous' })).toBeVisible();
    const first = page.getByTestId('recommendation').first();
    await expect(first).toBeVisible();
    await first.getByText('Pourquoi ?').click();
    await expect(first.getByTestId('reasons').getByRole('listitem').first()).toBeVisible();

    // Personal statistics.
    await page.getByRole('link', { name: 'Mes statistiques' }).click();
    await expect(page).toHaveURL(/\/my-stats$/);
    await expect(page.getByTestId('kpi-past')).toContainText('0');
    await expect(page.getByTestId('kpi-cancellations')).toContainText('0');
    await expect(page.getByTestId('kpi-reports')).toContainText('0');
    await expect(page.getByRole('heading', { name: 'Moyens de paiement préférés' })).toBeVisible();

    // Travel detail -> public page of the organiser -> report it.
    await page.getByRole('link', { name: 'Voyages', exact: true }).click();
    await catalogueLink(page, travel.name).click();
    await page.getByTestId('manager-link').click();
    await expect(page).toHaveURL(/\/managers\/[0-9a-f-]+$/);
    await expect(page.getByTestId('kpi-travels')).toContainText('1');
    await expect(page.getByTestId('kpi-reports')).toContainText('0');

    await page.getByRole('button', { name: "Signaler l'organisateur" }).click();
    await page.getByLabel('Motif du signalement').fill(`Signalement e2e ${Date.now()}`);
    await page.getByRole('button', { name: 'Envoyer le signalement' }).click();
    await expect(page.getByText('Votre signalement a été transmis')).toBeVisible();
    await expect(page.getByTestId('kpi-reports')).toContainText('1');
  });

  test('a traveler is offered no feedback form before the travel has ended', async ({ page, request }) => {
    // Ended travels cannot be arranged through the API (a subscription to a past travel is refused),
    // so the submit path is covered by Vitest; here we check the form is not offered too early.
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager, { startInDays: 30, price: 0 });
    const traveler = await createTestUser(request, 'TRAVELER');
    await loginAsTestUser(page, traveler);

    await catalogueLink(page, travel.name).click();
    await page.getByRole('button', { name: "S'inscrire" }).click();
    await expect(page.getByRole('button', { name: 'Se désinscrire' })).toBeVisible();

    await expect(page.getByTestId('feedback-form')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Votre avis' })).toHaveCount(0);
  });
});
