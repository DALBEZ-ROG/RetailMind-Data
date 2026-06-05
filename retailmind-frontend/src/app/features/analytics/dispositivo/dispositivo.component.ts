import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-dispositivo',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatSelectModule, MatTableModule,
    MatProgressSpinnerModule, MatChipsModule
  ],
  templateUrl: './dispositivo.component.html',
  styleUrl: './dispositivo.component.scss'
})
export class DispositivoComponent implements OnInit {

  resumen:   any[] = [];
  tendencia: any[] = [];
  loading = false;
  loadingTendencia = false;
  queryMs = 0;
  queryMsTendencia = 0;

  semanaFiltro: number | null = null;
  canalFiltro  = '';
  semanaInicio: number | null = null;
  semanaFin:    number | null = null;

  semanas = Array.from({ length: 52 }, (_, i) => i + 1);
  canales = ['mobile', 'web', 'app'];

  dispositivosUnicos: string[] = [];

  private readonly base = `${environment.apiUrl}/api/analytics/dispositivo`;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.cargar();
    this.cargarTendencia();
  }

  cargar(): void {
    this.loading = true;
    const t0 = Date.now();
    let url = `${this.base}/resumen?`;
    if (this.semanaFiltro) url += `semana=${this.semanaFiltro}&`;
    if (this.canalFiltro)  url += `canal=${this.canalFiltro}`;

    this.http.get<any[]>(url).subscribe({
      next: (data) => {
        this.resumen = data;
        this.queryMs = Date.now() - t0;
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  cargarTendencia(): void {
    this.loadingTendencia = true;
    const t0 = Date.now();
    let url = `${this.base}/tendencia?`;
    if (this.semanaInicio) url += `semana_inicio=${this.semanaInicio}&`;
    if (this.semanaFin)    url += `semana_fin=${this.semanaFin}`;

    this.http.get<any[]>(url).subscribe({
      next: (data) => {
        this.tendencia = data;
        this.dispositivosUnicos = [...new Set(data.map(d => d.dispositivoNombre))];
        this.queryMsTendencia = Date.now() - t0;
        this.loadingTendencia = false;
      },
      error: () => { this.loadingTendencia = false; }
    });
  }

  limpiarFiltros(): void {
    this.semanaFiltro = null;
    this.canalFiltro  = '';
    this.cargar();
  }

  get totalSesiones(): number {
    return this.resumen.reduce((s, r) => s + (r.totalSesiones || 0), 0);
  }

  getPorcentaje(sesiones: number): number {
    return this.totalSesiones > 0 ? Math.round((sesiones / this.totalSesiones) * 100) : 0;
  }

  getMaxConversion(): number {
    return Math.max(...this.resumen.map(r => r.tasaConversion), 1);
  }

  getColorConversion(tasa: number): string {
    if (tasa >= 4)  return '#2e7d32';
    if (tasa >= 2)  return '#f57c00';
    return '#c62828';
  }

  getDispositivoIcon(nombre: string): string {
    const n = (nombre || '').toLowerCase();
    if (n.includes('mobile') || n.includes('smartphone')) return 'smartphone';
    if (n.includes('desktop') || n.includes('computer'))  return 'computer';
    if (n.includes('tablet'))   return 'tablet';
    if (n.includes('app'))      return 'apps';
    return 'devices';
  }

  getSesionesPorSemanaDispositivo(semana: number, dispositivo: string): number {
    const found = this.tendencia.find(
      d => d.semana === semana && d.dispositivoNombre === dispositivo
    );
    return found ? found.sesiones : 0;
  }

  get semanasUnicas(): number[] {
    return [...new Set(this.tendencia.map(d => d.semana))].sort((a, b) => a - b);
  }
}
