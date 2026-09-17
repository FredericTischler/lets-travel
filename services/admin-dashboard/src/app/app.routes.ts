import { Routes } from '@angular/router';

import { authGuard } from './core/guards/auth.guard';
import { AppShellComponent } from './shared/layout/app-shell.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'users' },
  {
    path: 'login',
    loadComponent: () =>
      import('./features/login/login.component').then((m) => m.LoginComponent),
  },
  {
    // Authenticated area: shared layout (nav + theme toggle) wrapping the
    // existing feature screens, which are otherwise untouched.
    path: '',
    component: AppShellComponent,
    canActivate: [authGuard],
    children: [
      {
        path: 'users',
        loadComponent: () =>
          import('./features/users/user-list.component').then((m) => m.UserListComponent),
      },
      {
        path: 'payments',
        loadComponent: () =>
          import('./features/payments/payment-list.component').then(
            (m) => m.PaymentListComponent,
          ),
      },
      {
        path: 'destinations',
        loadComponent: () =>
          import('./features/destinations/destination-list.component').then(
            (m) => m.DestinationListComponent,
          ),
      },
    ],
  },
];
