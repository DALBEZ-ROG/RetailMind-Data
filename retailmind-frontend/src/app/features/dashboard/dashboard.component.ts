import {
  Component,
  OnInit,
  OnDestroy,
  AfterViewInit,
  ViewChild,
  ElementRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import {
  Chart,
  BarController, BarElement,
  DoughnutController, ArcElement,
  LineController, LineElement, PointElement,
  CategoryScale, LinearScale,
  Tooltip, Legend, Filler
} from 'chart.js';

import { DashboardService } from '../../core/services/dashboard.service';
import { SesionService }    from '../../core/services/sesion.service';
import { DashboardResumen } from '../../core/models/dashboard.model';
import { Sesion }           from '../../core/models/sesion.model';

// Registrar solo los componentes de Chart.js que usamos
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
    MatSnackBarModule
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

  // Paleta Material Indigo / Pink
  private readonly COLORS = [
    '#3f51b5', '#e91e63', '#00bcd4', '#ff9800',
    '#4caf50', '#9c27b0', '#f44336', '#2196f3'
  ];

  constructor(
    private dashboardService: DashboardService,
    private sesionService:    SesionService,
    private snackBar:         MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadDashboard();
    this.loadSesiones(0, this.pageSize);
  }

  ngAfterViewInit(): void {
    // Los graficos se crean despues de que los datos lleguen (en buildCharts)
  }

  ngOnDestroy(): void {
    this.barChart?.destroy();
    this.donutChart?.destroy();
    this.lineChart?.destroy();
  }

  // ── Carga principal ────────────────────────────────────────────────────────

  loadDashboard(): void {
    this.loading = true;
    this.dashboardService.getResumen().subscribe({
      next: data => {
        this.resumen = data;
        this.buildKpiCards(data);
        this.loading = false;
        // Esperar un tick para que el DOM renderice los canvas
        setTimeout(() => this.buildCharts(data), 0);
      },
      error: () => {
        this.loading = false;
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

  // ── KPI Cards ──────────────────────────────────────────────────────────────

  private buildKpiCards(d: DashboardResumen): void {
    this.kpiCards = [
      { title: 'Total Sesiones',     value: d.totalSesiones.toLocaleString(),     icon: 'timeline',      color: '#3f51b5' },
      { title: 'Total Usuarios',     value: d.totalUsuarios.toLocaleString(),     icon: 'people',        color: '#00bcd4' },
      { title: 'Conversiones',       value: d.totalConversiones.toLocaleString(), icon: 'trending_up',   color: '#4caf50' },
      { title: 'Tasa Conversion',    value: d.tasaConversion.toFixed(2) + '%',    icon: 'percent',       color: '#ff9800' },
      { title: 'Abandonos',          value: d.totalAbandonos.toLocaleString(),    icon: 'trending_down', color: '#f44336' }
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

    // Datos de tasa por semana se cargan desde el endpoint de conversiones
    // Para el dashboard usamos los datos ya disponibles en resumen
    // Si se necesita detalle por semana, llamar /api/conversiones/tasa-por-semana
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
