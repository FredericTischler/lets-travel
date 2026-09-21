import { APIRequestContext, Locator, Page } from '@playwright/test';

import { TestUser, apiLogin } from './test-user';

const TRAVEL_API_URL = 'https://travel.localhost';

/** ISO local date `days` days from today. */
export function isoDaysFromNow(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toLocaleDateString('sv-SE');
}

export interface ArrangedTravel {
  id: string;
  name: string;
}

/**
 * Creates a travel (a `Destination`) owned by `manager` straight through the
 * travel-service API, to arrange the data of a specific scenario without
 * clicking through the manager screen (which has its own spec).
 * `startInDays` decides on which side of the 3-day cancellation cutoff the
 * travel falls.
 */
export async function createTravelViaApi(
  request: APIRequestContext,
  manager: TestUser,
  options: { startInDays?: number; name?: string; price?: number } = {},
): Promise<ArrangedTravel> {
  const token = await apiLogin(request, manager);
  const startInDays = options.startInDays ?? 60;
  const name = options.name ?? `E2E Travel ${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

  const response = await request.post(`${TRAVEL_API_URL}/destinations`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name,
      country: 'Portugal',
      startDate: isoDaysFromNow(startInDays),
      endDate: isoDaysFromNow(startInDays + 5),
      managerId: manager.id,
      // 0 = a free travel (ACTIVE at once); a price makes the subscribe call open a payment.
      price: options.price ?? 499,
      capacity: 10,
      activities: ['Visite guidée'],
      accommodations: [],
    },
  });
  if (!response.ok()) {
    throw new Error(`Failed to create travel: ${response.status()} ${await response.text()}`);
  }

  return { id: ((await response.json()) as { id: string }).id, name };
}

/**
 * The catalogue link of a travel on /travels. The page also carries the
 * "Suggestions pour vous" block, which can list the same travel: scoping to the
 * catalogue keeps Playwright's strict-mode locators unambiguous.
 */
export function catalogueLink(page: Page, travelName: string): Locator {
  return page.getByTestId('catalogue').getByRole('link', { name: travelName });
}

/** True when the Elasticsearch-backed endpoints answer (the `search` profile is up). */
export async function isSearchAvailable(request: APIRequestContext, user: TestUser): Promise<boolean> {
  const token = await apiLogin(request, user);
  const response = await request.get(`${TRAVEL_API_URL}/destinations/autocomplete?prefix=zz`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return response.status() !== 503;
}
