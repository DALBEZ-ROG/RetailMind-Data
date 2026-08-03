import { Injectable } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, map } from 'rxjs';
import {
  ConfirmDialogComponent, ConfirmacionData
} from '../components/confirm-dialog/confirm-dialog.component';

/**
 * Confirmación de seguridad del patrón de interfaz (regla 5).
 *
 * Sustituye a `window.confirm()`: mismo contrato (un booleano) pero con el
 * diseño Dubai y sitio para explicar la CONSECUENCIA de la acción.
 *
 * Uso:
 * ```ts
 * this.confirmar.eliminacion('el producto «Camiseta azul»',
 *     'Dejará de mostrarse en la tienda; su histórico de ventas se conserva.')
 *   .subscribe(ok => { if (ok) this.borrar(); });
 * ```
 * El observable emite UNA vez y se completa: no hace falta desuscribirse.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {

  constructor(private dialog: MatDialog) {}

  /** Confirmación genérica. Devuelve `true` solo si el usuario pulsa Aceptar. */
  confirmar(data: ConfirmacionData): Observable<boolean> {
    return this.dialog.open(ConfirmDialogComponent, {
      data,
      panelClass: 'dubai-dialog',
      autoFocus: false,
      restoreFocus: true
    }).afterClosed().pipe(map(resultado => resultado === true));
  }

  /**
   * Atajo para la acción «Eliminar» de la barra de acciones.
   *
   * @param queSeElimina descripción en minúscula con el nombre del registro,
   *        p. ej. `el producto «Camiseta azul»` — se inserta en la pregunta.
   * @param consecuencia qué pasa de verdad; obligatorio en la práctica cuando
   *        la baja es LÓGICA, porque «eliminar» y «desactivar» no son lo mismo
   *        y el usuario merece saber cuál de las dos está ejecutando.
   */
  eliminacion(queSeElimina: string, consecuencia?: string): Observable<boolean> {
    return this.confirmar({
      titulo: 'Confirmar eliminación',
      mensaje: `¿Seguro que deseas eliminar ${queSeElimina}?`,
      consecuencia,
      tono: 'peligro'
    });
  }
}
