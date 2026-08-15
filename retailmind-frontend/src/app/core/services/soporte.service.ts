import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CategoriaTicketRow, CategoriaTicketRef, TicketRow, TicketDetalle,
  UsuarioSoporteRef, PedidoSoporteRef, ProductoTicketRef, FaqRow, FaqActiva, Pagina
} from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class SoporteService {
  private readonly base = `${environment.apiUrl}/api/soporte`;

  constructor(private http: HttpClient) {}

  // Categorías de ticket
  categorias(): Observable<CategoriaTicketRow[]> {
    return this.http.get<CategoriaTicketRow[]>(`${this.base}/categorias`);
  }
  categoriasRef(): Observable<CategoriaTicketRef[]> {
    return this.http.get<CategoriaTicketRef[]>(`${this.base}/categorias-ref`);
  }
  crearCategoria(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/categorias`, body);
  }
  editarCategoria(id: number, body: unknown): Observable<unknown> {
    return this.http.put(`${this.base}/categorias/${id}`, body);
  }
  activarCategoria(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/categorias/${id}/activo`, { activo });
  }

  // Tickets
  /**
   * Bandeja de tickets PAGINADA EN EL SERVIDOR (antes: los 179.851, 78,98 MB).
   *
   * Los CUATRO filtros —bandeja, estado, categoría, prioridad— viajan al
   * backend y se resuelven contra el conjunto completo. Aplicarlos aquí sobre
   * la página recibida daría resultados plausibles y falsos: «cerrado» vive al
   * final de un orden por urgencia y no aparece en la primera página nunca.
   */
  tickets(opts: {
    page?: number; size?: number; bandeja?: string; estado?: string;
    categoria?: string; prioridad?: string; conTotal?: boolean;
  } = {}): Observable<Pagina<TicketRow>> {
    let params = new HttpParams();
    if (opts.page != null)  { params = params.set('page', opts.page); }
    if (opts.size != null)  { params = params.set('size', opts.size); }
    if (opts.bandeja && opts.bandeja !== 'todos') { params = params.set('bandeja', opts.bandeja); }
    if (opts.estado)        { params = params.set('estado', opts.estado); }
    if (opts.categoria)     { params = params.set('categoria', opts.categoria); }
    if (opts.prioridad)     { params = params.set('prioridad', opts.prioridad); }
    if (opts.conTotal === false) { params = params.set('conTotal', false); }
    return this.http.get<Pagina<TicketRow>>(`${this.base}/tickets`, { params });
  }
  ticket(id: number): Observable<TicketDetalle> {
    return this.http.get<TicketDetalle>(`${this.base}/tickets/${id}`);
  }
  crearTicket(body: unknown): Observable<{ id: number; numero: string }> {
    return this.http.post<{ id: number; numero: string }>(`${this.base}/tickets`, body);
  }
  responder(id: number, mensaje: string, esInterno: boolean): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/tickets/${id}/mensajes`,
      { mensaje, esInterno });
  }
  cambiarEstado(id: number, estado: string): Observable<unknown> {
    return this.http.patch(`${this.base}/tickets/${id}/estado`, { estado });
  }
  asignar(id: number, usuarioId: number | null): Observable<unknown> {
    return this.http.patch(`${this.base}/tickets/${id}/asignar`, { usuarioId });
  }
  /** El agente toma el ticket (auto-asignación). ADMIN/SOPORTE. */
  tomar(id: number): Observable<{ success: boolean; estado: string }> {
    return this.http.post<{ success: boolean; estado: string }>(
      `${this.base}/tickets/${id}/tomar`, {});
  }
  /** Ajuste manual de prioridad (nace automática por categoría). ADMIN/SOPORTE. */
  cambiarPrioridad(id: number, prioridad: string): Observable<unknown> {
    return this.http.patch(`${this.base}/tickets/${id}/prioridad`, { prioridad });
  }
  usuariosRef(): Observable<UsuarioSoporteRef[]> {
    return this.http.get<UsuarioSoporteRef[]>(`${this.base}/usuarios-ref`);
  }
  pedidosRef(clienteId?: number | null): Observable<PedidoSoporteRef[]> {
    return this.http.get<PedidoSoporteRef[]>(`${this.base}/pedidos-ref`,
      { params: clienteId ? { clienteId } : {} });
  }
  /** Buscador del producto del reclamo (script 50): búsqueda en servidor. */
  productosRef(q: string): Observable<ProductoTicketRef[]> {
    return this.http.get<ProductoTicketRef[]>(`${this.base}/productos-ref`, { params: { q } });
  }

  // FAQ
  faqs(): Observable<FaqRow[]> { return this.http.get<FaqRow[]>(`${this.base}/faqs`); }
  faqsActivas(): Observable<FaqActiva[]> {
    return this.http.get<FaqActiva[]>(`${this.base}/faqs-activas`);
  }
  crearFaq(body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/faqs`, body);
  }
  editarFaq(id: number, body: unknown): Observable<unknown> {
    return this.http.put(`${this.base}/faqs/${id}`, body);
  }
  activarFaq(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/faqs/${id}/activo`, { activo });
  }
}
