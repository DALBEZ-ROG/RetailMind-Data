import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { Subject, Subscription } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ShopService } from './shop.service';
import { ShopUiService } from './shop-ui.service';
import { paletaCategoria, PaletaCategoria } from './catalogo-visual';
import { mensajeError } from '../../core/services/api-error.util';

/** Un tramo de precio de los accesos rápidos del panel de filtros. */
interface TramoPrecio {
  etiqueta: string;
  min: number | null;
  max: number | null;
}

/**
 * Catálogo de la tienda del cliente: productos REALES de PostgreSQL
 * (producto/producto_variante con stock de inventario). Solo rol CLIENTE.
 *
 * TODO EL ESTADO DE FILTRADO VIVE EN LA URL (`q`, `cat`, `marca`, `min`,
 * `max`, `page`, `size`). No es cosmético: es lo que permite que el campo de
 * búsqueda de la barra superior —que está en `app.component`, fuera de este
 * componente— dispare una búsqueda navegando, que el botón «atrás» del
 * navegador deshaga un filtro y que un resultado se pueda compartir por enlace.
 * El componente NUNCA guarda el filtro en un campo y luego consulta: escribe en
 * la URL y reacciona a lo que la URL diga.
 *
 * LO QUE FILTRA EL SERVIDOR Y LO QUE NO. Búsqueda, categoría, marca y rango de
 * precio los resuelve `/api/catalogo/productos` sobre el catálogo entero. El
 * ORDEN y el «solo disponibles» NO: el endpoint no los ofrece, así que se
 * aplican sobre la página ya recibida y la pantalla lo DICE al lado del
 * control. Es la diferencia entre refinar 6.217 productos y reordenar 24.
 */
@Component({
  selector: 'app-shop',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink, MatButtonModule, MatIconModule,
    MatPaginatorModule, MatSnackBarModule, MatTooltipModule
  ],
  templateUrl: './shop.component.html',
  styleUrls: ['./shop-shared.scss', './shop.component.scss']
})
export class ShopComponent implements OnInit, OnDestroy {

  // ── Datos ────────────────────────────────────────────────────────────────
  productos: any[] = [];
  /** Lo que se pinta: `productos` tras el orden y el «solo disponibles». */
  vista: any[] = [];
  categorias: any[] = [];
  marcas: string[] = [];
  destacados: any[] = [];
  totalProductos = 0;
  loading = false;
  primeraCarga = true;

  // ── Filtros del servidor (espejo de la URL) ──────────────────────────────
  busqueda = '';
  categoriaSeleccionada: number | null = null;
  marcaSeleccionada: string | null = null;
  precioMin: number | null = null;
  precioMax: number | null = null;
  page = 0;
  size = 24;

  // ── Refinamientos de la página recibida (no del catálogo) ────────────────
  orden: 'relevancia' | 'precio_asc' | 'precio_desc' | 'nombre' | 'stock' = 'relevancia';
  soloDisponibles = false;

  // ── Preferencias de presentación ─────────────────────────────────────────
  vistaLista = false;
  filtrosAbiertos = false;      // en móvil el panel se despliega
  marcaFiltro = '';             // buscador dentro de la lista de marcas
  panel = { categorias: true, marcas: true, precio: true, disponibilidad: true };

  /** Campos del rango de precio antes de aplicarse (no tocan la URL al teclear). */
  precioMinBorrador: number | null = null;
  precioMaxBorrador: number | null = null;

  readonly tramos: TramoPrecio[] = [
    { etiqueta: 'Hasta $10',      min: null, max: 10 },
    { etiqueta: '$10 a $30',      min: 10,   max: 30 },
    { etiqueta: '$30 a $80',      min: 30,   max: 80 },
    { etiqueta: '$80 a $200',     min: 80,   max: 200 },
    { etiqueta: '$200 a $500',    min: 200,  max: 500 },
    { etiqueta: 'Más de $500',    min: 500,  max: null }
  ];

  @ViewChild('campoBusqueda') campoBusqueda?: ElementRef<HTMLInputElement>;

  private readonly busqueda$ = new Subject<string>();
  private readonly subs = new Subscription();

