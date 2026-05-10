import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'dashboard',
    pathMatch: 'full'
  },
  {
    path: 'dashboard',
    loadComponent: () =>
      import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
  },
  {
    path: 'sesiones',
    loadComponent: () =>
      import('./features/sesiones/sesiones-list.component').then(m => m.SesionesListComponent)
  },
  {
    path: 'conversiones',
    loadComponent: () =>
      import('./features/conversiones/conversiones-list.component').then(m => m.ConversionesListComponent)
  },
  {
    path: 'admin-etl',
    loadComponent: () =>
      import('./features/admin-etl/admin-etl.component').then(m => m.AdminEtlComponent)
  },
  {
    path: '**',
    redirectTo: 'dashboard'
  }
];
