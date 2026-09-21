import { expect, test } from '@playwright/test';

import { catalogueLink, createTravelViaApi, isSearchAvailable } from './support/travel-api';
import { createTestUser, loginAsTestUser } from './support/test-user';

/**
 * Traveler catalogue search, against the real travel-service. Elasticsearch
 * only runs with the optional Compose `search` profile, so the spec probes the
 * API first and asserts the matching behaviour: real autocomplete/search when
 * the profile is up, or the graceful fallback (plain list + notice) when it is
 * not. Both are part of the feature.
 */
test.describe('Travel search', () => {
  test('autocomplete and search find a travel — or fall back to the list when search is down', async ({
    page,
    request,
  }) => {
    const manager = await createTestUser(request, 'TRAVEL_MANAGER');
    const unique = `Zanzibarquest${Date.now()}`;
    const travel = await createTravelViaApi(request, manager, { name: unique });
    const traveler = await createTestUser(request, 'TRAVELER');
    const searchUp = await isSearchAvailable(request, traveler);
    await loginAsTestUser(page, traveler);

    const box = page.getByLabel('Rechercher un voyage');
    await box.fill(unique.slice(0, 9));

    if (searchUp) {
      // Debounced autocomplete: the suggestion list shows the travel.
      const suggestion = page.getByRole('option', { name: new RegExp(unique) });
      await expect(suggestion).toBeVisible();

      // Full-text search narrows the catalogue down to it.
      await box.fill(unique);
      await page.getByRole('button', { name: 'Rechercher' }).click();
      await expect(page.getByText(/résultat\(s\) pour/)).toBeVisible();
      await expect(catalogueLink(page, travel.name)).toBeVisible();
      await expect(page.getByRole('status')).toHaveCount(0);

      // Choosing a suggestion opens the detail page.
      await page.getByRole('button', { name: 'Effacer' }).click();
      await box.fill(unique.slice(0, 9));
      await page.getByRole('option', { name: new RegExp(unique) }).click();
      await expect(page).toHaveURL(/\/travels\/[0-9a-f-]+$/);
      await expect(page.getByRole('heading', { name: travel.name })).toBeVisible();
    } else {
      await page.getByRole('button', { name: 'Rechercher' }).click();
      await expect(page.getByRole('status')).toContainText('indisponible');
      // The plain list is still browsable and the error banner is not shown.
      await expect(catalogueLink(page, travel.name)).toBeVisible();
      await expect(page.getByRole('alert')).toHaveCount(0);
    }
  });
});
