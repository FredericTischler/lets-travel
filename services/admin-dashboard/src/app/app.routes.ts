import { Routes } from '@angular/router';

import { ROLES } from './core/auth/roles';
import { authGuard } from './core/guards/auth.guard';
import { homeGuard, roleGuard } from './core/guards/role.guard';
import { AppShellComponent } from './shared/layout/app-shell.component';

/**
 * Route table. Every screen inside the shell carries a `roleGuard` naming the
 * role it is meant for; the hierarchy (ADMIN passes everywhere, a
 * TRAVEL_MANAGER also passes TRAVELER routes) is applied by the guard, and
 * the navigation entries of shared/layout/nav-items.ts must stay in sync.
 * The backend re-checks every call: these guards only spare the user a
 * screen that would answer 403.
 */
export const routes: Routes = [
  // Redirects to the landing page of the current role (or /login).
  { path: '', pathMatch: 'full', canActivate: [homeGuard], children: [] },
  {
    path: 'login',
    loadComponent: () =>
      import('./features/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./features/register/register.component').then((m) => m.RegisterComponent),
  },
  {
    // Authenticated area: shared layout (role-filtered nav, logout, theme).
    path: '',
    component: AppShellComponent,
    canActivate: [authGuard],
    children: [
      // --- Traveler (also reachable by TRAVEL_MANAGER and ADMIN) ---
      {
        path: 'travels',
        canActivate: [roleGuard(ROLES.TRAVELER)],
        loadComponent: () =>
          import('./features/travels/travel-list.component').then((m) => m.TravelListComponent),
      },
      {
        path: 'travels/:id',
        canActivate: [roleGuard(ROLES.TRAVELER)],
        loadComponent: () =>
          import('./features/travels/travel-detail.component').then((m) => m.TravelDetailComponent),
      },
      {
        path: 'my-subscriptions',
        canActivate: [roleGuard(ROLES.TRAVELER)],
        loadComponent: () =>
          import('./features/subscriptions/my-subscriptions.component').then(
            (m) => m.MySubscriptionsComponent,
          ),
      },
      {
        path: 'my-stats',
        canActivate: [roleGuard(ROLES.TRAVELER)],
        loadComponent: () =>
          import('./features/stats/traveler-stats.component').then((m) => m.TravelerStatsComponent),
      },
      {
        path: 'managers/:id',
        canActivate: [roleGuard(ROLES.TRAVELER)],
        loadComponent: () =>
          import('./features/managers/manager-page.component').then((m) => m.ManagerPageComponent),
      },
      {
        // Where PayPal sends the payer back (?token=<orderId>): captures the order.
        path: 'paypal/return',
        canActivate: [roleGuard(ROLES.TRAVELER)],
        loadComponent: () =>
          import('./features/subscriptions/paypal-return.component').then(
            (m) => m.PaypalReturnComponent,
          ),
      },
      // --- Travel Manager (also reachable by ADMIN) ---
      {
        // An admin may add ?managerId= to look at another manager's dashboard.
        path: 'manager/dashboard',
        canActivate: [roleGuard(ROLES.TRAVEL_MANAGER)],
        loadComponent: () =>
          import('./features/manager/manager-dashboard.component').then(
            (m) => m.ManagerDashboardComponent,
          ),
      },
      {
        path: 'manager/travels/:id/feedback',
        canActivate: [roleGuard(ROLES.TRAVEL_MANAGER)],
        loadComponent: () =>
          import('./features/manager/travel-feedback.component').then(
            (m) => m.TravelFeedbackComponent,
          ),
      },
      {
        path: 'manager/travels',
        canActivate: [roleGuard(ROLES.TRAVEL_MANAGER)],
        loadComponent: () =>
          import('./features/manager/my-travels.component').then((m) => m.MyTravelsComponent),
      },
      {
        path: 'manager/travels/:id/subscribers',
        canActivate: [roleGuard(ROLES.TRAVEL_MANAGER)],
        loadComponent: () =>
          import('./features/manager/travel-subscribers.component').then(
            (m) => m.TravelSubscribersComponent,
          ),
      },
      // --- Admin ---
      {
        path: 'admin/dashboard',
        canActivate: [roleGuard(ROLES.ADMIN)],
        loadComponent: () =>
          import('./features/admin/admin-dashboard.component').then(
            (m) => m.AdminDashboardComponent,
          ),
      },
      {
        path: 'admin/feedback',
        canActivate: [roleGuard(ROLES.ADMIN)],
        loadComponent: () =>
          import('./features/feedback/admin-feedback.component').then(
            (m) => m.AdminFeedbackComponent,
          ),
      },
      {
        path: 'admin/reports',
        canActivate: [roleGuard(ROLES.ADMIN)],
        loadComponent: () =>
          import('./features/reports/report-queue.component').then((m) => m.ReportQueueComponent),
      },
      {
        path: 'users',
        canActivate: [roleGuard(ROLES.ADMIN)],
        loadComponent: () =>
          import('./features/users/user-list.component').then((m) => m.UserListComponent),
      },
      {
        path: 'payments',
        canActivate: [roleGuard(ROLES.ADMIN)],
        loadComponent: () =>
          import('./features/payments/payment-list.component').then(
            (m) => m.PaymentListComponent,
          ),
      },
      {
        path: 'destinations',
        canActivate: [roleGuard(ROLES.ADMIN)],
        loadComponent: () =>
          import('./features/destinations/destination-list.component').then(
            (m) => m.DestinationListComponent,
          ),
      },
    ],
  },
  // Unknown URL: let the home guard pick the right landing page.
  { path: '**', redirectTo: '' },
];
