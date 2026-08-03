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
import { ReporteResenaRow } from '../../../core/models/operativo.model';

export interface ReporteDialogData {
  reporte?: ReporteResenaRow;
  modo: ModoFormulario;
  /** Texto de la reseña sobre la que se abre el reporte (Modo Nuevo). */
  resenaResumen?: string;
  motivos: string[];
  /** Estados a los que el reporte puede pasar (lista del backend). */
  transiciones: string[];
}

export interface ReporteDialogResultado {
  motivo: string;
  comentario: string;
  /** Solo lo usa el moderador. */
  estado: string;
}

/**
 * Reporte de abuso sobre una reseña. Patrón: docs/PATRON_UI.md §5.
 *
 * - **Cliente en Modo Nuevo**: denuncia una reseña ajena (motivo + comentario).
 * - **Moderador en Modo Actualizar**: el reporte va en solo lectura y lo
 *   editable es el ESTADO. Los dos destinos —«atendido» y «descartado»— son
 *   TERMINALES: el backend no admite ninguna transición desde ellos.
 */
@Component({
  selector: 'app-reporte-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, ModoFormComponent],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>flag</mat-icon>
      Reporte de reseña
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="citada" *ngIf="citada">
        <div class="slug-hint">Reseña reportada</div>
        {{ citada }}
      </div>

      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Motivo</mat-label>
          <mat-select [(ngModel)]="form.motivo" [disabled]="motivoBloqueado">
            <mat-option *ngFor="let m of data.motivos" [value]="m">{{ m }}</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" *ngIf="!esNuevo">
          <mat-label>Resolución</mat-label>
          <mat-select [(ngModel)]="form.estado" [disabled]="soloLectura || !data.transiciones.length">
            <mat-option [value]="data.reporte?.estado">
              {{ data.reporte?.estado }} (sin cambios)
            </mat-option>
            <mat-option *ngFor="let e of data.transiciones" [value]="e">{{ e }}</mat-option>
          </mat-select>
          <mat-hint *ngIf="!data.transiciones.length">
            El reporte ya está resuelto: «{{ data.reporte?.estado }}» es un estado terminal.
          </mat-hint>
          <mat-hint *ngIf="data.transiciones.length && !soloLectura">
            «atendido» y «descartado» son ambos definitivos: no se puede volver atrás.
          </mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Comentario {{ esNuevo ? '(opcional)' : '' }}</mat-label>
          <textarea matInput rows="2" [(ngModel)]="form.comentario"
                    [disabled]="motivoBloqueado"></textarea>
        </mat-form-field>
      </div>

      <p class="hint" *ngIf="!esNuevo && data.reporte">
        Reportado por {{ data.reporte.reportado_por || 'un cliente' }}
        el {{ data.reporte.fecha_creacion | date:'dd/MM/yyyy HH:mm' }}.
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(600px, 82vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    .citada {
      margin-bottom: 14px; padding: 10px 14px; border-radius: 10px;
      background: rgba(128, 128, 128, 0.10);
      border-left: 3px solid rgba(128, 128, 128, 0.4);
      font-size: 13px; white-space: pre-wrap;
    }
    .slug-hint { font-size: 11px; opacity: 0.7; margin-bottom: 2px; }
    .hint { margin-top: 10px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class ReporteDialogComponent {

  form: ReporteDialogResultado;

  constructor(public dialogRef: MatDialogRef<ReporteDialogComponent, ReporteDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: ReporteDialogData) {
    const r = data.reporte;
    this.form = {
      motivo: r?.motivo ?? data.motivos[0] ?? 'ofensivo',
      comentario: r?.comentario ?? '',
      estado: r?.estado ?? ''
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }
  /** Motivo y comentario los escribe el denunciante; el moderador no los toca. */
  get motivoBloqueado(): boolean { return this.soloLectura || !this.esNuevo; }

  get citada(): string {
    if (this.data.resenaResumen) return this.data.resenaResumen;
    const r = this.data.reporte;
    if (!r) return '';
    return `${r.producto} · ${'★'.repeat(r.calificacion)} — ${r.resena_titulo || '(sin título)'}`
         + (r.resena_comentario ? `\n${r.resena_comentario}` : '');
  }

  get valido(): boolean {
    return this.esNuevo ? !!this.form.motivo : !!this.form.estado;
  }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
