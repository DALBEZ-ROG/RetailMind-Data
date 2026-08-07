import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';

import { AccionesRegistroComponent } from
  '../../../core/components/acciones-registro/acciones-registro.component';
import { RolGrupoPipe, nombreRolGrupo } from '../../../core/pipes/etiquetas.pipe';
import { ConfirmService } from '../../../core/services/confirm.service';
import { PermisosMotorService } from '../../../core/services/permisos-motor.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  MapaSeguridad, PermisoColumna, PermisoTabla, PoliticaRls, ObjetoAdministrable,
  RolMotor, UsuarioDeRol, VentanaHoraria, CambioPermiso, RolPersonalizado
} from '../../../core/models/seguridad.model';
import {
  PermisoDialogComponent, PermisoDialogData, PermisoDialogResultado
} from './permiso-dialog.component';
import { RolDialogComponent, RolDialogResultado } from './rol-dialog.component';

/** Fila unificada de la grilla de permisos: de tabla o de columna. */
interface FilaPermiso {
  clave: string;
  rol_motor: string;
  tabla: string;
  columna: string | null;
  privilegio: string;
  /** Solo en los de columna: el privilegio de tabla ya lo incluye. */
  cubierto_por_tabla: boolean;
}

const DIAS = ['Domingo', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado'];

/** Los cuatro privilegios que la pantalla administra (R4 del backend). */
const PRIVILEGIOS_EDITOR = ['SELECT', 'INSERT', 'UPDATE', 'DELETE'] as const;
type PrivilegioEditor = typeof PRIVILEGIOS_EDITOR[number];

/** Una fila del editor: una tabla y el estado de sus cuatro interruptores. */
interface FilaEditor {
  tabla: string;
  con_rls: boolean;
  columnas: string[];
  /** Privilegio -> lo tiene a nivel de TABLA. */
  estado: Record<string, boolean>;
  /** Cuántas columnas de esa tabla tienen permiso propio para este rol. */
  columnasConPermiso: number;
  /** En curso: se apaga el interruptor mientras el motor responde. */
  ocupado: Record<string, boolean>;
}

/**
 * Mapa de la seguridad del MOTOR y administración de privilegios.
 *
 * Es la pantalla que hace visible el diferenciador del proyecto: la seguridad
 * de RetailMind no vive en tablas de aplicación (`permiso` y `rol_permiso`
 * existen, están VACÍAS y son vestigiales) sino en los GRANT, las políticas RLS
 * y la ventana horaria de PostgreSQL. Todo lo que se pinta aquí sale de
 * `pg_catalog`.
 *
 * <h3>Por qué «Modificar» y «Ver» no están en la barra</h3>
 * Un privilegio es una ASOCIACIÓN rol × objeto × privilegio, no un registro con
 * campos: no hay nada que editar ni ficha que consultar (§8.11 del patrón de
 * interfaz, el caso de `promocion_producto`). Quedan Nuevo = conceder y
 * Eliminar = revocar. A diferencia de aquella, aquí revocar SÍ es reversible
 * desde esta misma pantalla, y la confirmación lo dice.
 */
@Component({
  selector: 'app-permisos-motor',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatTabsModule, MatTooltipModule,
    MatDialogModule, MatSnackBarModule, MatSlideToggleModule, MatCheckboxModule,
    MatProgressBarModule, AccionesRegistroComponent, RolGrupoPipe],
  templateUrl: './permisos-motor.component.html',
  styleUrls: ['../operativo-shared.scss', './permisos-motor.scss']
})
export class PermisosMotorComponent implements OnInit {

  readonly dias = DIAS;

  mapa: MapaSeguridad | null = null;
  objetos: ObjetoAdministrable[] = [];
  politicas: PoliticaRls[] = [];

  /** Permisos: todos los que llegaron y los que se pintan tras filtrar. */
  private permisosTabla: PermisoTabla[] = [];
  private permisosColumna: PermisoColumna[] = [];
  filas: FilaPermiso[] = [];
  totalTablaEstandar = 0;
  totalTablaMaintain = 0;

  filaSeleccionada: FilaPermiso | null = null;

  // Filtros (aplicados en el SERVIDOR: son miles de filas)
  filtroRol = '';
  filtroTabla = '';
  filtroPrivilegio = '';
  filtroTipo = '';

