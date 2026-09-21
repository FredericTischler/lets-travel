import { expect, test } from '@playwright/test';

import { apiLogin, createTestUser, loginAsTestUser } from './support/test-user';
import { catalogueLink, createTravelViaApi } from './support/travel-api';

const PAYMENT_API_URL = 'https://payment.localhost';

/**
 * Paying for a subscription, against the real stack. Only the MANUAL provider
 * is end-to-end testable here: PayPal and Stripe need real provider accounts
 * (the PayPal redirect + capture and the Stripe limit are covered by Vitest).
 *
 * Flow: a traveler subscribes to a priced travel choosing "Paiement manuel" ->
 * the reservation is PENDING_PAYMENT with an explanation and a deadline -> an
 * admin confirms the payment (PATCH /payments/{id}/status) -> payment-service
 * notifies travel-service -> the subscription becomes ACTIVE.
 */
test.describe('Subscription payment', () => {
  test('a manual payment keeps the seat pending until an admin confirms it', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager, { startInDays: 60, price: 499 });
    const traveler = await createTestUser(request, 'TRAVELER');
    const admin = await createTestUser(request, 'ADMIN');
    await loginAsTestUser(page, traveler);

    await catalogueLink(page, travel.name).click();
    await expect(page.getByTestId('provider-choice')).toBeVisible();
    await page.getByTestId('provider-MANUAL').check();
    await page.getByRole('button', { name: "S'inscrire" }).click();

    // Pending state: explanation, countdown, cancel option; no "Se désinscrire".
    const panel = page.getByTestId('pending-payment');
    await expect(panel).toContainText('En attente de paiement');
    await expect(panel.getByTestId('manual-explanation')).toContainText('administrateur doit confirmer');
    await expect(panel.getByTestId('countdown')).toContainText('Votre place est réservée encore');
    await expect(page.getByRole('button', { name: 'Se désinscrire' })).toHaveCount(0);

    // It is listed as a pending reservation in "Mes abonnements", not as upcoming.
    await page.getByRole('link', { name: 'Mes abonnements' }).click();
    await expect(page.getByTestId('pending-list')).toContainText(travel.name);
    await expect(page.getByTestId('count-upcoming')).toHaveText('0');

    // The admin confirms the money was received (arranged through the API).
    const adminToken = await apiLogin(request, admin);
    const headers = { Authorization: `Bearer ${adminToken}` };
    const payments = (await (await request.get(`${PAYMENT_API_URL}/payments`, { headers })).json()) as {
      id: string;
      userId: string;
      status: string;
    }[];
    const payment = payments.find((p) => p.userId === traveler.id && p.status === 'PENDING');
    expect(payment).toBeDefined();
    const confirm = await request.patch(`${PAYMENT_API_URL}/payments/${payment!.id}/status`, {
      headers,
      data: { status: 'COMPLETED' },
    });
    expect(confirm.ok()).toBe(true);

    // The subscription is now active.
    await page.reload();
    await expect(page.getByTestId('count-upcoming')).toHaveText('1');
    await expect(page.getByTestId('pending-list')).toHaveCount(0);
  });

  test('a pending reservation can be cancelled by the traveler', async ({ page, request }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const travel = await createTravelViaApi(request, manager, { startInDays: 60, price: 499 });
    const traveler = await createTestUser(request, 'TRAVELER');
    await loginAsTestUser(page, traveler);

    await catalogueLink(page, travel.name).click();
    await page.getByTestId('provider-MANUAL').check();
    await page.getByRole('button', { name: "S'inscrire" }).click();
    await expect(page.getByTestId('pending-payment')).toBeVisible();

    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: 'Annuler la réservation' }).click();

    await expect(page.getByText('Votre réservation a été annulée.')).toBeVisible();
    await expect(page.getByRole('button', { name: "S'inscrire" })).toBeVisible();
  });
});
