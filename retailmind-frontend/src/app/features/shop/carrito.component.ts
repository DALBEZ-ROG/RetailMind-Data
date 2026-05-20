import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ShopService } from './shop.service';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-carrito',
  standalone: true,
  imports: [
    CommonModule, MatCardModule, MatButtonModule,
    MatIconModule, MatDividerModule, MatSnackBarModule
  ],
  templateUrl: './carrito.component.html',
  styleUrl: './carrito.component.scss'
})
export class CarritoComponent implements OnInit {

  items: any[] = [];
  loading = true;
  checkoutExitoso = false;
  ordenId = '';

  constructor(
    private shopService: ShopService,
    private authService: AuthService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadCarrito();
  }

  loadCarrito(): void {
    const user = this.authService.getCurrentUser();
    if (!user) return;

    this.loading = true;
    this.shopService.getCarrito(user.username).subscribe({
      next: (items) => { this.items = items; this.loading = false; },
      error: () => { this.items = []; this.loading = false; }
    });
  }

  get total(): number {
    return this.items.reduce((sum, item) => sum + (item.precioUnitario * item.cantidad), 0);
  }

  eliminarItem(productoId: string): void {
    const user = this.authService.getCurrentUser();
    if (!user) return;

    this.shopService.eliminarDelCarrito(user.username, productoId).subscribe({
      next: () => {
        this.items = this.items.filter(i => i.productoId !== productoId);
        this.snackBar.open('Producto eliminado', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Error al eliminar', 'Cerrar', { duration: 3000 })
    });
  }

  finalizarCompra(): void {
    const user = this.authService.getCurrentUser();
    if (!user) return;

    this.shopService.checkout(user.username).subscribe({
      next: (res) => {
        this.checkoutExitoso = true;
        this.ordenId = res.ordenId;
        this.items = [];
        this.snackBar.open('Compra realizada con exito!', 'OK', { duration: 4000, panelClass: ['snack-success'] });
      },
      error: (e) => {
        this.snackBar.open(e.error?.error || 'Error en checkout', 'Cerrar', { duration: 4000, panelClass: ['snack-error'] });
      }
    });
  }

  seguirComprando(): void {
    this.router.navigate(['/shop']);
  }
}
