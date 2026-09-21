import { expect, test } from '@playwright/test';

/**
 * Public sign-up (/register), against the real identity-service: the form
 * offers TRAVELER and TRAVEL_MANAGER only, logs the new user in and lands on
 * the home page of the chosen role, with a role-appropriate navigation and a
 * working logout.
 */
test.describe('Sign-up', () => {
  function uniqueEmail(): string {
    return `e2e-signup-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  }

  test('the login page links to the sign-up page, which never offers ADMIN', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('link', { name: 'Créer un compte' }).click();
    await expect(page).toHaveURL(/\/register$/);

    await expect(page.getByRole('radio')).toHaveCount(2);
    await expect(page.getByRole('radio', { name: /Voyageur/ })).toBeChecked();
    await expect(page.getByRole('radio', { name: /Organisateur/ })).toBeVisible();
    await expect(page.getByText('Administrateur')).toHaveCount(0);
  });

  test('a new traveler lands on the catalogue, sees only traveler navigation, and can log out', async ({
    page,
  }) => {
    await page.goto('/register');
    await page.getByLabel('Email').fill(uniqueEmail());
    await page.getByLabel('Mot de passe').fill('TestPass1234');
    await page.getByRole('button', { name: 'Créer mon compte' }).click();

    await expect(page).toHaveURL(/\/travels$/);
    const nav = page.getByRole('navigation', { name: 'Navigation principale' });
    await expect(nav.getByRole('link', { name: 'Voyages' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Mes abonnements' })).toBeVisible();
    await expect(nav.getByRole('link')).toHaveCount(2);
    await expect(page.getByText('Voyageur', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Se déconnecter' }).click();
    await expect(page).toHaveURL(/\/login$/);

    // The session is really gone: a protected URL bounces back to /login.
    await page.goto('/travels');
    await expect(page).toHaveURL(/\/login$/);
  });

  test('a new travel manager lands on "my travels" and also sees the traveler navigation', async ({
    page,
  }) => {
    await page.goto('/register');
    await page.getByLabel('Email').fill(uniqueEmail());
    await page.getByLabel('Mot de passe').fill('TestPass1234');
    await page.getByRole('radio', { name: /Organisateur/ }).check();
    await page.getByRole('button', { name: 'Créer mon compte' }).click();

    await expect(page).toHaveURL(/\/manager\/travels$/);
    const nav = page.getByRole('navigation', { name: 'Navigation principale' });
    await expect(nav.getByRole('link', { name: 'Mes voyages organisés' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Voyages', exact: true })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Utilisateurs' })).toHaveCount(0);
  });

  test('a too short password is refused before any call to the backend', async ({ page }) => {
    await page.goto('/register');
    await page.getByLabel('Email').fill(uniqueEmail());
    await page.getByLabel('Mot de passe').fill('short');
    await page.getByRole('button', { name: 'Créer mon compte' }).click();

    await expect(page.getByRole('alert')).toContainText('au moins 8 caractères');
    await expect(page).toHaveURL(/\/register$/);
  });
});
