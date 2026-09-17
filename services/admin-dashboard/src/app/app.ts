import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { ThemeService } from './core/theme/theme.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  // Instantiated eagerly so its theme-sync effect (html class + localStorage)
  // is active for the whole app lifetime, on every route.
  private readonly themeService = inject(ThemeService);
}