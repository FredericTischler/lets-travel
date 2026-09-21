import { expect, test } from '@playwright/test';

import { createTestUser, loginAsTestUser } from './support/test-user';

/**
 * Destination CRUD, against the real travel-service (no mocks): create with
 * activities and accommodations, see it in the list, edit a date, verify
 * the update, delete it, verify it disappears.
 *
 * This is the richest flow of the dashboard, and the one that most
 * justified an E2E test: the PUT /destinations/{id} path (edit) was broken
 * and fixed earlier in this project — this spec is the non-regression
 * guarantee for that fix, at the E2E level rather than only unit-tested.
 */
test.describe('Destination CRUD', () => {
  test('create, edit, and delete a destination', async ({ page, request }) => {
    const user = await createTestUser(request);
    await loginAsTestUser(page, user);

    await page.getByRole('link', { name: 'Destinations' }).click();
    await expect(page).toHaveURL(/\/destinations$/);

    const destinationName = `E2E Destination ${Date.now()}`;

    // --- Create, with one activity and one accommodation ---------------
    await page.getByLabel('Nom', { exact: true }).fill(destinationName);
    await page.getByLabel('Pays').fill('Portugal');
    await page.locator('#destStartDate').fill('2027-01-10');
    await page.locator('#destEndDate').fill('2027-01-20');
    // Since Phase 1 a destination is a "Travel": price, capacity and an owning
    // manager are required. An admin types the manager id (here: their own).
    await page.getByLabel('Prix (€)').fill('750');
    await page.getByLabel('Capacité (places)').fill('12');
    await page.getByLabel("Identifiant de l'organisateur").fill(user.id);

    await page.getByRole('button', { name: 'Ajouter une activité' }).click();
    await page.getByPlaceholder('Ex: Tram 28 ride').fill('Visite du chateau');

    await page.getByRole('button', { name: 'Ajouter un hébergement' }).click();
    await page.getByPlaceholder('Ex: Hotel Lisboa').fill('Hotel Lisboa');
    await page.getByPlaceholder('Ex: HOTEL', { exact: true }).fill('HOTEL');

    await page.getByRole('button', { name: 'Créer', exact: true }).click();

    const row = page.locator('tr.table-row', { hasText: destinationName });
    await expect(row).toBeVisible();
    await expect(row).toContainText('2027-01-10');
    await expect(row).toContainText('2027-01-20');
    await expect(row).toContainText('750.00');
    await expect(row).toContainText('12');

    // --- Edit: change the end date --------------------------------------
    await row.getByRole('button', { name: 'Modifier' }).click();
    await page.locator('#destEndDate').fill('2027-01-25');
    await page.getByRole('button', { name: 'Enregistrer les modifications' }).click();

    const updatedRow = page.locator('tr.table-row', { hasText: destinationName });
    await expect(updatedRow).toContainText('2027-01-25');

    // --- Delete ----------------------------------------------------------
    page.once('dialog', (dialog) => dialog.accept());
    await updatedRow.getByRole('button', { name: 'Supprimer' }).click();

    await expect(page.locator('tr.table-row', { hasText: destinationName })).toHaveCount(0);
  });
});
