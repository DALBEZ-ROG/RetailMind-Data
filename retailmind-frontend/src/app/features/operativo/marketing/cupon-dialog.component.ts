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
import { CuponRow } from '../../../core/models/operativo.model';

export interface CuponDialogData {
  cupon?: CuponRow;
  modo: ModoFormulario;
}

export interface CuponDialogResultado {
  codigo: string; descripcion: string; tipoDescuento: string;
  valor: number; montoMinimoPedido: number;
  usosMaximos: number | null; usosPorCliente: number;
  fechaInicio: string; fechaFin: string;
  activo: boolean;
}

/** Alta / edición / consulta de cupón. Patrón: docs/PATRON_UI.md §5. */
@Component({
  selector: 'app-cupon-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>confirmation_number</mat-icon>
      Cupón
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Código</mat-label>
          <input matInput [(ngModel)]="form.codigo" maxlength="50"
                 style="text-transform: uppercase;" [disabled]="soloLectura" required>
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
          <mat-label>Monto mínimo del pedido</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="form.montoMinimoPedido"
                 [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Usos máximos (vacío = ilimitado)</mat-label>
          <input matInput type="number" min="1" [(ngModel)]="form.usosMaximos"
                 [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Usos por cliente</mat-label>
          <input matInput type="number" min="1" [(ngModel)]="form.usosPorCliente"
                 [disabled]="soloLectura">
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
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Descripción</mat-label>
          <input matInput [(ngModel)]="form.descripcion" [disabled]="soloLectura">
        </mat-form-field>
      </div>

      <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
        Activo (si se desmarca, equivale a eliminar)
      </mat-checkbox>

      <p class="hint" *ngIf="!esNuevo && data.cupon">
        Canjeado {{ data.cupon.usos_actuales }} vez/veces.
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(700px, 82vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    .hint { margin-top: 10px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class CuponDialogComponent {

  readonly tiposDescuento = ['porcentaje', 'monto_fijo', 'envio_gratis'];

  form: CuponDialogResultado;

  constructor(public dialogRef: MatDialogRef<CuponDialogComponent, CuponDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: CuponDialogData) {
    const c = data.cupon;
    this.form = {
      codigo: c?.codigo ?? '',
      descripcion: c?.descripcion ?? '',
      tipoDescuento: c?.tipo_descuento ?? 'porcentaje',
      valor: c?.valor ?? 0,
      montoMinimoPedido: c?.monto_minimo_pedido ?? 0,
      usosMaximos: c?.usos_maximos ?? null,
      usosPorCliente: c?.usos_por_cliente ?? 1,
      // `datetime-local` solo acepta 'AAAA-MM-DDTHH:mm': hay que recortar.
      fechaInicio: (c?.fecha_inicio ?? '').substring(0, 16),
      fechaFin: (c?.fecha_fin ?? '').substring(0, 16),
      activo: c?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean { return !!this.form.codigo.trim() && !!this.form.fechaInicio; }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
