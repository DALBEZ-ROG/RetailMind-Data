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
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  InicializacionService,
  SemanaEstado,
  SemanasEstado
} from '../../../core/services/inicializacion.service';
import { mensajeError } from '../../../core/services/api-error.util';

/** Una opción del selector: la semana y por qué se puede o no generar. */
interface OpcionSemana {
  semana: number;
  libre: boolean;
  motivo: string;
  eventosTienda: number;
}

/**
 * Administración de la capa analítica legada por semana.
 *
 * ── POR QUE YA NO SE INFIERE EL NUMERO DE SEMANAS ───────────────────────────
 * `parseSemanas` leia el `stdout` de un script Python y reconstruia la lista
 * DIVIDIENDO el total entre la constante 108.584:
 *
 *   const numSemanas = Math.round(total / 108584) || 1;
 *   for (let i = 1; i <= numSemanas; i++)
 *       this.semanasCargadas.push({ semana: i, registros: 108584 });
 *
 * Sobre las 2.823.245 filas reales eso da **26 donde hay 27**, inventa 26
 * tramos idénticos y borra del mapa las cuatro semanas de conteo irregular
 * (23 → 108.593, 25 → 108.678, 26 → 108.592 y 27 → 19). Ademas el total salia
 * de esa misma suma inventada, de ahi el «0 registros» cuando el script fallaba
 * mientras el encabezado mostraba 27 semanas leidas por SQL.
 *
 * Ahora todo viene de `GET /api/init/semanas`, que agrupa con `GROUP BY semana`.
 */
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

  // ── Estado por semana (todo desde la base) ─────────────────────────────────
  estado: SemanasEstado | null = null;
  loadingSemanas = false;
  errorSemanas = '';

  // ── Selector ───────────────────────────────────────────────────────────────
  opciones: OpcionSemana[] = [];
  semanaSeleccionada: number | null = null;

  // ── Generación ─────────────────────────────────────────────────────────────
  ejecutando = false;
  tiempoTranscurrido = 0;
  private timerInterval: any = null;

  consoleOutput = '';

  historial: { semana: number; registros: number; duracion: number; fecha: string }[] = [];
  historialCols = ['semana', 'registros', 'duracion', 'fecha'];

  constructor(
    private inicializacionService: InicializacionService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.actualizarEstado();
  }

  ngOnDestroy(): void {
    this.detenerTimer();
  }

  // ── Computed ───────────────────────────────────────────────────────────────

  /** Total REAL de `fact_eventos`, servido por la base. */
  get totalRegistros(): number {
    return this.estado?.totalRegistros ?? 0;
  }

  get semanasCargadas(): SemanaEstado[] {
    return this.estado?.semanas ?? [];
  }

  get numeroSemanas(): number {
    return this.estado?.semanasCargadas ?? 0;
  }

  get eventosTienda(): number {
    return this.estado?.eventosTienda ?? 0;
  }

  get hayLibres(): boolean {
    return (this.estado?.libres?.length ?? 0) > 0;
  }

  get opcionSeleccionada(): OpcionSemana | undefined {
    return this.opciones.find(o => o.semana === this.semanaSeleccionada);
  }

  get puedeGenerar(): boolean {
    return !this.ejecutando && !!this.opcionSeleccionada?.libre;
  }

  // ── Carga de estado ────────────────────────────────────────────────────────

  actualizarEstado(): void {
    this.loadingSemanas = true;
    this.errorSemanas = '';

    this.inicializacionService.semanas().subscribe({
      next: (est) => {
        this.loadingSemanas = false;
        this.estado = est;
        if (!est.disponible) {
          this.errorSemanas = est.error || 'La analítica no está disponible.';
        }
        this.construirOpciones(est);
      },
      error: (err) => {
        this.loadingSemanas = false;
        this.estado = null;
        this.opciones = [];
        this.errorSemanas = mensajeError(err, 'No se pudo leer el estado de las semanas.');
      }
    });
  }

  /**
   * El selector enumera TODO el rango admitido (2-52) y dice de cada semana si
   * está libre y por qué no lo está. Antes solo listaba las ya cargadas y
   * proponía `max + 1`, de modo que un hueco intermedio era invisible y la
   * razón del rechazo solo aparecía cuando Python ya había abortado.
   */
  private construirOpciones(est: SemanasEstado): void {
    const porSemana = new Map<number, SemanaEstado>();
    for (const s of est.semanas) porSemana.set(s.semana, s);

    this.opciones = [];
    for (let s = 2; s <= 52; s++) {
      const ocupada = porSemana.get(s);
      this.opciones.push({
        semana: s,
        libre: !ocupada,
        motivo: ocupada ? ocupada.motivo : 'Sin registros: disponible para generar',
        eventosTienda: ocupada?.eventosTienda ?? 0
      });
    }

    // Preselección: la primera libre. Nunca una ocupada.
    this.semanaSeleccionada = est.proximaLibre ?? null;
  }

  // ── Generación ─────────────────────────────────────────────────────────────

  generarSemana(): void {
    const opcion = this.opcionSeleccionada;
    if (!opcion) {
      this.snackBar.open('Elige una semana', 'Cerrar', { duration: 3000 });
      return;
    }
    if (!opcion.libre) {
      this.snackBar.open(`Semana ${opcion.semana} no disponible: ${opcion.motivo}`,
                         'Cerrar', { duration: 6000 });
      return;
    }

    this.ejecutando = true;
    this.tiempoTranscurrido = 0;
    this.consoleOutput += `\n>>> Generando datos para semana ${opcion.semana}...\n`;
    this.iniciarTimer();

    this.inicializacionService.generarSemana(opcion.semana).subscribe({
      next: (res) => {
        this.detenerTimer();
        this.ejecutando = false;
        this.consoleOutput += res.output + '\n';
        this.consoleOutput += `\n${res.success ? '✅' : '❌'} ${res.mensaje} (${res.duracionSegundos}s)\n`;

        if (res.success) {
          this.historial.unshift({
            semana: opcion.semana,
            registros: res.registrosCargados || 0,
            duracion: res.duracionSegundos,
            fecha: new Date().toISOString()
          });
          this.snackBar.open(`Semana ${opcion.semana} generada`, 'OK', { duration: 3000 });
        } else {
          this.snackBar.open(res.mensaje, 'Cerrar', { duration: 5000 });
        }
        this.actualizarEstado();
      },
      error: (err) => {
        this.detenerTimer();
        this.ejecutando = false;
        const msg = mensajeError(err, 'No se pudo generar la semana.');
        this.consoleOutput += `\n❌ ${msg}\n`;
        this.snackBar.open(msg, 'Cerrar', { duration: 5000 });
        this.actualizarEstado();
      }
    });
  }

  // ── Timer ──────────────────────────────────────────────────────────────────

  private iniciarTimer(): void {
    this.timerInterval = setInterval(() => this.tiempoTranscurrido++, 1000);
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
