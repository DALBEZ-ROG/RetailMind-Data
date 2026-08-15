import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ResenaRow, ResenaPublica, ReporteResenaRow, PreguntaProductoRow, ProductoResenaRef,
  Pagina
} from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class ResenasService {
  private readonly base = `${environment.apiUrl}/api/resenas`;

  constructor(private http: HttpClient) {}

  // Reseñas
  /**
   * Bandeja de moderación PAGINADA EN EL SERVIDOR (antes: las 263.077 reseñas,
   * 82,07 MB — el listado más grande del sistema).
   *
   * Los CUATRO criterios —estado, calificación, reportadas y el buscador de
   * texto— viajan al backend. Aplicarlos aquí sobre la página recibida daría
   * un «sin resultados» perfectamente creíble en cuanto se busque algo que no
   * esté entre las 25 primeras.
   */
  resenas(opts: {
    estado?: string; calificacion?: number; reportadas?: string; q?: string;
    page?: number; size?: number; conTotal?: boolean;
  } = {}): Observable<Pagina<ResenaRow>> {
    let params = new HttpParams();
    if (opts.estado && opts.estado !== 'todos') { params = params.set('estado', opts.estado); }
    if (opts.calificacion != null) { params = params.set('calificacion', opts.calificacion); }
    if (opts.reportadas && opts.reportadas !== 'todos') {
      params = params.set('reportadas', opts.reportadas);
    }
    if (opts.q)             { params = params.set('q', opts.q); }
    if (opts.page != null)  { params = params.set('page', opts.page); }
    if (opts.size != null)  { params = params.set('size', opts.size); }
    if (opts.conTotal === false) { params = params.set('conTotal', false); }
    return this.http.get<Pagina<ResenaRow>>(this.base, { params });
  }
  resenasProducto(productoId: number): Observable<ResenaPublica[]> {
    return this.http.get<ResenaPublica[]>(`${this.base}/producto/${productoId}`);
  }
  misResenas(): Observable<ResenaRow[]> {
    return this.http.get<ResenaRow[]>(`${this.base}/mias`);
  }
  crearResena(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(this.base, body);
  }
  moderarResena(id: number, estado: string): Observable<unknown> {
    return this.http.patch(`${this.base}/${id}/estado`, { estado });
  }

  // Votos de utilidad
  votar(id: number, esUtil: boolean): Observable<{ utiles: number; no_utiles: number }> {
    return this.http.post<{ utiles: number; no_utiles: number }>(
      `${this.base}/${id}/voto`, { esUtil });
  }

  // Reportes de reseña
  reportar(id: number, motivo: string, comentario: string): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/${id}/reporte`, { motivo, comentario });
  }
  reportes(estado?: string): Observable<ReporteResenaRow[]> {
    return this.http.get<ReporteResenaRow[]>(`${this.base}/reportes`,
      { params: estado ? { estado } : {} });
  }
  resolverReporte(id: number, estado: string): Observable<unknown> {
    return this.http.patch(`${this.base}/reportes/${id}/estado`, { estado });
  }

  // Preguntas y respuestas
  preguntas(estado?: string): Observable<PreguntaProductoRow[]> {
    return this.http.get<PreguntaProductoRow[]>(`${this.base}/preguntas`,
      { params: estado ? { estado } : {} });
  }
  preguntasProducto(productoId: number): Observable<PreguntaProductoRow[]> {
    return this.http.get<PreguntaProductoRow[]>(`${this.base}/preguntas/producto/${productoId}`);
  }
  crearPregunta(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/preguntas`, body);
  }
  responderPregunta(id: number, respuesta: string): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/preguntas/${id}/respuestas`, { respuesta });
  }
  moderarPregunta(id: number, estado: string): Observable<unknown> {
    return this.http.patch(`${this.base}/preguntas/${id}/estado`, { estado });
  }

  // Referencias
  productosRef(): Observable<ProductoResenaRef[]> {
    return this.http.get<ProductoResenaRef[]>(`${this.base}/productos-ref`);
  }
  /** Productos comprados por el cliente: los únicos reseñables. */
  productosComprados(): Observable<ProductoResenaRef[]> {
    return this.http.get<ProductoResenaRef[]>(`${this.base}/productos-comprados`);
  }
}
