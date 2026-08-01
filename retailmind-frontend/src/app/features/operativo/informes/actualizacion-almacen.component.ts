import { Component, EventEmitter, OnDestroy, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { DwhService, EstadoDwh } from '../../../core/services/dwh.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';

/**
 * ACTUALIZAR EL ALMACÉN — control de la pantalla de informes.
 *
 * Los informes compuestos se sirven del data warehouse, que el ETL reconstruye
 * una vez al día. Este panel es lo que permite forzar esa reconstrucción sin
 * esperar a la corrida nocturna: se cambia algo en la aplicación, se pulsa, y
 * el informe compuesto lo refleja.
 *
 * Se pinta SOLO para ADMIN y GERENTE, espejando la línea de `/api/dwh/**` en
 * SecurityConfig. El resto de roles ni siquiera ve la tarjeta: enseñar un botón
 * que devuelve 403 es peor que no enseñarlo.
 *
 * <h2>Sondeo</h2>
 *
 * Mientras hay una corrida viva se consulta el estado cada 2 s. El intervalo se
 * apaga en cuanto la corrida cierra y en `ngOnDestroy` — un temporizador que
 * sobrevive a su pantalla sigue pegándole a la API para siempre.
 */
@Component({
  selector: 'app-actualizacion-almacen',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatButtonModule, MatProgressBarModule,
    MatTooltipModule, MatSnackBarModule],
  templateUrl: './actualizacion-almacen.component.html',
  styleUrls: ['./informes.scss']
})
export class ActualizacionAlmacenComponent implements OnInit, OnDestroy {

  /** Avisa a la pantalla de informes de que hay dato nuevo que recargar. */
  @Output() actualizado = new EventEmitter<void>();

  estado?: EstadoDwh;
  disparando = false;
  abierto = false;

  private sondeo?: ReturnType<typeof setInterval>;
  private readonly MS_SONDEO = 2000;

  constructor(private srv: DwhService,
              private auth: AuthService,
              private snackBar: MatSnackBar) {}

  /** Espeja la ruta del backend: solo dirección actualiza el almacén. */
  get permitido(): boolean {
    const rol = this.auth.getCurrentUser()?.rol ?? '';
    return rol === 'ADMIN' || rol === 'GERENTE';
  }

  ngOnInit(): void {
    if (this.permitido) {
      this.consultar();
    }
  }

  ngOnDestroy(): void {
    this.detener();
  }

  // ── Acciones ─────────────────────────────────────────────────────────

  actualizar(): void {
    if (this.disparando || this.estado?.enCurso) { return; }
    this.disparando = true;

    this.srv.actualizar().subscribe({
      next: res => {
        this.disparando = false;
        this.abierto = true;
        this.snackBar.open(res.mensaje, 'Cerrar', { duration: 4000 });
        this.consultar();
        this.arrancarSondeo();
      },
      error: e => {
        this.disparando = false;
        // El 409 de corrida concurrente llega aquí con su mensaje del backend:
        // es información útil, no un fallo que haya que disimular.
        this.snackBar.open(
          mensajeError(e, 'No se pudo iniciar la actualización del almacén'),
          'Cerrar', { duration: 7000 });
        this.consultar();
      }
    });
  }

  consultar(): void {
    this.srv.estado().subscribe({
      next: estado => {
        const terminaba = this.estado?.enCurso && !estado.enCurso;
        this.estado = estado;
        if (!estado.enCurso) {
          this.detener();
        }
        if (terminaba) {
          // La corrida acaba de cerrar: los informes de la pantalla están
          // mostrando el dato viejo y hay que recargarlos.
          this.actualizado.emit();
          this.snackBar.open(
            estado.exito
              ? 'Almacén actualizado. Los informes ya muestran el dato nuevo.'
              : 'La actualización terminó con incidencias. Revisa el detalle.',
            'Cerrar', { duration: 6000 });
        }
      },
      error: () => { /* el propio sobre degrada; no se molesta al usuario */ }
    });
  }

  alternar(): void {
    this.abierto = !this.abierto;
  }

  // ── Sondeo ───────────────────────────────────────────────────────────

  private arrancarSondeo(): void {
    this.detener();
    this.sondeo = setInterval(() => this.consultar(), this.MS_SONDEO);
  }

  private detener(): void {
    if (this.sondeo) {
      clearInterval(this.sondeo);
      this.sondeo = undefined;
    }
  }

  // ── Presentación ─────────────────────────────────────────────────────

  get avance(): number {
    const total = this.estado?.tareasTotales ?? 0;
    if (!total) { return 0; }
    return Math.min(((this.estado?.tareasCompletadas ?? 0) / total) * 100, 100);
  }

  get hayErrores(): boolean {
    return (this.estado?.errores?.length ?? 0) > 0;
  }

  /** Verde solo si la corrida cerró bien Y los controles cuadraron. */
  get correcta(): boolean {
    return !!this.estado?.exito && this.estado?.validacion !== 'fallo';
  }

  /**
   * Nombre legible de un resultado de la bitácora. `abortado` es el que más
   * importa explicar: no es un error del sistema sino la red de seguridad
   * haciendo su trabajo — la tabla no cuadró y por eso NO se publicó.
   */
  etiquetaResultado(resultado: string): string {
    switch (resultado) {
      case 'exito':         return 'Publicada';
      case 'abortado':      return 'No publicada (no cuadra)';
      case 'omitido':       return 'Omitida (depende de otra)';
      case 'fallo':         return 'Error';
      case 'fallo_parcial': return 'Fallo parcial';
      case 'en_curso':      return 'En curso';
      default:              return resultado;
    }
  }

  /** Variantes de `.estado-chip` que ya existen en operativo-shared.scss. */
  claseResultado(resultado: string): string {
    if (resultado === 'exito') { return 'ok'; }
    if (resultado === 'omitido') { return 'neutral'; }
    return 'error';
  }
}
