import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import {
  ModoFormComponent, ModoFormulario
} from '../../../core/components/modo-form/modo-form.component';
import { SuscriptorRow } from '../../../core/models/operativo.model';

export interface SuscriptorDialogData {
  suscriptor?: SuscriptorRow;
  modo: ModoFormulario;
}

export interface SuscriptorDialogResultado {
  email: string;
  activo: boolean;
}

/**
 * Alta / gestión / consulta de suscriptor del boletín.
 *
 * El backend NO tiene endpoint de edición: un suscriptor ES su email, y
 * cambiarlo sería otro suscriptor distinto. Por eso, fuera del alta el email
 * se muestra en solo lectura y lo único que Modificar gobierna es el estado
 * de la suscripción — que es también lo que hace falta para REACTIVAR a
 * alguien dado de baja.
 */
@Component({
  selector: 'app-suscriptor-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatIconModule, ModoFormComponent],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>mail</mat-icon>
      Suscriptor
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <mat-form-field appearance="outline" class="ancho">
        <mat-label>Email</mat-label>
        <input matInput type="email" [(ngModel)]="form.email" maxlength="255"
               [disabled]="!esNuevo" required>
        <mat-hint *ngIf="!esNuevo">El email identifica al suscriptor y no se cambia</mat-hint>
      </mat-form-field>

      <div class="datos" *ngIf="!esNuevo && data.suscriptor">
        <div><span>Cliente:</span> {{ data.suscriptor.cliente || '—' }}</div>
        <div><span>Confirmado:</span> {{ data.suscriptor.confirmado ? 'Sí' : 'No' }}</div>
        <div><span>Alta:</span> {{ data.suscriptor.fecha_suscripcion | date:'dd/MM/yy HH:mm' }}</div>
        <div *ngIf="data.suscriptor.fecha_baja">
          <span>Baja:</span> {{ data.suscriptor.fecha_baja | date:'dd/MM/yy HH:mm' }}
        </div>
      </div>

      <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
        Suscripción activa (si se desmarca, equivale a eliminar)
      </mat-checkbox>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(480px, 80vw); }
    .ancho { width: 100%; }
    .datos {
      display: grid;
      gap: 4px;
      margin: 4px 0 14px;
      font-size: 13px;
      color: var(--text-secondary);
      span { color: var(--text-light); }
    }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class SuscriptorDialogComponent {

  form: SuscriptorDialogResultado;

  constructor(public dialogRef: MatDialogRef<SuscriptorDialogComponent, SuscriptorDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: SuscriptorDialogData) {
    const s = data.suscriptor;
    this.form = { email: s?.email ?? '', activo: s?.activo ?? true };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean {
    return this.esNuevo ? this.form.email.trim().includes('@') : true;
  }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close({ ...this.form, email: this.form.email.trim() });
  }
}
