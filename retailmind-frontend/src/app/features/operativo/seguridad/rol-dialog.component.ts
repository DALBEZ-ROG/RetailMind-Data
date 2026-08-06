import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import {
  ModoFormComponent, ModoFormulario
} from '../../../core/components/modo-form/modo-form.component';

export interface RolDialogData {
  modo: ModoFormulario;
  /** Los 9 códigos del sistema, para elegir a cuál imita en las rutas. */
  rolesBase: { codigo: string; nombre: string }[];
}

export interface RolDialogResultado {
  codigo: string;
  nombre: string;
  rolBase: string | null;
}

/**
 * Alta de un ROL PROPIO (script 87).
 *
 * El campo que más se malinterpreta es «Rol base»: NO concede nada en la base
 * de datos —no hay herencia ni membresía— y solo decide qué PANTALLAS ve su
 * usuario, porque la autorización de rutas de Spring es código compilado que no
 * conoce roles creados en caliente. Los privilegios reales se encienden después
 * con los interruptores, y son los que deciden qué DATOS ve.
 */
@Component({
  selector: 'app-rol-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, ModoFormComponent],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>badge</mat-icon>
      Rol propio
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <p class="ayuda">
        Se crea un rol de verdad en PostgreSQL con las <strong>seis piezas</strong> que
        necesita para funcionar: rol <code>NOLOGIN</code>, <code>USAGE</code> sobre el
        esquema, membresía en <code>retailmind_app</code>, 7 ventanas horarias,
        <strong>una política RLS por cada tabla con RLS</strong> y su fila en la tabla de
        roles. Nace <strong>sin ningún privilegio</strong>.
      </p>

      <mat-form-field appearance="outline">
        <mat-label>Código</mat-label>
        <input matInput [(ngModel)]="codigo" (ngModelChange)="alEscribirCodigo()"
               placeholder="PRUEBA" maxlength="21">
        <mat-hint>
          Mayúsculas, dígitos y guion bajo. Rol de motor:
          <code>{{ rolMotor || 'grp_…' }}</code>
        </mat-hint>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Nombre</mat-label>
        <input matInput [(ngModel)]="nombre" placeholder="Rol de prueba" maxlength="100">
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Imita para las pantallas a…</mat-label>
        <mat-select [(ngModel)]="rolBase">
          <mat-option [value]="null">— Ninguno (sin pantallas) —</mat-option>
          <mat-option *ngFor="let r of data.rolesBase" [value]="r.codigo">
            {{ r.nombre }} ({{ r.codigo }})
          </mat-option>
        </mat-select>
        <mat-hint>Decide qué MENÚS ve. No le concede ningún dato.</mat-hint>
      </mat-form-field>

      <p class="ayuda aviso">
        <mat-icon>info</mat-icon>
        <span>
          Sin rol base, su usuario podrá iniciar sesión pero no verá ninguna pantalla:
          <code>SecurityConfig</code> enumera los 9 códigos del sistema y no conoce este.
          Con rol base, ve <em>sus</em> pantallas pero <strong>solo los datos</strong> que
          le permitan los interruptores.
        </span>
      </p>
      <p class="error-codigo" *ngIf="codigo && !codigoValido">
        <mat-icon>error</mat-icon>
        El código debe empezar por letra y tener de 3 a 21 caracteres (A–Z, 0–9, _).
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { display: flex; flex-direction: column; gap: 4px; min-width: 480px; }
    .ayuda {
      margin: 0 0 14px; font-size: 12px; line-height: 1.55; color: var(--text-secondary);
    }
    .ayuda code { background: rgba(26,35,126,.07); padding: 1px 5px; border-radius: 4px; }
    .aviso {
      display: flex; gap: 8px; align-items: flex-start; margin-top: 2px;
      padding: 8px 12px; border-radius: 8px; background: rgba(26,35,126,.05);
      border-left: 3px solid rgba(26,35,126,.25);
      mat-icon { font-size: 16px; width: 16px; height: 16px; opacity: .7; flex: 0 0 auto; }
    }
    .error-codigo {
      display: flex; gap: 6px; align-items: center; margin: 0;
      font-size: 12px; color: #c62828; font-weight: 600;
      mat-icon { font-size: 16px; width: 16px; height: 16px; }
    }
  `]
})
export class RolDialogComponent {

  codigo = '';
  nombre = '';
  rolBase: string | null = null;

  constructor(
    public dialogRef: MatDialogRef<RolDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: RolDialogData
  ) {}

  /** El mismo patrón que valida la función del script 87. */
  get codigoValido(): boolean {
    return /^[A-Z][A-Z0-9_]{2,20}$/.test(this.codigo);
  }

  /** Se deriva por convención, igual que en el motor: grp_ + minúsculas. */
  get rolMotor(): string {
    return this.codigoValido ? 'grp_' + this.codigo.toLowerCase() : '';
  }

  get puedeAceptar(): boolean {
    return this.codigoValido && this.nombre.trim().length > 0;
  }

  /** Se escribe en mayúsculas para que lo que se ve sea lo que se guarda. */
  alEscribirCodigo(): void {
    this.codigo = (this.codigo || '').toUpperCase().replace(/[^A-Z0-9_]/g, '');
  }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (!this.puedeAceptar) { return; }
    this.dialogRef.close({
      codigo: this.codigo,
      nombre: this.nombre.trim(),
      rolBase: this.rolBase
    } as RolDialogResultado);
  }
}