  // Filtros de las pestañas de lectura
  filtroUsuarioRol = '';
  filtroPoliticaTabla = '';

  loading = true;
  guardando = false;

  // ── Editor de rol (interruptores) ───────────────────────────────────────
  readonly privilegios = PRIVILEGIOS_EDITOR;
  rolesPropios: RolPersonalizado[] = [];
  rolEditor = '';
  filtroTablaEditor = '';
  soloConPermisos = true;
  filasEditor: FilaEditor[] = [];
  tablaExpandida: string | null = null;
  /** Estado de los interruptores de COLUMNA de la tabla expandida. */
  columnasEditor: { columna: string; estado: Record<string, boolean>;
                    cubierta: Record<string, boolean>; ocupado: Record<string, boolean> }[] = [];
  cargandoEditor = false;

  private permisosDelRol: PermisoTabla[] = [];
  private columnasDelRol: PermisoColumna[] = [];

  readonly columnasPermiso = ['rol', 'objeto', 'alcance', 'privilegio', 'nota'];
  readonly columnasRol = ['rol', 'clase', 'usuarios', 'tabla', 'columna', 'politicas', 'atributos'];
  readonly columnasUsuario = ['usuario', 'email', 'rol_app', 'rol_motor', 'estado'];
  readonly columnasPolitica = ['tabla', 'politica', 'comando', 'roles', 'explicacion'];
  readonly columnasHorario = ['rol', 'dia', 'ventana', 'estado', 'ahora'];

  constructor(
    private srv: PermisosMotorService,
    private dialog: MatDialog,
    private confirmar: ConfirmService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void { this.cargarTodo(); }

  // ── Carga ───────────────────────────────────────────────────────────────

  cargarTodo(): void {
    this.loading = true;
    forkJoin({
      mapa: this.srv.mapa(),
      objetos: this.srv.objetos(),
      politicas: this.srv.politicas({}),
      propios: this.srv.rolesPersonalizados()
    }).subscribe({
      next: r => {
        this.mapa = r.mapa;
        this.objetos = r.objetos;
        this.politicas = r.politicas;
        this.rolesPropios = r.propios;
        this.loading = false;
        this.cargarPermisos();
        this.cargarEditor();
      },
      error: e => {
        this.loading = false;
        this.error(e, 'No se pudo cargar el mapa de seguridad');
      }
    });
  }

  cargarPermisos(): void {
    this.srv.permisos({
      rol: this.filtroRol, tabla: this.filtroTabla,
      privilegio: this.filtroPrivilegio, tipo: this.filtroTipo
    }).subscribe({
      next: p => {
        this.permisosTabla = p.tabla;
        this.permisosColumna = p.columna;
        this.totalTablaEstandar = p.totalTablaEstandar;
        this.totalTablaMaintain = p.totalTablaMaintain;
        this.componerFilas();
      },
      error: e => this.error(e, 'No se pudieron cargar los privilegios')
    });
  }

  /**
   * Las dos listas se unen en una grilla, PERO la columna «Alcance» las
   * distingue siempre: mezclarlas sin marcar cuál es cuál escondería justo la
   * diferencia que esta pantalla existe para enseñar.
   */
  private componerFilas(): void {
    const deTabla: FilaPermiso[] = this.permisosTabla.map(p => ({
      clave: `T|${p.rol_motor}|${p.tabla}|${p.privilegio}`,
      rol_motor: p.rol_motor, tabla: p.tabla, columna: null,
      privilegio: p.privilegio, cubierto_por_tabla: false
    }));
    const deColumna: FilaPermiso[] = this.permisosColumna.map(p => ({
      clave: `C|${p.rol_motor}|${p.tabla}|${p.columna}|${p.privilegio}`,
      rol_motor: p.rol_motor, tabla: p.tabla, columna: p.columna,
      privilegio: p.privilegio, cubierto_por_tabla: p.cubierto_por_tabla
    }));
    this.filas = [...deColumna, ...deTabla];
    this.resincronizarSeleccion();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) { return; }
    const vigente = this.filas.find(f => f.clave === this.filaSeleccionada!.clave);
    this.filaSeleccionada = vigente ?? null;
  }

