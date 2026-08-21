import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { ShopService } from './shop.service';
import { ShopUiService } from './shop-ui.service';
import { SesionRequeridaService } from '../../core/services/sesion-requerida.service';
import { paletaCategoria, PaletaCategoria } from './catalogo-visual';
import { AuthService } from '../../core/services/auth.service';
import { mensajeError } from '../../core/services/api-error.util';

/**
 * Ficha de producto de la tienda (PostgreSQL). El id de la ruta es el id de la
 * VARIANTE, que es la clave que usan carrito, wishlist y pedido_detalle.
 *
 * La pantalla se organiza como una ficha de comercio: lienzo a la izquierda,
 * descripción en el centro y CAJA DE COMPRA a la derecha. La caja repite el
 * precio y el stock a propósito —ya están en el centro— porque es donde se
 * decide, y bajar la vista para comprobar el precio antes de pulsar «agregar»
 * es exactamente el momento en que se abandona una compra.
 */
@Component({
  selector: 'app-producto-detalle',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink, MatButtonModule, MatIconModule,
    MatSnackBarModule, MatTooltipModule
  ],
  templateUrl: './producto-detalle.component.html',
  styleUrls: ['./shop-shared.scss', './producto-detalle.component.scss']
})
export class ProductoDetalleComponent implements OnInit, OnDestroy {

  producto: any = null;
  cantidad = 1;
  loading = true;
  noEncontrado = false;
  agregando = false;

  similares: any[] = [];
  loadingSimilares = false;
  similaresMsLabel = '';

  private routeSub?: Subscription;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private shopService: ShopService,
    public ui: ShopUiService,
    private authService: AuthService,
    private snackBar: MatSnackBar,
    public sesion: SesionRequeridaService
  ) {}

  ngOnInit(): void {
    // Se escucha el paramMap y no una lectura única: navegar de un «similar» a
    // otro cambia el parámetro sin recrear el componente.
    this.routeSub = this.route.paramMap.subscribe(params => {
      const id = params.get('id');
      if (id) {
        this.resetState();
        this.cargarProducto(id);
      }
    });
    this.ui.refrescarTodo();
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
  }

  private resetState(): void {
    this.producto = null;
    this.loading = true;
    this.noEncontrado = false;
    this.similares = [];
    this.loadingSimilares = false;
    this.similaresMsLabel = '';
    this.cantidad = 1;
    window.scrollTo({ top: 0 });
  }

  private cargarProducto(id: string): void {
    this.shopService.getProductoById(id).subscribe({
      next: (p) => {
        this.producto = p;
        this.loading = false;
        // Las dos son de CLIENTE y la ficha ya es publica: sin sesion no se
        // piden. La senal de evento alimenta las recomendaciones —un evento sin
        // cliente no recomienda nada a nadie— y los similares se calculan a
        // partir de lo que esa persona ha visto. Pedirlos igual solo dejaria dos
        // 403 por cada ficha que un visitante abra.
        if (this.sesion.haySesionDeCliente) {
          this.registrarView(id);
          this.cargarSimilares(id);
        }
      },
      error: () => { this.loading = false; this.noEncontrado = true; }
    });
  }

  private cargarSimilares(productoId: string): void {
    this.loadingSimilares = true;
    const t0 = Date.now();
    this.shopService.getSimilares(productoId).subscribe({
      next: (items) => {
        this.similares = items || [];
        this.similaresMsLabel = `${Date.now() - t0} ms`;
        this.loadingSimilares = false;
      },
      error: () => { this.loadingSimilares = false; }
    });
  }

  private registrarView(productId: string): void {
    const user = this.authService.getCurrentUser();
    this.shopService.registrarEvento({
      user_id: user?.username || 'anonymous',
      product_id: productId,
      user_action: 'view',
      channel: 'web',
      price: this.producto?.price
    }).subscribe({ next: () => {}, error: () => {} });
  }

  // ── Presentación ─────────────────────────────────────────────────────────
  get paleta(): PaletaCategoria {
    return paletaCategoria(this.producto?.categoriaNombre, this.producto?.categoriaId);
  }

  paletaDe(p: any): PaletaCategoria {
    return paletaCategoria(p?.categoriaNombre, p?.categoriaId);
  }

  get enWishlist(): boolean {
    return this.producto ? this.ui.estaEnWishlist(this.producto.productoId) : false;
  }

  get subtotalLinea(): number {
    return Number(this.producto?.price || 0) * this.cantidad;
  }

  /** Tope del selector de cantidad: nunca por encima del stock ni de 10. */
  get maxCantidad(): number {
    return Math.max(1, Math.min(Number(this.producto?.stock || 0), 10));
  }

  get opcionesCantidad(): number[] {
    return Array.from({ length: this.maxCantidad }, (_, i) => i + 1);
  }

  // ── Acciones ─────────────────────────────────────────────────────────────
  agregarAlCarrito(irAlPago = false): void {
    if (!this.producto || this.agregando) return;
    this.sesion.exigir(irAlPago ? 'para comprar' : 'para agregar productos al carrito')
      .subscribe(ok => { if (ok) { this.agregarAlCarritoReal(irAlPago); } });
  }

  private agregarAlCarritoReal(irAlPago: boolean): void {
    this.agregando = true;
    this.shopService.agregarAlCarrito(this.producto.productoId, this.cantidad).subscribe({
      next: () => {
        this.agregando = false;
        this.ui.refrescarCarrito();
        if (irAlPago) { this.router.navigate(['/shop/checkout']); return; }
        this.snackBar.open(`${this.cantidad} × «${this.producto.nombre}» en el carrito`,
          'Ver carrito', { duration: 3500, panelClass: ['snack-success'] })
          .onAction().subscribe(() => this.router.navigate(['/shop/carrito']));
      },
      error: (e) => {
        this.agregando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo agregar al carrito'), 'Cerrar',
          { duration: 3500, panelClass: ['snack-error'] });
      }
    });
  }

  toggleWishlist(): void {
    if (!this.producto) return;
    this.sesion.exigir('para guardar productos en tu lista de deseos')
      .subscribe(ok => { if (ok) { this.toggleWishlistReal(); } });
  }

  private toggleWishlistReal(): void {
    const id = Number(this.producto.productoId);

    if (this.enWishlist) {
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
          this.snackBar.open('Guardado en tu lista de deseos', 'OK', { duration: 2000 });
        },
        error: (e) => this.snackBar.open(mensajeError(e, 'Ya está en tu lista'), 'OK', { duration: 2500 })
      });
    }
  }

  agregarSimilar(p: any, event: Event): void {
    event.stopPropagation();
    this.sesion.exigir('para agregar productos al carrito').subscribe(ok => {
      if (ok) { this.agregarSimilarReal(p); }
    });
  }

  private agregarSimilarReal(p: any): void {
    this.shopService.agregarAlCarrito(p.productoId, 1).subscribe({
      next: () => {
        this.ui.refrescarCarrito();
        this.snackBar.open('Agregado al carrito', 'OK', { duration: 2000, panelClass: ['snack-success'] });
      },
      error: (e) => this.snackBar.open(mensajeError(e, 'No se pudo agregar'), 'Cerrar', { duration: 2500 })
    });
  }

  verSimilar(productoId: number): void {
    this.router.navigate(['/shop/producto', productoId]);
  }

  /** Vuelve al catálogo dentro del departamento del producto. */
  verCategoria(): void {
    this.router.navigate(['/shop'], { queryParams: { cat: this.producto?.categoriaId || null } });
  }

  volver(): void {
    this.router.navigate(['/shop']);
  }
}
