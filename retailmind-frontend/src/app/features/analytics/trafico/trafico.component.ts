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

const CANAL_ICONS: Record<string, string> = {
  mobile: 'phone_iphone',
  web:    'language',
  app:    'widgets'
};
const CANAL_COLORS: Record<string, string> = {
  mobile: '#1565c0',
  web:    '#2e7d32',
  app:    '#7b1fa2'
};

@Component({
  selector: 'app-trafico',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatSelectModule, MatTableModule,
    MatProgressSpinnerModule, MatChipsModule
  ],
  templateUrl: './trafico.component.html',
  styleUrl: './trafico.component.scss'
})
export class TraficoComponent implements OnInit {

  resumen:  any[] = [];
  embudo:   any[] = [];
  loading  = false;
  loadingEmbudo = false;
  queryMs  = 0;
  queryMsEmbudo = 0;

  semanaFiltro: number | null = null;
  semanas = Array.from({ length: 52 }, (_, i) => i + 1);

  displayedColumns = [
    'fuente','totalSesiones','totalUsuarios','totalConversiones',
    'tasaConversion','revenueTotal','tiempoPromedio','totalAbandonos'
  ];

  private readonly base = `${environment.apiUrl}/api/analytics/trafico`;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.cargar();
    this.cargarEmbudo();
  }

  cargar(): void {
    this.loading = true;
    const t0 = Date.now();
    let url = `${this.base}/resumen`;
    if (this.semanaFiltro) url += `?semana=${this.semanaFiltro}`;
    this.http.get<any[]>(url).subscribe({
      next: (data) => { this.resumen = data; this.queryMs = Date.now() - t0; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }

  cargarEmbudo(): void {
    this.loadingEmbudo = true;
    const t0 = Date.now();
    let url = `${this.base}/embudo-por-canal`;
    if (this.semanaFiltro) url += `?semana=${this.semanaFiltro}`;
    this.http.get<any[]>(url).subscribe({
      next: (data) => { this.embudo = data; this.queryMsEmbudo = Date.now() - t0; this.loadingEmbudo = false; },
      error: () => { this.loadingEmbudo = false; }
    });
  }

  aplicar(): void { this.cargar(); this.cargarEmbudo(); }
  limpiar(): void { this.semanaFiltro = null; this.aplicar(); }

  get totalSesionesGlobal(): number {
    return this.resumen.reduce((s, r) => s + (r.totalSesiones || 0), 0);
  }

  getPorcentaje(sesiones: number): number {
    return this.totalSesionesGlobal > 0
      ? Math.round((sesiones / this.totalSesionesGlobal) * 100) : 0;
  }

  getCanalIcon(canal: string): string {
    return CANAL_ICONS[canal?.toLowerCase()] || 'wifi';
  }

  getCanalColor(canal: string): string {
    return CANAL_COLORS[canal?.toLowerCase()] || '#1a237e';
  }

  getColorConversion(tasa: number): string {
    if (tasa >= 4)  return '#2e7d32';
    if (tasa >= 2)  return '#f57c00';
    return '#c62828';
  }

  formatRevenue(value: number): string {
    if (value >= 1000000) return '$' + (value / 1000000).toFixed(1) + 'M';
    if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'K';
    return '$' + value.toFixed(0);
  }

  getMaxEmbudo(canal: any): number {
    return Math.max(canal.vistas, canal.clicks, canal.carritos, canal.compras, canal.abandonos, 1);
  }

  getPctEmbudo(val: number, canal: any): number {
    return Math.round((val / this.getMaxEmbudo(canal)) * 100);
  }

  isMax(row: any, campo: string): boolean {
    return row[campo] === Math.max(...this.resumen.map(r => r[campo] ?? 0));
  }
}
