import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { HttpClient } from '@angular/common/http';
import { ShopService } from './shop.service';
import { AuthService } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';

const CATEGORY_ICONS: Record<number, string> = {
  1: 'devices', 2: 'shopping_basket', 3: 'sports_soccer', 4: 'watch',
  5: 'spa', 6: 'home', 7: 'directions_walk', 8: 'checkroom'
};

@Component({
  selector: 'app-producto-detalle',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatButtonModule,
    MatIconModule, MatFormFieldModule, MatInputModule, MatSnackBarModule
  ],
  templateUrl: './producto-detalle.component.html',
  styleUrl: './producto-detalle.component.scss'
})
export class ProductoDetalleComponent implements OnInit {

  producto: any = null;
  cantidad = 1;
  loading = true;
  enWishlist = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private shopService: ShopService,
    private authService: AuthService,
    private http: HttpClient,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.shopService.getProductoById(id).subscribe({
        next: (p) => { this.producto = p; this.loading = false; this.registrarView(id); this.checkWishlist(id); },
        error: () => { this.loading = false; }
      });
    }
  }

  private registrarView(productId: string): void {
    const user = this.authService.getCurrentUser();
    this.shopService.registrarEvento({
      user_id: user?.username || 'anonymous',
      product_id: productId,
      user_action: 'view',
      channel: 'web',
      price: this.producto?.price
    }).subscribe();
  }

  private checkWishlist(productoId: string): void {
    const user = this.authService.getCurrentUser();
    if (!user) return;
    this.http.get<any[]>(`${environment.apiUrl}/api/wishlist/${user.username}`).subscribe({
      next: (items) => {
        this.enWishlist = items.some(i => i.productoId === productoId);
      },
      error: () => {}
    });
  }

  agregarAlCarrito(): void {
    const user = this.authService.getCurrentUser();
    if (!user || !this.producto) return;

    this.shopService.agregarAlCarrito(user.username, this.producto.productoId, this.cantidad).subscribe({
      next: () => this.snackBar.open('Agregado al carrito ✓', 'OK', { duration: 2000, panelClass: ['snack-success'] }),
      error: () => this.snackBar.open('Error al agregar', 'Cerrar', { duration: 3000, panelClass: ['snack-error'] })
    });
  }

  toggleWishlist(): void {
    const user = this.authService.getCurrentUser();
    if (!user || !this.producto) return;

    const productoId = this.producto.productoId;

    if (this.enWishlist) {
      this.http.delete(`${environment.apiUrl}/api/wishlist/${user.username}/${productoId}`).subscribe({
        next: () => {
          this.enWishlist = false;
          this.snackBar.open('Eliminado de wishlist', 'OK', { duration: 2000 });
        },
        error: () => this.snackBar.open('Error', 'Cerrar', { duration: 2000 })
      });
    } else {
      this.shopService.agregarAWishlist(user.username, productoId).subscribe({
        next: () => {
          this.enWishlist = true;
          this.snackBar.open('Agregado a wishlist ❤️', 'OK', { duration: 2000 });
        },
        error: (e: any) => this.snackBar.open(e.error?.error || 'Error', 'OK', { duration: 2000 })
      });
    }
  }

  volver(): void {
    this.router.navigate(['/shop']);
  }

  getCategoryIcon(catId: number): string {
    return CATEGORY_ICONS[catId] || 'inventory_2';
  }
}
