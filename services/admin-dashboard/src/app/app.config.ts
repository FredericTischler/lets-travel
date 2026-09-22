import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, isDevMode, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Component input binding: route params (`:id`) reach `input()` signals.
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor])),
    // PWA (bonus, sujet §11 addendum) : scaffolding standard `ng add @angular/pwa`,
    // désactivé en dev (isDevMode()) pour ne pas gêner `ng serve` avec un cache de
    // service worker. `registerWhenStable:30000` = enregistrement différé (30 s
    // après stabilisation de l'app), pas au chargement initial.
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
