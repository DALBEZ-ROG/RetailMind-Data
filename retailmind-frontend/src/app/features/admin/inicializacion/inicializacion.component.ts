import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  InicializacionService,
  EstadoLegado,
  SemanasEstado
} from '../../../core/services/inicializacion.service';
import { mensajeError } from '../../../core/services/api-error.util';

/**
 * Estado de la capa analitica LEGADA (base `retailmind` de ClickHouse).
 *
 * ── NO REINTRODUCIR ─────────────────────────────────────────────────────────
 * Esta pantalla tenia una «Zona de Peligro» con «RESETEAR SISTEMA COMPLETO»
 * (que ejecutaba `DROP TABLE` sobre `fact_eventos` y las 7 dimensiones) y una
 * «Carga Inicial» con «CARGA COMPLETA DESDE POCKETBASE» (cuyo segundo paso
 * hacia `TRUNCATE` de las dimensiones desde un parquet de mayo). Se retiraron
 * el 2026-08-08 junto con sus endpoints: el dato son 2.823.245 filas
 * acumuladas durante un semestre y NO se pueden regenerar.
 *
 * ── POR QUE YA NO SE PARSEA EL `stdout` ─────────────────────────────────────
 * Los cuatro indicadores anteriores no consultaban nada. Salian de una sola
 * llamada POST y se deducian aqui con expresiones regulares sobre la salida de
 * un proceso Python:
 *
 *   estadoClickhouse = res.success            // el CODIGO DE SALIDA, no la base
 *   estadoPocketbase = true                   // una CONSTANTE
 *   estadoParquet    = output.includes('fact_eventos')   // no miraba el disco
 *
 * Con el proceso caido los cuatro se apagaban aunque ClickHouse tuviera sus
 * 2,8 M de filas, y el de Pocketbase se pintaba verde sin servicio y sin red.
 * Ahora cada cifra viene de `GET /api/init/estado`, que las consulta.
 */
@Component({
  selector: 'app-inicializacion',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatDividerModule,
    MatTooltipModule
  ],
  templateUrl: './inicializacion.component.html',
  styleUrl: './inicializacion.component.scss'
})
export class InicializacionComponent implements OnInit, OnDestroy {

  // ── Estado real ────────────────────────────────────────────────────────────
  estado: EstadoLegado | null = null;
  semanas: SemanasEstado | null = null;
  verificando = false;
  errorEstado = '';

  // ── Diagnostico (script de solo lectura) ───────────────────────────────────
  ejecutando = false;
  tiempoTranscurrido = 0;
  private timerInterval: any = null;

  consolaOutput = '';

  constructor(private inicializacionService: InicializacionService) {}

  ngOnInit(): void {
    this.verificarEstado();
  }

  ngOnDestroy(): void {
    this.detenerTimer();
  }

  // ── Estado del sistema ─────────────────────────────────────────────────────

  verificarEstado(): void {
    this.verificando = true;
    this.errorEstado = '';

    this.inicializacionService.estado().subscribe({
      next: (est) => {
        this.estado = est;
        this.verificando = false;
      },
      error: (err) => {
        this.estado = null;
        this.verificando = false;
        this.errorEstado = mensajeError(err, 'No se pudo leer el estado de la capa analitica.');
      }
    });

    this.inicializacionService.semanas().subscribe({
      next: (sem) => (this.semanas = sem),
      error: () => (this.semanas = null)
    });
  }

  // ── Indicadores derivados (siempre de una cifra real) ──────────────────────

  get clickhouseOk(): boolean {
    return !!this.estado?.clickhouseConectado;
  }

  get factEventosOk(): boolean {
    return !!this.estado?.factEventosConDatos;
  }

  get dimensionesOk(): boolean {
    return !!this.estado && this.estado.dimensionesConDatos === this.estado.dimensionesTotales;
  }

  get semanasOk(): boolean {
    return !!this.estado && this.estado.semanasDistintas > 0;
  }

  /** Tamaño legible del parquet, para el bloque de historial. */
  get parquetTamano(): string {
    const b = this.estado?.parquetBytes ?? 0;
    if (!b) return '—';
    return (b / 1024 / 1024).toFixed(2) + ' MB';
  }

  // ── Diagnostico de solo lectura ────────────────────────────────────────────

  ejecutarDiagnostico(): void {
    this.ejecutando = true;
    this.tiempoTranscurrido = 0;
    this.consolaOutput += `\n>>> Ejecutando verificador de solo lectura...\n`;
    this.iniciarTimer();

    this.inicializacionService.verificarClickhouse().subscribe({
      next: (res) => {
        this.detenerTimer();
        this.ejecutando = false;
        this.consolaOutput += res.output + '\n';
        this.consolaOutput += `\n${res.success ? '✅' : '❌'} ${res.mensaje} (${res.duracionSegundos}s)\n`;
        this.verificarEstado();
      },
      error: (err) => {
        this.detenerTimer();
        this.ejecutando = false;
        this.consolaOutput += `\n❌ ${mensajeError(err, 'No se pudo ejecutar el verificador.')}\n`;
      }
    });
  }

  // ── Utilidades ─────────────────────────────────────────────────────────────

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
    this.consolaOutput = '';
  }
}
