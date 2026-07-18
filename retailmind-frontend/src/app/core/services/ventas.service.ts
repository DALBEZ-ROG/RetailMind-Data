import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { HttpParams } from '@angular/common/http';
import {
  ItemPedidoReq, PedidoVentaRow, PedidoVentaDetalle,
  FacturaVenta, PaginaFacturasVenta, PagoClienteRes, EnvioDetalle,
  SeguimientoRow, PreparacionRow, DetalleLogistico, NovedadesEnvioRes
} from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class VentasService {
  private readonly base = `${environment.apiUrl}/api/ventas`;

  constructor(private http: HttpClient) {}

  crearPedido(body: { clienteId: number; bodegaId: number; canal: string; items: ItemPedidoReq[] }):
      Observable<PedidoVentaDetalle> {
    return this.http.post<PedidoVentaDetalle>(`${this.base}/pedidos`, body);
  }
  pedidos(): Observable<PedidoVentaRow[]> { return this.http.get<PedidoVentaRow[]>(`${this.base}/pedidos`); }
  pedido(id: number): Observable<PedidoVentaDetalle> {
    return this.http.get<PedidoVentaDetalle>(`${this.base}/pedidos/${id}`);
  }
  crearNota(pedidoId: number, nota: string, esVisibleCliente: boolean): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/pedidos/${pedidoId}/notas`,
      { nota, esVisibleCliente });
  }

  /** Cobro del pedido: compuerta previa a facturar/despachar (monto null = saldo). */
  registrarPago(pedidoId: number, body: {
    metodoPagoId: number; monto?: number | null; referencia?: string;
  }): Observable<PagoClienteRes> {
    return this.http.post<PagoClienteRes>(`${this.base}/pedidos/${pedidoId}/pagos`, body);
  }

  emitirFactura(pedidoId: number): Observable<FacturaVenta> {
    return this.http.post<FacturaVenta>(`${this.base}/pedidos/${pedidoId}/factura`, {});
  }
  facturas(q: string, page: number, size: number): Observable<PaginaFacturasVenta> {
    const params = new HttpParams().set('q', q).set('page', page).set('size', size);
    return this.http.get<PaginaFacturasVenta>(`${this.base}/facturas`, { params });
  }
  factura(id: number): Observable<FacturaVenta> {
    return this.http.get<FacturaVenta>(`${this.base}/facturas/${id}`);
  }
  facturaPdf(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/facturas/${id}/pdf`, { responseType: 'blob' });
  }

  // ── Preparación por BODEGA (cola de picking, script 39) ──────────────
  colaPreparacion(): Observable<PreparacionRow[]> {
    return this.http.get<PreparacionRow[]>(`${this.base}/preparacion`);
  }
  detallePreparacion(pedidoId: number): Observable<DetalleLogistico> {
    return this.http.get<DetalleLogistico>(`${this.base}/preparacion/${pedidoId}`);
  }
  iniciarPreparacion(pedidoId: number): Observable<DetalleLogistico> {
    return this.http.post<DetalleLogistico>(`${this.base}/pedidos/${pedidoId}/preparacion`, {});
  }
  marcarPreparado(pedidoId: number): Observable<DetalleLogistico> {
    return this.http.post<DetalleLogistico>(`${this.base}/pedidos/${pedidoId}/preparado`, {});
  }

  /** Detalle logístico para la pantalla de despacho (ítems + dirección + asignación). */
  detalleDespacho(pedidoId: number): Observable<DetalleLogistico> {
    return this.http.get<DetalleLogistico>(`${this.base}/despacho/${pedidoId}`);
  }
  /** transportista/método opcionales: sin ellos se despacha con los ASIGNADOS. */
  despachar(pedidoId: number, body: {
    transportistaId?: number | null; metodoEnvioId?: number | null;
    bodegaId?: number | null; observacion?: string;
  }): Observable<EnvioDetalle> {
    return this.http.post<EnvioDetalle>(`${this.base}/pedidos/${pedidoId}/despacho`, body);
  }
  /** Marca la entrega del pedido despachado (cierra la logística). */
  entregar(pedidoId: number, observacion?: string): Observable<PedidoVentaDetalle> {
    return this.http.post<PedidoVentaDetalle>(
      `${this.base}/pedidos/${pedidoId}/entrega`, { observacion });
  }
  envio(id: number): Observable<EnvioDetalle> {
    return this.http.get<EnvioDetalle>(`${this.base}/envios/${id}`);
  }
  seguimiento(envioId: number): Observable<SeguimientoRow[]> {
    return this.http.get<SeguimientoRow[]>(`${this.base}/envios/${envioId}/seguimiento`);
  }

  // ── Novedades / incidencias de envío (script 44) ─────────────────────
  novedadesPedido(pedidoId: number): Observable<NovedadesEnvioRes> {
    return this.http.get<NovedadesEnvioRes>(`${this.base}/pedidos/${pedidoId}/novedades`);
  }
  /** Novedad sobre un envío en tránsito (queda 'fallido' hasta resolverla). */
  registrarNovedad(envioId: number, body: { tipo: string; descripcion?: string }):
      Observable<NovedadesEnvioRes> {
    return this.http.post<NovedadesEnvioRes>(`${this.base}/envios/${envioId}/novedades`, body);
  }
  /** Nuevo intento de entrega (máx. 3): el envío vuelve a tránsito. */
  reprogramarNovedad(novedadId: number, observacion?: string): Observable<NovedadesEnvioRes> {
    return this.http.post<NovedadesEnvioRes>(
      `${this.base}/novedades/${novedadId}/reprogramar`, { observacion });
  }
  /** Devuelve el envío al almacén; el pedido queda 'no_entregado'. */
  devolverAlmacen(novedadId: number, observacion?: string): Observable<NovedadesEnvioRes> {
    return this.http.post<NovedadesEnvioRes>(
      `${this.base}/novedades/${novedadId}/devolver-almacen`, { observacion });
  }

  // Devoluciones (RMA / logística inversa): core/services/devoluciones.service.ts
}
