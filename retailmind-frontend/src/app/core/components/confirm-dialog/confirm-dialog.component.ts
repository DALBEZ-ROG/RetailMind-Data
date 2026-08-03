import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

/** Tono del diálogo: `peligro` pinta el botón de aceptar en rojo. */
export type TonoConfirmacion = 'peligro' | 'normal';

export interface ConfirmacionData {
  /** Título corto del diálogo. Ej.: «Eliminar producto». */
  titulo: string;
  /** La pregunta. Ej.: «¿Seguro que deseas eliminar «Camiseta azul»?». */
  mensaje: string;
  /**
   * QUÉ VA A PASAR de verdad. Un diálogo que solo dice «¿Está seguro?» no
   * informa: el usuario ya sabe que pulsó el botón. Ej.: «El producto dejará
   * de mostrarse en la tienda; su histórico de ventas se conserva».
   */
  consecuencia?: string;
  /** Solo si «Aceptar» no describe la acción. Por defecto, «Aceptar». */
  textoAceptar?: string;
  tono?: TonoConfirmacion;
}

/**
 * Diálogo de confirmación ÚNICO del sistema (regla 5 del patrón de interfaz).
 * No se abre directamente: se usa a través de `ConfirmService`.
 *
 * Cierra con `true` al aceptar y con `false`/`undefined` en cualquier otro
 * caso (Cancelar, Esc o clic fuera), así que cancelar es siempre lo seguro.
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>{{ esPeligro ? 'warning_amber' : 'help_outline' }}</mat-icon>
      {{ data.titulo }}
    </h2>

    <mat-dialog-content>
      <p class="cd-mensaje">{{ data.mensaje }}</p>
      <div class="cd-consecuencia" *ngIf="data.consecuencia">
        <mat-icon>info</mat-icon>
        <span>{{ data.consecuencia }}</span>
      </div>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [class.peligro]="esPeligro" (click)="aceptar()">
        {{ data.textoAceptar || 'Aceptar' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(420px, 78vw); }

    .cd-mensaje {
      color: var(--text-secondary);
      font-size: 14px;
      line-height: 1.55;
      margin: 0;
    }

    .cd-consecuencia {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      margin-top: 14px;
      padding: 12px 14px;
      border-radius: var(--border-radius-sm);
      border-left: 4px solid var(--warning);
      background: #fff8e1;
      font-size: 13px;
      line-height: 1.5;
      color: #6d4c00;

      mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
        color: var(--warning);
        flex-shrink: 0;
      }
    }

    mat-dialog-actions { padding: 14px 24px 20px; gap: 10px; }
  `]
})
export class ConfirmDialogComponent {

  constructor(
    public dialogRef: MatDialogRef<ConfirmDialogComponent, boolean>,
    @Inject(MAT_DIALOG_DATA) public data: ConfirmacionData
  ) {}

  get esPeligro(): boolean { return this.data.tono === 'peligro'; }

  cancelar(): void { this.dialogRef.close(false); }
  aceptar(): void { this.dialogRef.close(true); }
}
