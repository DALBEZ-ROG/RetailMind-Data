import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ShopService } from './shop.service';
import { ShopUiService } from './shop-ui.service';
import { PaletaCategoria } from './catalogo-visual';
import { mensajeError } from '../../core/services/api-error.util';

/**
 * Carrito del cliente sobre PostgreSQL (carrito/carrito_item, RLS propio).
 * El checkout crea un PEDIDO REAL del ciclo de venta: el mismo que ven
 * vendedor/despacho/admin en el back-office.
 *
 * Los importes que se muestran aquí son los que devuelve el backend
 * (`precioUnitario` y `descuentoPromo` por línea): la pantalla NO inventa un
 * precio final. Impuestos y envío no se estiman aquí porque los calcula el
 * checkout con la dirección puesta, y la pantalla lo dice en vez de enseñar un
 * total que luego cambiaría.
 */
@Component({
  selector: 'app-carrito',
  standalone: true,
  imports: [
    CommonModule, RouterLink, MatButtonModule, MatIconModule,
    MatSnackBarModule, MatTooltipModule
  ],
  templateUrl: './carrito.component.html',
  styleUrls: ['./shop-shared.scss', './carrito.component.scss']
})
export class CarritoComponent implements OnInit {

  items: any[] = [];
  loading = true;
  /** Ids en curso de actualización: bloquean sus botones sin congelar la lista. */
  ocupados = new Set<number>();
  sugerencias: any[] = [];

  constructor(
    private shopService: ShopService,
    public ui: ShopUiService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    // El carrito solo trae `categoriaId`; el nombre —del que salen icono y
    // color— se resuelve con la tabla de departamentos del servicio de UI.
    this.ui.cargarCategorias();
    this.loadCarrito();
    this.loadSugerencias();
  }

  loadCarrito(): void {
    this.loading = true;
    this.shopService.getCarrito().subscribe({
      next: (items) => {
        this.items = items || [];
        this.ui.fijarCarrito(this.items.length);
        this.loading = false;
      },
      error: (e) => {
        this.items = [];
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudo cargar el carrito'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  /** Fila «también te puede interesar»; degrada sola si no hay analítica. */
  loadSugerencias(): void {
    this.shopService.getRecomendaciones().subscribe({
      next: (r) => this.sugerencias = (r?.recomendaciones || []).slice(0, 6),
      error: () => this.sugerencias = []
    });
  }

  // ── Totales ──────────────────────────────────────────────────────────────
  /** Suma de precios de lista, ANTES de promociones. */
  get bruto(): number {
    return this.items.reduce((s, i) => s + Number(i.precioUnitario) * Number(i.cantidad), 0);
  }

  get ahorroPromos(): number {
    return this.items.reduce((s, i) => s + Number(i.descuentoPromo || 0), 0);
  }

  /** Lo que se paga por los productos, ya rebajadas las promociones. */
  get subtotal(): number {
    return this.bruto - this.ahorroPromos;
  }

  get unidades(): number {
    return this.items.reduce((s, i) => s + Number(i.cantidad), 0);
  }

  lineaTotal(item: any): number {
    return Number(item.precioUnitario) * Number(item.cantidad) - Number(item.descuentoPromo || 0);
  }

  // ── Acciones sobre una línea ─────────────────────────────────────────────
  cambiarCantidad(item: any, delta: number): void {
    const nueva = Number(item.cantidad) + delta;
    if (nueva <= 0) { this.eliminarItem(item); return; }
    if (item.stock !== undefined && nueva > Number(item.stock)) {
      this.snackBar.open(`Solo quedan ${item.stock} unidades de este producto`, 'OK', { duration: 3000 });
      return;
    }

    this.ocupados.add(item.productoId);
    this.shopService.cambiarCantidad(item.productoId, nueva).subscribe({
      // Recarga completa: el descuento promocional lo recalcula el backend y
      // multiplicar aquí el descuento por la cantidad nueva sería inventarlo.
      next: () => { this.ocupados.delete(item.productoId); this.loadCarrito(); },
      error: (e) => {
        this.ocupados.delete(item.productoId);
        this.snackBar.open(mensajeError(e, 'No se pudo actualizar la cantidad'), 'Cerrar', { duration: 3000 });
      }
    });
  }

  eliminarItem(item: any): void {
    this.ocupados.add(item.productoId);
    this.shopService.eliminarDelCarrito(item.productoId).subscribe({
      next: () => {
        this.ocupados.delete(item.productoId);
        this.items = this.items.filter(i => i.productoId !== item.productoId);
        this.ui.fijarCarrito(this.items.length);
        this.snackBar.open(`«${item.nombre}» ya no está en el carrito`, 'OK', { duration: 2500 });
      },
      error: (e) => {
        this.ocupados.delete(item.productoId);
        this.snackBar.open(mensajeError(e, 'No se pudo eliminar'), 'Cerrar', { duration: 3000 });
      }
    });
  }

  /** Mover a la lista de deseos = alta en wishlist + baja del carrito. */
  guardarParaDespues(item: any): void {
    this.ocupados.add(item.productoId);
    this.shopService.agregarAWishlist(item.productoId).subscribe({
      next: () => {
        this.ui.marcarWishlist(item.productoId, true);
        this.ocupados.delete(item.productoId);
        this.eliminarItem(item);
      },
      error: (e) => {
        this.ocupados.delete(item.productoId);
        // Si ya estaba en la lista, la intención sigue siendo sacarlo del carrito.
        if (String(mensajeError(e, '')).toLowerCase().includes('ya')) { this.eliminarItem(item); return; }
        this.snackBar.open(mensajeError(e, 'No se pudo guardar en la lista'), 'Cerrar', { duration: 3000 });
      }
    });
  }

  agregarSugerencia(p: any, event: Event): void {
    event.stopPropagation();
    this.shopService.agregarAlCarrito(p.productoId, 1).subscribe({
      next: () => { this.snackBar.open('Agregado al carrito', 'OK', { duration: 2000 }); this.loadCarrito(); },
      error: (e) => this.snackBar.open(mensajeError(e, 'No se pudo agregar'), 'Cerrar', { duration: 3000 })
    });
  }

  // ── Navegación ───────────────────────────────────────────────────────────
  finalizarCompra(): void { this.router.navigate(['/shop/checkout']); }
  seguirComprando(): void { this.router.navigate(['/shop']); }
  verProducto(id: number): void { this.router.navigate(['/shop/producto', id]); }

  // ── Presentación ─────────────────────────────────────────────────────────
  paleta(p: any): PaletaCategoria {
    return this.ui.paleta(p);
  }

  estaOcupado(item: any): boolean { return this.ocupados.has(item.productoId); }

  trackByItem(_i: number, item: any): number { return item.productoId; }
}
