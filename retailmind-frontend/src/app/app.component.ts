import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
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
import { ShopService } from './features/shop/shop.service';
import { ShopUiService } from './features/shop/shop-ui.service';

import { CampoTextoDirective } from './core/validacion';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
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
    ServerStatusComponent,
    CampoTextoDirective
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
  title = 'RetailMind Shop';

  // ── Barra de la tienda (solo rol CLIENTE) ───────────────────────────────
  // El campo de búsqueda vive AQUÍ y no en el catálogo porque tiene que
  // acompañar al cliente por toda la tienda: desde el carrito, desde la ficha
  // de un producto o desde «Mis Pedidos» se busca sin volver antes al catálogo.
  // No guarda resultados: navega a /shop con el término en la URL, que es
  // donde el catálogo lee su estado.
  busquedaTienda = '';
  catBusqueda: number | null = null;
  categoriasTienda: any[] = [];
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
    '/operativo/inventario/existencias': 'Existencias',
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
    '/operativo/informes/ventas': 'Informes de Ventas',
    '/operativo/informes/inventario': 'Informes de Inventario',
    '/operativo/informes/compras': 'Informes de Compras',
    '/operativo/informes/logistica': 'Informes de Logística',
    '/operativo/informes/soporte': 'Informes de Soporte',
    '/operativo/informes/gerencia': 'Informes de Gerencia',
    '/operativo/panorama': 'Panorama del Negocio',
    '/operativo/tableros/omnicanal': 'Tablero Omnicanal',
    '/operativo/tableros/rentabilidad': 'Tablero de Rentabilidad y Rotación',
    '/operativo/tableros/cliente-posventa': 'Tablero de Cliente y Posventa',
    '/operativo/tableros/operacion': 'Tablero de Operación y Última Milla',
    '/operativo/tableros/costo-operacion': 'Tablero de Costo de la Operación',
    '/operativo/tableros/abastecimiento': 'Tablero de Abastecimiento',
    '/operativo/tableros/gobierno-dato': 'Tablero de Gobierno del Dato',
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
    '/operativo/horarios': 'Horarios de Acceso',
    '/operativo/seguridad/accesos': 'Intentos de Acceso',
    '/operativo/seguridad/permisos': 'Permisos del Motor',
    '/operativo/red': 'Red Logística'
  };

  constructor(
    public authService: AuthService,
    private nav: NavPermissionsService,
    private router: Router,
    private shop: ShopService,
    public shopUi: ShopUiService
  ) {
    this.router.events.pipe(
      filter(e => e instanceof NavigationEnd)
    ).subscribe((e: any) => {
      const url: string = e.urlAfterRedirects;
      const sinParams = url.split('?')[0];
      this.breadcrumb = this.routeMap[sinParams]
        || (sinParams.startsWith('/inicio/') ? (this.nav.area(sinParams.split('/')[2])?.titulo || 'Inicio') : null)
        || (sinParams.includes('/shop/producto') ? 'Detalle Producto' : 'Inicio');

      // El campo de la barra refleja lo que diga la URL: si el cliente quita el
      // filtro desde el catálogo o pulsa «atrás», el término de arriba no puede
      // quedarse mostrando una búsqueda que ya no está aplicada.
      const params = this.router.parseUrl(url).queryParams;
      const enTienda = sinParams.startsWith('/shop');
      this.busquedaTienda = enTienda ? (params['q'] || '') : '';
      // El selector de departamento hace de ÁMBITO de la búsqueda, así que
      // tiene que enseñar el departamento que ya está filtrando; si no, la
      // primera búsqueda dentro de «Electrónica» saldría del departamento.
      this.catBusqueda = enTienda && params['cat'] ? Number(params['cat']) : null;

      this.prepararTienda();
    });
  }

  ngOnInit(): void {
    this.prepararTienda();
  }

  /**
   * Carga perezosa de lo que necesita la barra de tienda.
   *
   * Se parte en dos porque el visitante y el cliente necesitan cosas distintas:
   * los DEPARTAMENTOS son del catálogo y hoy son públicos, así que el visitante
   * también los ve en su buscador; el carrito, la lista de deseos y las
   * direcciones son del cliente y pedirlos sin sesión sería llamar a tres
   * endpoints para recibir tres 403.
   */
  private prepararTienda(): void {
    if (!this.modoTienda) return;

    if (!this.categoriasTienda.length) {
      this.shop.getCategorias().subscribe({
        next: (c) => this.categoriasTienda = c || [],
        error: () => {}
      });
    }
    if (this.isCliente && !this.shopUi.direcciones.length) {
      this.shopUi.refrescarTodo();
      this.shopUi.cargarDirecciones();
    }
  }

  /**
   * Busca en la tienda. Navega SIEMPRE a /shop —también desde el carrito o
   * desde una ficha— con el término y el departamento en la URL, y resetea la
   * página: buscar y quedarse en la página 7 del resultado anterior no tiene
   * sentido para nadie.
   */
  buscarEnTienda(): void {
    const q = this.busquedaTienda.trim();
    this.router.navigate(['/shop'], {
      queryParams: { q: q || null, cat: this.catBusqueda || null, page: null },
      queryParamsHandling: 'merge'
    });
  }

  limpiarBusquedaTienda(): void {
    this.busquedaTienda = '';
    if (this.router.url.startsWith('/shop')) this.buscarEnTienda();
  }

  /**
   * Atajo de búsqueda para pantallas estrechas: lleva al catálogo y le pide
   * que ponga el cursor en su propio campo (`buscar=1`, que el catálogo borra
   * de la URL en cuanto lo atiende). Es la única vía de búsqueda que queda
   * fuera del catálogo cuando la barra ya no muestra la suya.
   */
  irABuscar(): void {
    this.router.navigate(['/shop'], { queryParams: { buscar: 1 }, queryParamsHandling: 'merge' });
  }

  // ── Dirección de envío de la barra ──────────────────────────────────────
  get direccionEnvio(): any | null {
    return this.shopUi.direccionEnvio;
  }

  /**
   * Rótulo corto: alias («Casa») o nombre de quien recibe, más la ciudad, que
   * es lo que decide la zona de envío. Sin dirección guardada NO se inventa un
   * lugar: se invita a agregar una.
   */
  get etiquetaEnvio(): string {
    const d = this.direccionEnvio;
    if (!d) return 'Agregar dirección';
    const quien = d.alias || d.destinatario || 'Mi dirección';
    return d.ciudad ? `${quien} · ${d.ciudad}` : quien;
  }

  elegirDireccionEnvio(d: any): void {
    this.shopUi.elegirDireccion(d?.id ?? null);
  }

  toggleSidebar(): void {
    this.sidebarOpen = !this.sidebarOpen;
  }

  /**
   * Pantallas que se pintan SOLAS, sin barra ni menú lateral: el acceso.
   * El registro entra aquí por el mismo motivo que el login —quien está creando
   * su cuenta todavía no tiene nada que navegar— y se compara contra la ruta sin
   * los parámetros, porque el muro de sesión llega con `?volver=…`.
   */
  get isLoginPage(): boolean {
    const ruta = this.router.url.split('?')[0];
    return ruta === '/login' || ruta === '/registro';
  }

  /**
   * ¿Se está pintando la TIENDA? Es lo que decide la barra de comercio, y no
   * basta con el rol: desde que el escaparate es público, un visitante sin
   * ninguna sesión también la ve. Se distinguen los dos casos porque el
   * visitante no tiene carrito, ni deseos, ni pedidos que enseñar.
   */
  get modoTienda(): boolean {
    return this.isCliente || this.esVisitante;
  }

  /** Nadie ha entrado y estamos en una pantalla de la tienda. */
  get esVisitante(): boolean {
    if (this.currentUser) { return false; }
    const ruta = this.router.url.split('?')[0];
    return ruta === '/shop' || ruta.startsWith('/shop/');
  }

  /** Lleva al muro de sesión, recordando dónde estaba. */
  iniciarSesion(): void {
    this.router.navigate(['/login'], { queryParams: { volver: this.router.url } });
  }

  crearCuenta(): void {
    this.router.navigate(['/registro'], { queryParams: { volver: this.router.url } });
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
  get canRedLogistica(): boolean { return this.nav.can('redLogistica'); }
  get canCatalogo(): boolean   { return this.nav.can('catalogo'); }
  get canCompras(): boolean    { return this.nav.can('compras'); }
  get canFacturasCompra(): boolean { return this.nav.can('facturasCompra'); }
  get canProveedores(): boolean { return this.nav.can('proveedores'); }
  get canExistencias(): boolean { return this.nav.can('existencias'); }
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
  get canInformesVentas(): boolean { return this.nav.can('informesVentas'); }
  get canInformesInventario(): boolean { return this.nav.can('informesInventario'); }
  get canInformesCompras(): boolean { return this.nav.can('informesCompras'); }
  get canInformesLogistica(): boolean { return this.nav.can('informesLogistica'); }
  get canInformesSoporte(): boolean { return this.nav.can('informesSoporte'); }
  get canInformesGerencia(): boolean { return this.nav.can('informesGerencia'); }
  // Tableros de dirección (nivel estratégico, fase E1-A)
  get canPanorama(): boolean { return this.nav.can('panorama'); }
  get canTableroOmnicanal(): boolean { return this.nav.can('tableroOmnicanal'); }
  get canTableroRentabilidad(): boolean { return this.nav.can('tableroRentabilidad'); }
  get canTableroPosventa(): boolean { return this.nav.can('tableroPosventa'); }
  get canTableroOperacion(): boolean { return this.nav.can('tableroOperacion'); }
  get canTableroCosto(): boolean { return this.nav.can('tableroCosto'); }
  get canTableroAbastecimiento(): boolean { return this.nav.can('tableroAbastecimiento'); }
  get canTableroGobierno(): boolean { return this.nav.can('tableroGobierno'); }
  get canAccesos(): boolean    { return this.nav.can('accesos'); }
  /** Permisos del Motor (script 86): SOLO ADMIN, como sus 6 endpoints. */
  get canPermisosMotor(): boolean { return this.nav.can('permisosMotor'); }
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
