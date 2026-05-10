import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Conversion, ConversionResumen } from '../models/conversion.model';
import { PageModel } from '../models/page.model';

@Injectable({ providedIn: 'root' })
export class ConversionService {
  private readonly base = `${environment.apiUrl}/api/conversiones`;

  constructor(private http: HttpClient) {}

  getAll(page: number, size: number): Observable<PageModel<Conversion>> {
    const params = new HttpParams()
      .set('page', page)
      .set('size', size);
    return this.http.get<PageModel<Conversion>>(this.base, { params });
  }

  getById(id: number): Observable<Conversion> {
    return this.http.get<Conversion>(`${this.base}/${id}`);
  }

  getResumen(): Observable<ConversionResumen> {
    return this.http.get<ConversionResumen>(`${this.base}/resumen`);
  }
}
