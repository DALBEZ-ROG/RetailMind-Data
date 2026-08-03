import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';

/** Los cuatro estados en los que puede abrirse un formulario de gestión. */
export type ModoFormulario = 'nuevo' | 'actualizar' | 'eliminar' | 'consulta';

interface Rotulo { etiqueta: string; icono: string; }

/**
 * Chip que declara el MODO del formulario (regla 3 del patrón de interfaz).
 *
 * La nomenclatura es literal y no se parafrasea: «Modo Nuevo», «Modo
 * Actualizar», «Modo Eliminar», «Modo Consulta». Un formulario que dice
 * «Editando cupón #12» informa del registro, no del modo, y obliga al
 * usuario a deducir en qué estado está.
 *
 * Uso: `<app-modo-form [modo]="modo"></app-modo-form>` — normalmente dentro
 * del `<h2 mat-dialog-title>` del diálogo, junto al nombre de la entidad.
 */
@Component({
  selector: 'app-modo-form',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  template: `
    <span class="modo-chip" [ngClass]="'modo-' + modo">
      <mat-icon>{{ rotulo.icono }}</mat-icon>
      {{ rotulo.etiqueta }}
    </span>
  `,
  styles: [`
    .modo-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 12px;
      border-radius: 20px;
      font-family: 'Inter', sans-serif;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.4px;
      text-transform: uppercase;
      white-space: nowrap;
      border: 1px solid transparent;

      /* El título del diálogo Dubai pinta TODO mat-icon descendiente como una
         insignia con degradado; aquí se repone el icono a su tamaño normal. */
      mat-icon {
        font-size: 15px !important;
        width: 15px !important;
        height: 15px !important;
        padding: 0 !important;
        background: none !important;
        border-radius: 0 !important;
        color: inherit !important;
      }
    }

    .modo-nuevo      { background: #e8eaf6; color: #283593; border-color: #c5cae9; }
    .modo-actualizar { background: #e0f7fa; color: #00838f; border-color: #b2ebf2; }
    .modo-eliminar   { background: #ffebee; color: #c62828; border-color: #ffcdd2; }
    .modo-consulta   { background: #eceff1; color: #546e7a; border-color: #cfd8dc; }
  `]
})
export class ModoFormComponent {

  private static readonly ROTULOS: Record<ModoFormulario, Rotulo> = {
    nuevo:      { etiqueta: 'Modo Nuevo',      icono: 'add_box' },
    actualizar: { etiqueta: 'Modo Actualizar', icono: 'edit' },
    eliminar:   { etiqueta: 'Modo Eliminar',   icono: 'delete' },
    consulta:   { etiqueta: 'Modo Consulta',   icono: 'visibility' }
  };

  @Input() modo: ModoFormulario = 'consulta';

  get rotulo(): Rotulo { return ModoFormComponent.ROTULOS[this.modo]; }
}
