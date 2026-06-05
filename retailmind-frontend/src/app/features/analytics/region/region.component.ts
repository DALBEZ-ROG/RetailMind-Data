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
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { environment } from '../../../../environments/environment';
import { RegionDialogComponent } from './region-dialog.component';

@Component({
  selector: 'app-region',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatSelectModule, MatTableModule,
    MatProgressSpinnerModule, MatDialogModule, MatChipsModule, MatTooltipModule
  ],
  templateUrl: './region.component.html',
  styleUrl: './region.component.scss'
})
export class RegionComponent implements OnInit {

  resumen: any[] = [];
  loading = false;
  queryMs = 0;

  semanaFiltro: number | null = null;
  canalFiltro  = '';

  semanas = Array.from({ length: 52 }, (_, i) => i + 1);
  canales = ['mobile', 'web', 'app'];

  displayedColumns = [
    'regionNombre', 'totalSesiones', 'totalUsuarios',
    'totalConversiones', 'tasaConversion', 'revenueTotal', 'precioPromedio'
  ];

  private readonly base = `${environment.apiUrl}/api/analytics/region`;

  constructor(private http: HttpClient, private dialog: MatDialog) {}

  ngOnInit(): void { this.cargar(); }

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

  limpiarFiltros(): void {
    this.semanaFiltro = null;
    this.canalFiltro  = '';
    this.cargar();
  }

  get maxSesiones(): number {
    return Math.max(...this.resumen.map(r => r.totalSesiones), 1);
  }

  getPorcentajeBarra(sesiones: number): number {
    return Math.round((sesiones / this.maxSesiones) * 100);
  }

  getColorConversion(tasa: number): string {
    if (tasa >= 4)  return '#2e7d32';
    if (tasa >= 2)  return '#f57c00';
    return '#c62828';
  }

  getColorAbandono(tasa: number): string {
    return tasa > 15 ? '#c62828' : '#388e3c';
  }

  abrirDialog(region: any): void {
    this.http.get<any[]>(`${this.base}/top-productos/${encodeURIComponent(region.regionNombre)}`)
      .subscribe(productos => {
        this.dialog.open(RegionDialogComponent, {
          width: '480px',
          data: { region, productos }
        });
      });
  }

  getMaxMetrica(campo: string): number {
    return Math.max(...this.resumen.map(r => r[campo] ?? 0), 1);
  }

  isMax(row: any, campo: string): boolean {
    return row[campo] === Math.max(...this.resumen.map(r => r[campo] ?? 0));
  }

  isMin(row: any, campo: string): boolean {
    const vals = this.resumen.map(r => r[campo] ?? 0).filter(v => v > 0);
    return vals.length > 0 && row[campo] === Math.min(...vals);
  }
}