  constructor(
    private shopService: ShopService,
    public ui: ShopUiService,
    private router: Router,
    private route: ActivatedRoute,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    // La URL manda: cualquier cambio de parámetro relee el catálogo.
    this.subs.add(this.route.queryParamMap.subscribe(p => {
      this.busqueda = p.get('q') || '';
      this.categoriaSeleccionada = p.get('cat') ? Number(p.get('cat')) : null;
      this.marcaSeleccionada = p.get('marca') || null;
      this.precioMin = p.get('min') != null ? Number(p.get('min')) : null;
      this.precioMax = p.get('max') != null ? Number(p.get('max')) : null;
      this.page = p.get('page') ? Number(p.get('page')) : 0;
      this.size = p.get('size') ? Number(p.get('size')) : 24;
      this.precioMinBorrador = this.precioMin;
      this.precioMaxBorrador = this.precioMax;
      this.loadProductos();

      // El atajo de búsqueda de la barra (pantallas estrechas) llega con
      // `buscar=1`: se pone el cursor en el campo del catálogo y se limpia el
      // parámetro con `replaceUrl` para que no quede en el historial ni
      // vuelva a disparar al pulsar «atrás».
      if (p.get('buscar')) {
        setTimeout(() => this.campoBusqueda?.nativeElement.focus());
        this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { buscar: null },
          queryParamsHandling: 'merge',
          replaceUrl: true
        });
      }
    }));

    // El campo de búsqueda de la propia pantalla (visible solo en pantallas
    // estrechas, donde la barra superior no lo muestra) navega tras la pausa.
    this.subs.add(this.busqueda$.pipe(debounceTime(350), distinctUntilChanged())
      .subscribe(q => this.aplicar({ q: q || null, page: null })));

    this.loadCategorias();
    this.loadMarcas();
    this.loadDestacados();
    this.ui.refrescarTodo();
  }

  ngOnDestroy(): void {
    this.busqueda$.complete();
    this.subs.unsubscribe();
  }

  // ── Carga ────────────────────────────────────────────────────────────────
  loadProductos(): void {
    this.loading = true;
    const filters: any = {};
    if (this.categoriaSeleccionada) filters.categoria_id = this.categoriaSeleccionada;
    if (this.marcaSeleccionada) filters.brand = this.marcaSeleccionada;
    if (this.busqueda.trim()) filters.q = this.busqueda.trim();
    if (this.precioMin != null) filters.min_price = this.precioMin;
    if (this.precioMax != null) filters.max_price = this.precioMax;

    this.shopService.getProductos(this.page, this.size, filters).subscribe({
      next: (res) => {
        this.productos = res.content || [];
        this.totalProductos = res.totalElements || 0;
        this.recalcularVista();
        this.loading = false;
        this.primeraCarga = false;
      },
      error: (e) => {
        this.loading = false;
        this.primeraCarga = false;
        this.productos = [];
        this.vista = [];
        this.snackBar.open(mensajeError(e, 'No se pudo cargar el catálogo'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  loadCategorias(): void {
    this.shopService.getCategorias().subscribe({
      next: (cats) => this.categorias = cats || [],
      error: () => {}
    });
  }

  loadMarcas(): void {
    this.shopService.getMarcas().subscribe({
      next: (m) => this.marcas = m || [],
      error: () => {}
    });
  }

  /**
   * Carrusel de la portada. Sale de `/api/recomendaciones`, que degrada sola a
   * «populares» cuando ClickHouse no responde, así que el bloque nunca queda
   * en error: o trae recomendación personalizada o trae destacados.
   */
  loadDestacados(): void {
    this.shopService.getRecomendaciones().subscribe({
      next: (r) => this.destacados = (r?.recomendaciones || []).slice(0, 8),
      error: () => this.destacados = []
    });
  }

  /** Orden y «solo disponibles» se aplican aquí, sobre la página recibida. */
  recalcularVista(): void {
    let lista = this.soloDisponibles
      ? this.productos.filter(p => Number(p.stock) > 0)
      : this.productos.slice();

    switch (this.orden) {
      case 'precio_asc':  lista.sort((a, b) => Number(a.price) - Number(b.price)); break;
      case 'precio_desc': lista.sort((a, b) => Number(b.price) - Number(a.price)); break;
      case 'nombre':      lista.sort((a, b) => String(a.nombre).localeCompare(String(b.nombre), 'es')); break;
      case 'stock':       lista.sort((a, b) => Number(b.stock) - Number(a.stock)); break;
    }
    this.vista = lista;
  }

  // ── Escritura del filtro en la URL ───────────────────────────────────────
  /** Fusiona parámetros; `null` borra el parámetro de la URL. */
  private aplicar(cambios: Record<string, any>): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: cambios,
      queryParamsHandling: 'merge'
    });
  }

  onBusquedaCambia(): void {
    this.busqueda$.next(this.busqueda.trim());
  }

  filtrarCategoria(catId: number | null): void {
    const nueva = this.categoriaSeleccionada === catId ? null : catId;
    this.aplicar({ cat: nueva, page: null });
  }

  filtrarMarca(m: string | null): void {
    const nueva = this.marcaSeleccionada === m ? null : m;
    this.aplicar({ marca: nueva, page: null });
  }

  aplicarTramo(t: TramoPrecio): void {
    const yaActivo = this.precioMin === t.min && this.precioMax === t.max;
    this.aplicar({
      min: yaActivo ? null : t.min,
      max: yaActivo ? null : t.max,
      page: null
    });
  }

  tramoActivo(t: TramoPrecio): boolean {
    return this.precioMin === t.min && this.precioMax === t.max;
  }

  aplicarRangoManual(): void {
    const min = this.precioMinBorrador;
    const max = this.precioMaxBorrador;
    if (min != null && max != null && min > max) {
      this.snackBar.open('El precio mínimo no puede ser mayor que el máximo', 'OK', { duration: 3000 });
      return;
    }
    this.aplicar({ min: min ?? null, max: max ?? null, page: null });
  }

  quitarBusqueda(): void { this.aplicar({ q: null, page: null }); }
  quitarCategoria(): void { this.aplicar({ cat: null, page: null }); }
  quitarMarca(): void { this.aplicar({ marca: null, page: null }); }
  quitarPrecio(): void { this.aplicar({ min: null, max: null, page: null }); }

  limpiarTodo(): void {
    this.soloDisponibles = false;
    this.orden = 'relevancia';
    this.router.navigate([], { relativeTo: this.route, queryParams: {} });
  }

  onPageChange(e: PageEvent): void {
    this.aplicar({ page: e.pageIndex || null, size: e.pageSize });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  // ── Estado derivado para la plantilla ────────────────────────────────────
  get hayFiltros(): boolean {
    return !!(this.busqueda || this.categoriaSeleccionada || this.marcaSeleccionada
           || this.precioMin != null || this.precioMax != null);
  }

  get nombreCategoria(): string {
    const c = this.categorias.find(x => Number(x.categoriaId) === Number(this.categoriaSeleccionada));
    return c ? c.nombre : '';
  }

  get etiquetaPrecio(): string {
    if (this.precioMin != null && this.precioMax != null) return `$${this.precioMin} – $${this.precioMax}`;
    if (this.precioMin != null) return `Desde $${this.precioMin}`;
    if (this.precioMax != null) return `Hasta $${this.precioMax}`;
    return '';
  }

  /** «25-48 de 6.217» — el rango real de la página, no el de la vista. */
  get rangoDesde(): number {
    return this.totalProductos === 0 ? 0 : this.page * this.size + 1;
  }

  get rangoHasta(): number {
    return Math.min((this.page + 1) * this.size, this.totalProductos);
  }

  /** Cuántos productos de la página oculta el refinamiento local. */
  get ocultosPorRefinamiento(): number {
    return this.productos.length - this.vista.length;
  }

  get marcasFiltradas(): string[] {
    const t = this.marcaFiltro.trim().toLowerCase();
    return t ? this.marcas.filter(m => m.toLowerCase().includes(t)) : this.marcas;
  }

  get esqueletos(): number[] {
    return Array.from({ length: this.size > 24 ? 12 : this.size }, (_, i) => i);
  }

  // ── Presentación de una tarjeta ──────────────────────────────────────────
  paleta(p: any): PaletaCategoria {
    return paletaCategoria(p?.categoriaNombre, p?.categoriaId);
  }

  paletaCat(cat: any): PaletaCategoria {
    return paletaCategoria(cat?.nombre, cat?.categoriaId);
  }

  /** Parte entera del precio, para pintarla grande y los centavos pequeños. */
  entero(precio: any): string {
    return Math.floor(Math.abs(Number(precio) || 0)).toLocaleString('en-US');
  }

  decimal(precio: any): string {
    const n = Math.abs(Number(precio) || 0);
    return (Math.round((n - Math.floor(n)) * 100)).toString().padStart(2, '0');
  }

  trackByProducto(_i: number, p: any): number {
    return p.productoId;
  }

  // ── Acciones ─────────────────────────────────────────────────────────────
  verProducto(productoId: number): void {
    this.router.navigate(['/shop/producto', productoId]);
  }

  agregarAlCarrito(producto: any, event: Event): void {
    event.stopPropagation();
    this.shopService.agregarAlCarrito(producto.productoId, 1).subscribe({
      next: () => {
        this.ui.refrescarCarrito();
        this.snackBar.open(`«${producto.nombre}» agregado al carrito`, 'Ver carrito',
          { duration: 3000, panelClass: ['snack-success'] })
          .onAction().subscribe(() => this.router.navigate(['/shop/carrito']));
      },
      error: (e) => this.snackBar.open(mensajeError(e, 'No se pudo agregar al carrito'), 'Cerrar',
        { duration: 3500, panelClass: ['snack-error'] })
    });
  }

  toggleWishlist(producto: any, event: Event): void {
    event.stopPropagation();
    const id = Number(producto.productoId);

    if (this.ui.estaEnWishlist(id)) {
      this.shopService.eliminarDeWishlist(id).subscribe({
        next: () => {
          this.ui.marcarWishlist(id, false);
          this.snackBar.open('Quitado de tu lista de deseos', 'OK', { duration: 2000 });
        },
        error: (e) => this.snackBar.open(mensajeError(e, 'No se pudo quitar'), 'Cerrar', { duration: 2500 })
      });
    } else {
      this.shopService.agregarAWishlist(id).subscribe({
        next: () => {
          this.ui.marcarWishlist(id, true);
          this.snackBar.open('Guardado en tu lista de deseos', 'Ver lista', { duration: 2500 })
            .onAction().subscribe(() => this.router.navigate(['/wishlist']));
        },
        error: (e) => this.snackBar.open(mensajeError(e, 'Ya está en tu lista'), 'OK', { duration: 2500 })
      });
    }
  }
}
