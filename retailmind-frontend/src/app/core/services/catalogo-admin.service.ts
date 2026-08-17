import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ProductoAdmin, ProductoDetalleAdmin, MarcaAdmin, CategoriaAdmin, PaginaProductos
} from '../models/operativo.model';

export interface ProductoBody {
  nombre: string; slug: string; marcaId: number | null; descripcionCorta: string;
  descripcion: string; publicado: boolean; categoriaIds: number[];
}
export interface VarianteBody {
  sku: string; precio: number; costo: number;
  /**
   * `codigo_barras` es UNIQUE en la BD y NULL no colisiona, pero la cadena
   * vacía SÍ: sin código de barras hay que mandar `null`, nunca `''`, o la
   * segunda variante sin código choca con la primera.
   */
  codigoBarras?: string | null;
  esPredeterminada?: boolean;
  /**
   * Obligatorio al CREAR (el backend devuelve 400 sin él) y opcional al editar,
   * donde omitirlo conserva el valor que hubiera. Sin peso, el costo de envío de
   * todo pedido que incluya la variante se calcula sin el cargo por kilo.
   */
  pesoKg?: number | null;
}

@Injectable({ providedIn: 'root' })
export class CatalogoAdminService {
  private readonly base = `${environment.apiUrl}/api/admin/catalogo`;

  constructor(private http: HttpClient) {}

  // Productos
  productos(): Observable<ProductoAdmin[]> { return this.http.get<ProductoAdmin[]>(`${this.base}/productos`); }

  /** Búsqueda paginada server-side (LIMIT/OFFSET): la usa la pantalla de productos. */
  buscarProductos(q: string, marcaId: number | null, categoriaId: number | null,
                  page: number, size: number): Observable<PaginaProductos> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (q.trim())          params = params.set('q', q.trim());
    if (marcaId != null)   params = params.set('marcaId', marcaId);
    if (categoriaId != null) params = params.set('categoriaId', categoriaId);
    return this.http.get<PaginaProductos>(`${this.base}/productos/buscar`, { params });
  }

  producto(id: number): Observable<ProductoDetalleAdmin> {
    return this.http.get<ProductoDetalleAdmin>(`${this.base}/productos/${id}`);
  }
  crearProducto(body: ProductoBody): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/productos`, body);
  }
  editarProducto(id: number, body: Omit<ProductoBody, 'categoriaIds'>): Observable<unknown> {
    return this.http.put(`${this.base}/productos/${id}`, body);
  }
  activarProducto(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/productos/${id}/activo`, { activo });
  }

  // Variantes
  crearVariante(productoId: number, body: VarianteBody): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/productos/${productoId}/variantes`, body);
  }
  editarVariante(id: number,
                 body: { sku: string; precio: number; costo: number; pesoKg?: number | null }
                ): Observable<unknown> {
    return this.http.put(`${this.base}/variantes/${id}`, body);
  }
  activarVariante(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/variantes/${id}/activo`, { activo });
  }

  // Marcas
  marcas(): Observable<MarcaAdmin[]> { return this.http.get<MarcaAdmin[]>(`${this.base}/marcas`); }
  crearMarca(body: { nombre: string; slug: string; descripcion: string }): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/marcas`, body);
  }
  editarMarca(id: number, body: { nombre: string; slug: string; descripcion: string }): Observable<unknown> {
    return this.http.put(`${this.base}/marcas/${id}`, body);
  }
  activarMarca(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/marcas/${id}/activo`, { activo });
  }

  // Categorías
  categorias(): Observable<CategoriaAdmin[]> { return this.http.get<CategoriaAdmin[]>(`${this.base}/categorias`); }
  crearCategoria(body: { nombre: string; slug: string; descripcion: string; padreId: number | null }): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/categorias`, body);
  }
  editarCategoria(id: number, body: { nombre: string; slug: string; descripcion: string }): Observable<unknown> {
    return this.http.put(`${this.base}/categorias/${id}`, body);
  }
  activarCategoria(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/categorias/${id}/activo`, { activo });
  }
}
