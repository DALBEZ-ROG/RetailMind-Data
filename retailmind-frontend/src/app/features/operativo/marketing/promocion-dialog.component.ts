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
import { PromocionRow } from '../../../core/models/operativo.model';

export interface PromocionDialogData {
  promocion?: PromocionRow;
  modo: ModoFormulario;
}

export interface PromocionDialogResultado {
  nombre: string; descripcion: string; tipoDescuento: string; valor: number;
  fechaInicio: string; fechaFin: string; prioridad: number; acumulable: boolean;
  activo: boolean;
}

/** Alta / edición / consulta de promoción. Patrón: docs/PATRON_UI.md §5. */
@Component({
  selector: 'app-promocion-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>local_offer</mat-icon>
      Promoción
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Nombre</mat-label>
          <input matInput [(ngModel)]="form.nombre" [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Tipo de descuento</mat-label>
          <mat-select [(ngModel)]="form.tipoDescuento" [disabled]="soloLectura">
            <mat-option *ngFor="let t of tiposDescuento" [value]="t">{{ t }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Valor {{ form.tipoDescuento === 'porcentaje' ? '(%)' : '(USD)' }}</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="form.valor" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Vigente desde</mat-label>
          <input matInput type="datetime-local" [(ngModel)]="form.fechaInicio"
                 [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Vigente hasta (opcional)</mat-label>
          <input matInput type="datetime-local" [(ngModel)]="form.fechaFin" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Prioridad (gana la más alta)</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="form.prioridad" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Descripción</mat-label>
          <input matInput [(ngModel)]="form.descripcion" [disabled]="soloLectura">
        </mat-form-field>
      </div>

      <div class="banderas">
        <mat-checkbox [(ngModel)]="form.acumulable" [disabled]="soloLectura">
          Acumulable con otras promociones
        </mat-checkbox>
        <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
          Activa (si se desmarca, equivale a eliminar)
        </mat-checkbox>
      </div>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(680px, 82vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    .banderas { display: flex; flex-wrap: wrap; gap: 8px 28px; }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class PromocionDialogComponent {

  readonly tiposDescuento = ['porcentaje', 'monto_fijo'];

  form: PromocionDialogResultado;

  constructor(public dialogRef: MatDialogRef<PromocionDialogComponent, PromocionDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: PromocionDialogData) {
    const p = data.promocion;
    this.form = {
      nombre: p?.nombre ?? '',
      descripcion: p?.descripcion ?? '',
      tipoDescuento: p?.tipo_descuento ?? 'porcentaje',
      valor: p?.valor ?? 0,
      fechaInicio: (p?.fecha_inicio ?? '').substring(0, 16),
      fechaFin: (p?.fecha_fin ?? '').substring(0, 16),
      prioridad: p?.prioridad ?? 0,
      acumulable: p?.acumulable ?? false,
      activo: p?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean { return !!this.form.nombre.trim() && !!this.form.fechaInicio; }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
