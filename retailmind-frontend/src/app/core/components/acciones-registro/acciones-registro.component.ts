import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * Barra de opciones estándar sobre el registro seleccionado en la grilla
 * (regla 2 del patrón de interfaz): **Nuevo, Modificar, Eliminar, Ver**.
 *
 * «Nuevo» está siempre disponible; los otros tres se deshabilitan mientras no
 * haya fila seleccionada, que es como el usuario descubre que la selección
 * es el paso previo — sin leer ninguna instrucción.
 *
 * NO contiene lógica de negocio: solo emite eventos. Quién abre qué diálogo y
 * qué endpoint se llama es asunto de la pantalla.
 *
 * Uso:
 * ```html
 * <app-acciones-registro [haySeleccion]="!!filaSeleccionada"
 *                        [nombreSeleccion]="filaSeleccionada?.nombre"
 *                        entidad="producto"
 *                        (nuevo)="nuevoProducto()" (modificar)="modificarProducto()"
 *                        (eliminar)="eliminarProducto()" (ver)="verProducto()">
 * </app-acciones-registro>
 * ```
 */
@Component({
  selector: 'app-acciones-registro',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatTooltipModule],
  template: `
    <div class="acciones-registro">
      <button class="btn-aplicar" *ngIf="mostrarNuevo" (click)="nuevo.emit()"
              [matTooltip]="'Crear ' + (articulo) + ' ' + entidad">
        <mat-icon>add</mat-icon> Nuevo
      </button>

      <button class="btn-limpiar" *ngIf="mostrarModificar"
              [disabled]="!haySeleccion" (click)="modificar.emit()"
              [matTooltip]="haySeleccion ? 'Modificar ' + articulo + ' ' + entidad + ' seleccionado' + terminacion
                                         : 'Selecciona una fila de la grilla'">
        <mat-icon>edit</mat-icon> Modificar
      </button>

      <button class="btn-limpiar btn-peligro" *ngIf="mostrarEliminar"
              [disabled]="!haySeleccion || !puedeEliminar" (click)="eliminar.emit()"
              [matTooltip]="tooltipEliminar">
        <mat-icon>delete</mat-icon> Eliminar
      </button>

      <button class="btn-limpiar" *ngIf="mostrarVer"
              [disabled]="!haySeleccion" (click)="ver.emit()"
              [matTooltip]="haySeleccion ? 'Consultar la ficha (solo lectura)'
                                         : 'Selecciona una fila de la grilla'">
        <mat-icon>visibility</mat-icon> Ver
      </button>

      <span class="seleccion-actual" [class.vacia]="!haySeleccion">
        <mat-icon>{{ haySeleccion ? 'check_circle' : 'touch_app' }}</mat-icon>
        <ng-container *ngIf="haySeleccion; else sinSeleccion">
          Seleccionado: <strong>{{ nombreSeleccion || '—' }}</strong>
        </ng-container>
        <ng-template #sinSeleccion>
          Selecciona {{ articulo }} {{ entidad }} de la grilla
        </ng-template>
      </span>
    </div>
  `,
  styles: [`
    .acciones-registro {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
      margin-bottom: 16px;
      padding-bottom: 16px;
      border-bottom: 1px dashed rgba(26, 35, 126, 0.12);
    }

    /* Deshabilitado = no hay registro sobre el que actuar. Se ve apagado,
       nunca oculto: la opción existe siempre, como pide el patrón. */
    .btn-limpiar:disabled {
      opacity: 0.4 !important;
      cursor: not-allowed !important;
      transform: none !important;
    }
    .btn-limpiar:disabled:hover {
      border-color: #e0e0e0 !important;
      color: #546e7a !important;
      background: transparent !important;
    }

    .btn-peligro:not(:disabled) {
      color: #c62828 !important;
      border-color: #ffcdd2 !important;
    }
    .btn-peligro:not(:disabled):hover {
      color: #b71c1c !important;
      border-color: #ef9a9a !important;
      background: rgba(198, 40, 40, 0.06) !important;
    }

    .seleccion-actual {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      margin-left: auto;
      font-size: 12px;
      color: var(--text-secondary);

      strong { color: var(--text-primary); }
      mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--success); }
      &.vacia mat-icon { color: var(--text-light); }
      &.vacia { color: var(--text-light); font-style: italic; }
    }
  `]
})
export class AccionesRegistroComponent {

  /** Nombre de la entidad en singular y minúscula: «producto», «variante»… */
  @Input() entidad = 'registro';
  /** `un` o `una`, según la entidad. Solo afecta a los textos de ayuda. */
  @Input() articulo: 'un' | 'una' = 'un';
  /** Hay una fila seleccionada en la grilla. */
  @Input() haySeleccion = false;
  /** Qué fila, para que el usuario vea sobre qué va a actuar. */
  @Input() nombreSeleccion: string | null = null;

  /**
   * Permite bloquear solo «Eliminar» aun habiendo selección — p. ej. cuando el
   * registro ya está dado de baja. El botón sigue visible y explica por qué.
   */
  @Input() puedeEliminar = true;
  @Input() motivoNoEliminable = '';

  /** Una pantalla puede no ofrecer alguna opción; el resto no se desordena. */
  @Input() mostrarNuevo = true;
  @Input() mostrarModificar = true;
  @Input() mostrarEliminar = true;
  @Input() mostrarVer = true;

  @Output() nuevo = new EventEmitter<void>();
  @Output() modificar = new EventEmitter<void>();
  @Output() eliminar = new EventEmitter<void>();
  @Output() ver = new EventEmitter<void>();

  get terminacion(): string { return this.articulo === 'una' ? 'a' : ''; }

  get tooltipEliminar(): string {
    if (!this.haySeleccion) return 'Selecciona una fila de la grilla';
    if (!this.puedeEliminar) return this.motivoNoEliminable || 'No se puede eliminar';
    return `Eliminar ${this.articulo} ${this.entidad} seleccionad${this.articulo === 'una' ? 'a' : 'o'}`;
  }
}
