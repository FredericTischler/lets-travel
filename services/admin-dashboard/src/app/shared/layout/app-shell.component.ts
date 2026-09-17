import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { ThemeService } from '../../core/theme/theme.service';

/**
 * Shared layout for the authenticated area of the dashboard: a header with
 * navigation between the feature screens and the theme toggle, wrapping a
 * router outlet for the active screen.
 *
 * Foundations-only for this increment: the header/nav/toggle are styled,
 * but the wrapped feature screens (users/payments/destinations) are not
 * touched.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app-shell.component.html',
})
export class AppShellComponent {
  protected readonly themeService = inject(ThemeService);
}