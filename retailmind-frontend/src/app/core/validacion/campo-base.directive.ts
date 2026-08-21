import { Directive, ElementRef, HostListener, OnDestroy } from '@angular/core';

/**
 * Base común de las directivas de validación de campo.
 *
 * Hace DOS cosas, y las dos son de presentación —ninguna toca el modelo ni la
 * lógica de negocio—:
 *
 * 1. **Pinta el motivo debajo del campo.** El mensaje se inyecta dentro del
 *    `subscript-wrapper` del `mat-form-field` cuando lo hay, que es el hueco que
 *    Material ya reserva para hints y errores; así el aviso no empuja el resto
 *    del formulario ni descuadra la rejilla. Si el campo no vive en un
 *    `mat-form-field` (los de la tienda son `input.campo-tienda` a secas) el
 *    mensaje cuelga del padre inmediato.
 * 2. **Retiene el aviso hasta que el campo se ha TOCADO.** Un campo vacío recién
 *    pintado no es un campo mal escrito: enseñar «obligatorio» antes de que el
 *    usuario llegue a él convierte cada formulario en un muro rojo y la gente
 *    deja de leer los avisos. Se marca como tocado en el primer `blur`.
 *
 * El `title` y el `aria-invalid` se ponen SIEMPRE que hay motivo, tocado o no:
 * son para el lector de pantalla y para el puntero, no ocupan sitio.
 */
@Directive()
export abstract class CampoBaseDirective implements OnDestroy {

  /** Nodo del mensaje. Se crea perezosamente y solo si hace falta. */
  private nodo: HTMLElement | null = null;

  /** El usuario ya salió del campo alguna vez. */
  protected tocado = false;

  /** Último motivo calculado, para no reescribir el DOM en cada tecla. */
  private motivo: string | null = null;

  constructor(protected readonly ref: ElementRef<HTMLInputElement | HTMLTextAreaElement>) {}

  protected get el(): HTMLInputElement | HTMLTextAreaElement { return this.ref.nativeElement; }

  @HostListener('blur')
  protected alSalir(): void {
    this.tocado = true;
    this.repintar();
  }

  /** Lo implementa cada directiva: devuelve el motivo o null si el valor sirve. */
  protected abstract motivoActual(): string | null;

  /** Recalcula el aviso y lo refleja en el DOM. */
  protected repintar(): void {
    const motivo = this.motivoActual();
    if (motivo) {
      this.el.setAttribute('title', motivo);
    } else {
      this.el.removeAttribute('title');
    }
    // El mensaje visible solo aparece tras el primer blur (ver cabecera).
    const visible = this.tocado ? motivo : null;
    // `aria-invalid` va con el mensaje y no con el motivo: es lo que enciende el
    // borde rojo, y un formulario recién abierto no debe salir todo en rojo.
    if (visible) {
      this.el.setAttribute('aria-invalid', 'true');
    } else {
      this.el.removeAttribute('aria-invalid');
    }
    if (visible === this.motivo) { return; }
    this.motivo = visible;
    if (!visible) { this.quitarNodo(); return; }
    this.asegurarNodo().textContent = visible;
  }

  private asegurarNodo(): HTMLElement {
    if (this.nodo) { return this.nodo; }
    const nodo = document.createElement('div');
    nodo.className = 'rm-campo-error';
    const campo = this.el.closest('.mat-mdc-form-field');
    const hueco = campo?.querySelector('.mat-mdc-form-field-subscript-wrapper');
    (hueco ?? this.el.parentElement ?? document.body).appendChild(nodo);
    this.nodo = nodo;
    return nodo;
  }

  private quitarNodo(): void {
    this.nodo?.remove();
    this.nodo = null;
  }

  ngOnDestroy(): void { this.quitarNodo(); }
}
