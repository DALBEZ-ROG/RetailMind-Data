import {
  Component,
  OnInit,
  OnDestroy,
  AfterViewInit,
  ViewChild,
  ElementRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  Chart,
  BarController, BarElement,
  DoughnutController, ArcElement,
  LineController, LineElement, PointElement,
  CategoryScale, LinearScale,
  Tooltip, Legend, Filler
} from 'chart.js';

import { DashboardService } from '../../../core/services/dashboard.service';
import { SesionService }    from '../../../core/services/sesion.service';
import { DashboardResumen } from '../../../core/models/dashboard.model';
import { Sesion }           from '../../../core/models/sesion.model';
import { environment }      from '../../../../environments/environment';

// Registrar componentes de Chart.js
Chart.register(
  BarController, BarElement,
  DoughnutController, ArcElement,
  LineController, LineElement, PointElement,
  CategoryScale, LinearScale,
  Tooltip, Legend, Filler
);

interface KpiCard {
  title: string;
  value: string;
  icon: string;
  color: string;
  tooltip: string;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatIconModule,
    MatTableModule,
    MatPaginatorModule,
    MatSnackBarModule,
    MatButtonModule,
    MatChipsModule,
    MatTooltipModule
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit, AfterViewInit, OnDestroy {

  @ViewChild('barCanvas')    barCanvas!:    ElementRef<HTMLCanvasElement>;
  @ViewChild('donutCanvas')  donutCanvas!:  ElementRef<HTMLCanvasElement>;
  @ViewChild('lineCanvas')   lineCanvas!:   ElementRef<HTMLCanvasElement>;
  @ViewChild(MatPaginator)   paginator!:    MatPaginator;

  loading       = true;
  resumen!:       DashboardResumen;
  kpiCards:       KpiCard[] = [];

  // Rendimiento
  responseTimeMs:    number | null = null;
  lastUpdated:       Date | null   = null;
  refreshingViews    = false;
  hasError           = false;
  promedioEventos    = '0.0';

  get currentUser(): string {
    const user = this.dashboardService as any;
    // Get from auth service via localStorage
    const raw = localStorage.getItem('rm_user');
    if (raw) {
      const u = JSON.parse(raw);
      return u.nombre || u.username;
    }
    return '';
  }

  // Tabla de sesiones recientes
  sesiones:       Sesion[] = [];
  totalSesiones   = 0;
  pageSize        = 20;
  pageIndex       = 0;
  loadingTable    = false;
  displayedCols   = ['sessionId', 'usuario', 'timestampUtc', 'canal', 'interactionCount'];

  private barChart?:   Chart;
  private donutChart?: Chart;
  private lineChart?:  Chart;

  private readonly COLORS = [
    '#3f51b5', '#e91e63', '#00bcd4', '#ff9800',
    '#4caf50', '#9c27b0', '#f44336', '#2196f3'
  ];

  constructor(
    private dashboardService: DashboardService,
    private sesionService:    SesionService,
    private snackBar:         MatSnackBar,
    private http:             HttpClient
  ) {}

  ngOnInit(): void {
    this.loadDashboard();
    this.loadSesiones(0, this.pageSize);
  }

  ngAfterViewInit(): void {}

  ngOnDestroy(): void {
    this.barChart?.destroy();
    this.donutChart?.destroy();
    this.lineChart?.destroy();
  }

  // ── Carga principal ────────────────────────────────────────────────────────

  loadDashboard(): void {
    this.loading = true;
    this.hasError = false;
    const start = Date.now();

    this.dashboardService.getResumen().subscribe({
      next: data => {
        this.responseTimeMs = Date.now() - start;
        this.lastUpdated    = new Date();
        this.resumen        = data;
        this.buildKpiCards(data);
        this.loading = false;
        setTimeout(() => this.buildCharts(data), 0);
      },
      error: () => {
        this.responseTimeMs = Date.now() - start;
        this.loading = false;
        this.hasError = true;
        this.snackBar.open(
          'Error al cargar el dashboard. Verifica que el backend este corriendo.',
          'Cerrar',
          { duration: 5000, panelClass: 'snack-error' }
        );
      }
    });
  }

  loadSesiones(page: number, size: number): void {
    this.loadingTable = true;
    this.sesionService.getAll(page, size).subscribe({
      next: result => {
        this.sesiones      = result.content;
        this.totalSesiones = result.totalElements;
        this.pageIndex     = result.number;
        this.loadingTable  = false;
      },
      error: () => { this.loadingTable = false; }
    });
  }

  onPageChange(event: PageEvent): void {
    this.loadSesiones(event.pageIndex, event.pageSize);
  }

  // ── Refrescar vistas materializadas ────────────────────────────────────────

  refrescarVistas(): void {
    this.refreshingViews = true;
    this.http.post<{success: boolean; mensaje: string; duracionMs: number}>(
      `${environment.apiUrl}/api/dashboard/refrescar-vistas`, {}
    ).subscribe({
      next: res => {
        this.refreshingViews = false;
        this.snackBar.open(
          res.mensaje + ` (${res.duracionMs}ms)`,
          'OK',
          { duration: 4000 }
        );
        // Recargar dashboard con datos frescos
        this.loadDashboard();
      },
      error: () => {
        this.refreshingViews = false;
        this.snackBar.open('Error al refrescar vistas.', 'Cerrar', { duration: 3000 });
      }
    });
  }

  // ── KPI Cards ──────────────────────────────────────────────────────────────

  private buildKpiCards(d: DashboardResumen): void {
    const totalEventos = d.totalEventos ?? 0;
    const semanasCargadas = d.semanasCargadas ?? 0;

    this.promedioEventos = d.totalSesiones > 0
      ? (totalEventos / d.totalSesiones).toFixed(1)
      : '0.0';

    this.kpiCards = [
      { title: 'Total Sesiones',     value: d.totalSesiones.toLocaleString(),     icon: 'timeline',       color: '#3f51b5', tooltip: 'Total de sesiones registradas en el sistema' },
      { title: 'Total Usuarios',     value: d.totalUsuarios.toLocaleString(),     icon: 'people',         color: '#00bcd4', tooltip: 'Usuarios unicos que han interactuado' },
      { title: 'Conversiones',       value: d.totalConversiones.toLocaleString(), icon: 'trending_up',    color: '#4caf50', tooltip: 'Sesiones que resultaron en una compra' },
      { title: 'Tasa Conversion',    value: d.tasaConversion.toFixed(2) + '%',    icon: 'percent',        color: '#ff9800', tooltip: 'Porcentaje de sesiones que convirtieron' },
      { title: 'Abandonos',          value: d.totalAbandonos.toLocaleString(),    icon: 'trending_down',  color: '#f44336', tooltip: 'Sesiones sin conversion con drop_off=true' },
      { title: 'Total Eventos',      value: totalEventos.toLocaleString(),        icon: 'bolt',           color: '#FF9800', tooltip: 'Total de eventos registrados en todas las sesiones' },
      { title: 'Semanas Cargadas',   value: semanasCargadas.toLocaleString(),     icon: 'calendar_today', color: '#9C27B0', tooltip: 'Número de semanas de datos cargadas al sistema' },
      { title: 'Promedio Eventos/Sesión', value: this.promedioEventos,            icon: 'analytics',      color: '#1F77B4', tooltip: 'Promedio de eventos registrados por cada sesión' }
    ];
  }

  // ── Graficos ───────────────────────────────────────────────────────────────

  private buildCharts(d: DashboardResumen): void {
    this.buildBarChart(d);
    this.buildDonutChart(d);
    this.buildLineChart();
  }

  private buildBarChart(d: DashboardResumen): void {
    if (!this.barCanvas?.nativeElement || !d.sesionesPorCanal?.length) return;
    this.barChart?.destroy();

    this.barChart = new Chart(this.barCanvas.nativeElement, {
      type: 'bar',
      data: {
        labels:   d.sesionesPorCanal.map(g => g.nombre),
        datasets: [{
          label:           'Sesiones',
          data:            d.sesionesPorCanal.map(g => g.total),
          backgroundColor: this.COLORS.slice(0, d.sesionesPorCanal.length),
          borderRadius:    6
        }]
      },
      options: {
        responsive: true,
        plugins: { legend: { display: false } },
        scales:  { y: { beginAtZero: true } }
      }
    });
  }

  private buildDonutChart(d: DashboardResumen): void {
    if (!this.donutCanvas?.nativeElement || !d.sesionesPorDispositivo?.length) return;
    this.donutChart?.destroy();

    this.donutChart = new Chart(this.donutCanvas.nativeElement, {
      type: 'doughnut',
      data: {
        labels:   d.sesionesPorDispositivo.map(g => g.nombre),
        datasets: [{
          data:            d.sesionesPorDispositivo.map(g => g.total),
          backgroundColor: this.COLORS.slice(0, d.sesionesPorDispositivo.length)
        }]
      },
      options: {
        responsive: true,
        plugins: { legend: { position: 'bottom' } }
      }
    });
  }

  private buildLineChart(): void {
    if (!this.lineCanvas?.nativeElement) return;
    this.lineChart?.destroy();

    this.lineChart = new Chart(this.lineCanvas.nativeElement, {
      type: 'line',
      data: {
        labels:   ['Sem 1', 'Sem 2', 'Sem 3', 'Sem 4'],
        datasets: [{
          label:           'Tasa de Conversion (%)',
          data:            [0, 0, 0, this.resumen?.tasaConversion ?? 0],
          borderColor:     '#3f51b5',
          backgroundColor: 'rgba(63,81,181,0.1)',
          tension:         0.4,
          fill:            true,
          pointRadius:     5
        }]
      },
      options: {
        responsive: true,
        plugins: { legend: { display: true } },
        scales:  { y: { beginAtZero: true, max: 100 } }
      }
    });
  }
}
