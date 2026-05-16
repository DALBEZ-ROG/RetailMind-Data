import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';

import { InicializacionService, InicializacionResponse } from '../../core/services/inicializacion.service';
import { DashboardService } from '../../core/services/dashboard.service';

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
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule
  ],
  templateUrl: './admin-etl.component.html',
  styleUrl: './admin-etl.component.scss'
})
export class AdminEtlComponent implements OnInit, OnDestroy {

  // ── Estado de semanas ──────────────────────────────────────────────────────
  semanasCargadas: { semana: number; registros: number }[] = [];
  loadingSemanas = false;

  // ── Generación ─────────────────────────────────────────────────────────────
  semanaInput = 2;
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
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.cargarEstadoSemanas();
  }

  ngOnDestroy(): void {
    this.detenerTimer();
  }

  // ── Sección 1: Estado de datos por semana ──────────────────────────────────

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
    // El output del verify muestra conteos por tabla
    // Intentar extraer info de semanas del output
    this.semanasCargadas = [];
    const lines = output.split('\n');
    for (const line of lines) {
      const match = line.match(/fact_eventos\s+([\d,]+)/);
      if (match) {
        const total = parseInt(match[1].replace(/,/g, ''), 10);
        if (total > 0) {
          // Estimar semanas basado en 108,584 por semana
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
    if (this.semanaInput < 2 || this.semanaInput > 52) {
      this.snackBar.open('La semana debe estar entre 2 y 52', 'Cerrar', { duration: 3000 });
      return;
    }

    this.ejecutando = true;
    this.tiempoTranscurrido = 0;
    this.consoleOutput += `\n>>> Generando datos para semana ${this.semanaInput}...\n`;
    this.iniciarTimer();

    this.inicializacionService.generarSemana(this.semanaInput).subscribe({
      next: (res) => {
        this.detenerTimer();
        this.ejecutando = false;
        this.consoleOutput += res.output + '\n';
        this.consoleOutput += `\n${res.success ? '✅' : '❌'} ${res.mensaje} (${res.duracionSegundos}s)\n`;

        if (res.success) {
          this.historial.unshift({
            semana: this.semanaInput,
            registros: res.registrosCargados || 108584,
            duracion: res.duracionSegundos,
            fecha: new Date().toISOString()
          });
          this.snackBar.open(`Semana ${this.semanaInput} generada exitosamente`, 'OK', { duration: 3000 });
          this.cargarEstadoSemanas();
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
