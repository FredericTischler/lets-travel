import { expect, test } from '@playwright/test';

import { createTestUser, loginAsTestUser } from './support/test-user';

/**
 * Basic payment CRUD, against the real payment-service (no mocks): create a
 * manual payment, see it in the list, change its status from PENDING to
 * COMPLETED.
 */
test.describe('Payment CRUD', () => {
  test('create a payment and change its status', async ({ page, request }) => {
    const user = await createTestUser(request);
    await loginAsTestUser(page, user);

    await page.getByRole('link', { name: 'Paiements' }).click();
    await expect(page).toHaveURL(/\/payments$/);

    const amount = (Math.floor(Math.random() * 9000) + 1000) / 100; // e.g. 42.17
    const currency = 'EUR';

    await page.getByLabel('Montant').fill(String(amount));
    await page.getByLabel('Devise').fill(currency);
    await page.getByRole('button', { name: 'Créer', exact: true }).click();

    const row = page.locator('tr.table-row', { hasText: String(amount) });
    await expect(row).toBeVisible();
    await expect(row).toContainText('PENDING');

    await row.getByRole('button', { name: 'Marquer complété' }).click();

    const updatedRow = page.locator('tr.table-row', { hasText: String(amount) });
    await expect(updatedRow).toContainText('COMPLETED');
  });
});
