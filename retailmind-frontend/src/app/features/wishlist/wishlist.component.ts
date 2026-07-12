import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ShopService } from '../shop/shop.service';
import { mensajeError } from '../../core/services/api-error.util';

const CATEGORY_ICONS: Record<number, string> = {
  1: 'devices', 2: 'shopping_basket', 3: 'sports_soccer', 4: 'watch',
  5: 'spa', 6: 'home', 7: 'directions_walk', 8: 'checkroom',
  9: 'checkroom', 10: 'checkroom', 11: 'category'
};

/** Wishlist del cliente sobre PostgreSQL (wishlist/wishlist_item, RLS propio). */
@Component({
  selector: 'app-wishlist',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatButtonModule, MatIconModule, MatSnackBarModule],
  templateUrl: './wishlist.component.html',
  styleUrl: './wishlist.component.scss'
})
export class WishlistComponent implements OnInit {

  items: any[] = [];
  loading = true;

  constructor(
    private shopService: ShopService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadWishlist();
  }

  loadWishlist(): void {
    this.loading = true;
    this.shopService.getWishlist().subscribe({
      next: (items) => { this.items = items; this.loading = false; },
      error: (e) => {
        this.items = [];
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudo cargar la wishlist'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  agregarAlCarrito(item: any): void {
    this.shopService.agregarAlCarrito(item.productoId, 1).subscribe({
      next: () => this.snackBar.open('Agregado al carrito ✓', 'OK',
        { duration: 2000, panelClass: ['snack-success'] }),
      error: (e) => this.snackBar.open(mensajeError(e, 'Error al agregar'), 'Cerrar', { duration: 3000 })
    });
  }

  eliminarDeWishlist(productoId: number): void {
    this.shopService.eliminarDeWishlist(productoId).subscribe({
      next: () => {
        this.items = this.items.filter(i => i.productoId !== productoId);
        this.snackBar.open('Eliminado de wishlist', 'OK', { duration: 2000 });
      },
      error: (e) => this.snackBar.open(mensajeError(e, 'Error al eliminar'), 'Cerrar', { duration: 3000 })
    });
  }

  verProducto(productoId: number): void {
    this.router.navigate(['/shop/producto', productoId]);
  }

  getCategoryIcon(catId: number): string {
    return CATEGORY_ICONS[catId] || 'inventory_2';
  }
}
