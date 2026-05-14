import { Component, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InicializacionService, InicializacionResponse } from '../../core/services/inicializacion.service';

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
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule
  ],
  templateUrl: './inicializacion.component.html',
  styleUrl: './inicializacion.component.scss'
})
export class InicializacionComponent implements OnDestroy {

  // Estado del sistema
  estadoPocketbase = false;
  estadoClickhouse = false;
  estadoParquet = false;
  estadoTablas = false;
  verificando = false;

  // Ejecución
  ejecutando = false;
  ejecutandoPaso: string | null = null;
  progreso = 0;
  tiempoTranscurrido = 0;
  private timerInterval: any = null;

  // Consola
  consolaOutput = '';

  // Reset
  mostrarDialogReset = false;
  confirmacionTexto = '';

  constructor(
    private inicializacionService: InicializacionService,
    private dialog: MatDialog
  ) {}

  ngOnDestroy(): void {
    this.detenerTimer();
  }

  // ── Sección 1: Estado del sistema ──────────────────────────────────────────

  verificarEstado(): void {
    this.verificando = true;
    this.inicializacionService.verificarClickhouse().subscribe({
      next: (res) => {
        this.verificando = false;
        this.consolaOutput += res.output + '\n';
        this.parseEstado(res);
      },
      error: (err) => {
        this.verificando = false;
        this.consolaOutput += `Error al verificar: ${err.message}\n`;
        this.estadoPocketbase = false;
        this.estadoClickhouse = false;
        this.estadoParquet = false;
        this.estadoTablas = false;
      }
    });
  }

  private parseEstado(res: InicializacionResponse): void {
    const output = res.output || '';
    this.estadoClickhouse = res.success;
    this.estadoPocketbase = true; // Si llegamos aquí, el backend responde
    this.estadoParquet = output.includes('fact_eventos') && !output.includes('ERROR');
    this.estadoTablas = output.includes('fact_eventos') &&
                        !output.includes('0') || output.match(/fact_eventos\s+\d{2,}/) !== null;

    // Verificar si fact_eventos tiene registros
    const match = output.match(/fact_eventos\s+(\d[\d,]*)/);
    if (match) {
      const count = parseInt(match[1].replace(/,/g, ''), 10);
      this.estadoTablas = count > 0;
    }
  }

  // ── Sección 2: Carga inicial ───────────────────────────────────────────────

  ejecutarCargaCompleta(): void {
    this.iniciarEjecucion('carga-completa');
    this.inicializacionService.cargaCompleta().subscribe({
      next: (res) => this.finalizarEjecucion(res),
      error: (err) => this.finalizarConError(err)
    });
  }

  ejecutarPaso1(): void {
    this.iniciarEjecucion('extraer');
    this.inicializacionService.extraerPocketbase().subscribe({
      next: (res) => this.finalizarEjecucion(res),
      error: (err) => this.finalizarConError(err)
    });
  }

  ejecutarPaso2(): void {
    this.iniciarEjecucion('cargar');
    this.inicializacionService.cargarClickhouse().subscribe({
      next: (res) => this.finalizarEjecucion(res),
      error: (err) => this.finalizarConError(err)
    });
  }

  ejecutarPaso3(): void {
    this.iniciarEjecucion('verificar');
    this.inicializacionService.verificarClickhouse().subscribe({
      next: (res) => this.finalizarEjecucion(res),
      error: (err) => this.finalizarConError(err)
    });
  }

  // ── Sección 4: Reset ──────────────────────────────────────────────────────

  abrirDialogReset(): void {
    this.mostrarDialogReset = true;
    this.confirmacionTexto = '';
  }

  cerrarDialogReset(): void {
    this.mostrarDialogReset = false;
    this.confirmacionTexto = '';
  }

  get puedeResetear(): boolean {
    return this.confirmacionTexto === 'CONFIRMAR';
  }

  ejecutarReset(): void {
    if (!this.puedeResetear) return;
    this.cerrarDialogReset();
    this.iniciarEjecucion('reset');
    this.inicializacionService.resetSistema().subscribe({
      next: (res) => {
        this.finalizarEjecucion(res);
        this.estadoTablas = false;
        this.estadoParquet = false;
      },
      error: (err) => this.finalizarConError(err)
    });
  }

  // ── Utilidades ─────────────────────────────────────────────────────────────

  private iniciarEjecucion(paso: string): void {
    this.ejecutando = true;
    this.ejecutandoPaso = paso;
    this.progreso = 0;
    this.tiempoTranscurrido = 0;
    this.consolaOutput += `\n>>> Iniciando: ${paso} ...\n`;
    this.iniciarTimer();
  }

  private finalizarEjecucion(res: InicializacionResponse): void {
    this.detenerTimer();
    this.ejecutando = false;
    this.ejecutandoPaso = null;
    this.progreso = 100;
    this.consolaOutput += res.output + '\n';
    this.consolaOutput += `\n${res.success ? '✅' : '❌'} ${res.mensaje} (${res.duracionSegundos}s)\n`;
  }

  private finalizarConError(err: any): void {
    this.detenerTimer();
    this.ejecutando = false;
    this.ejecutandoPaso = null;
    this.consolaOutput += `\n❌ Error de conexión: ${err.message || err.statusText}\n`;
  }

  private iniciarTimer(): void {
    this.timerInterval = setInterval(() => {
      this.tiempoTranscurrido++;
      // Progreso indeterminado simulado
      if (this.progreso < 90) {
        this.progreso += 2;
      }
    }, 1000);
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
