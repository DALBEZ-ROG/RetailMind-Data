import {
  booleanAttribute, Directive, ElementRef, HostListener, Input, OnInit
} from '@angular/core';
import { AbstractControl, NG_VALIDATORS, ValidationErrors, Validator } from '@angular/forms';
import { CampoBaseDirective } from './campo-base.directive';
import { NombrePerfilTexto, PERFILES_TEXTO, PerfilTexto } from './perfiles-texto';

/**
 * Campo de texto acotado a un perfil de `perfiles-texto.ts`.
 *
 * Limpia MIENTRAS se escribe en vez de rechazar al guardar. La diferencia
 * importa: estos formularios son de `[(ngModel)]` suelto y el botón «Aceptar» se
 * habilita con un getter escrito a mano en cada componente, así que un valor
 * inválido que llegue al modelo se envía igual. Retirando el carácter en el
 * momento, el modelo nunca llega a contenerlo y no hay que tocar ni un getter
 * —que es justo lo que no se quiere mover—.
 *
 * El saneado se aplica en el evento `input`, así que cubre por igual el teclado,
 * el pegado, el arrastre de texto y el autocompletado del navegador; no hace
 * falta un manejador por vía. Como el valor se corrige a mano hay que **volver a
 * emitir `input`** para que el accesor de Angular recoja el valor limpio: su
 * manejador ya se ejecutó con el sucio.
 *
 * La posición del cursor se recoloca a mano tras la corrección. Sin eso, al
 * escribir en medio de un texto ya tecleado el cursor salta al final en cuanto
 * se retira un carácter, y no se puede corregir una palabra.
 */
@Directive({
  selector: 'input[appTexto], textarea[appTexto]',
  standalone: true,
  providers: [{ provide: NG_VALIDATORS, useExisting: CampoTextoDirective, multi: true }]
})
export class CampoTextoDirective extends CampoBaseDirective implements Validator, OnInit {

  /** Nombre del perfil: nombre, libre, codigo, slug, telefono, email, url… */
  @Input('appTexto') perfil: NombrePerfilTexto | '' = 'libre';

  /**
   * Obligatorio: avisa cuando queda vacío, sin bloquear nada.
   *
   * Con `booleanAttribute` se puede escribir `exigido` a secas en la plantilla,
   * que es como se lee un atributo booleano en HTML; sin la transformación
   * Angular pasaría la cadena vacía y el compilador rechaza la plantilla.
   */
  @Input({ transform: booleanAttribute }) exigido = false;

  private reemitiendo = false;

  constructor(ref: ElementRef<HTMLInputElement | HTMLTextAreaElement>) { super(ref); }

  ngOnInit(): void {
    const p = this.def;
    // El largo se declara en el perfil y se refleja en el elemento, para que el
    // navegador lo corte también en el pegado y el lector de pantalla lo anuncie.
    if (p.largo && !this.el.getAttribute('maxlength')) {
      this.el.setAttribute('maxlength', String(p.largo));
    }
  }

  private get def(): PerfilTexto {
    return PERFILES_TEXTO[this.perfil || 'libre'] ?? PERFILES_TEXTO['libre'];
  }

  @HostListener('input')
  alEscribir(): void {
    if (this.reemitiendo) { return; }
    const campo = this.el;
    const sucio = campo.value;
    const limpio = this.sanear(sucio);
    if (limpio !== sucio) {
      // Cuánto se retiró por delante del cursor: eso es lo que hay que retroceder.
      const cursor = campo.selectionStart ?? sucio.length;
      const quitados = sucio.length - limpio.length;
      campo.value = limpio;
      this.reemitiendo = true;
      campo.dispatchEvent(new Event('input', { bubbles: true }));
      this.reemitiendo = false;
      const destino = Math.max(0, cursor - quitados);
      try { campo.setSelectionRange(destino, destino); } catch { /* type=email no lo admite */ }
    }
    this.repintar();
  }

  /**
   * El orden de los cuatro pasos NO es indiferente y costó un caso de prueba:
   * **la caja va ANTES de retirar lo prohibido**. Al revés, un perfil de
   * minúsculas como el slug empieza por borrar las mayúsculas —que no están en
   * su juego de caracteres— en vez de bajarlas, y «Zapatos De Cuero» se
   * convierte en «apatos-e-uero»: se pierden justamente las iniciales.
   */
  private sanear(v: string): string {
    const p = this.def;
    let s = p.previo ? p.previo(v) : v;
    if (p.caja === 'mayus') { s = s.toUpperCase(); }
    if (p.caja === 'minus') { s = s.toLowerCase(); }
    s = s.replace(p.prohibido, '');
    if (p.largo && s.length > p.largo) { s = s.slice(0, p.largo); }
    return s;
  }

  validate(_c: AbstractControl): ValidationErrors | null {
    const motivo = this.motivoActual();
    return motivo ? { texto: motivo } : null;
  }

  protected motivoActual(): string | null {
    const v = this.el.value ?? '';
    if (v.trim() === '') {
      return this.exigido ? 'Este campo es obligatorio.' : null;
    }
    return this.def.validar ? this.def.validar(v) : null;
  }
}
