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
 * Lista de deseos del cliente sobre PostgreSQL (wishlist/wishlist_item, RLS
 * propio). Usa las mismas tarjetas que el catálogo —`shop-shared.scss`— para
 * que un producto se vea igual en los dos sitios.
 */
@Component({
  selector: 'app-wishlist',
  standalone: true,
  imports: [
    CommonModule, RouterLink, MatButtonModule, MatIconModule,
    MatSnackBarModule, MatTooltipModule
  ],
  templateUrl: './wishlist.component.html',
  styleUrls: ['../shop/shop-shared.scss', './wishlist.component.scss']
})
export class WishlistComponent implements OnInit {

  items: any[] = [];
  loading = true;
  moviendo = new Set<number>();

  constructor(
    private shopService: ShopService,
    public ui: ShopUiService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.ui.cargarCategorias();
    this.loadWishlist();
    this.ui.refrescarCarrito();
  }

  loadWishlist(): void {
    this.loading = true;
    this.shopService.getWishlist().subscribe({
      next: (items) => {
        this.items = items || [];
        this.loading = false;
        this.ui.refrescarWishlist();
      },
      error: (e) => {
        this.items = [];
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudo cargar la lista de deseos'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  get disponibles(): number {
    return this.items.filter(i => Number(i.stock) > 0).length;
  }

  get valorTotal(): number {
    return this.items.reduce((s, i) => s + Number(i.price || 0), 0);
  }

  agregarAlCarrito(item: any, event?: Event): void {
    event?.stopPropagation();
    this.moviendo.add(item.productoId);
    this.shopService.agregarAlCarrito(item.productoId, 1).subscribe({
      next: () => {
        this.moviendo.delete(item.productoId);
        this.ui.refrescarCarrito();
        this.snackBar.open(`«${item.nombre}» agregado al carrito`, 'Ver carrito', { duration: 3000 })
          .onAction().subscribe(() => this.router.navigate(['/shop/carrito']));
      },
      error: (e) => {
        this.moviendo.delete(item.productoId);
        this.snackBar.open(mensajeError(e, 'No se pudo agregar al carrito'), 'Cerrar', { duration: 3000 });
      }
    });
  }

  eliminar(item: any, event?: Event): void {
    event?.stopPropagation();
    this.shopService.eliminarDeWishlist(item.productoId).subscribe({
      next: () => {
        this.items = this.items.filter(i => i.productoId !== item.productoId);
        this.ui.marcarWishlist(item.productoId, false);
        this.snackBar.open('Quitado de tu lista de deseos', 'OK', { duration: 2000 });
      },
      error: (e) => this.snackBar.open(mensajeError(e, 'No se pudo quitar'), 'Cerrar', { duration: 3000 })
    });
  }

  /** Agrega al carrito todo lo que tenga stock, de una vez. */
  agregarDisponibles(): void {
    const conStock = this.items.filter(i => Number(i.stock) > 0);
    if (!conStock.length) return;
    conStock.forEach(i => this.agregarAlCarrito(i));
  }

  verProducto(productoId: number): void {
    this.router.navigate(['/shop/producto', productoId]);
  }

  irATienda(): void { this.router.navigate(['/shop']); }

  paleta(p: any): PaletaCategoria {
    return this.ui.paleta(p);
  }

  trackByItem(_i: number, item: any): number { return item.productoId; }
}
