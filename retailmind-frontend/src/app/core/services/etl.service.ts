import { Injectable } from '@angular/core';
import { HttpClient, HttpEvent, HttpRequest } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { EtlResponse, EstadoTabla, CargaHistorial } from '../models/etl.model';

@Injectable({ providedIn: 'root' })
export class EtlService {
  private readonly base = `${environment.apiUrl}/api/etl`;

  constructor(private http: HttpClient) {}

  uploadCsv(file: File): Observable<HttpEvent<EtlResponse>> {
    const form = new FormData();
    form.append('file', file);
    const req = new HttpRequest('POST', `${this.base}/upload-csv`, form, {
      reportProgress: true
    });
    return this.http.request<EtlResponse>(req);
  }

  cargarStaging(): Observable<EtlResponse> {
    return this.http.post<EtlResponse>(`${this.base}/cargar-staging`, {});
  }

  ejecutarEtl(): Observable<EtlResponse> {
    return this.http.post<EtlResponse>(`${this.base}/ejecutar-etl`, {});
  }

  ejecutarCompleto(): Observable<EtlResponse> {
    return this.http.post<EtlResponse>(`${this.base}/ejecutar-completo`, {});
  }

  getHistorial(): Observable<CargaHistorial[]> {
    return this.http.get<CargaHistorial[]>(`${this.base}/historial`);
  }

  getEstadoTablas(): Observable<EstadoTabla[]> {
    return this.http.get<EstadoTabla[]>(`${this.base}/estado-tablas`);
  }
}
