import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CategoriaTicketRow, CategoriaTicketRef, TicketRow, TicketDetalle,
  UsuarioSoporteRef, PedidoSoporteRef, FaqRow, FaqActiva
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
  tickets(): Observable<TicketRow[]> { return this.http.get<TicketRow[]>(`${this.base}/tickets`); }
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
