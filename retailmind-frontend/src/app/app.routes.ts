import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard, roleGuard } from './core/guards/role.guard';

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
      import('./features/admin/pedidos/admin-pedidos.component').then(m => m.AdminPedidosComponent)
  },
  {
    path: 'admin-usuarios',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/usuarios/admin-usuarios.component').then(m => m.AdminUsuariosComponent)
  },
  {
    path: 'funnel',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/funnel/funnel.component').then(m => m.FunnelComponent)
  },
  {
    path: 'dashboard',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/dashboard/dashboard.component').then(m => m.DashboardComponent)
  },
  {
    path: 'sesiones',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/sesiones/sesiones-list.component').then(m => m.SesionesListComponent)
  },
  {
    path: 'conversiones',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/conversiones/conversiones-list.component').then(m => m.ConversionesListComponent)
  },
  {
    path: 'admin-etl',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/etl/admin-etl.component').then(m => m.AdminEtlComponent)
  },
  {
    path: 'gestion-datos',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/gestion/gestion-datos.component').then(m => m.GestionDatosComponent)
  },
  {
    path: 'inicializacion',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/inicializacion/inicializacion.component').then(m => m.InicializacionComponent)
  },
  {
    path: 'analytics/region',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/region/region.component').then(m => m.RegionComponent)
  },
  {
    path: 'analytics/dispositivo',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/dispositivo/dispositivo.component').then(m => m.DispositivoComponent)
  },
  {
    path: 'analytics/trafico',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/trafico/trafico.component').then(m => m.TraficoComponent)
  },
  {
    path: 'admin/reportes',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/reportes/reportes.component').then(m => m.ReportesComponent)
  },
  {
    path: 'perfil',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/perfil/perfil.component').then(m => m.PerfilComponent)
  },
  {
    path: 'recomendaciones',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/recomendaciones/recomendaciones.component').then(m => m.RecomendacionesComponent)
  },
  // ── Módulo operativo (casos de uso 1-10) ────────────────────────────────
  {
    path: 'operativo/productos',
    canActivate: [authGuard, roleGuard(['ADMIN'])],
    loadComponent: () =>
      import('./features/operativo/catalogo/productos-admin.component').then(m => m.ProductosAdminComponent)
  },
  {
    path: 'operativo/compras/ordenes',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/compras/ordenes-compra.component').then(m => m.OrdenesCompraComponent)
  },
  {
    path: 'operativo/compras/recepciones',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/compras/recepciones.component').then(m => m.RecepcionesComponent)
  },
  {
    path: 'operativo/compras/facturas',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/compras/facturas-compra.component').then(m => m.FacturasCompraComponent)
  },
  {
    path: 'operativo/inventario/transferencias',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/inventario/transferencias.component').then(m => m.TransferenciasComponent)
  },
  {
    path: 'operativo/inventario/ajustes',
    canActivate: [authGuard, roleGuard(['ADMIN', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/inventario/ajustes.component').then(m => m.AjustesComponent)
  },
  {
    path: 'operativo/inventario/kardex',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'BODEGA', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/inventario/kardex.component').then(m => m.KardexComponent)
  },
  {
    path: 'operativo/ventas/pedidos',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'VENDEDOR'])],
    loadComponent: () =>
      import('./features/operativo/ventas/pedidos-venta.component').then(m => m.PedidosVentaComponent)
  },
  {
    path: 'operativo/ventas/mis-pedidos',
    canActivate: [authGuard, roleGuard(['CLIENTE'])],
    loadComponent: () =>
      import('./features/operativo/ventas/mis-pedidos-tienda.component').then(m => m.MisPedidosTiendaComponent)
  },
  {
    path: 'operativo/ventas/facturas',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'VENDEDOR'])],
    loadComponent: () =>
      import('./features/operativo/ventas/facturas-venta.component').then(m => m.FacturasVentaComponent)
  },
  {
    path: 'operativo/ventas/despachos',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'DESPACHO'])],
    loadComponent: () =>
      import('./features/operativo/ventas/despachos.component').then(m => m.DespachosComponent)
  },
  {
    path: 'operativo/ventas/devoluciones',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'VENDEDOR', 'DESPACHO'])],
    loadComponent: () =>
      import('./features/operativo/ventas/devoluciones.component').then(m => m.DevolucionesComponent)
  },
  // ── Módulo marketing (lectura ADMIN/GERENTE; escrituras solo ADMIN, espeja SecurityConfig)
  {
    path: 'operativo/marketing/cupones',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/cupones.component').then(m => m.CuponesComponent)
  },
  {
    path: 'operativo/marketing/promociones',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/promociones.component').then(m => m.PromocionesComponent)
  },
  {
    path: 'operativo/marketing/campanas',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/campanas.component').then(m => m.CampanasComponent)
  },
  {
    path: 'operativo/marketing/banners',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/banners.component').then(m => m.BannersComponent)
  },
  {
    path: 'operativo/marketing/newsletter',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/newsletter.component').then(m => m.NewsletterComponent)
  },
  {
    path: 'operativo/horarios',
    canActivate: [authGuard, roleGuard(['ADMIN'])],
    loadComponent: () =>
      import('./features/operativo/horarios/horarios.component').then(m => m.HorariosComponent)
  },
  {
    path: '**',
    redirectTo: 'shop'
  }
];
