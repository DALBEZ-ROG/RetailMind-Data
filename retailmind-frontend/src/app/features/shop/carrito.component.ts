import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ShopService } from './shop.service';
import { mensajeError } from '../../core/services/api-error.util';

/**
 * Carrito del cliente sobre PostgreSQL (carrito/carrito_item, RLS propio).
 * El checkout crea un PEDIDO REAL del ciclo de venta: el mismo que ven
 * vendedor/despacho/admin en el back-office.
 */
@Component({
  selector: 'app-carrito',
  standalone: true,
  imports: [
    CommonModule, RouterLink, MatCardModule, MatButtonModule,
    MatIconModule, MatDividerModule, MatSnackBarModule
  ],
  templateUrl: './carrito.component.html',
  styleUrl: './carrito.component.scss'
})
export class CarritoComponent implements OnInit {

  items: any[] = [];
  loading = true;

  constructor(
    private shopService: ShopService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadCarrito();
  }

  loadCarrito(): void {
    this.loading = true;
    this.shopService.getCarrito().subscribe({
      next: (items) => { this.items = items; this.loading = false; },
      error: (e) => {
        this.items = [];
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudo cargar el carrito'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  get total(): number {
    return this.items.reduce((sum, item) => sum + (item.precioUnitario * item.cantidad), 0);
  }

  cambiarCantidad(item: any, delta: number): void {
    const nueva = item.cantidad + delta;
    if (nueva <= 0) { this.eliminarItem(item.productoId); return; }
    this.shopService.cambiarCantidad(item.productoId, nueva).subscribe({
      next: () => item.cantidad = nueva,
      error: (e) => this.snackBar.open(mensajeError(e, 'No se pudo actualizar la cantidad'),
        'Cerrar', { duration: 3000 })
    });
  }

  eliminarItem(productoId: number): void {
    this.shopService.eliminarDelCarrito(productoId).subscribe({
      next: () => {
        this.items = this.items.filter(i => i.productoId !== productoId);
        this.snackBar.open('Producto eliminado', 'OK', { duration: 2000 });
      },
      error: (e) => this.snackBar.open(mensajeError(e, 'Error al eliminar'), 'Cerrar', { duration: 3000 })
    });
  }

  /** El pago ocurre en el checkout (dirección + método + tarjeta simulada). */
  finalizarCompra(): void {
    this.router.navigate(['/shop/checkout']);
  }

  seguirComprando(): void {
    this.router.navigate(['/shop']);
  }
}
