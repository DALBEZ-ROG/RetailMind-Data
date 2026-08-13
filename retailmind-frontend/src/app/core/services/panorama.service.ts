import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { Panorama } from '../models/panorama.model';
import { environment } from '../../../environments/environment';

/**
 * Panorama del Negocio. UNA petición trae los 6 indicadores, los 6 bloques y el
 * estado del almacén: comparten marca de agua y degradan a la vez.
 */
@Injectable({ providedIn: 'root' })
export class PanoramaService {

  // `environment.apiUrl` es SOLO el host: el `/api` lo pone cada servicio, como
  // los otros treinta y tantos de esta carpeta.
  private readonly base = `${environment.apiUrl}/api/panorama`;

  constructor(private http: HttpClient) {}

  obtener(): Observable<Panorama> {
    return this.http.get<Panorama>(this.base);
  }
}
