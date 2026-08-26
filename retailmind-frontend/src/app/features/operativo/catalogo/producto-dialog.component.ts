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
import { MarcaAdmin, CategoriaAdmin, ProductoDetalleAdmin } from '../../../core/models/operativo.model';
import { ProductoBody } from '../../../core/services/catalogo-admin.service';

import { CampoTextoDirective } from '../../../core/validacion';

export interface ProductoDialogData {
  marcas: MarcaAdmin[];
  categorias: CategoriaAdmin[];
  /** Presente en 'actualizar' y 'consulta': todos los campos llegan precargados. */
  producto?: ProductoDetalleAdmin;
  modo: ModoFormulario;
}

/**
 * Lo que devuelve el diálogo. `activo` NO forma parte de `ProductoBody`: es
 * la baja lógica y viaja por su propio endpoint (PATCH .../activo), así que
 * la pantalla lo separa antes de llamar al servicio.
 */
export type ProductoDialogResultado = ProductoBody & { activo: boolean };

/**
 * Alta / edición / consulta de producto en modal estilo Dubai.
 *
 * Molde del patrón de interfaz (docs/PATRON_UI.md):
 * chip de modo en el título (regla 3) y exactamente dos botones,
 * Aceptar y Cancelar (regla 4).
 */
@Component({
  selector: 'app-producto-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent,
    CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>inventory_2</mat-icon>
      Producto
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Nombre</mat-label>
          <input appTexto="nombre" exigido matInput [(ngModel)]="form.nombre" maxlength="200" (blur)="autoSlug()"
                 [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Slug</mat-label>
          <input appTexto="slug" exigido matInput [(ngModel)]="form.slug" maxlength="220" [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Marca</mat-label>
          <mat-select [(ngModel)]="form.marcaId" [disabled]="soloLectura">
            <mat-option [value]="null">— Sin marca —</mat-option>
            <mat-option *ngFor="let m of data.marcas" [value]="m.id">{{ m.nombre }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" *ngIf="esNuevo">
          <mat-label>Categorías</mat-label>
          <mat-select [(ngModel)]="form.categoriaIds" multiple>
            <mat-option *ngFor="let c of data.categorias" [value]="c.id">{{ c.nombre }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Descripción corta</mat-label>
          <input appTexto="libre" matInput [(ngModel)]="form.descripcionCorta" maxlength="500" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Descripción</mat-label>
          <textarea appTexto="libre" matInput rows="3" [(ngModel)]="form.descripcion" [disabled]="soloLectura"></textarea>
        </mat-form-field>
      </div>

      <div class="banderas">
        <mat-checkbox [(ngModel)]="form.publicado" [disabled]="soloLectura">
          Publicado en la tienda
        </mat-checkbox>
        <!-- La baja lógica se ve y se revierte aquí: sin esto, «Eliminar» sería
             un viaje de ida y el producto quedaría inactivo para siempre. -->
        <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
          Activo (si se desmarca, equivale a eliminar)
        </mat-checkbox>
      </div>

      <p class="hint" *ngIf="!esNuevo && data.producto!.categorias.length">
        Categorías: {{ nombresCategorias }} (se administran al crear el producto)
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
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
    .banderas { display: flex; flex-wrap: wrap; gap: 8px 28px; }
    .hint { margin-top: 10px; font-size: 12px; color: var(--text-light); }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class ProductoDialogComponent {

  form: ProductoDialogResultado;

  constructor(public dialogRef: MatDialogRef<ProductoDialogComponent, ProductoDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: ProductoDialogData) {
    const p = data.producto;
    // Precarga TOTAL fuera del alta: ningún campo debe llegar vacío.
    this.form = {
      nombre: p?.nombre ?? '',
      slug: p?.slug ?? '',
      marcaId: p?.marca_id ?? null,
      descripcionCorta: p?.descripcion_corta ?? '',
      descripcion: p?.descripcion ?? '',
      publicado: p?.publicado ?? true,
      categoriaIds: p ? p.categorias.map(c => c.id) : [],
      activo: p?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  /** En Modo Consulta se ve todo y no se toca nada. */
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get nombresCategorias(): string {
    return (this.data.producto?.categorias ?? []).map(c => c.nombre).join(', ');
  }

  get valido(): boolean {
    return !!this.form.nombre.trim() && !!this.form.slug.trim();
  }

  /** En consulta, Aceptar siempre puede pulsarse: solo cierra la ficha. */
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
