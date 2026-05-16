import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/role.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/login/login.component').then(m => m.LoginComponent)
  },
  {
    path: '',
    redirectTo: 'dashboard',
    pathMatch: 'full'
  },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
  },
  {
    path: 'sesiones',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/sesiones/sesiones-list.component').then(m => m.SesionesListComponent)
  },
  {
    path: 'conversiones',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/conversiones/conversiones-list.component').then(m => m.ConversionesListComponent)
  },
  {
    path: 'admin-etl',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin-etl/admin-etl.component').then(m => m.AdminEtlComponent)
  },
  {
    path: 'gestion-datos',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/gestion-datos/gestion-datos.component').then(m => m.GestionDatosComponent)
  },
  {
    path: 'inicializacion',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/inicializacion/inicializacion.component').then(m => m.InicializacionComponent)
  },
  {
    path: '**',
    redirectTo: 'dashboard'
  }
];
