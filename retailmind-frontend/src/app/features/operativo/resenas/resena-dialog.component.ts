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
import { ResenaRow } from '../../../core/models/operativo.model';

import { CampoTextoDirective } from '../../../core/validacion';

export interface ResenaDialogData {
  resena?: ResenaRow;
  modo: ModoFormulario;
  /** El moderador no escribe reseñas: gobierna su ESTADO. */
  esModerador: boolean;
  /** Solo en Modo Nuevo del cliente: productos con compra verificada. */
  comprados: OpcionBuscable[];
  /** Estados a los que la reseña puede pasar desde el suyo (lista del backend). */
  transiciones: string[];
}

export interface ResenaDialogResultado {
  productoId: number | null;
  calificacion: number;
  titulo: string;
  comentario: string;
  /** Solo lo usa el moderador; en el alta del cliente viaja vacío. */
  estado: string;
}

/**
 * Reseña de producto. Patrón: docs/PATRON_UI.md §5.
 *
 * El MISMO diálogo sirve a los dos públicos porque es el mismo registro:
 * - **Cliente en Modo Nuevo**: escribe la reseña (producto comprado, estrellas,
 *   título y comentario). No hay Modo Actualizar para él — ver la nota de la
 *   pantalla: el backend no expone edición de la reseña propia.
 * - **Moderador en Modo Actualizar**: el contenido va en solo lectura (moderar
 *   no es reescribir lo que dijo el cliente) y lo editable es el ESTADO, con
 *   las transiciones que el backend admite desde el estado actual. Es el mismo
 *   recurso que se usó en Campañas: el ciclo de vida cabe DENTRO de Modificar.
 */
@Component({
  selector: 'app-resena-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, ModoFormComponent, SelectBuscableComponent,
    CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>rate_review</mat-icon>
      Reseña
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <!-- Alta del cliente: elige entre lo que compró -->
        <app-select-buscable *ngIf="esNuevo" class="ancho"
                             label="Producto comprado" [opciones]="data.comprados"
                             [(value)]="form.productoId"></app-select-buscable>
        <mat-form-field appearance="outline" class="ancho" *ngIf="!esNuevo">
          <mat-label>Producto</mat-label>
          <input matInput [value]="data.resena?.producto || '—'" disabled>
        </mat-form-field>

        <mat-form-field appearance="outline" *ngIf="data.esModerador && !esNuevo">
          <mat-label>Cliente</mat-label>
          <input matInput [value]="data.resena?.cliente || '—'" disabled>
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Calificación</mat-label>
          <mat-select [(ngModel)]="form.calificacion" [disabled]="contenidoBloqueado">
            <mat-option *ngFor="let c of calificaciones" [value]="c">{{ estrellas(c) }}</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" *ngIf="data.esModerador && !esNuevo">
          <mat-label>Estado de moderación</mat-label>
          <mat-select [(ngModel)]="form.estado" [disabled]="soloLectura">
            <mat-option [value]="data.resena?.estado">
              {{ data.resena?.estado }} (sin cambios)
            </mat-option>
            <mat-option *ngFor="let e of data.transiciones" [value]="e">{{ e }}</mat-option>
          </mat-select>
          <mat-hint *ngIf="!soloLectura">
            «aprobada» la publica en la ficha del producto; «rechazada» la oculta.
          </mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Título (opcional)</mat-label>
          <input appTexto="libre" matInput [(ngModel)]="form.titulo" maxlength="150"
                 [disabled]="contenidoBloqueado">
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Comentario (opcional)</mat-label>
          <textarea appTexto="libre" matInput rows="3" [(ngModel)]="form.comentario"
                    [disabled]="contenidoBloqueado"></textarea>
        </mat-form-field>
      </div>

      <p class="hint" *ngIf="esNuevo">
        Solo puedes reseñar productos que has comprado. La reseña queda
        <strong>pendiente</strong> hasta que el equipo la apruebe.
      </p>
      <p class="hint" *ngIf="data.esModerador && !esNuevo">
        Moderar no reescribe lo que dijo el cliente: el contenido va en solo lectura.
        Creada el {{ data.resena?.fecha_creacion | date:'dd/MM/yyyy HH:mm' }} ·
        {{ data.resena?.utiles }} voto/s de «útil» ·
        {{ data.resena?.compra_verificada ? 'compra verificada' : 'SIN compra verificada' }}.
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
    .hint { margin-top: 10px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class ResenaDialogComponent {

  readonly calificaciones = [1, 2, 3, 4, 5];

  form: ResenaDialogResultado;

  constructor(public dialogRef: MatDialogRef<ResenaDialogComponent, ResenaDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: ResenaDialogData) {
    const r = data.resena;
    this.form = {
      productoId: r?.producto_id ?? null,
      calificacion: r?.calificacion ?? 5,
      titulo: r?.titulo ?? '',
      comentario: r?.comentario ?? '',
      estado: r?.estado ?? ''
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  /** El contenido solo es editable en el alta del cliente. */
  get contenidoBloqueado(): boolean { return this.soloLectura || !this.esNuevo; }

  get valido(): boolean {
    if (this.esNuevo) return !!this.form.productoId;
    return !!this.form.estado;
  }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  estrellas(n: number): string { return '★'.repeat(n) + '☆'.repeat(5 - n); }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
