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
import {
  SelectBuscableComponent, OpcionBuscable
} from '../../../core/components/select-buscable/select-buscable.component';
import { PreguntaProductoRow } from '../../../core/models/operativo.model';

import { CampoTextoDirective } from '../../../core/validacion';

export interface PreguntaDialogData {
  pregunta?: PreguntaProductoRow;
  modo: ModoFormulario;
  /** El moderador gobierna el estado y escribe la respuesta oficial. */
  esModerador: boolean;
  /** Solo en Modo Nuevo: catálogo sobre el que preguntar. */
  productos: OpcionBuscable[];
  /** Estados a los que la pregunta puede pasar (lista del backend). */
  transiciones: string[];
}

export interface PreguntaDialogResultado {
  productoId: number | null;
  pregunta: string;
  estado: string;
  /** Respuesta oficial NUEVA; vacía = no se publica ninguna. */
  respuesta: string;
}

/**
 * Pregunta sobre un producto. Patrón: docs/PATRON_UI.md §5.
 *
 * - **Cliente en Modo Nuevo**: elige producto y escribe la pregunta.
 * - **Moderador en Modo Actualizar**: la pregunta va en solo lectura (moderar
 *   no es reescribir lo que preguntó el cliente) y lo editable es el ESTADO
 *   más una RESPUESTA oficial opcional. Las dos cosas caben en Modificar, así
 *   que la pantalla no necesita los botones sueltos «Responder / Publicar /
 *   Rechazar» que tenía antes.
 */
@Component({
  selector: 'app-pregunta-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, ModoFormComponent, SelectBuscableComponent,
    CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>question_answer</mat-icon>
      Pregunta de producto
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <app-select-buscable *ngIf="esNuevo" class="ancho"
                             label="Producto" [opciones]="data.productos"
                             [(value)]="form.productoId"></app-select-buscable>
        <mat-form-field appearance="outline" class="ancho" *ngIf="!esNuevo">
          <mat-label>Producto</mat-label>
          <input matInput [value]="data.pregunta?.producto || '—'" disabled>
        </mat-form-field>

        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Pregunta</mat-label>
          <textarea appTexto="libre" matInput rows="2" [(ngModel)]="form.pregunta"
                    [disabled]="preguntaBloqueada"></textarea>
        </mat-form-field>

        <mat-form-field appearance="outline" *ngIf="data.esModerador && !esNuevo">
          <mat-label>Estado de publicación</mat-label>
          <mat-select [(ngModel)]="form.estado" [disabled]="soloLectura">
            <mat-option [value]="data.pregunta?.estado">
              {{ data.pregunta?.estado }} (sin cambios)
            </mat-option>
            <mat-option *ngFor="let e of data.transiciones" [value]="e">{{ e }}</mat-option>
          </mat-select>
          <mat-hint *ngIf="!soloLectura">
            «publicada» la muestra en la ficha del producto; «rechazada» la oculta.
          </mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" *ngIf="data.esModerador && !esNuevo">
          <mat-label>Cliente</mat-label>
          <input matInput [value]="data.pregunta?.cliente || 'Cliente'" disabled>
        </mat-form-field>
      </div>

      <!-- Respuestas ya publicadas -->
      <div class="respuestas" *ngIf="!esNuevo && data.pregunta?.respuestas?.length">
        <div class="slug-hint">Respuestas publicadas</div>
        <div class="respuesta" *ngFor="let a of data.pregunta!.respuestas">
          <div class="slug-hint">
            <strong>{{ a.autor || '—' }}</strong>
            <span class="stat-chip chip-info" *ngIf="a.es_oficial">oficial</span>
            · {{ a.fecha_creacion | date:'dd/MM/yy HH:mm' }}
          </div>
          <div>{{ a.respuesta }}</div>
        </div>
      </div>
      <p class="hint" *ngIf="!esNuevo && !data.pregunta?.respuestas?.length">
        Sin respuestas todavía.
      </p>

      <!-- Respuesta NUEVA: solo el moderador y solo en Modo Actualizar -->
      <mat-form-field appearance="outline" class="ancho-total"
                      *ngIf="data.esModerador && data.modo === 'actualizar'">
        <mat-label>Publicar una respuesta oficial (opcional)</mat-label>
        <textarea appTexto="libre" matInput rows="2" [(ngModel)]="form.respuesta"></textarea>
        <mat-hint>Se añade a las anteriores; las respuestas no se editan ni se borran.</mat-hint>
      </mat-form-field>

      <p class="hint" *ngIf="esNuevo">
        La pregunta queda <strong>pendiente</strong> hasta que el equipo la publique.
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    /* El panel del autocompletado se recorta si el contenido hace scroll (§8.13) */
    mat-dialog-content { min-width: min(640px, 82vw); overflow: visible; }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    .ancho-total { width: 100%; margin-top: 8px; }
    .respuestas { margin: 6px 0 4px; }
    .respuesta {
      margin: 6px 0; padding: 8px 12px; border-radius: 10px;
      background: rgba(63, 81, 181, 0.10); white-space: pre-wrap; font-size: 13px;
    }
    .slug-hint { font-size: 11px; opacity: 0.72; }
    .hint { margin-top: 10px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class PreguntaDialogComponent {

  form: PreguntaDialogResultado;

  constructor(public dialogRef: MatDialogRef<PreguntaDialogComponent, PreguntaDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: PreguntaDialogData) {
    const q = data.pregunta;
    this.form = {
      productoId: q?.producto_id ?? null,
      pregunta: q?.pregunta ?? '',
      estado: q?.estado ?? '',
      respuesta: ''
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }
  /** El texto de la pregunta solo se escribe al crearla. */
  get preguntaBloqueada(): boolean { return this.soloLectura || !this.esNuevo; }

  get valido(): boolean {
    if (this.esNuevo) return !!this.form.productoId && !!this.form.pregunta.trim();
    // En Modo Actualizar basta con que haya ALGO que aplicar
    return this.form.estado !== this.data.pregunta?.estado || !!this.form.respuesta.trim();
  }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