  filtrarPoliticas(): void {
    this.srv.politicas({ tabla: this.filtroPoliticaTabla }).subscribe({
      next: p => this.politicas = p,
      error: e => this.error(e, 'No se pudieron cargar las políticas')
    });
  }

  limpiarFiltros(): void {
    this.filtroRol = ''; this.filtroTabla = '';
    this.filtroPrivilegio = ''; this.filtroTipo = '';
    this.cargarPermisos();
  }

  seleccionarFila(f: FilaPermiso): void {
    this.filaSeleccionada = this.filaSeleccionada?.clave === f.clave ? null : f;
  }

  // ── Vistas derivadas ────────────────────────────────────────────────────

  get roles(): RolMotor[] { return this.mapa?.roles ?? []; }

  /** Los 9 grupos, para los desplegables. */
  get rolesGrupo(): string[] {
    return this.roles.filter(r => r.clase === 'grupo').map(r => r.rol_motor);
  }

  /** Destinatarios administrables: los 9 MENOS el del propio administrador. */
  get rolesAdministrables(): string[] {
    return this.rolesGrupo.filter(r => r !== 'grp_administrador');
  }

  get usuarios(): UsuarioDeRol[] {
    const todos = this.mapa?.usuarios ?? [];
    return this.filtroUsuarioRol
      ? todos.filter(u => u.rol_motor === this.filtroUsuarioRol)
      : todos;
  }

  get horarios(): VentanaHoraria[] { return this.mapa?.horarios ?? []; }

  /** Roles cuya ventana está ABIERTA ahora mismo, según el propio motor. */
  get rolesDentroDeHorario(): string[] {
    const dentro = new Set(this.horarios.filter(h => h.dentro_ahora).map(h => h.rol_grupo));
    return [...dentro].sort();
  }

  /** El mismo listado con los nombres limpios (el crudo va en el `title`). */
  get rolesDentroDeHorarioTexto(): string {
    return this.rolesDentroDeHorario.map(nombreRolGrupo).join(', ');
  }

  get tablasConColumnas(): string[] {
    const t = new Set(this.permisosColumna.map(p => p.tabla));
    return [...t].sort();
  }

  /**
   * Motivo por el que la fila seleccionada NO se puede revocar. Devuelve
   * cadena vacía cuando sí se puede: el componente de acciones lo usa como
   * tooltip del botón apagado.
   */
  get motivoNoRevocable(): string {
    const f = this.filaSeleccionada;
    if (!f) { return ''; }
    if (f.rol_motor === 'grp_administrador') {
      return 'PERMISO PROTEGIDO (R1): grp_administrador es el rol con el que operas. '
           + 'Revocarle un privilegio puede dejarte sin poder entrar a deshacerlo.';
    }
    if (this.tablaProtegida(f.tabla)) {
      return `PERMISO PROTEGIDO (R2): «${f.tabla}» pertenece al núcleo de identidad y `
           + 'seguridad del sistema.';
    }
    if (!this.privilegioAdministrable(f)) {
      return `El privilegio ${f.privilegio} no se administra desde esta pantalla (R4): `
           + 'solo SELECT, INSERT, UPDATE y DELETE.';
    }
    return '';
  }

  get puedeRevocar(): boolean {
    return !!this.filaSeleccionada && !this.motivoNoRevocable;
  }

  private tablaProtegida(tabla: string): boolean {
    const r2 = this.mapa?.protegidos.find(p => p.id === 'R2');
    return !!r2 && r2.alcance.includes(tabla);
  }

  private privilegioAdministrable(f: FilaPermiso): boolean {
    const permitidos = f.columna
      ? ['SELECT', 'INSERT', 'UPDATE']
      : ['SELECT', 'INSERT', 'UPDATE', 'DELETE'];
    return permitidos.includes(f.privilegio);
  }

  nombreSeleccion(): string | null {
    const f = this.filaSeleccionada;
    if (!f) { return null; }
    const objeto = f.columna ? `${f.tabla}.${f.columna}` : f.tabla;
    // Los dos nombres: el legible identifica al destinatario y el del motor es
    // el que se escribirá en el GRANT/REVOKE y quedará en `log_auditoria`.
    return `${f.privilegio} sobre ${objeto} → ${nombreRolGrupo(f.rol_motor)} (${f.rol_motor})`;
  }

