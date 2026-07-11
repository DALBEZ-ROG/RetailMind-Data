import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MarcaAdmin, CategoriaAdmin, ProductoDetalleAdmin } from '../../../core/models/operativo.model';
import { ProductoBody } from '../../../core/services/catalogo-admin.service';

export interface ProductoDialogData {
  marcas: MarcaAdmin[];
  categorias: CategoriaAdmin[];
  /** Presente = edición: todos los campos llegan precargados. */
  producto?: ProductoDetalleAdmin;
}

/** Alta/edición de producto en modal estilo Dubai (panelClass dubai-dialog). */
@Component({
  selector: 'app-producto-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>{{ esEdicion ? 'edit' : 'add_box' }}</mat-icon>
      {{ esEdicion ? 'Editar producto' : 'Nuevo producto' }}
    </h2>
    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Nombre</mat-label>
          <input matInput [(ngModel)]="form.nombre" (blur)="autoSlug()" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Slug</mat-label>
          <input matInput [(ngModel)]="form.slug" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Marca</mat-label>
          <mat-select [(ngModel)]="form.marcaId">
            <mat-option [value]="null">— Sin marca —</mat-option>
            <mat-option *ngFor="let m of data.marcas" [value]="m.id">{{ m.nombre }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" *ngIf="!esEdicion">
          <mat-label>Categorías</mat-label>
          <mat-select [(ngModel)]="form.categoriaIds" multiple>
            <mat-option *ngFor="let c of data.categorias" [value]="c.id">{{ c.nombre }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Descripción corta</mat-label>
          <input matInput [(ngModel)]="form.descripcionCorta">
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Descripción</mat-label>
          <textarea matInput rows="3" [(ngModel)]="form.descripcion"></textarea>
        </mat-form-field>
      </div>
      <mat-checkbox [(ngModel)]="form.publicado">Publicado en la tienda</mat-checkbox>
      <p class="hint" *ngIf="esEdicion && data.producto!.categorias.length">
        Categorías: {{ nombresCategorias }} (se administran al crear el producto)
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-stroked-button (click)="dialogRef.close()">Cancelar</button>
      <button mat-raised-button color="primary" [disabled]="!valido" (click)="guardar()">
        <mat-icon>save</mat-icon> {{ esEdicion ? 'Guardar cambios' : 'Crear producto' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(680px, 80vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    .hint { margin-top: 10px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 8px; }
  `]
})
export class ProductoDialogComponent {

  form: ProductoBody;

  constructor(public dialogRef: MatDialogRef<ProductoDialogComponent, ProductoBody>,
              @Inject(MAT_DIALOG_DATA) public data: ProductoDialogData) {
    const p = data.producto;
    // Precarga TOTAL en edición: ningún campo debe llegar vacío.
    this.form = {
      nombre: p?.nombre ?? '',
      slug: p?.slug ?? '',
      marcaId: p?.marca_id ?? null,
      descripcionCorta: p?.descripcion_corta ?? '',
      descripcion: p?.descripcion ?? '',
      publicado: p?.publicado ?? true,
      categoriaIds: p ? p.categorias.map(c => c.id) : []
    };
  }

  get esEdicion(): boolean { return !!this.data.producto; }

  get nombresCategorias(): string {
    return (this.data.producto?.categorias ?? []).map(c => c.nombre).join(', ');
  }

  get valido(): boolean {
    return !!this.form.nombre.trim() && !!this.form.slug.trim();
  }

  autoSlug(): void {
    if (this.esEdicion && this.form.slug) return; // no pisar el slug existente
    this.form.slug = this.form.nombre.toLowerCase().trim()
      .replace(/[áéíóúñ]/g, c => ({ 'á': 'a', 'é': 'e', 'í': 'i', 'ó': 'o', 'ú': 'u', 'ñ': 'n' }[c] || c))
      .replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
  }

  guardar(): void {
    if (this.valido) this.dialogRef.close(this.form);
  }
}
