import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-funnel',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatButtonModule, MatIconModule,
    MatSelectModule, MatFormFieldModule, MatTableModule, MatChipsModule,
    MatPaginatorModule, MatProgressBarModule, MatDialogModule
  ],
  templateUrl: './funnel.component.html',
  styleUrl: './funnel.component.scss'
})
export class FunnelComponent implements OnInit {

  // Resumen
  resumen: any = {};
  resumenTime = 0;

  // Filtros
  semana: number | null = null;
  canal: string | null = null;
  semanasDisponibles: number[] = [];

  // Tabla
  sesiones: any[] = [];
  totalElements = 0;
  page = 0;
  size = 50;
  tableTime = 0;
  loading = false;

  // Detalle
  detalleSesion: any[] = [];
  detalleSessionId = '';
  showDetalle = false;

  constructor(private http: HttpClient, private dialog: MatDialog) {}

  ngOnInit(): void {
    this.loadSemanasDisponibles();
    this.loadResumen();
    this.loadSesiones();
  }

  loadSemanasDisponibles(): void {
    this.http.get<number[]>(`${environment.apiUrl}/api/funnel/semanas-disponibles`).subscribe({
      next: (data) => this.semanasDisponibles = data,
      error: () => this.semanasDisponibles = []
    });
  }

  loadResumen(): void {
    const start = performance.now();
    let url = `${environment.apiUrl}/api/funnel/resumen`;
    const params: string[] = [];
    if (this.semana) params.push(`semana=${this.semana}`);
    if (this.canal) params.push(`canal=${this.canal}`);
    if (params.length) url += '?' + params.join('&');

    this.http.get<any>(url).subscribe({
      next: (data) => { this.resumen = data; this.resumenTime = Math.round(performance.now() - start); },
      error: () => { this.resumen = {}; }
    });
  }

  loadSesiones(): void {
    this.loading = true;
    const start = performance.now();
    let url = `${environment.apiUrl}/api/funnel/sesiones?page=${this.page}&size=${this.size}`;
    if (this.semana) url += `&semana=${this.semana}`;
    if (this.canal) url += `&canal=${this.canal}`;

    this.http.get<any>(url).subscribe({
      next: (data) => {
        this.sesiones = data.content;
        this.totalElements = data.totalElements;
        this.tableTime = Math.round(performance.now() - start);
        this.loading = false;
      },
      error: () => { this.sesiones = []; this.loading = false; }
    });
  }

  aplicarFiltros(): void {
    this.page = 0;
    this.loadResumen();
    this.loadSesiones();
  }

  limpiarFiltros(): void {
    this.semana = null;
    this.canal = null;
    this.aplicarFiltros();
  }

  onPageChange(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.loadSesiones();
  }

  verDetalle(sessionId: string): void {
    this.detalleSessionId = sessionId;
    this.http.get<any[]>(`${environment.apiUrl}/api/funnel/sesion/${sessionId}`).subscribe({
      next: (data) => { this.detalleSesion = data; this.showDetalle = true; },
      error: () => { this.detalleSesion = []; }
    });
  }

  cerrarDetalle(): void { this.showDetalle = false; }

  getEtapaClass(etapa: string): string {
    switch (etapa) {
      case 'COMPRA': return 'etapa-compra';
      case 'ABANDONO': return 'etapa-abandono';
      case 'CARRITO': return 'etapa-carrito';
      case 'WISHLIST': return 'etapa-wishlist';
      case 'CLICK': return 'etapa-click';
      default: return 'etapa-vista';
    }
  }

  getPercent(value: number): number {
    const total = this.resumen.totalSesiones || 1;
    return Math.round((value / total) * 100);
  }
}
