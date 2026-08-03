import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import {
  ModoFormComponent
} from '../../../core/components/modo-form/modo-form.component';
import {
  SelectBuscableComponent, OpcionBuscable
} from '../../../core/components/select-buscable/select-buscable.component';

export interface PromocionProductoDialogData {
  promocionNombre: string;
  /** Catálogo de productos elegibles (1.200+: por eso el select buscable). */
  opciones: OpcionBuscable[];
}

/**
 * Asociar un producto a una promoción. Solo existe en Modo Nuevo: una
 * asociación es un ENLACE, no un registro con campos, así que no hay nada que
 * actualizar ni que consultar en una ficha aparte.
 */
@Component({
  selector: 'app-promocion-producto-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatIconModule,
    ModoFormComponent, SelectBuscableComponent],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>add_link</mat-icon>
      Producto de la promoción
      <app-modo-form modo="nuevo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <p class="sub">Promoción: <strong>{{ data.promocionNombre }}</strong></p>
      <app-select-buscable label="Producto a asociar" [opciones]="data.opciones"
                           [(value)]="productoId"></app-select-buscable>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="productoId === null" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(520px, 80vw); overflow: visible; }
    .sub { font-size: 13px; color: var(--text-secondary); margin: 0 0 14px; }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class PromocionProductoDialogComponent {

  productoId: number | null = null;

  constructor(public dialogRef: MatDialogRef<PromocionProductoDialogComponent, number>,
              @Inject(MAT_DIALOG_DATA) public data: PromocionProductoDialogData) {}

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.productoId !== null) this.dialogRef.close(this.productoId);
  }
}