  // ── PARTE 2: conceder y revocar ─────────────────────────────────────────

  conceder(): void {
    const data: PermisoDialogData = {
      modo: 'nuevo',
      roles: this.rolesAdministrables,
      objetos: this.objetos,
      inicial: this.filaSeleccionada
        ? {
            rol: this.filaSeleccionada.rol_motor !== 'grp_administrador'
              ? this.filaSeleccionada.rol_motor : undefined,
            tabla: this.filaSeleccionada.tabla
          }
        : undefined
    };
    this.dialog.open(PermisoDialogComponent, {
      data, panelClass: 'dubai-dialog', autoFocus: false
    }).afterClosed().subscribe((res: PermisoDialogResultado | undefined) => {
      if (res) { this.confirmarConcesion(res); }
    });
  }

  /**
   * La confirmación dice la CONSECUENCIA REAL, no «¿está seguro?»: qué gana ese
   * rol, sobre qué pantallas se nota y —cuando toca— que está abriendo un dato
   * que la segregación financiera mantiene cerrado.
   */
  private confirmarConcesion(p: PermisoDialogResultado): void {
    const objeto = p.columna ? `${p.tabla}.${p.columna}` : `toda la tabla ${p.tabla}`;
    const gana = this.queGana(p.privilegio, objeto);

    let consecuencia = `${p.rol} pasará a ${gana}. El cambio es INMEDIATO para las `
      + 'transacciones nuevas: la aplicación asume el rol del usuario en cada transacción '
      + `(SET LOCAL ROLE), así que la próxima pantalla que abra un usuario de ${p.rol} `
      + 'ya lo verá.';

    if (this.abreDatoFinanciero(p)) {
      consecuencia += ' ⚠ ESTE PRIVILEGIO ABRE UN DATO ECONÓMICO a un rol de operación: '
        + 'la segregación financiera del sistema mantiene a Bodega y Despacho sin acceso a '
        + 'columnas de dinero. Concederlo rompe esa separación.';
    }
    if (p.columna) {
      consecuencia += ' Recuerda que los privilegios de columna solo SUMAN: si el rol ya '
        + 'tiene el privilegio sobre la tabla entera, esto no cambiará nada.';
    }
    consecuencia += ' Queda registrado en la auditoría con tu usuario.';

    this.confirmar.confirmar({
      titulo: 'Conceder privilegio en el motor',
      mensaje: `¿Conceder ${p.privilegio} sobre ${objeto} al rol ${p.rol}?`,
      consecuencia,
      textoAceptar: 'Conceder'
    }).subscribe(ok => {
      if (!ok) { return; }
      this.guardando = true;
      this.srv.conceder(p).subscribe({
        next: r => this.tras(r),
        error: e => { this.guardando = false; this.error(e, 'No se pudo conceder el privilegio'); }
      });
    });
  }

  revocar(): void {
    const f = this.filaSeleccionada;
    if (!f || !this.puedeRevocar) { return; }
    const objeto = f.columna ? `${f.tabla}.${f.columna}` : `toda la tabla ${f.tabla}`;
    const pierde = this.quePierde(f.privilegio, objeto);

    let consecuencia = `${f.rol_motor} dejará de ${pierde}. `
      + 'PUEDE DEJAR PANTALLAS SIN DATOS: el motor no devuelve un error amable, devuelve '
      + '42501 —que la aplicación traduce a un 403— o, si hay una política RLS por medio, '
      + 'CERO FILAS sin mensaje alguno. Revisa qué pantallas usan esa tabla antes de aceptar.';

    if (f.cubierto_por_tabla) {
      consecuencia += ' AVISO: este rol tiene además el privilegio sobre la TABLA entera, '
        + 'que incluye esta columna, así que quitar la entrada de columna NO cambiará nada '
        + 'en la práctica.';
    }
    consecuencia += ' Es REVERSIBLE: puedes volver a concederlo desde esta misma pantalla. '
      + 'Queda registrado en la auditoría con tu usuario.';

    this.confirmar.confirmar({
      titulo: 'Revocar privilegio en el motor',
      mensaje: `¿Revocar ${f.privilegio} sobre ${objeto} al rol ${f.rol_motor}?`,
      consecuencia,
      textoAceptar: 'Revocar',
      tono: 'peligro'
    }).subscribe(ok => {
      if (!ok) { return; }
      this.guardando = true;
      this.srv.revocar({
        rol: f.rol_motor, tabla: f.tabla, columna: f.columna, privilegio: f.privilegio
      }).subscribe({
        next: r => this.tras(r),
        error: e => { this.guardando = false; this.error(e, 'No se pudo revocar el privilegio'); }
      });
    });
  }

