import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatRadioModule } from '@angular/material/radio';
import {
  ModoFormComponent, ModoFormulario
} from '../../../core/components/modo-form/modo-form.component';
import { ObjetoAdministrable } from '../../../core/models/seguridad.model';

export interface PermisoDialogData {
  modo: ModoFormulario;
  roles: string[];
  objetos: ObjetoAdministrable[];
  /** Precarga cuando se concede desde una fila ya seleccionada. */
  inicial?: { rol?: string; tabla?: string; columna?: string | null; privilegio?: string };
}

export interface PermisoDialogResultado {
  rol: string;
  tabla: string;
  columna: string | null;
  privilegio: string;
}

/**
 * Formulario de un privilegio de motor (regla 3 y 4 del patrón de interfaz).
 *
 * Solo se usa en modo «nuevo» (conceder): un GRANT es una ASOCIACIÓN
 * rol × objeto × privilegio, no un registro con campos que modificar —el mismo
 * caso que `promocion_producto` en §8.11 del patrón—. Revocar no abre este
 * diálogo: actúa sobre la fila seleccionada y solo pide confirmación.
 *
 * El alcance (TABLA o COLUMNA) es un radio y no una casilla porque son dos
 * cosas distintas de verdad: el de tabla alcanza la fila entera y el de columna
 * es la excepción quirúrgica con la que este sistema implementa la segregación
 * financiera. DELETE desaparece del desplegable al elegir COLUMNA porque no
 * existe como privilegio de columna en PostgreSQL: se borra la fila completa.
 */
@Component({
  selector: 'app-permiso-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, MatRadioModule, ModoFormComponent],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>vpn_key</mat-icon>
      Conceder privilegio
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <p class="ayuda">
        El privilegio se aplica en el MOTOR con un <code>GRANT</code> real. La pantalla
        confirmará el efecto leyendo el catálogo después de ejecutarlo.
      </p>

      <mat-form-field appearance="outline">
        <mat-label>Rol de grupo</mat-label>
        <mat-select [(ngModel)]="form.rol">
          <mat-option *ngFor="let r of data.roles" [value]="r">{{ r }}</mat-option>
        </mat-select>
        <mat-hint>grp_administrador no aparece: es el rol con el que operas.</mat-hint>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Tabla</mat-label>
        <mat-select [(ngModel)]="form.tabla" (selectionChange)="alCambiarTabla()">
          <mat-option *ngFor="let o of data.objetos" [value]="o.tabla">{{ o.tabla }}</mat-option>
        </mat-select>
        <mat-hint>Las 8 tablas del núcleo de identidad y seguridad no se listan.</mat-hint>
      </mat-form-field>

      <div class="alcance">
        <span class="alcance-lbl">Alcance</span>
        <mat-radio-group [(ngModel)]="alcance" (ngModelChange)="alCambiarAlcance()">
          <mat-radio-button value="tabla">Toda la tabla</mat-radio-button>
          <mat-radio-button value="columna" [disabled]="!columnas.length">
            Una columna
          </mat-radio-button>
        </mat-radio-group>
      </div>

      <mat-form-field appearance="outline" *ngIf="alcance === 'columna'">
        <mat-label>Columna</mat-label>
        <mat-select [(ngModel)]="form.columna">
          <mat-option *ngFor="let c of columnas" [value]="c">{{ c }}</mat-option>
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Privilegio</mat-label>
        <mat-select [(ngModel)]="form.privilegio">
          <mat-option *ngFor="let p of privilegios" [value]="p">{{ p }}</mat-option>
        </mat-select>
        <mat-hint *ngIf="alcance === 'columna'">
          DELETE no existe por columna: se borra la fila entera.
        </mat-hint>
      </mat-form-field>

      <p class="ayuda aviso" *ngIf="alcance === 'columna'">
        <mat-icon>info</mat-icon>
        Los privilegios de columna solo <strong>SUMAN</strong>. Si el rol ya tiene este
        privilegio sobre toda la tabla, conceder la columna no cambia nada.
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { display: flex; flex-direction: column; gap: 4px; min-width: 460px; }
    .ayuda {
      margin: 0 0 12px; font-size: 12px; line-height: 1.5; color: var(--text-secondary);
    }
    .ayuda code { background: rgba(26,35,126,.07); padding: 1px 5px; border-radius: 4px; }
    .aviso {
      display: flex; gap: 8px; align-items: flex-start; margin-top: 4px;
      padding: 8px 12px; border-radius: 8px; background: rgba(26,35,126,.05);
      border-left: 3px solid rgba(26,35,126,.25);
      mat-icon { font-size: 16px; width: 16px; height: 16px; opacity: .7; }
    }
    .alcance { margin: 4px 0 16px; }
    .alcance-lbl {
      display: block; font-size: 11px; text-transform: uppercase; letter-spacing: .5px;
      color: var(--text-secondary); margin-bottom: 6px;
    }
    mat-radio-button { margin-right: 20px; }
  `]
})
export class PermisoDialogComponent {

  alcance: 'tabla' | 'columna' = 'tabla';
  columnas: string[] = [];

  form: PermisoDialogResultado = {
    rol: '', tabla: '', columna: null, privilegio: 'SELECT'
  };

  constructor(
    public dialogRef: MatDialogRef<PermisoDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: PermisoDialogData
  ) {
    const i = data.inicial;
    if (i) {
      this.form.rol = i.rol ?? '';
      this.form.tabla = i.tabla ?? '';
      this.form.privilegio = i.privilegio ?? 'SELECT';
      if (i.columna) { this.alcance = 'columna'; this.form.columna = i.columna; }
      this.alCambiarTabla(false);
    }
  }

  /** DELETE solo tiene sentido sobre la tabla entera. */
  get privilegios(): string[] {
    return this.alcance === 'columna'
      ? ['SELECT', 'INSERT', 'UPDATE']
      : ['SELECT', 'INSERT', 'UPDATE', 'DELETE'];
  }

  get puedeAceptar(): boolean {
    return !!this.form.rol && !!this.form.tabla && !!this.form.privilegio
        && (this.alcance === 'tabla' || !!this.form.columna);
  }

  alCambiarTabla(limpiarColumna = true): void {
    const obj = this.data.objetos.find(o => o.tabla === this.form.tabla);
    this.columnas = obj ? obj.columnas.split(',') : [];
    if (limpiarColumna) { this.form.columna = null; }
    if (!this.columnas.length) { this.alcance = 'tabla'; }
  }

  /** Cambiar a TABLA borra la columna; si no, viajaría un valor invisible. */
  alCambiarAlcance(): void {
    if (this.alcance === 'tabla') {
      this.form.columna = null;
    }
    if (!this.privilegios.includes(this.form.privilegio)) {
      this.form.privilegio = 'SELECT';
    }
  }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (!this.puedeAceptar) { return; }
    this.dialogRef.close({
      ...this.form,
      columna: this.alcance === 'columna' ? this.form.columna : null
    });
  }
}
