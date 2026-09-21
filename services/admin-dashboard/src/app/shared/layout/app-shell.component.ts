import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { ROLE_LABELS, isRole } from '../../core/auth/roles';
import { ThemeService } from '../../core/theme/theme.service';
import { BadgeComponent } from '../ui/badge/badge.component';
import { navItemsFor } from './nav-items';

/**
 * Shared layout for the authenticated area: brand, role-filtered navigation
 * (data-driven, see nav-items.ts), the logged-in user's email and role badge,
 * the theme toggle and the logout button, wrapping a router outlet.
 *
 * Responsive: below the `lg` breakpoint the navigation and user controls
 * collapse behind a menu button. There is a single navigation element in the
 * DOM — CSS decides whether it is a bar or a drop-down — so links are never
 * duplicated for assistive technology.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, BadgeComponent],
  templateUrl: './app-shell.component.html',
})
export class AppShellComponent {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly themeService = inject(ThemeService);

  protected readonly menuOpen = signal(false);

  protected readonly navItems = computed(() => navItemsFor(this.authService.role()));
  protected readonly email = this.authService.email;
  protected readonly roleLabel = computed(() => {
    const role = this.authService.role();
    return isRole(role) ? ROLE_LABELS[role] : null;
  });

  toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  closeMenu(): void {
    this.menuOpen.set(false);
  }

  logout(): void {
    this.authService.logout();
    this.menuOpen.set(false);
    this.router.navigate(['/login']);
  }
}
