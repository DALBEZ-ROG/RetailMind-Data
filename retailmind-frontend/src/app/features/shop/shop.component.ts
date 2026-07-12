import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatBadgeModule } from '@angular/material/badge';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ShopService } from './shop.service';
import { mensajeError } from '../../core/services/api-error.util';

const CATEGORY_ICONS: Record<number, string> = {
  1: 'devices', 2: 'shopping_basket', 3: 'sports_soccer', 4: 'watch',
  5: 'spa', 6: 'home', 7: 'directions_walk', 8: 'checkroom',
  9: 'checkroom', 10: 'checkroom', 11: 'category'
};

const CATEGORY_COLORS: Record<number, { bg: string; border: string; icon: string }> = {
  1: { bg: '#e3f2fd', border: '#90caf9', icon: '#1565c0' },
  2: { bg: '#fff3e0', border: '#ffcc80', icon: '#e65100' },
  3: { bg: '#e8f5e9', border: '#a5d6a7', icon: '#2e7d32' },
  4: { bg: '#fff8e1', border: '#ffe082', icon: '#f57f17' },
  5: { bg: '#fce4ec', border: '#f48fb1', icon: '#880e4f' },
  6: { bg: '#e0f2f1', border: '#80cbc4', icon: '#00695c' },
  7: { bg: '#ede7f6', border: '#ce93d8', icon: '#4a148c' },
  8: { bg: '#e8eaf6', border: '#9fa8da', icon: '#1a237e' }
};

/**
 * Catálogo de la tienda del cliente: productos REALES de PostgreSQL
 * (producto/producto_variante con stock de inventario), con búsqueda y
 * paginación en servidor (~1.200 productos). Solo rol CLIENTE (roleGuard).
 */
@Component({
  selector: 'app-shop',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatButtonModule,
    MatIconModule, MatFormFieldModule, MatInputModule,
    MatPaginatorModule, MatSnackBarModule, MatBadgeModule, MatProgressSpinnerModule
  ],
  templateUrl: './shop.component.html',
  styleUrl: './shop.component.scss'
})
export class ShopComponent implements OnInit {

  productos: any[] = [];
  categorias: any[] = [];
  totalProductos = 0;
  page = 0;
  size = 12;
  loading = false;

  // Filtros (se aplican en el servidor)
  categoriaSeleccionada: number | null = null;
  busqueda = '';

  // Carrito badge
  carritoCount = 0;

  // Wishlist
  productosEnWishlist = new Set<number>();

  // Color de categoría activa
  categoriaColorActiva: { bg: string; border: string; icon: string } | null = null;

  constructor(
    private shopService: ShopService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadProductos();
    this.loadCategorias();
    this.loadCarritoCount();
    this.loadWishlistIds();
  }

  loadProductos(): void {
    this.loading = true;
    const filters: any = {};
    if (this.categoriaSeleccionada) filters.categoria_id = this.categoriaSeleccionada;
    if (this.busqueda.trim()) filters.q = this.busqueda.trim();

    this.shopService.getProductos(this.page, this.size, filters).subscribe({
      next: (res) => {
        this.productos = res.content;
        this.totalProductos = res.totalElements;
        this.loading = false;
      },
      error: (e) => {
        this.loading = false;
        this.productos = [];
        this.snackBar.open(mensajeError(e, 'No se pudo cargar el catálogo'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  buscar(): void {
    this.page = 0;
    this.loadProductos();
  }

  limpiarBusqueda(): void {
    if (!this.busqueda) return;
    this.busqueda = '';
    this.buscar();
  }

  loadCategorias(): void {
    this.shopService.getCategorias().subscribe({
      next: (cats) => this.categorias = cats,
      error: () => {}
    });
  }

  loadCarritoCount(): void {
    this.shopService.getCarrito().subscribe({
      next: (items) => this.carritoCount = items.length,
      error: () => {}
    });
  }

  loadWishlistIds(): void {
    this.shopService.getWishlist().subscribe({
      next: (items) => this.productosEnWishlist = new Set(items.map(i => Number(i.productoId))),
      error: () => {}
    });
  }

  isInWishlist(productoId: number): boolean {
    return this.productosEnWishlist.has(Number(productoId));
  }

  filtrarCategoria(catId: number | null): void {
    this.categoriaSeleccionada = this.categoriaSeleccionada === catId ? null : catId;
    this.categoriaColorActiva = this.categoriaSeleccionada
      ? (CATEGORY_COLORS[this.categoriaSeleccionada] || null)
      : null;
    this.page = 0;
    this.loadProductos();
  }

  onPageChange(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.loadProductos();
  }

  verProducto(productoId: number): void {
    this.router.navigate(['/shop/producto', productoId]);
  }

  agregarAlCarrito(producto: any, event: Event): void {
    event.stopPropagation();
    this.shopService.agregarAlCarrito(producto.productoId, 1).subscribe({
      next: () => {
        this.carritoCount++;
        this.snackBar.open('Agregado al carrito', 'OK', { duration: 2000, panelClass: ['snack-success'] });
      },
      error: (e) => this.snackBar.open(mensajeError(e, 'Error al agregar'), 'Cerrar',
        { duration: 3000, panelClass: ['snack-error'] })
    });
  }

  toggleWishlist(producto: any, event: Event): void {
    event.stopPropagation();
    const productoId = Number(producto.productoId);

    if (this.productosEnWishlist.has(productoId)) {
      this.shopService.eliminarDeWishlist(productoId).subscribe({
        next: () => {
          this.productosEnWishlist.delete(productoId);
          this.snackBar.open('Eliminado de wishlist', 'OK', { duration: 2000 });
        },
        error: (e) => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 2000 })
      });
    } else {
      this.shopService.agregarAWishlist(productoId).subscribe({
        next: () => {
          this.productosEnWishlist.add(productoId);
          this.snackBar.open('Agregado a wishlist ❤️', 'OK', { duration: 2000 });
        },
        error: (e) => this.snackBar.open(mensajeError(e, 'Ya esta en wishlist'), 'OK', { duration: 2000 })
      });
    }
  }

  irAlCarrito(): void {
    this.router.navigate(['/shop/carrito']);
  }

  getCategoryIcon(catId: number): string {
    return CATEGORY_ICONS[catId] || 'inventory_2';
  }

  // ── Dynamic card styles based on active category ─────────────────────────
  getCardStyle(producto: any): Record<string, string> {
    if (!this.categoriaSeleccionada || !this.categoriaColorActiva) return {};
    if (producto.categoriaId === this.categoriaSeleccionada) {
      return { 'border': '1.5px solid ' + this.categoriaColorActiva.border };
    }
    return {};
  }

  getImageAreaStyle(producto: any): Record<string, string> {
    if (!this.categoriaSeleccionada || !this.categoriaColorActiva) return {};
    if (producto.categoriaId === this.categoriaSeleccionada) {
      return { 'background-color': this.categoriaColorActiva.bg };
    }
    return {};
  }

  getIconStyle(producto: any): Record<string, string> {
    if (!this.categoriaSeleccionada || !this.categoriaColorActiva) return {};
    if (producto.categoriaId === this.categoriaSeleccionada) {
      return { 'color': this.categoriaColorActiva.icon, 'opacity': '0.7' };
    }
    return {};
  }
}
