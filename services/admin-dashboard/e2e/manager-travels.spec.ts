import { expect, test } from '@playwright/test';

import { createTravelViaApi } from './support/travel-api';
import { createTestUser, loginAsTestUser } from './support/test-user';

/**
 * Travel Manager's "Mes voyages organisés", against the real travel-service:
 * create a travel with price/capacity/activity/accommodation (no manager-id
 * field: it is pinned to the caller), see only own travels, edit, delete.
 */
test.describe('Manager travels', () => {
  test('create, edit and delete a travel, seeing only own travels', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const otherManager = await createTestUser(request, 'TRAVEL_MANAGER');
    const foreignTravel = await createTravelViaApi(request, otherManager);
    await loginAsTestUser(page, manager);

    // Someone else's travel is not listed.
    await expect(page.getByRole('heading', { name: 'Mes voyages', exact: true })).toBeVisible();
    await expect(page.locator('tr.table-row', { hasText: foreignTravel.name })).toHaveCount(0);

    const name = `E2E Manager Travel ${Date.now()}`;
    await page.getByLabel('Nom', { exact: true }).fill(name);
    await page.getByLabel('Pays').fill('Italie');
    await page.locator('#destStartDate').fill('2027-05-10');
    await page.locator('#destEndDate').fill('2027-05-17');
    await page.getByLabel('Prix (€)').fill('820.5');
    await page.getByLabel('Capacité (places)').fill('8');
    // The manager never types (nor sees) a manager id.
    await expect(page.getByLabel("Identifiant de l'organisateur")).toHaveCount(0);

    await page.getByRole('button', { name: 'Ajouter une activité' }).click();
    await page.getByLabel("Nom de l'activité").fill('Visite du Colisée');
    await page.getByRole('button', { name: 'Ajouter un hébergement' }).click();
    await page.getByLabel("Nom de l'hébergement").fill('Hotel Roma');
    await page.getByLabel("Type d'hébergement").fill('HOTEL');
    await page.getByRole('button', { name: 'Créer', exact: true }).click();

    const row = page.locator('tr.table-row', { hasText: name });
    await expect(row).toBeVisible();
    await expect(row).toContainText('2027-05-10');
    await expect(row).toContainText('820.50');
    await expect(row).toContainText('8');

    // Edit the capacity.
    await row.getByRole('button', { name: 'Modifier' }).click();
    await expect(page.getByLabel('Capacité (places)')).toHaveValue('8');
    await page.getByLabel('Capacité (places)').fill('15');
    await page.getByRole('button', { name: 'Enregistrer les modifications' }).click();
    await expect(page.locator('tr.table-row', { hasText: name })).toContainText('15');

    // Delete.
    page.once('dialog', (dialog) => dialog.accept());
    await page.locator('tr.table-row', { hasText: name }).getByRole('button', { name: 'Supprimer' }).click();
    await expect(page.locator('tr.table-row', { hasText: name })).toHaveCount(0);
  });

  test('client-side validation refuses an end date before the start date', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    await loginAsTestUser(page, manager);

    await page.getByLabel('Nom', { exact: true }).fill('Invalid dates');
    await page.getByLabel('Pays').fill('Italie');
    await page.locator('#destStartDate').fill('2027-05-10');
    await page.locator('#destEndDate').fill('2027-05-01');
    await page.getByLabel('Prix (€)').fill('10');
    await page.getByLabel('Capacité (places)').fill('1');
    await page.getByRole('button', { name: 'Créer', exact: true }).click();

    await expect(page.getByText('La date de fin doit être postérieure')).toBeVisible();
    await expect(page.locator('tr.table-row', { hasText: 'Invalid dates' })).toHaveCount(0);
  });
});
