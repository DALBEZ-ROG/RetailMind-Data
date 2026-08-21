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
import { FaqRow, CategoriaTicketRef } from '../../../core/models/operativo.model';

import { CampoNumeroDirective, CampoTextoDirective } from '../../../core/validacion';

export interface FaqDialogData {
  faq?: FaqRow;
  categorias: CategoriaTicketRef[];
  modo: ModoFormulario;
}

export interface FaqDialogResultado {
  categoriaId: number | null; pregunta: string; respuesta: string;
  orden: number | null; activo: boolean;
}

/** Alta / edición / consulta de FAQ. Patrón: docs/PATRON_UI.md §5. */
@Component({
  selector: 'app-faq-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent,
    CampoNumeroDirective, CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>quiz</mat-icon>
      Pregunta frecuente
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Categoría (opcional)</mat-label>
          <mat-select [(ngModel)]="form.categoriaId" [disabled]="soloLectura">
            <mat-option [value]="null">— Sin categoría —</mat-option>
            <mat-option *ngFor="let c of data.categorias" [value]="c.id">{{ c.nombre }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Orden</mat-label>
          <input appNumero="entero" matInput type="number" min="0" [(ngModel)]="form.orden" [disabled]="soloLectura">
          <mat-hint>Menor primero en el centro de ayuda</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Pregunta</mat-label>
          <input appTexto="libre" exigido matInput [(ngModel)]="form.pregunta" [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Respuesta</mat-label>
          <textarea appTexto="libre" exigido matInput rows="5" [(ngModel)]="form.respuesta"
                    [disabled]="soloLectura" required></textarea>
        </mat-form-field>
      </div>

      <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
        Activa (si se desmarca, equivale a eliminar)
      </mat-checkbox>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(660px, 82vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class FaqDialogComponent {

  form: FaqDialogResultado;

  constructor(public dialogRef: MatDialogRef<FaqDialogComponent, FaqDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: FaqDialogData) {
    const f = data.faq;
    this.form = {
      categoriaId: f?.categoria_ticket_id ?? null,
      pregunta: f?.pregunta ?? '',
      respuesta: f?.respuesta ?? '',
      orden: f?.orden ?? 0,
      activo: f?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean {
    return !!this.form.pregunta.trim() && !!this.form.respuesta.trim();
  }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
