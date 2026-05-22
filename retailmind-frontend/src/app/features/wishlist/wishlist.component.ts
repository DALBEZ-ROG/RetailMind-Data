import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ShopService } from '../shop/shop.service';
import { AuthService } from '../../core/services/auth.service';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';

const CATEGORY_ICONS: Record<number, string> = {
  1: 'devices', 2: 'shopping_basket', 3: 'sports_soccer', 4: 'watch',
  5: 'spa', 6: 'home', 7: 'directions_walk', 8: 'checkroom'
};

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
    private http: HttpClient,
    private shopService: ShopService,
    private authService: AuthService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadWishlist();
  }

  loadWishlist(): void {
    const user = this.authService.getCurrentUser();
    if (!user) return;
    this.loading = true;
    this.http.get<any[]>(`${environment.apiUrl}/api/wishlist/${user.username}`).subscribe({
      next: (items) => { this.items = items; this.loading = false; },
      error: () => { this.items = []; this.loading = false; }
    });
  }

  agregarAlCarrito(item: any): void {
    const user = this.authService.getCurrentUser();
    if (!user) return;
    this.shopService.agregarAlCarrito(user.username, item.productoId, 1).subscribe({
      next: () => this.snackBar.open('Agregado al carrito ✓', 'OK', { duration: 2000, panelClass: ['snack-success'] }),
      error: () => this.snackBar.open('Error al agregar', 'Cerrar', { duration: 3000 })
    });
  }

  eliminarDeWishlist(productoId: string): void {
    const user = this.authService.getCurrentUser();
    if (!user) return;
    this.http.delete(`${environment.apiUrl}/api/wishlist/${user.username}/${productoId}`).subscribe({
      next: () => {
        this.items = this.items.filter(i => i.productoId !== productoId);
        this.snackBar.open('Eliminado de wishlist', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Error al eliminar', 'Cerrar', { duration: 3000 })
    });
  }

  verProducto(productoId: string): void {
    this.router.navigate(['/shop/producto', productoId]);
  }

  getCategoryIcon(catId: number): string {
    return CATEGORY_ICONS[catId] || 'inventory_2';
  }
}
