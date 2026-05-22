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
    redirectTo: 'shop',
    pathMatch: 'full'
  },
  {
    path: 'shop',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/shop/shop.component').then(m => m.ShopComponent)
  },
  {
    path: 'shop/producto/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/shop/producto-detalle.component').then(m => m.ProductoDetalleComponent)
  },
  {
    path: 'shop/carrito',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/shop/carrito.component').then(m => m.CarritoComponent)
  },
  {
    path: 'wishlist',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/wishlist/wishlist.component').then(m => m.WishlistComponent)
  },
  {
    path: 'mis-pedidos',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/pedidos/mis-pedidos.component').then(m => m.MisPedidosComponent)
  },
  {
    path: 'admin-pedidos',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin-pedidos/admin-pedidos.component').then(m => m.AdminPedidosComponent)
  },
  {
    path: 'admin-usuarios',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin-usuarios/admin-usuarios.component').then(m => m.AdminUsuariosComponent)
  },
  {
    path: 'funnel',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/funnel/funnel.component').then(m => m.FunnelComponent)
  },
  {
    path: 'dashboard',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
  },
  {
    path: 'sesiones',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/sesiones/sesiones-list.component').then(m => m.SesionesListComponent)
  },
  {
    path: 'conversiones',
    canActivate: [authGuard, adminGuard],
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
    redirectTo: 'shop'
  }
];
