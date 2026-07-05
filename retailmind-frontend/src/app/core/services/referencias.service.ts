import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ProveedorRef, BodegaRef, ClienteRef, CatalogoRef, VarianteRef, StockRow
} from '../models/operativo.model';

@Injectable({ providedIn: 'root' })
export class ReferenciasService {
  private readonly base = `${environment.apiUrl}/api/referencias`;

  constructor(private http: HttpClient) {}

  proveedores(): Observable<ProveedorRef[]>     { return this.http.get<ProveedorRef[]>(`${this.base}/proveedores`); }
  bodegas(): Observable<BodegaRef[]>            { return this.http.get<BodegaRef[]>(`${this.base}/bodegas`); }
  clientes(): Observable<ClienteRef[]>          { return this.http.get<ClienteRef[]>(`${this.base}/clientes`); }
  transportistas(): Observable<CatalogoRef[]>   { return this.http.get<CatalogoRef[]>(`${this.base}/transportistas`); }
  metodosEnvio(): Observable<CatalogoRef[]>     { return this.http.get<CatalogoRef[]>(`${this.base}/metodos-envio`); }
  metodosPago(): Observable<CatalogoRef[]>      { return this.http.get<CatalogoRef[]>(`${this.base}/metodos-pago`); }
  motivosDevolucion(): Observable<CatalogoRef[]> { return this.http.get<CatalogoRef[]>(`${this.base}/motivos-devolucion`); }
  variantes(): Observable<VarianteRef[]>        { return this.http.get<VarianteRef[]>(`${this.base}/variantes`); }

  stock(varianteId?: number, bodegaId?: number): Observable<StockRow[]> {
    let params = new HttpParams();
    if (varianteId != null) params = params.set('varianteId', varianteId);
    if (bodegaId != null)   params = params.set('bodegaId', bodegaId);
    return this.http.get<StockRow[]>(`${this.base}/stock`, { params });
  }
}
