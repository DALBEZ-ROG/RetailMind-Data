import { Injectable } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, map, of } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * El portero de la tienda pública.
 *
 * Se llama al PRINCIPIO de cualquier acción que necesite cuenta y devuelve un
 * observable que emite `true` solo si se puede seguir. Con sesión de cliente ya
 * abierta responde de inmediato y sin pintar nada; sin ella abre el muro y
 * espera a ver qué hace el visitante.
 *
 * El patrón —`this.sesion.exigir(...).subscribe(ok => { if (!ok) return; ... })`—
 * se eligió para que **la acción original no se reescriba**: el cuerpo que ya
 * existía se queda intacto dentro del `if`. La alternativa era interceptar el
 * 401/403 en el interceptor de HTTP y abrir el muro desde ahí, y se descartó
 * por dos motivos: obligaría a reintentar la petición a ciegas después de
 * entrar —sin saber cuál era ni si era idempotente— y el usuario vería primero
 * un error y después la pregunta, que es el orden inverso al que espera.
 *
 * El diálogo se carga PEREZOSAMENTE (`import()` dentro del método). Es la
 * diferencia entre que el paquete inicial cargue una pantalla de sesión que la
 * mayoría de las visitas no llega a ver, o que se traiga solo cuando alguien
 * pulsa «agregar al carrito» sin cuenta.
 */
@Injectable({ providedIn: 'root' })
export class SesionRequeridaService {

  constructor(private readonly auth: AuthService,
              private readonly dialog: MatDialog) {}

  /** Hay sesión y además es de un cliente de la tienda. */
  get haySesionDeCliente(): boolean {
    return this.auth.isAuthenticated() && this.auth.hasRole('CLIENTE');
  }

  /**
   * @param motivo cómo se completa la frase «Necesitas una cuenta …»
   *               (por ejemplo: «para agregar productos al carrito»).
   */
  exigir(motivo: string): Observable<boolean> {
    if (this.haySesionDeCliente) { return of(true); }

    return new Observable<boolean>(observador => {
      import('../../features/shop/sesion-requerida.dialog').then(m => {
        const ref = this.dialog.open(m.SesionRequeridaDialog, {
          data: { motivo },
          // La clase del telón es lo que difumina lo de atrás; va en styles.scss
          // porque el overlay de Material vive FUERA del componente y un estilo
          // encapsulado no lo alcanzaría.
          backdropClass: 'muro-sesion-telon',
          panelClass: 'muro-sesion-panel',
          autoFocus: 'first-tabbable',
          // Se puede cerrar: el visitante tiene derecho a decir que no y seguir
          // mirando. Un muro del que no se sale es una puerta.
          disableClose: false
        });
        ref.afterClosed().subscribe(resultado => {
          observador.next(resultado === 'cliente');
          observador.complete();
        });
      });
    });
  }

  /** Azúcar para los sitios donde solo interesa el «sí». */
  exigirYSeguir(motivo: string, accion: () => void): void {
    this.exigir(motivo).pipe(map(ok => ok)).subscribe(ok => { if (ok) { accion(); } });
  }
}
