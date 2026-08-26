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
import { CategoriaTicketRow } from '../../../core/models/operativo.model';

import { CampoTextoDirective } from '../../../core/validacion';

export interface CategoriaTicketDialogData {
  categoria?: CategoriaTicketRow;
  modo: ModoFormulario;
}

export interface CategoriaTicketDialogResultado {
  nombre: string; descripcion: string; prioridadDefecto: string; activo: boolean;
}

/** Alta / edición / consulta de categoría de ticket. Patrón: docs/PATRON_UI.md §5. */
@Component({
  selector: 'app-categoria-ticket-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent,
    CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>category</mat-icon>
      Categoría de ticket
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Nombre</mat-label>
          <input appTexto="nombre" exigido matInput [(ngModel)]="form.nombre" maxlength="100" [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Prioridad por defecto</mat-label>
          <mat-select [(ngModel)]="form.prioridadDefecto" [disabled]="soloLectura">
            <mat-option *ngFor="let p of prioridades" [value]="p">{{ p }}</mat-option>
          </mat-select>
          <mat-hint>El ticket nace con esta prioridad; el cliente no la elige</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Descripción</mat-label>
          <input appTexto="libre" matInput [(ngModel)]="form.descripcion" [disabled]="soloLectura">
        </mat-form-field>
      </div>

      <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
        Activa (si se desmarca, equivale a eliminar)
      </mat-checkbox>

      <p class="hint" *ngIf="!esNuevo && data.categoria">
        En uso: {{ data.categoria.tickets }} ticket/s y {{ data.categoria.faqs }} FAQ.
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(600px, 80vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    .hint { margin-top: 10px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class CategoriaTicketDialogComponent {

  readonly prioridades = ['baja', 'media', 'alta', 'urgente'];

  form: CategoriaTicketDialogResultado;

  constructor(
    public dialogRef: MatDialogRef<CategoriaTicketDialogComponent, CategoriaTicketDialogResultado>,
    @Inject(MAT_DIALOG_DATA) public data: CategoriaTicketDialogData) {
    const c = data.categoria;
    this.form = {
      nombre: c?.nombre ?? '',
      descripcion: c?.descripcion ?? '',
      prioridadDefecto: c?.prioridad_defecto ?? 'media',
      activo: c?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean { return !!this.form.nombre.trim(); }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