  /**
   * El resultado se cuenta con lo que el MOTOR confirmó, no con lo que se pidió.
   * Un GRANT que no cambió nada sale en tono de aviso, no de éxito.
   */
  private tras(r: CambioPermiso): void {
    this.guardando = false;
    this.snackBar.open(r.mensaje, 'Cerrar', { duration: r.aplicado ? 5000 : 9000 });
    this.cargarTodo();
  }

  private queGana(privilegio: string, objeto: string): string {
    switch (privilegio) {
      case 'SELECT': return `poder LEER ${objeto}`;
      case 'INSERT': return `poder CREAR registros en ${objeto}`;
      case 'UPDATE': return `poder MODIFICAR ${objeto}`;
      case 'DELETE': return `poder BORRAR filas de ${objeto}`;
      default:       return `tener ${privilegio} sobre ${objeto}`;
    }
  }

  private quePierde(privilegio: string, objeto: string): string {
    switch (privilegio) {
      case 'SELECT': return `poder LEER ${objeto}: las consultas que la toquen fallarán o `
                          + 'saldrán vacías';
      case 'INSERT': return `poder CREAR registros en ${objeto}`;
      case 'UPDATE': return `poder MODIFICAR ${objeto}`;
      case 'DELETE': return `poder BORRAR filas de ${objeto}`;
      default:       return `tener ${privilegio} sobre ${objeto}`;
    }
  }

  /** Heurística de aviso, no de bloqueo: nombres de columna con contenido económico. */
  private abreDatoFinanciero(p: PermisoDialogResultado): boolean {
    const rolesOperacion = ['grp_bodega', 'grp_despacho'];
    if (!rolesOperacion.includes(p.rol)) { return false; }
    if (!p.columna) { return true; }
    return /total|monto|precio|costo|subtotal|impuesto|descuento|saldo|credito/i
      .test(p.columna);
  }

  // ══════════ EDITOR DE ROL: los interruptores ══════════

  /** ¿El rol seleccionado lo creaste tú? Decide si hace falta confirmar. */
  get rolEditorEsPropio(): boolean {
    return this.esRolPropio(this.rolEditor);
  }

  esRolPropio(rolGrupo: string): boolean {
    return this.rolesPropios.some(r => r.rol_grupo === rolGrupo);
  }

  get rolPropioSeleccionado(): RolPersonalizado | null {
    return this.rolesPropios.find(r => r.rol_grupo === this.rolEditor) ?? null;
  }

  /** grp_administrador no se edita (R1): ni siquiera se ofrece. */
  get rolesEditables(): string[] {
    return this.rolesGrupo.filter(r => r !== 'grp_administrador');
  }

  cargarRolesPropios(): void {
    this.srv.rolesPersonalizados().subscribe({
      next: r => this.rolesPropios = r,
      error: e => this.error(e, 'No se pudieron cargar los roles propios')
    });
  }

  /** Al elegir rol se piden SUS permisos y se arma la rejilla. */
  cargarEditor(): void {
    if (!this.rolEditor) { this.filasEditor = []; return; }
    this.cargandoEditor = true;
    this.tablaExpandida = null;
    this.srv.permisos({ rol: this.rolEditor }).subscribe({
      next: p => {
        this.permisosDelRol = p.tabla;
        this.columnasDelRol = p.columna;
        this.componerEditor();
        this.cargandoEditor = false;
      },
      error: e => { this.cargandoEditor = false; this.error(e, 'No se pudieron cargar los permisos'); }
    });
  }

