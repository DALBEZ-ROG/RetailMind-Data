import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatBadgeModule } from '@angular/material/badge';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ShopService } from '../shop/shop.service';
import { mensajeError } from '../../core/services/api-error.util';

const CATEGORY_ICONS: Record<number, string> = {
  1: 'devices', 2: 'shopping_basket', 3: 'sports_soccer', 4: 'watch',
  5: 'spa', 6: 'home', 7: 'directions_walk', 8: 'checkroom',
  9: 'checkroom', 10: 'checkroom', 11: 'category'
};

/**
 * Recomendaciones: la señal viene de ClickHouse (eventos) y los productos de
 * PostgreSQL. Si la analítica está apagada, el backend degrada a destacados
 * del catálogo y lo avisa con mensajeFallback (nunca 500).
 */
@Component({
  selector: 'app-recomendaciones',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule, MatButtonModule, MatIconModule,
    MatChipsModule, MatProgressSpinnerModule,
    MatSnackBarModule, MatBadgeModule, MatTooltipModule
  ],
  templateUrl: './recomendaciones.component.html',
  styleUrl: './recomendaciones.component.scss'
})
export class RecomendacionesComponent implements OnInit {

  recomendaciones: any[] = [];
  categoriaFavorita = '';
  totalEventos = 0;
  esPersonalizado = false;
  mensajeFallback = '';
  queryMs = 0;
  loading = true;

  private wishlistIds = new Set<number>();

  constructor(
    private shopService: ShopService,
    private snackBar: MatSnackBar,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.cargarRecomendaciones();
    this.cargarWishlist();
  }

  private cargarRecomendaciones(): void {
    const t0 = Date.now();
    this.shopService.getRecomendaciones().subscribe({
      next: (data) => {
        this.recomendaciones  = data.recomendaciones  || [];
        this.categoriaFavorita = data.categoriaFavorita || '';
        this.totalEventos     = data.totalEventos      || 0;
        this.esPersonalizado  = data.esPersonalizado   ?? false;
        this.mensajeFallback  = data.mensajeFallback   || '';
        this.queryMs          = Date.now() - t0;
        this.loading          = false;
      },
      error: (e) => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'Error al cargar recomendaciones'), 'Cerrar', { duration: 3000 });
      }
    });
  }

  private cargarWishlist(): void {
    this.shopService.getWishlist().subscribe({
      next: (items) => items.forEach(i => this.wishlistIds.add(Number(i.productoId))),
      error: () => {}
    });
  }

  agregarAlCarrito(producto: any, event: Event): void {
    event.stopPropagation();
    this.shopService.agregarAlCarrito(producto.productoId, 1).subscribe({
      next: () => this.snackBar.open('Agregado al carrito ✓', 'OK',
          { duration: 2000, panelClass: ['snack-success'] }),
      error: (e) => this.snackBar.open(mensajeError(e, 'Error al agregar'), 'Cerrar', { duration: 2000 })
    });
  }

  toggleWishlist(producto: any, event: Event): void {
    event.stopPropagation();
    const id = Number(producto.productoId);
    if (this.wishlistIds.has(id)) {
      this.shopService.eliminarDeWishlist(id).subscribe({
        next: () => {
          this.wishlistIds.delete(id);
          this.snackBar.open('Eliminado de wishlist', 'OK', { duration: 1500 });
        },
        error: () => {}
      });
    } else {
      this.shopService.agregarAWishlist(id).subscribe({
        next: () => {
          this.wishlistIds.add(id);
          this.snackBar.open('Agregado a wishlist ❤️', 'OK', { duration: 1500 });
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

  isInWishlist(id: number): boolean {
    return this.wishlistIds.has(Number(id));
  }

  getCategoryIcon(catId: number): string {
    return CATEGORY_ICONS[catId] || 'inventory_2';
  }

  get pocosDatos(): boolean {
    return this.totalEventos < 10;
  }

  // Títulos dinámicos según si es personalizado o no
  get tituloHeader(): string {
    return this.esPersonalizado ? 'Recomendado para ti' : 'Productos Destacados';
  }

  get subtituloHeader(): string {
    return this.esPersonalizado
      ? 'Basado en tu historial de navegación en RetailMind Shop'
      : 'Selección del catálogo de RetailMind Shop';
  }

  get iconoHeader(): string {
    return this.esPersonalizado ? 'recommend' : 'trending_up';
  }
}
