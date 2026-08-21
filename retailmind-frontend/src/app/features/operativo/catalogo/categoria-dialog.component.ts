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
import { CategoriaAdmin } from '../../../core/models/operativo.model';

import { CampoTextoDirective } from '../../../core/validacion';

export interface CategoriaDialogData {
  categoria?: CategoriaAdmin;
  /** Candidatas a padre (sin la propia categoría, para no crear un ciclo). */
  padresPosibles: CategoriaAdmin[];
  modo: ModoFormulario;
}

export interface CategoriaDialogResultado {
  nombre: string; slug: string; descripcion: string;
  padreId: number | null; activo: boolean;
}

/** Alta / edición / consulta de categoría. Patrón: docs/PATRON_UI.md §5. */
@Component({
  selector: 'app-categoria-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent,
    CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>category</mat-icon>
      Categoría
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Nombre</mat-label>
          <input appTexto="nombre" exigido matInput [(ngModel)]="form.nombre" (blur)="autoSlug()"
                 [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Slug</mat-label>
          <input appTexto="slug" exigido matInput [(ngModel)]="form.slug" [disabled]="soloLectura" required>
        </mat-form-field>
        <!-- El padre solo se fija al crear: el endpoint de edición no lo cambia.
             Fuera del alta se muestra deshabilitado para poder CONSULTARLO. -->
        <mat-form-field appearance="outline">
          <mat-label>Categoría padre</mat-label>
          <mat-select [(ngModel)]="form.padreId" [disabled]="!esNuevo">
            <mat-option [value]="null">— Raíz —</mat-option>
            <mat-option *ngFor="let c of data.padresPosibles" [value]="c.id">{{ c.nombre }}</mat-option>
          </mat-select>
          <mat-hint *ngIf="!esNuevo">El padre se fija al crear la categoría</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Descripción</mat-label>
          <input appTexto="libre" matInput [(ngModel)]="form.descripcion" [disabled]="soloLectura">
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
    mat-dialog-content { min-width: min(620px, 80vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class CategoriaDialogComponent {

  form: CategoriaDialogResultado;

  constructor(public dialogRef: MatDialogRef<CategoriaDialogComponent, CategoriaDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: CategoriaDialogData) {
    const c = data.categoria;
    this.form = {
      nombre: c?.nombre ?? '',
      slug: c?.slug ?? '',
      descripcion: c?.descripcion ?? '',
      padreId: c?.categoria_padre_id ?? null,
      activo: c?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean { return !!this.form.nombre.trim() && !!this.form.slug.trim(); }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  autoSlug(): void {
    if (!this.esNuevo && this.form.slug) return;
    this.form.slug = this.form.nombre.toLowerCase().trim()
      .replace(/[áéíóúñ]/g, c => ({ 'á': 'a', 'é': 'e', 'í': 'i', 'ó': 'o', 'ú': 'u', 'ñ': 'n' }[c] || c))
      .replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
  }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
