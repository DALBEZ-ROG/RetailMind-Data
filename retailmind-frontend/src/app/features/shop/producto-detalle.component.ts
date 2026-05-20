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
import { ShopService } from './shop.service';
import { AuthService } from '../../core/services/auth.service';

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

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private shopService: ShopService,
    private authService: AuthService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.shopService.getProductoById(id).subscribe({
        next: (p) => { this.producto = p; this.loading = false; this.registrarView(id); },
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

  agregarAlCarrito(): void {
    const user = this.authService.getCurrentUser();
    if (!user || !this.producto) return;

    this.shopService.agregarAlCarrito(user.username, this.producto.productoId, this.cantidad).subscribe({
      next: () => this.snackBar.open('Agregado al carrito ✓', 'OK', { duration: 2000, panelClass: ['snack-success'] }),
      error: () => this.snackBar.open('Error al agregar', 'Cerrar', { duration: 3000, panelClass: ['snack-error'] })
    });
  }

  agregarWishlist(): void {
    const user = this.authService.getCurrentUser();
    this.shopService.registrarEvento({
      user_id: user?.username || 'anonymous',
      product_id: this.producto.productoId,
      user_action: 'wishlist',
      channel: 'web',
      price: this.producto.price
    }).subscribe();
    this.snackBar.open('Agregado a wishlist ❤️', 'OK', { duration: 2000 });
  }

  volver(): void {
    this.router.navigate(['/shop']);
  }

  getCategoryIcon(catId: number): string {
    return CATEGORY_ICONS[catId] || 'inventory_2';
  }
}
