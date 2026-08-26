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

  /** El saneado tuvo que recortar por largo en la última pulsación. */
  private truncado = false;

  constructor(ref: ElementRef<HTMLInputElement | HTMLTextAreaElement>) { super(ref); }

  ngOnInit(): void {
    // El largo se refleja en el elemento para que lo corte también el navegador
    // —el pegado, el arrastre— y para que el lector de pantalla lo anuncie.
    if (!this.el.getAttribute('maxlength')) {
      this.el.setAttribute('maxlength', String(this.def.largo));
    }
  }

  private get def(): PerfilTexto {
    return PERFILES_TEXTO[this.perfil || 'libre'] ?? PERFILES_TEXTO['libre'];
  }

  /**
   * Largo que se aplica de verdad: el del perfil, o el `maxlength` de la
   * plantilla cuando lo hay Y es más corto.
   *
   * El perfil declara el techo de la CLASE de dato y la plantilla el de la
   * COLUMNA concreta, que a veces es menor —`nombre` admite 150 y
   * `cliente.nombre` es varchar(100)—. Se toma el mínimo y no «el de la
   * plantilla si existe»: así un `maxlength` escrito de más no puede aflojar
   * en silencio el tope del perfil.
   */
  private get largo(): number {
    const declarado = Number(this.el.getAttribute('maxlength'));
    return Number.isFinite(declarado) && declarado > 0
      ? Math.min(declarado, this.def.largo)
      : this.def.largo;
  }

  /**
   * Con el campo lleno hasta el tope el navegador se limita a IGNORAR la tecla:
   * no pasa nada, no se ve nada y el usuario vuelve a pulsar creyendo que el
   * teclado falla. Esto detecta ese momento —tecla imprimible, sin selección
   * que reemplazar, valor ya en el máximo— y enciende el aviso.
   */
  @HostListener('keydown', ['$event'])
  alTeclear(ev: KeyboardEvent): void {
    if (ev.ctrlKey || ev.metaKey || ev.altKey || ev.key.length !== 1) { return; }
    const campo = this.el;
    const reemplaza = (campo.selectionEnd ?? 0) > (campo.selectionStart ?? 0);
    if (!reemplaza && campo.value.length >= this.largo) {
      this.truncado = true;
      this.tocado = true;      // el aviso tiene que verse AHORA, no tras el blur
      this.repintar();
    }
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
    const tope = this.largo;
    this.truncado = s.length > tope;
    if (this.truncado) { s = s.slice(0, tope); }
    return s;
  }

  /**
   * Lo que hace INVÁLIDO al control. Deliberadamente NO incluye el aviso de
   * tope: un campo lleno hasta su largo máximo es un campo correcto, y
   * marcarlo inválido bloquearía el «Aceptar» de cualquier formulario que se
   * apoye en la validación de Angular.
   */
  validate(_c: AbstractControl): ValidationErrors | null {
    const v = this.el.value ?? '';
    if (v.trim() === '') {
      return this.exigido ? { texto: 'Este campo es obligatorio.' } : null;
    }
    const motivo = this.def.validar ? this.def.validar(v) : null;
    return motivo ? { texto: motivo } : null;
  }

  protected motivoActual(): string | null {
    const v = this.el.value ?? '';
    if (v.trim() === '') {
      return this.exigido ? 'Este campo es obligatorio.' : null;
    }
    const motivo = this.def.validar ? this.def.validar(v) : null;
    if (motivo) { return motivo; }
    // El tope se AVISA sin invalidar: al llegar a él el campo deja de admitir
    // teclas y, sin este mensaje, no hay nada que explique por qué.
    return this.truncado ? `Máximo ${this.largo} caracteres.` : null;
  }
}
