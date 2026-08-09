import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DashboardResumen, DashboardSeries } from '../models/dashboard.model';

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly base = `${environment.apiUrl}/api/dashboard`;

  constructor(private http: HttpClient) {}

  getResumen(): Observable<DashboardResumen> {
    return this.http.get<DashboardResumen>(`${this.base}/resumen`);
  }

  /**
   * Series y desgloses de los gráficos. Va en una llamada APARTE de
   * `getResumen()` y las dos se lanzan EN PARALELO: encadenarlas habría sumado
   * sus latencias, y el resumen ya tarda ~470 ms por sí solo.
   */
  getSeries(): Observable<DashboardSeries> {
    return this.http.get<DashboardSeries>(`${this.base}/series`);
  }
}
