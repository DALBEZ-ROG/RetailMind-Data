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
import { MetaVentaRow } from '../../../core/models/operativo.model';

import { CampoNumeroDirective, CampoTextoDirective } from '../../../core/validacion';

export interface MetaDialogData {
  meta?: MetaVentaRow;
  modo: ModoFormulario;
  meses: string[];
  departamentos: string[];
}

export interface MetaDialogResultado {
  anio: number;
  mes: number;
  departamento: string;
  montoMeta: number | null;
  notas: string;
  activo: boolean;
}

/** Alta / edición / consulta de meta de venta. Patrón: docs/PATRON_UI.md §5. */
@Component({
  selector: 'app-meta-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent,
    CampoNumeroDirective, CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>flag</mat-icon>
      Meta de venta
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Año</mat-label>
          <input appNumero="entero" matInput type="number" min="2000" max="2100" [(ngModel)]="form.anio"
                 [disabled]="soloLectura">
          <!-- Sin validación de futuro: se cargan metas de meses pasados (histórico) -->
          <mat-hint>Admite períodos pasados (metas históricas)</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Mes</mat-label>
          <mat-select [(ngModel)]="form.mes" [disabled]="soloLectura">
            <mat-option *ngFor="let m of data.meses; let i = index" [value]="i + 1">{{ m }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Departamento</mat-label>
          <mat-select [(ngModel)]="form.departamento" [disabled]="soloLectura">
            <mat-option *ngFor="let d of data.departamentos" [value]="d">{{ d }}</mat-option>
          </mat-select>
          <mat-hint>«general» = meta global de la tienda</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Monto de la meta (USD)</mat-label>
          <input appNumero="dinero" matInput type="number" min="0.01" step="0.01" [(ngModel)]="form.montoMeta"
                 [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Notas (opcional)</mat-label>
          <input appTexto="libre" matInput [(ngModel)]="form.notas" maxlength="500" [disabled]="soloLectura">
        </mat-form-field>
      </div>

      <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
        Vigente (si se desmarca, equivale a eliminar)
      </mat-checkbox>

      <p class="hint" *ngIf="!esNuevo && data.meta">
        Fijada por {{ data.meta.fijada_por || '—' }}
        el {{ data.meta.fecha_creacion | date:'dd/MM/yyyy HH:mm' }}.
        <ng-container *ngIf="data.meta.venta_real !== null && data.meta.venta_real !== undefined">
          Facturado del período: {{ data.meta.venta_real | currency:'USD' }}.
        </ng-container>
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(620px, 82vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    .hint { margin-top: 12px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class MetaDialogComponent {

  form: MetaDialogResultado;

  constructor(public dialogRef: MatDialogRef<MetaDialogComponent, MetaDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: MetaDialogData) {
    const m = data.meta;
    const hoy = new Date();
    this.form = {
      anio: m?.anio ?? hoy.getFullYear(),
      mes: m?.mes ?? hoy.getMonth() + 1,
      departamento: m?.departamento ?? 'ventas',
      montoMeta: m?.monto_meta ?? null,
      notas: m?.notas ?? '',
      activo: m?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean {
    return !!this.form.montoMeta && this.form.montoMeta > 0
        && !!this.form.departamento && this.form.mes >= 1 && this.form.mes <= 12;
  }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
