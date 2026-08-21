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
import { MarcaAdmin } from '../../../core/models/operativo.model';

import { CampoTextoDirective } from '../../../core/validacion';

export interface MarcaDialogData {
  /** Presente en 'actualizar' y 'consulta': todos los campos llegan precargados. */
  marca?: MarcaAdmin;
  modo: ModoFormulario;
}

/** `activo` viaja aparte: es la baja lógica y tiene su propio endpoint. */
export interface MarcaDialogResultado {
  nombre: string; slug: string; descripcion: string; activo: boolean;
}

/** Alta / edición / consulta de marca. Patrón: docs/PATRON_UI.md §5. */
@Component({
  selector: 'app-marca-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatIconModule, ModoFormComponent,
    CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>sell</mat-icon>
      Marca
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
    mat-dialog-content { min-width: min(560px, 80vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class MarcaDialogComponent {

  form: MarcaDialogResultado;

  constructor(public dialogRef: MatDialogRef<MarcaDialogComponent, MarcaDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: MarcaDialogData) {
    const m = data.marca;
    this.form = {
      nombre: m?.nombre ?? '',
      slug: m?.slug ?? '',
      descripcion: m?.descripcion ?? '',
      activo: m?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean { return !!this.form.nombre.trim() && !!this.form.slug.trim(); }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  autoSlug(): void {
    if (!this.esNuevo && this.form.slug) return; // no pisar el slug existente
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
