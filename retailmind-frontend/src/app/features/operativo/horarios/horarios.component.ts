import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { HorariosService } from '../../../core/services/horarios.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import { VentanaHoraria } from '../../../core/models/operativo.model';
import { RolGrupoPipe, nombreRolGrupo } from '../../../core/pipes/etiquetas.pipe';
import {
  HorarioDialogComponent, HorarioDialogData, HorarioDialogResultado
} from './horario-dialog.component';

type FiltroEstado = 'todos' | 'activas' | 'inactivas';

/**
 * Horarios de acceso por rol (`grupo_horario`), alineada al patrón
 * (docs/PATRON_UI.md).
 *
 * Es la pantalla de más riesgo del sistema: la ventana no la interpreta el
 * frontend, la aplica el MOTOR (`esta_en_horario()` + triggers + política
 * `pol_horario`). Desactivar la ventana equivocada deja a un rol entero fuera
 * —login incluido, script 53—, así que aquí «Eliminar» confirma SIEMPRE y el
 * mensaje dice exactamente a quién deja fuera y cuándo.
 *
 * `grp_administrador` no aparece: el administrador está EXENTO de horario por
 * diseño y darle una ventana solo podría restringirlo.
 */
@Component({
  selector: 'app-horarios',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule,
    MatDialogModule, AccionesRegistroComponent, RolGrupoPipe],
  templateUrl: './horarios.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class HorariosComponent implements OnInit {

  readonly dias = ['Domingo', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado'];
  /**
   * Los 8 roles sujetos a horario. `grp_soporte` (script 37) faltaba en esta
   * lista: la tabla ya tenía sus 7 ventanas sembradas —cubren las 24 h— pero
   * desde la interfaz no se podía crear ninguna para él.
   *
   * **Se guardan con el prefijo y así viajan al backend**: son los nombres
   * REALES de los roles de PostgreSQL. Lo único que cambia es cómo se pintan
   * (`| rolGrupo`); el `[value]` de los desplegables sigue siendo esto.
   */
  readonly roles = ['grp_gerente', 'grp_vendedor', 'grp_compras', 'grp_bodega',
                    'grp_despacho', 'grp_cliente', 'grp_analista', 'grp_soporte'];

  private todas: VentanaHoraria[] = [];
  ventanas: VentanaHoraria[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroRol = 'todos';
  filtroDia: number | 'todos' = 'todos';
  filtroEstado: FiltroEstado = 'todos';

  filaSeleccionada: VentanaHoraria | null = null;

  columnas = ['rol', 'dia', 'inicio', 'fin', 'activo'];

  constructor(private horarios: HorariosService, private snackBar: MatSnackBar,
              private dialog: MatDialog, private confirmar: ConfirmService) {}

  ngOnInit(): void { this.cargar(); }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.horarios.listar().subscribe({
      next: v => { this.todas = v; this.aplicarFiltros(); this.loading = false; },
      error: () => { this.loading = false; }
    });
  }

  aplicarFiltros(): void {
    this.ventanas = this.todas.filter(v => {
      if (this.filtroEstado === 'activas' && !v.activo) return false;
      if (this.filtroEstado === 'inactivas' && v.activo) return false;
      if (this.filtroRol !== 'todos' && v.rol_grupo !== this.filtroRol) return false;
      if (this.filtroDia !== 'todos' && v.dia_semana !== this.filtroDia) return false;
      return true;
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroRol = 'todos';
    this.filtroDia = 'todos';
    this.filtroEstado = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.ventanas.find(v => v.id === this.filaSeleccionada!.id) ?? null;
  }

  /** Ventanas ACTIVAS que le quedarían a un rol si se desactiva la dada. */
  private ventanasActivasRestantes(v: VentanaHoraria): number {
    return this.todas.filter(o => o.rol_grupo === v.rol_grupo && o.activo && o.id !== v.id).length;
  }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(v: VentanaHoraria): void { this.filaSeleccionada = v; }

  nuevaVentana(): void { this.abrirDialogo('nuevo'); }
  modificarVentana(): void { this.abrirDialogo('actualizar'); }
  verVentana(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const ventana = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !ventana) return;

    const data: HorarioDialogData = { ventana, modo, roles: this.roles, dias: this.dias };
    this.dialog.open(HorarioDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: HorarioDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(ventana!, res);
      });
  }

  private crear(res: HorarioDialogResultado): void {
    this.horarios.crear(res).subscribe({
      next: () => {
        this.snackBar.open('Ventana horaria creada', 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear la ventana horaria'),
        'Cerrar', { duration: 4000 })
    });
  }

  /**
   * Modificar puede DESACTIVAR la ventana desde la casilla «Activa». Eso es
   * exactamente lo mismo que Eliminar, así que también confirma: el riesgo no
   * depende de por qué botón se llegue.
   */
  private guardar(original: VentanaHoraria, res: HorarioDialogResultado): void {
    if (original.activo && !res.activo) {
      this.confirmarCierre(original, () => this.aplicarEdicion(original, res));
      return;
    }
    this.aplicarEdicion(original, res);
  }

  private aplicarEdicion(original: VentanaHoraria, res: HorarioDialogResultado): void {
    this.horarios.editar(original.id, {
      horaInicio: res.horaInicio, horaFin: res.horaFin, activo: res.activo
    }).subscribe({
      next: () => {
        this.snackBar.open('Ventana horaria actualizada', 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la ventana horaria'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: la confirmación más importante del sistema ──────────────

  /**
   * `grupo_horario` no tiene borrado: «Eliminar» es DESACTIVAR la ventana, y
   * eso cierra el acceso del rol ese día en ese tramo.
   */
  eliminarVentana(): void {
    const v = this.filaSeleccionada;
    if (!v || !v.activo) return;
    this.confirmarCierre(v, () => {
      this.horarios.editar(v.id, {
        horaInicio: v.hora_inicio, horaFin: v.hora_fin, activo: false
      }).subscribe({
        next: () => {
          this.snackBar.open('Ventana horaria eliminada (desactivada)', 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar la ventana horaria'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  /**
   * El mensaje dice a QUIÉN deja fuera, CUÁNDO y con qué margen: si es la
   * última ventana activa del rol, lo dice en mayúsculas, porque ese caso deja
   * al rol entero fuera del sistema los siete días.
   */
  private confirmarCierre(v: VentanaHoraria, alAceptar: () => void): void {
    const restantes = this.ventanasActivasRestantes(v);
    const ultima = restantes === 0;
    // En una confirmación de seguridad van los DOS nombres: el legible para que
    // se entienda a quién se deja fuera, y el del motor entre paréntesis porque
    // es el que aparecerá en `grupo_horario` y en cualquier traza del rechazo.
    const rol = `${nombreRolGrupo(v.rol_grupo)} (${v.rol_grupo})`;
    const consecuencia =
      `A partir de ese momento, ${rol} NO podrá iniciar sesión ni operar los ` +
      `${this.dias[v.dia_semana].toLowerCase()}s entre las ${v.hora_inicio} y las ` +
      `${v.hora_fin}: el rechazo lo hace la propia base de datos (esta_en_horario()), ` +
      'no la interfaz, así que ninguna pantalla puede saltárselo. ' +
      (ultima
        ? '⚠ ES LA ÚLTIMA VENTANA ACTIVA DE ESTE ROL: al desactivarla, sus usuarios ' +
          'quedan fuera del sistema TODOS LOS DÍAS. Solo un ADMIN podrá volver a abrirla. '
        : `Al rol le quedarán ${restantes} ventana/s activa/s en otros días u horas. `) +
      'No se borra nada: la ventana se conserva inactiva y se restaura marcando «Activa» ' +
      'desde Modificar.';

    this.confirmar.confirmar({
      titulo: ultima ? 'Vas a dejar a un rol fuera del sistema' : 'Confirmar cierre de acceso',
      mensaje: `¿Seguro que deseas eliminar la ventana de ${rol} del `
             + `${this.dias[v.dia_semana].toLowerCase()} (${v.hora_inicio}–${v.hora_fin})?`,
      consecuencia,
      textoAceptar: 'Cerrar el acceso',
      tono: 'peligro'
    }).subscribe(ok => { if (ok) alAceptar(); });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'La ventana ya está eliminada (inactiva). Restáurala desde Modificar.'
      : '';
  }

  /** Cuántas ventanas activas tiene cada rol: se pinta como aviso. */
  get rolesSinVentanaActiva(): string[] {
    return this.roles.filter(r => !this.todas.some(v => v.rol_grupo === r && v.activo));
  }

  /** El mismo aviso, con los nombres limpios; el crudo va en el `title`. */
  get rolesSinVentanaActivaTexto(): string {
    return this.rolesSinVentanaActiva.map(nombreRolGrupo).join(', ');
  }
}
