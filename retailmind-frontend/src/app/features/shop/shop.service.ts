import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * API de la tienda del cliente — todo contra PostgreSQL (el backend deriva el
 * cliente del JWT y el RLS lo aísla; ya no se envían usernames por URL).
 * productoId = id de la variante (producto_variante), la misma clave que usan
 * carrito_item / wishlist_item / pedido_detalle.
 */
@Injectable({ providedIn: 'root' })
export class ShopService {
  private readonly base = environment.apiUrl;

  constructor(private http: HttpClient) {}

  // ── Catálogo ──────────────────────────────────────────────────────────
  getProductos(page: number, size: number, filters?: {
    categoria_id?: number | null; brand?: string; q?: string;
    min_price?: number; max_price?: number;
  }): Observable<any> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (filters?.categoria_id) params = params.set('categoria_id', filters.categoria_id);
    if (filters?.brand) params = params.set('brand', filters.brand);
    if (filters?.q) params = params.set('q', filters.q);
    if (filters?.min_price != null) params = params.set('min_price', filters.min_price);
    if (filters?.max_price != null) params = params.set('max_price', filters.max_price);
    return this.http.get(`${this.base}/api/catalogo/productos`, { params });
  }

  getProductoById(id: number | string): Observable<any> {
    return this.http.get(`${this.base}/api/catalogo/productos/${id}`);
  }

  getCategorias(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/api/catalogo/categorias`);
  }

  getMarcas(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/api/catalogo/marcas`);
  }

  /** Evento de navegación hacia analítica (best-effort en el backend). */
  registrarEvento(body: any): Observable<any> {
    return this.http.post(`${this.base}/api/catalogo/eventos`, body);
  }

  // ── Carrito (PostgreSQL, RLS por cliente) ─────────────────────────────
  getCarrito(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/api/carrito`);
  }

  agregarAlCarrito(varianteId: number | string, cantidad = 1): Observable<any> {
    return this.http.post(`${this.base}/api/carrito/items`, { varianteId, cantidad });
  }

  cambiarCantidad(varianteId: number | string, cantidad: number): Observable<any> {
    return this.http.patch(`${this.base}/api/carrito/items/${varianteId}`, { cantidad });
  }

  eliminarDelCarrito(varianteId: number | string): Observable<any> {
    return this.http.delete(`${this.base}/api/carrito/items/${varianteId}`);
  }

  // ── Checkout online (el pedido nace PAGADO; pago simulado) ───────────
  /** Métodos de pago que ofrece el checkout (tarjeta / transferencia). */
  checkoutMetodos(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/api/carrito/checkout/metodos`);
  }

  /**
   * Checkout completo: dirección + método de pago + cupón (preparado para la
   * fase de descuentos). La tarjeta viaja solo para VALIDAR formato: el
   * backend guarda únicamente marca + últimos 4 dígitos (nunca PAN/CVV).
   */
  checkout(body: {
    direccionId: number; metodoPagoId: number; cupon?: string;
    tarjeta?: { numero: string; titular: string; vencimiento: string; cvv: string };
    referenciaTransferencia?: string;
  }): Observable<any> {
    return this.http.post(`${this.base}/api/carrito/checkout`, body);
  }

  // ── Direcciones del cliente (mismas del perfil) ───────────────────────
  getDirecciones(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/api/perfil/direcciones`);
  }

  crearDireccion(body: any): Observable<any> {
    return this.http.post(`${this.base}/api/perfil/direcciones`, body);
  }

  getCiudades(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/api/perfil/ciudades`);
  }

  // ── Wishlist ──────────────────────────────────────────────────────────
  getWishlist(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/api/wishlist`);
  }

  agregarAWishlist(varianteId: number | string): Observable<any> {
    return this.http.post(`${this.base}/api/wishlist/items`, { varianteId });
  }

  eliminarDeWishlist(varianteId: number | string): Observable<any> {
    return this.http.delete(`${this.base}/api/wishlist/items/${varianteId}`);
  }

  // ── Recomendaciones (señal CH + productos PG; degrada sin ClickHouse) ─
  getRecomendaciones(): Observable<any> {
    return this.http.get(`${this.base}/api/recomendaciones`);
  }

  getSimilares(varianteId: number | string): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/api/recomendaciones/similares/${varianteId}`);
  }
}
