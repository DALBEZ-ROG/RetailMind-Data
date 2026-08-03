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
import { VarianteAdmin } from '../../../core/models/operativo.model';
import { VarianteBody } from '../../../core/services/catalogo-admin.service';

export interface VarianteDialogData {
  productoNombre: string;
  /** Presente en 'actualizar' y 'consulta' (SKU/precio/costo, precargados). */
  variante?: VarianteAdmin;
  modo: ModoFormulario;
}

/** Igual que en producto: `activo` viaja aparte, por su propio endpoint. */
export type VarianteDialogResultado = VarianteBody & { activo: boolean };

/**
 * Alta / edición / consulta de variante (SKU) en modal estilo Dubai,
 * con el mismo patrón que el diálogo de producto: chip de modo (regla 3)
 * y dos botones, Aceptar y Cancelar (regla 4).
 */
@Component({
  selector: 'app-variante-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatIconModule, ModoFormComponent],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>style</mat-icon>
      Variante
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <p class="sub">Producto: <strong>{{ data.productoNombre }}</strong></p>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>SKU</mat-label>
          <input matInput [(ngModel)]="form.sku" [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Precio</mat-label>
          <input matInput type="number" min="0" step="0.01" [(ngModel)]="form.precio"
                 [disabled]="soloLectura" required>
          <span matTextPrefix>$&nbsp;</span>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Costo</mat-label>
          <input matInput type="number" min="0" step="0.01" [(ngModel)]="form.costo"
                 [disabled]="soloLectura">
          <span matTextPrefix>$&nbsp;</span>
        </mat-form-field>
        <mat-form-field appearance="outline" *ngIf="esNuevo">
          <mat-label>Código de barras</mat-label>
          <input matInput [(ngModel)]="form.codigoBarras">
        </mat-form-field>
      </div>

      <div class="banderas">
        <mat-checkbox *ngIf="esNuevo" [(ngModel)]="form.esPredeterminada">
          Variante predeterminada
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
    mat-dialog-content { min-width: min(560px, 80vw); }
    .sub { font-size: 13px; color: var(--text-secondary); margin: 0 0 12px; }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 8px 16px;
    }
    .banderas { display: flex; flex-wrap: wrap; gap: 8px 28px; }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class VarianteDialogComponent {

  form: VarianteDialogResultado;

  constructor(public dialogRef: MatDialogRef<VarianteDialogComponent, VarianteDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: VarianteDialogData) {
    const v = data.variante;
    // Precarga total fuera del alta: nada de campos vacíos que obliguen a reescribir.
    this.form = {
      sku: v?.sku ?? '',
      precio: v != null ? Number(v.precio) : 0,
      costo: v != null ? Number(v.costo) : 0,
      codigoBarras: '',
      esPredeterminada: false,
      activo: v?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean { return !!this.form.sku.trim() && this.form.precio > 0; }

  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (!this.valido) return;
    // Sin código de barras se manda null, no ''. La columna es UNIQUE y en
    // Postgres los NULL no colisionan entre sí, pero dos cadenas vacías sí:
    // con '' la segunda variante sin código rebota con un 400 del motor.
    this.dialogRef.close({
      ...this.form,
      codigoBarras: this.form.codigoBarras?.trim() || null
    });
  }
}
