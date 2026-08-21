import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { of, switchMap } from 'rxjs';
import { UsuariosAdminService } from '../../../core/services/usuarios-admin.service';
import { AuthService } from '../../../core/services/auth.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import { UsuarioAdminRow, RolRef } from '../../../core/models/operativo.model';
import {
  UsuarioDialogComponent, UsuarioDialogData, UsuarioDialogResultado
} from './usuario-dialog.component';

import { CampoTextoDirective } from '../../../core/validacion';

type FiltroEstado = 'todos' | 'activos' | 'eliminados';

/** Correo del administrador semilla (DataInitializer.ADMIN_EMAIL). */
const ADMIN_SEMILLA = 'admin@retailmind.com';

/**
 * Gestión de usuarios, alineada al patrón (docs/PATRON_UI.md).
 *
 * «Eliminar» es la BAJA LÓGICA (`PATCH .../activo` con false): el usuario deja
 * de poder iniciar sesión y todo su rastro —pedidos que vendió, tickets que
 * atendió, auditoría— se conserva. El borrado FÍSICO del endpoint viejo
 * (`DELETE`) no se usa desde aquí: con 32 claves foráneas apuntando a
 * `usuario`, la mitad de ellas RESTRICT/NO ACTION, fallaba para cualquier
 * cliente y para casi todo el personal.
 */
@Component({
  selector: 'app-admin-usuarios',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatPaginatorModule,
    MatSnackBarModule, MatTooltipModule, MatDialogModule, AccionesRegistroComponent,
    CampoTextoDirective
  ],
  templateUrl: './admin-usuarios.component.html',
  styleUrl: '../../operativo/operativo-shared.scss'
})
export class AdminUsuariosComponent implements OnInit {

  private todos: UsuarioAdminRow[] = [];
  /** Resultado de los criterios (todas las páginas). */
  private filtrados: UsuarioAdminRow[] = [];
  /** Lo que se pinta: la página actual. */
  usuarios: UsuarioAdminRow[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroRol = 'todos';
  filtroEstado: FiltroEstado = 'todos';

  roles: RolRef[] = [];

  // Paginación en cliente: la lista llega entera (≈90 filas)
  pagina = 0;
  tamPagina = 15;

  filaSeleccionada: UsuarioAdminRow | null = null;

  columnas = ['email', 'nombre', 'rol', 'ultimo', 'creado', 'activo'];

  constructor(private servicio: UsuariosAdminService, private auth: AuthService,
              private snackBar: MatSnackBar, private dialog: MatDialog,
              private confirmar: ConfirmService) {}

  ngOnInit(): void {
    this.servicio.roles().subscribe({ next: r => this.roles = r, error: () => this.roles = [] });
    this.cargar();
  }

  get total(): number { return this.filtrados.length; }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    this.servicio.usuarios().subscribe({
      next: data => { this.todos = data; this.aplicarFiltros(); this.loading = false; },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudo cargar la lista de usuarios'),
          'Cerrar', { duration: 4000 });
      }
    });
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.filtrados = this.todos.filter(u => {
      if (this.filtroEstado === 'activos' && !u.activo) return false;
      if (this.filtroEstado === 'eliminados' && u.activo) return false;
      if (this.filtroRol !== 'todos' && (u.rol ?? '') !== this.filtroRol) return false;
      if (!q) return true;
      return u.username.toLowerCase().includes(q)
          || (u.nombre ?? '').toLowerCase().includes(q)
          || (u.telefono ?? '').toLowerCase().includes(q);
    });
    this.pagina = 0;
    this.repaginar();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroRol = 'todos';
    this.filtroEstado = 'todos';
    this.aplicarFiltros();
  }

  cambiarPagina(e: PageEvent): void {
    this.pagina = e.pageIndex;
    this.tamPagina = e.pageSize;
    this.repaginar();
  }

  private repaginar(): void {
    const desde = this.pagina * this.tamPagina;
    this.usuarios = this.filtrados.slice(desde, desde + this.tamPagina);
    this.resincronizarSeleccion();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.usuarios.find(u => u.id === this.filaSeleccionada!.id) ?? null;
  }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(u: UsuarioAdminRow): void { this.filaSeleccionada = u; }

  nuevoUsuario(): void { this.abrirDialogo('nuevo'); }
  modificarUsuario(): void { this.abrirDialogo('actualizar'); }
  verUsuario(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const usuario = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !usuario) return;

    const data: UsuarioDialogData = { usuario, modo, roles: this.roles };
    this.dialog.open(UsuarioDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: UsuarioDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(usuario!, res);
      });
  }

  private crear(res: UsuarioDialogResultado): void {
    this.servicio.crear({
      email: res.email.trim(), password: res.password, nombre: res.nombre.trim(),
      apellido: res.apellido.trim(), telefono: res.telefono.trim(), rol: res.rol
    }).subscribe({
      next: () => {
        this.snackBar.open('Usuario creado', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear el usuario'),
        'Cerrar', { duration: 4000 })
    });
  }

  /** `activo` viaja por su propio endpoint; el PUT solo lleva datos y rol. */
  private guardar(original: UsuarioAdminRow, res: UsuarioDialogResultado): void {
    this.servicio.editar(original.id, {
      nombre: res.nombre.trim(), apellido: res.apellido.trim(),
      telefono: res.telefono.trim(), rol: res.rol
    }).pipe(
      switchMap(() => res.activo === original.activo
        ? of(null)
        : this.servicio.activar(original.id, res.activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('Usuario actualizado', 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar el usuario'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (baja lógica) siempre pregunta antes ───────────

  eliminarUsuario(): void {
    const u = this.filaSeleccionada;
    if (!u || !this.puedeEliminar) return;
    this.confirmar.eliminacion(
      `el usuario «${u.username}»`,
      'Dejará de poder iniciar sesión de inmediato: el motor rechazará su acceso con el ' +
      'mismo mensaje que una contraseña incorrecta. No se borra nada: su historial ' +
      '(pedidos, tickets, movimientos y auditoría) se conserva íntegro, y puedes ' +
      'restaurarlo marcando «Activo» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.servicio.activar(u.id, false).subscribe({
        next: () => {
          this.snackBar.open(`Usuario «${u.username}» eliminado`, 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar el usuario'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get puedeEliminar(): boolean {
    const u = this.filaSeleccionada;
    if (!u || !u.activo) return false;
    if (u.username.toLowerCase() === ADMIN_SEMILLA) return false;
    return u.username.toLowerCase() !== (this.auth.getCurrentUser()?.username ?? '').toLowerCase();
  }

  get motivoNoEliminable(): string {
    const u = this.filaSeleccionada;
    if (!u) return '';
    if (!u.activo) return 'El usuario ya está eliminado (inactivo). Restáuralo desde Modificar.';
    if (u.username.toLowerCase() === ADMIN_SEMILLA) {
      return 'Es el administrador del sistema: eliminarlo dejaría la instalación sin acceso.';
    }
    if (u.username.toLowerCase() === (this.auth.getCurrentUser()?.username ?? '').toLowerCase()) {
      return 'Es tu propia cuenta: perderías el acceso al sistema en el acto.';
    }
    return '';
  }
}