  /**
   * La rejilla se recalcula en un campo y NUNCA en un getter: un getter
   * devuelve un array nuevo en cada ciclo de detección y con ~110 tablas × 4
   * interruptores la tabla se repinta entera constantemente (§8.6 del patrón).
   */
  private componerEditor(): void {
    const porTabla = new Map<string, Set<string>>();
    for (const p of this.permisosDelRol) {
      if (!porTabla.has(p.tabla)) { porTabla.set(p.tabla, new Set()); }
      porTabla.get(p.tabla)!.add(p.privilegio);
    }
    const colsPorTabla = new Map<string, number>();
    for (const c of this.columnasDelRol) {
      colsPorTabla.set(c.tabla, (colsPorTabla.get(c.tabla) ?? 0) + 1);
    }

    const busqueda = this.filtroTablaEditor.trim().toLowerCase();
    this.filasEditor = this.objetos
      .filter(o => !busqueda || o.tabla.toLowerCase().includes(busqueda))
      .map(o => {
        const tiene = porTabla.get(o.tabla) ?? new Set<string>();
        const estado: Record<string, boolean> = {};
        const ocupado: Record<string, boolean> = {};
        for (const p of PRIVILEGIOS_EDITOR) { estado[p] = tiene.has(p); ocupado[p] = false; }
        return {
          tabla: o.tabla,
          con_rls: o.con_rls,
          columnas: o.columnas ? o.columnas.split(',') : [],
          estado,
          columnasConPermiso: colsPorTabla.get(o.tabla) ?? 0,
          ocupado
        };
      })
      .filter(f => !this.soloConPermisos
        || Object.values(f.estado).some(Boolean) || f.columnasConPermiso > 0);
  }

  aplicarFiltroEditor(): void { this.componerEditor(); }

  /**
   * Un interruptor. La confirmación depende de SOBRE QUIÉN se actúa:
   *
   *  · Rol PROPIO  -> se aplica directo. Es un sandbox recién creado del que no
   *    depende ninguna pantalla; pedir confirmación a cada clic convertiría el
   *    editor en un formulario y ese es justo el problema que venimos a
   *    resolver.
   *  · Uno de los 9 -> confirmación con la consecuencia real, como siempre.
   *    Ahí sí hay pantallas y usuarios detrás.
   */
  alternar(fila: FilaEditor, privilegio: PrivilegioEditor, encender: boolean): void {
    const peticion = { rol: this.rolEditor, tabla: fila.tabla, columna: null, privilegio };

    const aplicar = () => {
      fila.ocupado[privilegio] = true;
      const obs = encender ? this.srv.conceder(peticion) : this.srv.revocar(peticion);
      obs.subscribe({
        next: r => {
          fila.ocupado[privilegio] = false;
          fila.estado[privilegio] = r.despues;   // lo que dice el MOTOR, no lo que pedimos
          this.snackBar.open(r.mensaje, 'Cerrar', { duration: r.aplicado ? 3500 : 8000 });
          this.cargarRolesPropios();
        },
        error: e => {
          fila.ocupado[privilegio] = false;
          fila.estado[privilegio] = !encender;   // revertir el interruptor
          this.error(e, 'No se pudo cambiar el privilegio');
        }
      });
    };

    if (this.rolEditorEsPropio) { aplicar(); return; }

    const objeto = `toda la tabla ${fila.tabla}`;
    const consecuencia = encender
      ? `${this.rolEditor} pasará a ${this.queGana(privilegio, objeto)}. Es un rol EN USO: `
        + 'el cambio afecta a los usuarios que lo tienen. Queda en la auditoría con tu usuario.'
      : `${this.rolEditor} dejará de ${this.quePierde(privilegio, objeto)}. PUEDE DEJAR `
        + 'PANTALLAS SIN DATOS: el motor responde 42501 (403) o, con RLS de por medio, CERO '
        + 'FILAS sin mensaje. Es REVERSIBLE desde este mismo interruptor.';

    this.confirmar.confirmar({
      titulo: encender ? 'Conceder privilegio en el motor' : 'Revocar privilegio en el motor',
      mensaje: `¿${encender ? 'Conceder' : 'Revocar'} ${privilegio} sobre ${objeto} `
             + `${encender ? 'al' : 'al'} rol ${this.rolEditor}?`,
      consecuencia,
      textoAceptar: encender ? 'Conceder' : 'Revocar',
      tono: encender ? 'normal' : 'peligro'
    }).subscribe(ok => {
      if (ok) { aplicar(); } else { fila.estado[privilegio] = !encender; }
    });
  }

