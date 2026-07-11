import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { VarianteAdmin } from '../../../core/models/operativo.model';
import { VarianteBody } from '../../../core/services/catalogo-admin.service';

export interface VarianteDialogData {
  productoNombre: string;
  /** Presente = edición (SKU/precio/costo, precargados). */
  variante?: VarianteAdmin;
}

/** Alta/edición de variante (SKU) en modal estilo Dubai. */
@Component({
  selector: 'app-variante-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>style</mat-icon>
      {{ esEdicion ? 'Editar variante' : 'Nueva variante' }}
      <span class="sub">· {{ data.productoNombre }}</span>
    </h2>
    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>SKU</mat-label>
          <input matInput [(ngModel)]="form.sku" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Precio</mat-label>
          <input matInput type="number" min="0" step="0.01" [(ngModel)]="form.precio" required>
          <span matTextPrefix>$&nbsp;</span>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Costo</mat-label>
          <input matInput type="number" min="0" step="0.01" [(ngModel)]="form.costo">
          <span matTextPrefix>$&nbsp;</span>
        </mat-form-field>
        <mat-form-field appearance="outline" *ngIf="!esEdicion">
          <mat-label>Código de barras</mat-label>
          <input matInput [(ngModel)]="form.codigoBarras">
        </mat-form-field>
      </div>
      <mat-checkbox *ngIf="!esEdicion" [(ngModel)]="form.esPredeterminada">
        Variante predeterminada
      </mat-checkbox>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-stroked-button (click)="dialogRef.close()">Cancelar</button>
      <button mat-raised-button color="primary" [disabled]="!valido" (click)="guardar()">
        <mat-icon>save</mat-icon> {{ esEdicion ? 'Guardar cambios' : 'Crear variante' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(560px, 80vw); }
    .sub { font-weight: 500; font-size: 13px; color: var(--text-secondary); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 8px 16px;
    }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 8px; }
  `]
})
export class VarianteDialogComponent {

  form: VarianteBody;

  constructor(public dialogRef: MatDialogRef<VarianteDialogComponent, VarianteBody>,
              @Inject(MAT_DIALOG_DATA) public data: VarianteDialogData) {
    const v = data.variante;
    // Precarga total en edición: nada de campos vacíos que obliguen a reescribir.
    this.form = {
      sku: v?.sku ?? '',
      precio: v != null ? Number(v.precio) : 0,
      costo: v != null ? Number(v.costo) : 0,
      codigoBarras: '',
      esPredeterminada: false
    };
  }

  get esEdicion(): boolean { return !!this.data.variante; }

  get valido(): boolean { return !!this.form.sku.trim() && this.form.precio > 0; }

  guardar(): void {
    if (this.valido) this.dialogRef.close(this.form);
  }
}
