import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ShopService {
  private readonly catalogo = `${environment.apiUrl}/api/catalogo`;
  private readonly carrito = `${environment.apiUrl}/api/carrito`;

  constructor(private http: HttpClient) {}

  getProductos(page: number, size: number, filters?: any): Observable<any> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (filters?.categoria_id) params = params.set('categoria_id', filters.categoria_id);
    if (filters?.brand) params = params.set('brand', filters.brand);
    if (filters?.min_price) params = params.set('min_price', filters.min_price);
    if (filters?.max_price) params = params.set('max_price', filters.max_price);
    return this.http.get(`${this.catalogo}/productos`, { params });
  }

  getProductoById(id: string): Observable<any> {
    return this.http.get(`${this.catalogo}/productos/${id}`);
  }

  getCategorias(): Observable<any[]> {
    return this.http.get<any[]>(`${this.catalogo}/categorias`);
  }

  getMarcas(): Observable<string[]> {
    return this.http.get<string[]>(`${this.catalogo}/marcas`);
  }

  registrarEvento(body: any): Observable<any> {
    return this.http.post(`${this.catalogo}/eventos`, body);
  }

  // Carrito
  agregarAlCarrito(userId: string, productoId: string, cantidad: number): Observable<any> {
    return this.http.post(`${this.carrito}/agregar`, { user_id: userId, producto_id: productoId, cantidad });
  }

  getCarrito(userId: string): Observable<any[]> {
    return this.http.get<any[]>(`${this.carrito}/${userId}`);
  }

  eliminarDelCarrito(userId: string, productoId: string): Observable<any> {
    return this.http.delete(`${this.carrito}/${userId}/${productoId}`);
  }

  checkout(userId: string): Observable<any> {
    return this.http.post(`${this.carrito}/${userId}/checkout`, {});
  }

  // Wishlist
  agregarAWishlist(userId: string, productoId: string): Observable<any> {
    return this.http.post(`${environment.apiUrl}/api/wishlist/agregar`, { user_id: userId, producto_id: productoId });
  }
}
