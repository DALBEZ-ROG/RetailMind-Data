import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { environment } from '../../../../environments/environment';

import { InicializacionService, InicializacionResponse } from '../../../core/services/inicializacion.service';

@Component({
  selector: 'app-admin-etl',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatChipsModule,
    MatDividerModule,
    MatSnackBarModule,
    MatSelectModule,
    MatTooltipModule
  ],
  templateUrl: './admin-etl.component.html',
  styleUrl: './admin-etl.component.scss'
})
export class AdminEtlComponent implements OnInit, OnDestroy {

  // ── Estado de semanas ──────────────────────────────────────────────────────
  semanasCargadas: { semana: number; registros: number }[] = [];
  loadingSemanas = false;

  // ── Semanas disponibles y selección ────────────────────────────────────────
  semanasDisponibles: number[] = [];
  proximaSemana = 2;
  semanaSeleccionada = 2;

  // ── Generación ─────────────────────────────────────────────────────────────
  ejecutando = false;
  tiempoTranscurrido = 0;
  private timerInterval: any = null;

  // ── Consola ────────────────────────────────────────────────────────────────
  consoleOutput = '';

  // ── Historial ──────────────────────────────────────────────────────────────
  historial: { semana: number; registros: number; duracion: number; fecha: string }[] = [];
  historialCols = ['semana', 'registros', 'duracion', 'fecha'];

  constructor(
    private inicializacionService: InicializacionService,
    private snackBar: MatSnackBar,
    private http: HttpClient
  ) {}

  ngOnInit(): void {
    this.cargarEstadoSemanas();
    this.loadSemanasDisponibles();
  }

  ngOnDestroy(): void {
    this.detenerTimer();
  }

  // ── Cargar semanas disponibles ─────────────────────────────────────────────

  loadSemanasDisponibles(): void {
    this.http.get<number[]>(`${environment.apiUrl}/api/funnel/semanas-disponibles`).subscribe({
      next: (data) => {
        this.semanasDisponibles = data;
        const maxSemana = data.length > 0 ? Math.max(...data) : 1;
        this.proximaSemana = maxSemana + 1;
        this.semanaSeleccionada = this.proximaSemana;
      },
      error: () => {
        this.semanasDisponibles = [];
        this.proximaSemana = 2;
        this.semanaSeleccionada = 2;
      }
    });
  }

  // ── Computed ───────────────────────────────────────────────────────────────

  get totalRegistros(): number {
    return this.semanasCargadas.reduce((s, r) => s + r.registros, 0);
  }

  // ── Sección 1: Estado de datos por semana ──────────────────────────────────

  actualizarEstado(): void {
    this.cargarEstadoSemanas();
    this.loadSemanasDisponibles();
  }

  cargarEstadoSemanas(): void {
    this.loadingSemanas = true;
    this.inicializacionService.verificarClickhouse().subscribe({
      next: (res) => {
        this.loadingSemanas = false;
        this.parseSemanas(res.output);
      },
      error: () => {
        this.loadingSemanas = false;
        this.semanasCargadas = [];
      }
    });
  }

  private parseSemanas(output: string): void {
    this.semanasCargadas = [];
    const lines = output.split('\n');
    for (const line of lines) {
      const match = line.match(/fact_eventos\s+([\d,]+)/);
      if (match) {
        const total = parseInt(match[1].replace(/,/g, ''), 10);
        if (total > 0) {
          const numSemanas = Math.round(total / 108584) || 1;
          for (let i = 1; i <= numSemanas; i++) {
            this.semanasCargadas.push({ semana: i, registros: 108584 });
          }
        }
      }
    }
  }

  // ── Sección 2: Generar nueva semana ────────────────────────────────────────

  generarSemana(): void {
    if (this.semanaSeleccionada < 2 || this.semanaSeleccionada > 52) {
      this.snackBar.open('La semana debe estar entre 2 y 52', 'Cerrar', { duration: 3000 });
      return;
    }

    this.ejecutando = true;
    this.tiempoTranscurrido = 0;
    this.consoleOutput += `\n>>> Generando datos para semana ${this.semanaSeleccionada}...\n`;
    this.iniciarTimer();

    this.inicializacionService.generarSemana(this.semanaSeleccionada).subscribe({
      next: (res) => {
        this.detenerTimer();
        this.ejecutando = false;
        this.consoleOutput += res.output + '\n';
        this.consoleOutput += `\n${res.success ? '✅' : '❌'} ${res.mensaje} (${res.duracionSegundos}s)\n`;

        if (res.success) {
          this.historial.unshift({
            semana: this.semanaSeleccionada,
            registros: res.registrosCargados || 108584,
            duracion: res.duracionSegundos,
            fecha: new Date().toISOString()
          });
          this.snackBar.open(`Semana ${this.semanaSeleccionada} generada exitosamente`, 'OK', { duration: 3000 });
          this.actualizarEstado();
        } else {
          this.snackBar.open(res.mensaje, 'Cerrar', { duration: 5000 });
        }
      },
      error: (err) => {
        this.detenerTimer();
        this.ejecutando = false;
        const msg = err.error?.mensaje || err.message || 'Error de conexion';
        this.consoleOutput += `\n❌ Error: ${msg}\n`;
        this.snackBar.open(msg, 'Cerrar', { duration: 5000 });
      }
    });
  }

  // ── Timer ──────────────────────────────────────────────────────────────────

  private iniciarTimer(): void {
    this.timerInterval = setInterval(() => {
      this.tiempoTranscurrido++;
    }, 1000);
  }

  private detenerTimer(): void {
    if (this.timerInterval) {
      clearInterval(this.timerInterval);
      this.timerInterval = null;
    }
  }

  limpiarConsola(): void {
    this.consoleOutput = '';
  }
}
