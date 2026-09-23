import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { AppShellComponent } from './app-shell.component';

describe('AppShellComponent', () => {
  let fixture: ComponentFixture<AppShellComponent>;
  const role = signal<string | null>('TRAVELER');
  const authServiceStub = {
    role,
    email: signal<string | null>('user@example.com'),
    logout: vi.fn(),
  };

  function setup(currentRole: string | null) {
    role.set(currentRole);
    authServiceStub.logout.mockClear();
    TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: authServiceStub }],
    });
    fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
  }

  function linkLabels(): string[] {
    const links = fixture.nativeElement.querySelectorAll('nav a') as NodeListOf<HTMLElement>;
    return Array.from(links).map((link) => link.textContent?.trim() ?? '');
  }

  function button(label: string): HTMLButtonElement {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const found = buttons.find((b) => b.textContent?.trim() === label);
    if (!found) {
      throw new Error(`No button labelled "${label}"`);
    }
    return found;
  }

  it('shows only the traveler entries to a TRAVELER, with their email and role badge', () => {
    setup('TRAVELER');

    expect(linkLabels()).toEqual(['Voyages', 'Mes abonnements', 'Mes statistiques']);
    expect(fixture.nativeElement.textContent).toContain('user@example.com');
    expect(fixture.nativeElement.textContent).toContain('Voyageur');
  });

  it('adds the manager entry for a TRAVEL_MANAGER', () => {
    setup('TRAVEL_MANAGER');

    expect(linkLabels()).toEqual([
      'Voyages',
      'Mes abonnements',
      'Mes statistiques',
      'Tableau de bord organisateur',
      'Mes voyages organisés',
    ]);
    expect(fixture.nativeElement.textContent).toContain('Organisateur');
  });

  it('shows every entry to an ADMIN', () => {
    setup('ADMIN');

    expect(linkLabels()).toEqual([
      'Voyages',
      'Mes abonnements',
      'Mes statistiques',
      'Tableau de bord organisateur',
      'Mes voyages organisés',
      'Tableau de bord admin',
      'Avis',
      'Signalements',
      'Utilisateurs',
      'Paiements',
      'Destinations',
    ]);
    expect(fixture.nativeElement.textContent).toContain('Administrateur');
  });

  it('shows no navigation and no role badge for an unknown role', () => {
    setup('SUPERUSER');

    expect(linkLabels()).toEqual([]);
    expect(fixture.nativeElement.textContent).not.toContain('Administrateur');
  });

  it('logout clears the session and goes to /login', () => {
    setup('TRAVELER');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    button('Se déconnecter').click();

    expect(authServiceStub.logout).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('groups a TRAVELER\'s entries under a collapsible "Voyageur" section, open by default', () => {
    setup('TRAVELER');

    const toggle = fixture.nativeElement.querySelector(
      '[aria-controls="nav-group-Voyageur"]',
    ) as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(linkLabels()).toEqual(['Voyages', 'Mes abonnements', 'Mes statistiques']);

    toggle.click();
    fixture.detectChanges();

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(linkLabels()).toEqual([]);
  });

  it('groups a TRAVEL_MANAGER\'s own entries under a collapsible "Organisateur" section, open by default', () => {
    setup('TRAVEL_MANAGER');

    const toggle = fixture.nativeElement.querySelector(
      '[aria-controls="nav-group-Organisateur"]',
    ) as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(linkLabels()).toEqual(
      expect.arrayContaining(['Tableau de bord organisateur', 'Mes voyages organisés']),
    );

    toggle.click();
    fixture.detectChanges();

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(linkLabels()).toEqual(['Voyages', 'Mes abonnements', 'Mes statistiques']);
  });

  it('groups the admin-only entries under a collapsible "Administration" section, open by default', () => {
    setup('ADMIN');

    const toggle = fixture.nativeElement.querySelector(
      '[aria-controls="nav-group-Administration"]',
    ) as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(linkLabels()).toEqual(
      expect.arrayContaining(['Tableau de bord admin', 'Avis', 'Signalements', 'Utilisateurs', 'Paiements', 'Destinations']),
    );

    toggle.click();
    fixture.detectChanges();

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(linkLabels()).toEqual([
      'Voyages',
      'Mes abonnements',
      'Mes statistiques',
      'Tableau de bord organisateur',
      'Mes voyages organisés',
    ]);
  });

  it('toggles the mobile menu and reflects it in aria-expanded', () => {
    setup('TRAVELER');
    const toggle = button('Menu');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelector('#main-menu').classList.contains('hidden')).toBe(true);

    toggle.click();
    fixture.detectChanges();

    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector('#main-menu').classList.contains('hidden')).toBe(false);
  });
});