  /** Abre/cierra el detalle por columnas de una tabla. */
  alternarDetalle(fila: FilaEditor): void {
    if (this.tablaExpandida === fila.tabla) { this.tablaExpandida = null; return; }
    this.tablaExpandida = fila.tabla;
    const propias = this.columnasDelRol.filter(c => c.tabla === fila.tabla);
    this.columnasEditor = fila.columnas.map(col => {
      const estado: Record<string, boolean> = {};
      const cubierta: Record<string, boolean> = {};
      const ocupado: Record<string, boolean> = {};
      for (const p of ['SELECT', 'INSERT', 'UPDATE']) {
        const e = propias.find(c => c.columna === col && c.privilegio === p);
        estado[p] = !!e;
        cubierta[p] = fila.estado[p];   // el de TABLA ya la incluye
        ocupado[p] = false;
      }
      return { columna: col, estado, cubierta, ocupado };
    });
  }

  alternarColumna(fila: FilaEditor, col: { columna: string; estado: Record<string, boolean>;
                  ocupado: Record<string, boolean> }, privilegio: string, encender: boolean): void {
    col.ocupado[privilegio] = true;
    const peticion = { rol: this.rolEditor, tabla: fila.tabla,
                       columna: col.columna, privilegio };
    const obs = encender ? this.srv.conceder(peticion) : this.srv.revocar(peticion);
    obs.subscribe({
      next: r => {
        col.ocupado[privilegio] = false;
        this.snackBar.open(r.mensaje, 'Cerrar', { duration: r.aplicado ? 3500 : 9000 });
        this.cargarEditor();
      },
      error: e => {
        col.ocupado[privilegio] = false;
        col.estado[privilegio] = !encender;
        this.error(e, 'No se pudo cambiar el privilegio de columna');
      }
    });
  }

  // ══════════ Crear y eliminar roles propios ══════════

  crearRol(): void {
    const rolesBase = this.roles
      .filter(r => r.clase === 'grupo' && r.rol_app)
      .map(r => ({ codigo: r.rol_app as string, nombre: r.rol_app as string }));

    this.dialog.open(RolDialogComponent, {
      data: { modo: 'nuevo', rolesBase }, panelClass: 'dubai-dialog', autoFocus: false
    }).afterClosed().subscribe((res: RolDialogResultado | undefined) => {
      if (!res) { return; }
      this.guardando = true;
      this.srv.crearRol(res).subscribe({
        next: r => {
          this.guardando = false;
          this.snackBar.open(r.mensaje, 'Cerrar', { duration: 12000 });
          this.rolEditor = r.rol_grupo;
          this.soloConPermisos = false;   // recién creado no tiene ninguno
          this.cargarTodo();
        },
        error: e => { this.guardando = false; this.error(e, 'No se pudo crear el rol'); }
      });
    });
  }

  eliminarRol(): void {
    const propio = this.rolPropioSeleccionado;
    if (!propio) { return; }

    const consecuencia = `Se eliminan su rol de motor «${propio.rol_grupo}», sus `
      + `${propio.politicas} políticas RLS, sus ${propio.ventanas} ventanas horarias y `
      + 'TODOS sus privilegios. ESTA ACCIÓN NO SE PUEDE DESHACER: para recuperarlo habría '
      + 'que crearlo de nuevo y volver a encender sus interruptores. Los 9 roles del '
      + 'sistema no se ven afectados.';

    this.confirmar.confirmar({
      titulo: 'Eliminar rol propio',
      mensaje: `¿Eliminar el rol «${propio.codigo}» (${propio.rol_grupo})?`,
      consecuencia,
      textoAceptar: 'Eliminar',
      tono: 'peligro'
    }).subscribe(ok => {
      if (!ok) { return; }
      this.guardando = true;
      this.srv.eliminarRol(propio.codigo).subscribe({
        next: r => {
          this.guardando = false;
          this.snackBar.open(r.mensaje, 'Cerrar', { duration: 9000 });
          this.rolEditor = '';
          this.filasEditor = [];
          this.cargarTodo();
        },
        error: e => { this.guardando = false; this.error(e, 'No se pudo eliminar el rol'); }
      });
    });
  }

  private error(e: unknown, porDefecto: string): void {
    this.snackBar.open(mensajeError(e, porDefecto), 'Cerrar', { duration: 8000 });
  }
}
