import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ItemOrdenReq, ItemRecepcionReq, OrdenCompraRow, OrdenCompraDetalle,
  FacturaCompra, CuentaPorPagarRow, ProveedorFichaRow, ProductoProveedorRow,
  ProductoCompraRef, Pagina
} from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class ComprasService {
  private readonly base = `${environment.apiUrl}/api/compras`;

  constructor(private http: HttpClient) {}

  emitirOrden(body: {
    proveedorId: number; bodegaId: number; monedaId?: number | null;
    fechaEntregaEsperada?: string | null; observacion?: string; items: ItemOrdenReq[];
  }): Observable<OrdenCompraDetalle> {
    return this.http.post<OrdenCompraDetalle>(`${this.base}/ordenes`, body);
  }

  /**
   * Órdenes de compra PAGINADAS EN EL SERVIDOR (antes: las 134.588, 27,18 MB).
   *
   * `facturables` es el predicado que `facturas-compra` aplicaba en el
   * navegador —«recibida y sin factura»— y que hoy casa con 4 órdenes de
   * 134.588: sobre una página de 25 ordenada por `id DESC` no aparecería
   * NINGUNA, y el selector saldría vacío sin dar error.
   */
  ordenes(opts: {
    page?: number; size?: number; facturables?: boolean; recibibles?: boolean;
    incluirOrdenId?: number | null; conTotal?: boolean;
  } = {}): Observable<Pagina<OrdenCompraRow>> {
    let params = new HttpParams();
    if (opts.page != null)    { params = params.set('page', opts.page); }
    if (opts.size != null)    { params = params.set('size', opts.size); }
    if (opts.facturables)     { params = params.set('facturables', true); }
    if (opts.recibibles)      { params = params.set('recibibles', true); }
    if (opts.incluirOrdenId != null) {
      params = params.set('incluirOrdenId', opts.incluirOrdenId);
    }
    if (opts.conTotal === false) { params = params.set('conTotal', false); }
    return this.http.get<Pagina<OrdenCompraRow>>(`${this.base}/ordenes`, { params });
  }

  /** CU-O-12: aprobar orden emitida (enviada → confirmada). Solo GERENTE/ADMIN. */
  aprobarOrden(id: number):
      Observable<{ id: number; numero: string; estadoAnterior: string; estado: string }> {
    return this.http.post<any>(`${this.base}/ordenes/${id}/aprobar`, {});
  }
  orden(id: number): Observable<OrdenCompraDetalle> {
    return this.http.get<OrdenCompraDetalle>(`${this.base}/ordenes/${id}`);
  }

  registrarRecepcion(ordenId: number, body: { observacion?: string; items: ItemRecepcionReq[] }):
      Observable<{ id: number; numero: string; ordenCompraId: number; estadoOrden: string }> {
    return this.http.post<any>(`${this.base}/ordenes/${ordenId}/recepciones`, body);
  }

  /** El número de factura lo genera el backend; no se envía. */
  registrarFactura(ordenId: number): Observable<FacturaCompra> {
    return this.http.post<FacturaCompra>(`${this.base}/ordenes/${ordenId}/facturas`, {});
  }
  factura(id: number): Observable<FacturaCompra> {
    return this.http.get<FacturaCompra>(`${this.base}/facturas/${id}`);
  }
  facturaPdf(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/facturas/${id}/pdf`, { responseType: 'blob' });
  }

  // ── Devolución a proveedor (script 45) ────────────────────────────────
  /**
   * Pool de ítems defectuosos PAGINADO EN EL SERVIDOR (antes: los 27.831,
   * 12,66 MB). El filtro por `estado` ya se resolvía en SQL y no cambia.
   */
  itemsDefectuosos(opts: {
    estado?: string | null; page?: number; size?: number; conTotal?: boolean;
  } = {}): Observable<Pagina<any>> {
    let params = new HttpParams();
    if (opts.estado)       { params = params.set('estado', opts.estado); }
    if (opts.page != null) { params = params.set('page', opts.page); }
    if (opts.size != null) { params = params.set('size', opts.size); }
    if (opts.conTotal === false) { params = params.set('conTotal', false); }
    return this.http.get<Pagina<any>>(`${this.base}/items-defectuosos`, { params });
  }
  recepcionDetallesRef(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/recepciones/detalles-ref`);
  }
  /** BODEGA: unidades ya recibidas que resultaron defectuosas (salen de stock). */
  marcarDefectuoso(recepcionDetalleId: number, body: { cantidad: number; nota?: string }):
      Observable<any> {
    return this.http.post<any>(`${this.base}/recepciones/detalles/${recepcionDetalleId}/defectuoso`, body);
  }
  asignarProveedorItem(itemId: number, proveedorId: number): Observable<any> {
    return this.http.patch<any>(`${this.base}/items-defectuosos/${itemId}/proveedor`, { proveedorId });
  }
  crearDevolucionProveedor(body: { proveedorId: number; itemIds: number[]; observacion?: string }):
      Observable<any> {
    return this.http.post<any>(`${this.base}/devoluciones-proveedor`, body);
  }
  devolucionesProveedor(estado?: string | null): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/devoluciones-proveedor`,
      { params: estado ? { estado } : {} });
  }
  devolucionProveedor(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/devoluciones-proveedor/${id}`);
  }
  enviarDevolucionProveedor(id: number, nota?: string): Observable<any> {
    return this.http.post<any>(`${this.base}/devoluciones-proveedor/${id}/enviar`, { nota });
  }
  resolverDevolucionProveedor(id: number, body: { tipoResolucion: string; nota?: string }):
      Observable<any> {
    return this.http.post<any>(`${this.base}/devoluciones-proveedor/${id}/resolver`, body);
  }
  cerrarDevolucionProveedor(id: number, nota?: string): Observable<any> {
    return this.http.post<any>(`${this.base}/devoluciones-proveedor/${id}/cerrar`, { nota });
  }

  // ── Catálogo proveedor-producto (OTD-COM-10, script 51) ───────────────
  proveedores(): Observable<ProveedorFichaRow[]> {
    return this.http.get<ProveedorFichaRow[]>(`${this.base}/proveedores`);
  }
  productosDeProveedor(proveedorId: number): Observable<ProductoProveedorRow[]> {
    return this.http.get<ProductoProveedorRow[]>(`${this.base}/proveedores/${proveedorId}/productos`);
  }
  asociarProducto(proveedorId: number, body: unknown): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.base}/proveedores/${proveedorId}/productos`, body);
  }
  editarProductoProveedor(id: number, body: unknown): Observable<unknown> {
    return this.http.put(`${this.base}/productos-proveedor/${id}`, body);
  }
  activarProductoProveedor(id: number, activo: boolean): Observable<unknown> {
    return this.http.patch(`${this.base}/productos-proveedor/${id}/activo`, { activo });
  }
  /** Buscador de producto en servidor (nunca la lista completa). */
  productosRef(q: string): Observable<ProductoCompraRef[]> {
    return this.http.get<ProductoCompraRef[]>(`${this.base}/productos-ref`, { params: { q } });
  }

  /** Cuentas por pagar PAGINADAS (antes: las 134.558, 26,11 MB). */
  cuentasPorPagar(opts: { page?: number; size?: number; conTotal?: boolean } = {}):
      Observable<Pagina<CuentaPorPagarRow>> {
    let params = new HttpParams();
    if (opts.page != null) { params = params.set('page', opts.page); }
    if (opts.size != null) { params = params.set('size', opts.size); }
    if (opts.conTotal === false) { params = params.set('conTotal', false); }
    return this.http.get<Pagina<CuentaPorPagarRow>>(`${this.base}/cuentas-por-pagar`, { params });
  }
  registrarPago(cuentaId: number, body: { monto: number; metodoPagoId: number; referencia?: string }):
      Observable<{ pagoId: number; saldoPendiente: number; estadoCuenta: string }> {
    return this.http.post<any>(`${this.base}/cuentas-por-pagar/${cuentaId}/pagos`, body);
  }
}
