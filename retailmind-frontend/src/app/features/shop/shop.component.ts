import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatBadgeModule } from '@angular/material/badge';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { HttpClient } from '@angular/common/http';
import { ShopService } from './shop.service';
import { AuthService } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';

const CATEGORY_ICONS: Record<number, string> = {
  1: 'devices', 2: 'shopping_basket', 3: 'sports_soccer', 4: 'watch',
  5: 'spa', 6: 'home', 7: 'directions_walk', 8: 'checkroom'
};

@Component({
  selector: 'app-shop',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatButtonModule,
    MatIconModule, MatChipsModule, MatFormFieldModule, MatInputModule,
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

  // Filtros
  categoriaSeleccionada: number | null = null;
  busqueda = '';

  // Carrito badge
  carritoCount = 0;

  // Wishlist
  productosEnWishlist = new Set<string>();

  constructor(
    private shopService: ShopService,
    private authService: AuthService,
    private http: HttpClient,
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

    this.shopService.getProductos(this.page, this.size, filters).subscribe({
      next: (res) => {
        this.productos = res.content;
        this.totalProductos = res.totalElements;
        this.loading = false;
      },
      error: () => { this.loading = false; this.productos = []; }
    });
  }

  loadCategorias(): void {
    this.shopService.getCategorias().subscribe({
      next: (cats) => this.categorias = cats,
      error: () => {}
    });
  }

  loadCarritoCount(): void {
    const user = this.authService.getCurrentUser();
    if (user) {
      this.shopService.getCarrito(user.username).subscribe({
        next: (items) => this.carritoCount = items.length,
        error: () => {}
      });
    }
  }

  loadWishlistIds(): void {
    const user = this.authService.getCurrentUser();
    if (!user) return;
    this.http.get<any[]>(`${environment.apiUrl}/api/wishlist/${user.username}`).subscribe({
      next: (items) => {
        this.productosEnWishlist = new Set(items.map(i => i.productoId));
      },
      error: () => {}
    });
  }

  isInWishlist(productoId: string): boolean {
    return this.productosEnWishlist.has(productoId);
  }

  filtrarCategoria(catId: number | null): void {
    this.categoriaSeleccionada = this.categoriaSeleccionada === catId ? null : catId;
    this.page = 0;
    this.loadProductos();
  }

  onPageChange(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.loadProductos();
  }

  verProducto(productoId: string): void {
    this.router.navigate(['/shop/producto', productoId]);
  }

  agregarAlCarrito(producto: any, event: Event): void {
    event.stopPropagation();
    const user = this.authService.getCurrentUser();
    if (!user) return;

    this.shopService.agregarAlCarrito(user.username, producto.productoId, 1).subscribe({
      next: () => {
        this.carritoCount++;
        this.snackBar.open('Agregado al carrito', 'OK', { duration: 2000, panelClass: ['snack-success'] });
      },
      error: () => this.snackBar.open('Error al agregar', 'Cerrar', { duration: 3000, panelClass: ['snack-error'] })
    });
  }

  toggleWishlist(producto: any, event: Event): void {
    event.stopPropagation();
    const user = this.authService.getCurrentUser();
    if (!user) return;

    const productoId = producto.productoId;

    if (this.productosEnWishlist.has(productoId)) {
      // Eliminar de wishlist
      this.http.delete(`${environment.apiUrl}/api/wishlist/${user.username}/${productoId}`).subscribe({
        next: () => {
          this.productosEnWishlist.delete(productoId);
          this.snackBar.open('Eliminado de wishlist', 'OK', { duration: 2000 });
        },
        error: () => this.snackBar.open('Error', 'Cerrar', { duration: 2000 })
      });
    } else {
      // Agregar a wishlist
      this.shopService.agregarAWishlist(user.username, productoId).subscribe({
        next: () => {
          this.productosEnWishlist.add(productoId);
          this.snackBar.open('Agregado a wishlist ❤️', 'OK', { duration: 2000 });
        },
        error: (e: any) => this.snackBar.open(e.error?.error || 'Ya esta en wishlist', 'OK', { duration: 2000 })
      });
    }
  }

  irAlCarrito(): void {
    this.router.navigate(['/shop/carrito']);
  }

  getCategoryIcon(catId: number): string {
    return CATEGORY_ICONS[catId] || 'inventory_2';
  }
}
