import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ProductoAdmin, ProductoDetalleAdmin, MarcaAdmin, CategoriaAdmin
} from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class CatalogoAdminService {
  private readonly base = `${environment.apiUrl}/api/admin/catalogo`;

  constructor(private http: HttpClient) {}

  // Productos
  productos(): Observable<ProductoAdmin[]> { return this.http.get<ProductoAdmin[]>(`${this.base}/productos`); }
  producto(id: number): Observable<ProductoDetalleAdmin> {
    return this.http.get<ProductoDetalleAdmin>(`${this.base}/productos/${id}`);
  }
  crearProducto(body: {
    nombre: string; slug: string; marcaId: number | null; descripcionCorta: string;
    descripcion: string; publicado: boolean; categoriaIds: number[];
  }): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/productos`, body);
  }
  activarProducto(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/productos/${id}/activo`, { activo });
  }

  // Variantes
  crearVariante(productoId: number, body: {
    sku: string; precio: number; costo: number; codigoBarras?: string; esPredeterminada?: boolean;
  }): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/productos/${productoId}/variantes`, body);
  }
  activarVariante(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/variantes/${id}/activo`, { activo });
  }

  // Marcas y categorías (para selects del formulario de producto)
  marcas(): Observable<MarcaAdmin[]>        { return this.http.get<MarcaAdmin[]>(`${this.base}/marcas`); }
  categorias(): Observable<CategoriaAdmin[]> { return this.http.get<CategoriaAdmin[]>(`${this.base}/categorias`); }
}
