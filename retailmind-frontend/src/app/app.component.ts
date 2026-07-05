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
  sidenavOpened = true;
  breadcrumb = 'Tienda';
  configExpanded = true;

  private routeMap: Record<string, string> = {
    '/shop': 'Tienda',
    '/shop/carrito': 'Mi Carrito',
    '/dashboard': 'Dashboard',
    '/sesiones': 'Sesiones',
    '/conversiones': 'Conversiones',
    '/admin-etl': 'Administracion ETL',
    '/gestion-datos': 'Gestion de Datos',
    '/inicializacion': 'Inicializacion del Sistema',
    '/operativo/productos': 'Productos y Variantes',
    '/operativo/compras/ordenes': 'Órdenes de Compra',
    '/operativo/compras/recepciones': 'Recepción de Mercancía',
    '/operativo/compras/facturas': 'Facturas de Compra',
    '/operativo/inventario/transferencias': 'Transferencias de Stock',
    '/operativo/ventas/pedidos': 'Pedidos de Venta',
    '/operativo/ventas/facturas': 'Facturas de Venta',
    '/operativo/ventas/despachos': 'Despachos',
    '/operativo/ventas/devoluciones': 'Devoluciones',
    '/operativo/horarios': 'Horarios de Acceso'
  };

  constructor(public authService: AuthService, private router: Router) {
    this.router.events.pipe(
      filter(e => e instanceof NavigationEnd)
    ).subscribe((e: any) => {
      const url = e.urlAfterRedirects;
      this.breadcrumb = this.routeMap[url] || (url.includes('/shop/producto') ? 'Detalle Producto' : 'Tienda');
    });
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

  /** true si el usuario actual tiene alguno de los roles indicados. */
  hasAnyRole(roles: string[]): boolean {
    const user = this.authService.getCurrentUser();
    return !!user && roles.includes(user.rol);
  }

  // Visibilidad de las secciones operativas del sidebar (espeja SecurityConfig)
  get canCatalogo(): boolean   { return this.hasAnyRole(['ADMIN']); }
  get canCompras(): boolean    { return this.hasAnyRole(['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA']); }
  get canInventario(): boolean { return this.hasAnyRole(['ADMIN', 'GERENTE', 'BODEGA']); }
  get canVentas(): boolean     { return this.hasAnyRole(['ADMIN', 'GERENTE', 'VENDEDOR']); }
  get canLogistica(): boolean  { return this.hasAnyRole(['ADMIN', 'GERENTE', 'VENDEDOR', 'DESPACHO']); }
  get canDespachar(): boolean  { return this.hasAnyRole(['ADMIN', 'GERENTE', 'DESPACHO']); }

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
