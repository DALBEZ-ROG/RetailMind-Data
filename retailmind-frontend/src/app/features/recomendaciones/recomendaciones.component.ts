import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ShopService } from '../shop/shop.service';
import { ShopUiService } from '../shop/shop-ui.service';
import { PaletaCategoria } from '../shop/catalogo-visual';
import { mensajeError } from '../../core/services/api-error.util';

/**
 * Recomendaciones: la señal viene de ClickHouse (eventos) y los productos de
 * PostgreSQL. Si la analítica está apagada, el backend degrada a destacados
 * del catálogo y lo avisa con `mensajeFallback` (nunca 500).
 *
 * La pantalla DICE de dónde sale lo que enseña —recomendación personalizada o
 * lo más vendido— porque son dos cosas distintas y el cliente no tiene otra
 * forma de saber cuál está viendo.
 */
@Component({
  selector: 'app-recomendaciones',
  standalone: true,
  imports: [
    CommonModule, RouterLink, MatButtonModule, MatIconModule,
    MatSnackBarModule, MatTooltipModule
  ],
  templateUrl: './recomendaciones.component.html',
  styleUrls: ['../shop/shop-shared.scss', './recomendaciones.component.scss']
})
export class RecomendacionesComponent implements OnInit {

  recomendaciones: any[] = [];
  categoriaFavorita = '';
  totalEventos = 0;
  esPersonalizado = false;
  mensajeFallback = '';
  queryMs = 0;
  loading = true;

  constructor(
    private shopService: ShopService,
    public ui: ShopUiService,
    private snackBar: MatSnackBar,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.ui.cargarCategorias();
    this.ui.refrescarTodo();
    this.cargarRecomendaciones();
  }

  private cargarRecomendaciones(): void {
    const t0 = Date.now();
    this.shopService.getRecomendaciones().subscribe({
      next: (data) => {
        this.recomendaciones = data.recomendaciones || [];
        this.categoriaFavorita = data.categoriaFavorita || '';
        this.totalEventos = data.totalEventos || 0;
        this.esPersonalizado = data.esPersonalizado ?? false;
        this.mensajeFallback = data.mensajeFallback || '';
        this.queryMs = Date.now() - t0;
        this.loading = false;
      },
      error: (e) => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudieron cargar las recomendaciones'),
          'Cerrar', { duration: 3500 });
      }
    });
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
      error: (e) => this.snackBar.open(mensajeError(e, 'No se pudo agregar'), 'Cerrar', { duration: 2500 })
    });
  }

  toggleWishlist(producto: any, event: Event): void {
    event.stopPropagation();
    const id = Number(producto.productoId);
    if (this.ui.estaEnWishlist(id)) {
      this.shopService.eliminarDeWishlist(id).subscribe({
        next: () => {
          this.ui.marcarWishlist(id, false);
          this.snackBar.open('Quitado de tu lista de deseos', 'OK', { duration: 1800 });
        },
        error: () => {}
      });
    } else {
      this.shopService.agregarAWishlist(id).subscribe({
        next: () => {
          this.ui.marcarWishlist(id, true);
          this.snackBar.open('Guardado en tu lista de deseos', 'OK', { duration: 1800 });
        },
        error: () => {}
      });
    }
  }

  verProducto(productoId: number): void {
    this.router.navigate(['/shop/producto', productoId]);
  }

  irATienda(): void {
    this.router.navigate(['/shop']);
  }

  paleta(p: any): PaletaCategoria {
    return this.ui.paleta(p);
  }

  trackByProducto(_i: number, p: any): number { return p.productoId; }

  // ── Rótulos, que dependen del ORIGEN de la lista ─────────────────────────
  get titulo(): string {
    return this.esPersonalizado ? 'Recomendado para ti' : 'Lo más vendido de la tienda';
  }

  get subtitulo(): string {
    return this.esPersonalizado
      ? 'Salido de los productos que has visitado en RetailMind'
      : 'Selección del catálogo mientras tu historial toma forma';
  }

  get icono(): string {
    return this.esPersonalizado ? 'recommend' : 'local_fire_department';
  }
}
