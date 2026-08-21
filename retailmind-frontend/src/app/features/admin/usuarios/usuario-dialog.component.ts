import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import {
  ModoFormComponent, ModoFormulario
} from '../../../core/components/modo-form/modo-form.component';
import { UsuarioAdminRow, RolRef } from '../../../core/models/operativo.model';

import { CampoTextoDirective } from '../../../core/validacion';

export interface UsuarioDialogData {
  usuario?: UsuarioAdminRow;
  modo: ModoFormulario;
  roles: RolRef[];
}

export interface UsuarioDialogResultado {
  email: string;
  password: string;        // SOLO en Modo Nuevo; en el resto viaja vacío y se descarta
  nombre: string;
  apellido: string;
  telefono: string;
  rol: string;
  activo: boolean;
}

/**
 * Alta / modificación / consulta de usuario. Patrón: docs/PATRON_UI.md §5.
 *
 * Dos campos son especiales y el formulario lo DICE con un `mat-hint`:
 * - **Email**: es la credencial de login y la clave por la que le apunta media
 *   aplicación. Solo se escribe al crear; después queda en solo lectura.
 * - **Contraseña**: solo existe en Modo Nuevo. Cambiarla no es «modificar un
 *   registro», es un caso de uso propio (con verificación de identidad) que el
 *   backend no expone — y el hash NUNCA vuelve del servidor.
 */
@Component({
  selector: 'app-usuario-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent,
    CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>person</mat-icon>
      Usuario
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Correo electrónico (usuario de acceso)</mat-label>
          <input appTexto="email" exigido matInput type="email" [(ngModel)]="form.email"
                 [disabled]="!esNuevo" required>
          <mat-hint *ngIf="!esNuevo">
            El correo es la credencial de login y no se puede cambiar.
          </mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" class="ancho" *ngIf="esNuevo">
          <mat-label>Contraseña</mat-label>
          <input matInput type="password" [(ngModel)]="form.password" required>
          <mat-hint>Mínimo 6 caracteres. Solo se define al crear la cuenta.</mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Nombre</mat-label>
          <input appTexto="nombre" exigido matInput [(ngModel)]="form.nombre" maxlength="100"
                 [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Apellido</mat-label>
          <input appTexto="nombre" matInput [(ngModel)]="form.apellido" maxlength="100" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Teléfono (opcional)</mat-label>
          <input appTexto="telefono" matInput [(ngModel)]="form.telefono" maxlength="30" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Rol</mat-label>
          <mat-select [(ngModel)]="form.rol" [disabled]="soloLectura || rolBloqueado">
            <mat-option *ngFor="let r of data.roles" [value]="r.codigo">
              {{ r.codigo }} — {{ r.nombre }}
            </mat-option>
          </mat-select>
          <mat-hint *ngIf="rolBloqueado">{{ motivoRolBloqueado }}</mat-hint>
        </mat-form-field>
      </div>

      <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
        Activo (si se desmarca, equivale a eliminar)
      </mat-checkbox>

      <p class="hint" *ngIf="!esNuevo && data.usuario">
        Creado el {{ data.usuario.fechaCreacion | date:'dd/MM/yyyy HH:mm' }}.
        Último acceso:
        {{ data.usuario.ultimoAcceso ? (data.usuario.ultimoAcceso | date:'dd/MM/yyyy HH:mm')
                                     : 'nunca ha entrado' }}.
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(640px, 82vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    .hint { margin-top: 12px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class UsuarioDialogComponent {

  /** Correo del administrador semilla: su rol no se puede tocar. */
  private static readonly ADMIN_SEMILLA = 'admin@retailmind.com';

  form: UsuarioDialogResultado;

  constructor(public dialogRef: MatDialogRef<UsuarioDialogComponent, UsuarioDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: UsuarioDialogData) {
    const u = data.usuario;
    this.form = {
      email: u?.username ?? '',
      password: '',
      nombre: u?.soloNombre ?? '',
      apellido: u?.apellido ?? '',
      telefono: u?.telefono ?? '',
      rol: u?.rol ?? 'CLIENTE',
      activo: u?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  /**
   * El backend rechaza estos dos cambios con un 409; el formulario lo dice
   * ANTES en vez de dejar que el usuario descubra el error al aceptar.
   */
  get rolBloqueado(): boolean {
    if (this.esNuevo || !this.data.usuario) return false;
    return this.esAdminSemilla || this.data.usuario.rol === 'CLIENTE';
  }

  get motivoRolBloqueado(): string {
    if (this.esAdminSemilla) return 'El administrador del sistema no puede cambiar de rol.';
    return 'Un CLIENTE no puede convertirse en personal interno: su ficha de cliente y sus '
         + 'pedidos quedarían huérfanos.';
  }

  private get esAdminSemilla(): boolean {
    return (this.data.usuario?.username ?? '').toLowerCase()
        === UsuarioDialogComponent.ADMIN_SEMILLA;
  }

  get valido(): boolean {
    if (!this.form.nombre.trim() || !this.form.rol) return false;
    if (!this.esNuevo) return true;
    const email = this.form.email.trim();
    return email.includes('@') && email.length > 3 && this.form.password.length >= 6;
  }

  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
