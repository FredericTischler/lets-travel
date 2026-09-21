import { expect, test } from '@playwright/test';

import { catalogueLink, createTravelViaApi } from './support/travel-api';
import { createTestUser, loginAsTestUser } from './support/test-user';

/**
 * Reports across two roles, against the real identity-service: a traveler
 * reports a travel's organiser from the travel page, an admin reviews the
 * report in the moderation queue and decides on it.
 */
test.describe('Reports', () => {
  test('a traveler reports an organiser and an admin decides on the report', async ({ browser, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager);
    const traveler = await createTestUser(request, 'TRAVELER');
    const admin = await createTestUser(request, 'ADMIN');
    // Free text on purpose containing markup: it must be displayed, not executed.
    const reason = `Comportement inapproprié <b>gras</b> ${Date.now()}`;

    // --- Traveler files the report ---
    const travelerContext = await browser.newContext({ ignoreHTTPSErrors: true });
    const travelerPage = await travelerContext.newPage();
    await loginAsTestUser(travelerPage, traveler);
    await catalogueLink(travelerPage, travel.name).click();
    await expect(travelerPage.getByTestId('report-count')).toContainText('0');

    await travelerPage.getByRole('button', { name: "Signaler l'organisateur" }).click();
    await travelerPage.getByLabel('Motif du signalement').fill(reason);
    await travelerPage.getByRole('button', { name: 'Envoyer le signalement' }).click();
    await expect(travelerPage.getByText('Votre signalement a été transmis')).toBeVisible();
    await expect(travelerPage.getByTestId('report-count')).toContainText('1');

    // --- Admin reviews it ---
    const adminContext = await browser.newContext({ ignoreHTTPSErrors: true });
    const adminPage = await adminContext.newPage();
    await loginAsTestUser(adminPage, admin);
    await adminPage.getByRole('link', { name: 'Signalements' }).click();
    await expect(adminPage).toHaveURL(/\/admin\/reports$/);

    const row = adminPage.locator('tr.table-row', { hasText: reason });
    await expect(row).toBeVisible();
    await expect(row).toContainText('Ouvert');
    await expect(row).toContainText(traveler.email);
    await expect(row).toContainText(manager.email);
    // The markup in the reason is shown literally and not interpreted.
    await expect(row.locator('b')).toHaveCount(0);

    await row.getByRole('button', { name: 'Sanctionner' }).click();
    await expect(row).toContainText('Sanctionné');
    // A decided report is immutable: no further action offered.
    await expect(row.getByRole('button')).toHaveCount(0);

    await adminPage.getByRole('button', { name: /^Sanctionné/ }).click();
    await expect(adminPage.locator('tr.table-row', { hasText: reason })).toBeVisible();

    await travelerContext.close();
    await adminContext.close();
  });

  test('an organiser is not offered to report themselves on their own travel', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager);
    await loginAsTestUser(page, manager);

    await page.getByRole('link', { name: 'Voyages', exact: true }).click();
    await catalogueLink(page, travel.name).click();

    await expect(page.getByRole('button', { name: "Signaler l'organisateur" })).toHaveCount(0);
  });
});
