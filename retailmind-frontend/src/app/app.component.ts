import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet, RouterLink, RouterLinkActive, Router, NavigationEnd } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { filter } from 'rxjs';
import { AuthService } from './core/services/auth.service';
import { NavPermissionsService } from './core/navigation/nav-permissions.service';
import { ServerStatusComponent } from './core/components/server-status/server-status.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatDividerModule,
    MatMenuModule,
    MatTooltipModule,
    MatChipsModule,
    MatExpansionModule,
    ServerStatusComponent
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  title = 'RetailMind Shop';
  // El dashboard de inicio es la entrada principal: el sidebar queda como
  // navegación secundaria, colapsado por defecto.
  sidebarOpen = false;
  breadcrumb = 'Inicio';
  configExpanded = true;

  private routeMap: Record<string, string> = {
    '/inicio': 'Inicio',
    '/shop': 'Tienda',
    '/shop/carrito': 'Mi Carrito',
    '/wishlist': 'Mi Wishlist',
    '/recomendaciones': 'Recomendaciones',
    '/perfil': 'Mi Perfil',
    '/dashboard': 'Dashboard',
    '/sesiones': 'Sesiones',
    '/conversiones': 'Conversiones',
    '/admin-etl': 'Administracion ETL',
    '/gestion-datos': 'Gestion de Datos',
    '/inicializacion': 'Inicializacion del Sistema',
    '/operativo/productos': 'Productos y Variantes',
    '/operativo/catalogo/marcas': 'Marcas',
    '/operativo/catalogo/categorias': 'Categorías',
    '/operativo/compras/ordenes': 'Órdenes de Compra',
    '/operativo/compras/recepciones': 'Recepción de Mercancía',
    '/operativo/compras/facturas': 'Facturas de Compra',
    '/operativo/compras/proveedores': 'Proveedores',
    '/operativo/compras/devoluciones-proveedor': 'Devolución a Proveedor',
    '/operativo/inventario/transferencias': 'Transferencias de Stock',
    '/operativo/inventario/ajustes': 'Ajustes de Inventario',
    '/operativo/inventario/kardex': 'Kardex de Inventario',
    '/operativo/ventas/pedidos': 'Pedidos de Venta',
    '/operativo/ventas/mis-pedidos': 'Mis Pedidos',
    '/operativo/ventas/facturas': 'Facturas de Venta',
    '/operativo/ventas/preparacion': 'Preparación de Pedidos',
    '/operativo/ventas/despachos': 'Despachos',
    '/operativo/ventas/devoluciones': 'Devoluciones',
    '/operativo/gerencia/metas': 'Metas de Venta',
    '/operativo/marketing/cupones': 'Cupones de Descuento',
    '/operativo/marketing/promociones': 'Promociones',
    '/operativo/marketing/campanas': 'Campañas de Marketing',
    '/operativo/marketing/banners': 'Banners',
    '/operativo/marketing/newsletter': 'Newsletter',
    '/operativo/soporte/tickets': 'Tickets de Soporte',
    '/operativo/soporte/categorias': 'Categorías de Ticket',
    '/operativo/soporte/faq': 'Preguntas Frecuentes',
    '/operativo/resenas': 'Reseñas de Productos',
    '/operativo/resenas/preguntas': 'Preguntas de Productos',
    '/operativo/horarios': 'Horarios de Acceso'
  };

  constructor(
    public authService: AuthService,
    private nav: NavPermissionsService,
    private router: Router
  ) {
    this.router.events.pipe(
      filter(e => e instanceof NavigationEnd)
    ).subscribe((e: any) => {
      const url = e.urlAfterRedirects;
      this.breadcrumb = this.routeMap[url]
        || (url.startsWith('/inicio/') ? (this.nav.area(url.split('/')[2])?.titulo || 'Inicio') : null)
        || (url.includes('/shop/producto') ? 'Detalle Producto' : 'Inicio');
    });
  }

  toggleSidebar(): void {
    this.sidebarOpen = !this.sidebarOpen;
  }

  get isLoginPage(): boolean {
    return this.router.url === '/login';
  }

  get currentUser() {
    return this.authService.getCurrentUser();
  }

  get isAdmin(): boolean {
    return this.authService.hasRole('ADMIN');
  }

  get isCliente(): boolean {
    return this.authService.hasRole('CLIENTE');
  }

  // Visibilidad de las secciones operativas del sidebar. Delegan en
  // NavPermissionsService (matriz ROLES_POR_PERMISO de core/navigation):
  // el mismo punto único de verdad que usa el dashboard de inicio.
  get canCatalogo(): boolean   { return this.nav.can('catalogo'); }
  get canCompras(): boolean    { return this.nav.can('compras'); }
  get canFacturasCompra(): boolean { return this.nav.can('facturasCompra'); }
  get canProveedores(): boolean { return this.nav.can('proveedores'); }
  get canInventario(): boolean { return this.nav.can('inventario'); }
  get canAjustes(): boolean    { return this.nav.can('ajustes'); }
  get canKardex(): boolean     { return this.nav.can('kardex'); }
  get canVentas(): boolean     { return this.nav.can('ventas'); }
  get canPreparar(): boolean   { return this.nav.can('preparar'); }
  get canLogistica(): boolean  { return this.nav.can('logistica'); }
  get canDespachar(): boolean  { return this.nav.can('despachar'); }
  get canDevoluciones(): boolean { return this.nav.can('devoluciones'); }
  get canMarketing(): boolean  { return this.nav.can('marketing'); }
  get canMetas(): boolean      { return this.nav.can('metas'); }
  get canTickets(): boolean    { return this.nav.can('tickets'); }
  get canCategoriasTicket(): boolean { return this.nav.can('categoriasTicket'); }
  get canFaq(): boolean        { return this.nav.can('faq'); }
  get canResenas(): boolean    { return this.nav.can('resenas'); }

  goToProfile(): void {
    this.router.navigate(['/perfil']);
  }

  logout(): void {
    this.authService.logout();
  }

  get userInitial(): string {
    const name = this.currentUser?.nombre || this.currentUser?.username || '?';
    return name.charAt(0).toUpperCase();
  }
}
