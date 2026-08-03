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
import { CampanaRow } from '../../../core/models/operativo.model';

export interface CampanaDialogData {
  campana?: CampanaRow;
  modo: ModoFormulario;
}

/**
 * La campaña NO tiene bandera `activo`: su ciclo de vida es el `estado`
 * (borrador → activa → pausada → finalizada), con su propio endpoint PATCH.
 * Viaja igual que `activo` en el resto de pantallas: aparte del cuerpo.
 */
export interface CampanaDialogResultado {
  nombre: string; descripcion: string; canal: string;
  presupuesto: number | null; fechaInicio: string; fechaFin: string;
  estado: string;
}

/** Alta / edición / consulta de campaña. Patrón: docs/PATRON_UI.md §5. */
@Component({
  selector: 'app-campana-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, ModoFormComponent],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>campaign</mat-icon>
      Campaña
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Nombre</mat-label>
          <input matInput [(ngModel)]="form.nombre" [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Canal</mat-label>
          <mat-select [(ngModel)]="form.canal" [disabled]="soloLectura">
            <mat-option *ngFor="let c of canales" [value]="c">{{ c }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Presupuesto (USD)</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="form.presupuesto"
                 [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Desde</mat-label>
          <input matInput type="date" [(ngModel)]="form.fechaInicio" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Hasta</mat-label>
          <input matInput type="date" [(ngModel)]="form.fechaFin" [disabled]="soloLectura">
        </mat-form-field>

        <!-- El ciclo de vida (activar / pausar / finalizar) se gobierna DESDE
             AQUÍ, para no añadir botones sueltos que romperían la regla 4. -->
        <mat-form-field appearance="outline" *ngIf="!esNuevo">
          <mat-label>Estado</mat-label>
          <mat-select [(ngModel)]="form.estado" [disabled]="soloLectura || yaFinalizada">
            <mat-option *ngFor="let e of estados" [value]="e">{{ e }}</mat-option>
          </mat-select>
          <mat-hint *ngIf="yaFinalizada">Una campaña finalizada ya no cambia de estado</mat-hint>
          <mat-hint *ngIf="!yaFinalizada">«finalizada» es terminal: no se puede deshacer</mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Descripción</mat-label>
          <input matInput [(ngModel)]="form.descripcion" [disabled]="soloLectura">
        </mat-form-field>
      </div>

      <p class="hint" *ngIf="esNuevo">La campaña nace en estado «borrador».</p>
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
    .hint { margin-top: 10px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class CampanaDialogComponent {

  readonly canales = ['email', 'redes', 'web', 'sms', 'mixto'];
  readonly estados = ['borrador', 'activa', 'pausada', 'finalizada'];

  form: CampanaDialogResultado;

  constructor(public dialogRef: MatDialogRef<CampanaDialogComponent, CampanaDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: CampanaDialogData) {
    const c = data.campana;
    this.form = {
      nombre: c?.nombre ?? '',
      descripcion: c?.descripcion ?? '',
      canal: c?.canal ?? 'web',
      presupuesto: c?.presupuesto ?? null,
      // `date` (no `datetime-local`): la campaña se mide en días.
      fechaInicio: (c?.fecha_inicio ?? '').substring(0, 10),
      fechaFin: (c?.fecha_fin ?? '').substring(0, 10),
      estado: c?.estado ?? 'borrador'
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }
  get yaFinalizada(): boolean { return this.data.campana?.estado === 'finalizada'; }

  get valido(): boolean { return !!this.form.nombre.trim(); }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
