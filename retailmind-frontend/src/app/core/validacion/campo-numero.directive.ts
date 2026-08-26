import { Directive, ElementRef, HostListener, Input, OnInit } from '@angular/core';
import { AbstractControl, NG_VALIDATORS, ValidationErrors, Validator } from '@angular/forms';
import { CampoBaseDirective } from './campo-base.directive';

/** Cuántos decimales admite cada perfil. */
export type PerfilNumero = 'entero' | 'dinero' | 'decimal';

const DECIMALES: Record<PerfilNumero, number> = { entero: 0, dinero: 2, decimal: 3 };

/**
 * Cuántos dígitos admite la parte ENTERA cuando el campo no declara un `max`.
 *
 * No es un número redondo elegido a ojo: 9 dígitos es el mayor valor que cabe
 * a la vez en un `integer` de PostgreSQL (999.999.999 < 2.147.483.647) y en un
 * `numeric(12,2)`, que son los dos tipos detrás de todos los campos numéricos
 * de la aplicación —52 columnas de dinero son `numeric(12,2)`—. Sin tope, el
 * campo acepta veinte dígitos, el modelo se los lleva y el error llega del
 * motor, que habla de desbordamiento y no dice en qué casilla está.
 */
const DIGITOS_ENTEROS_POR_DEFECTO = 9;

/** Teclas que, viniendo del teclado, forman parte de un número a medio escribir. */
const TECLAS_DE_NUMERO = /^[0-9.\-]$/;

/**
 * Campo que SOLO admite un número.
 *
 * Se pone sobre los `<input type="number">` que ya existen, y ese detalle no es
 * menor: **`type="number"` por sí solo NO impide escribir basura**. El navegador
 * acepta `e`, `E`, `+` y `-` en cualquier posición porque la notación científica
 * es un número válido para el estándar, y cuando lo tecleado no se puede
 * interpretar, `el.value` devuelve **cadena vacía** y `validity.badInput` se pone
 * en true. Con `[(ngModel)]` eso llega al modelo como `null` — o sea, el usuario
 * ve «12e» escrito en la caja y el formulario cree que el campo está VACÍO.
 * Ninguna de las dos mitades avisa de nada.
 *
 * De ahí las tres capas, que son distintas a propósito:
 *
 * 1. **`keydown`** descarta la tecla antes de que entre: letras, símbolos, el
 *    separador decimal cuando el perfil es entero y el signo menos cuando el
 *    mínimo no es negativo. Es la que evita el 99 % de los casos.
 * 2. **`input`** es la red de seguridad para lo que no pasa por el teclado
 *    —pegar, arrastrar texto, autocompletar del navegador—: si el campo quedó en
 *    `badInput` **sin que la última tecla fuera parte de un número**, se restaura
 *    el último valor bueno; y si sobran decimales, se recorta. Como el valor se
 *    corrige a mano hay que **volver a emitir el evento `input`**, porque el
 *    accesor de Angular ya leyó el valor sucio en su propio manejador y no vuelve
 *    a mirar por su cuenta.
 * 3. **`blur`** ajusta a `min`/`max` y deshace lo que quedara a medio escribir.
 *    El ajuste se hace al salir y no al teclear: para llegar a `10` hay que pasar
 *    por `1`, y con un mínimo de 5 un ajuste en caliente lo convertiría en `5` en
 *    cuanto se escribe el primer dígito.
 *
 * **La condición «sin que la última tecla fuera parte de un número» es lo que
 * hace utilizable el campo de dinero.** Escribir `12.` deja el input en
 * `badInput` —un punto suelto al final no es un número— y una restauración a
 * secas se come el punto en el momento de teclearlo: el usuario escribe
 * «12.99» y en la caja aparece «1299». La tecla se recuerda en `keydown` y se
 * OLVIDA al terminar cada `input`, para que lo que llega sin teclado (pegado,
 * `insertText`) siga cayendo del lado que restaura.
 *
 * `min` y `max` se leen del propio elemento, así que los que ya están escritos
 * en las plantillas —incluidos los enlazados, como `[max]="saldo_pendiente"`—
 * pasan a estar EXIGIDOS y no solo declarados.
 */
@Directive({
  selector: 'input[appNumero]',
  standalone: true,
  providers: [{ provide: NG_VALIDATORS, useExisting: CampoNumeroDirective, multi: true }]
})
export class CampoNumeroDirective extends CampoBaseDirective implements Validator, OnInit {

  /** Perfil: `entero` (0 decimales), `dinero` (2) o `decimal` (ver `decimales`). */
  @Input('appNumero') perfil: PerfilNumero | '' = 'entero';

  /** Decimales del perfil `decimal`. Sin efecto en los otros dos. */
  @Input() decimales?: number;

  /**
   * Dígitos de la parte entera. Solo hace falta declararlo cuando el campo no
   * tiene `max` y su tope real es más corto que el de por defecto (un año, un
   * porcentaje, un número de intentos).
   */
  @Input() maxDigitos?: number;

  private ultimoBueno = '';
  private reemitiendo = false;

  /** La última pulsación chocó con el tope de dígitos. */
  private truncado = false;

  /** La tecla del `keydown` en curso; se borra al acabar el `input` que provoca. */
  private ultimaTecla: string | null = null;

  constructor(ref: ElementRef<HTMLInputElement>) { super(ref); }

  ngOnInit(): void {
    const campo = this.el as HTMLInputElement;
    if (!campo.getAttribute('inputmode')) {
      campo.setAttribute('inputmode', this.tope === 0 ? 'numeric' : 'decimal');
    }
    this.ultimoBueno = campo.value;
  }

  /** Decimales admitidos por este campo. */
  private get tope(): number {
    if (this.perfil === 'decimal') { return this.decimales ?? DECIMALES.decimal; }
    return DECIMALES[(this.perfil || 'entero') as PerfilNumero];
  }

  private get minimo(): number | null {
    const v = (this.el as HTMLInputElement).min;
    return v === '' || v == null ? null : Number(v);
  }

  private get maximo(): number | null {
    const v = (this.el as HTMLInputElement).max;
    return v === '' || v == null ? null : Number(v);
  }

  /**
   * Dígitos admitidos en la parte entera.
   *
   * Cuando el campo declara un `max` se deduce de ÉL, y no del defecto: un
   * pago acotado a `[max]="saldo_pendiente"` de 1.234,56 no tiene por qué
   * dejar teclear nueve cifras para recortarlas al salir del campo.
   */
  private get topeEnteros(): number {
    if (this.maxDigitos && this.maxDigitos > 0) { return this.maxDigitos; }
    const max = this.maximo;
    if (max != null && Number.isFinite(max)) {
      return Math.max(1, String(Math.floor(Math.abs(max))).length);
    }
    return DIGITOS_ENTEROS_POR_DEFECTO;
  }

  /** El signo menos solo se admite si el mínimo declarado lo permite. */
  private get admiteNegativo(): boolean {
    const min = this.minimo;
    return min == null || min < 0;
  }

  // ---------------------------------------------------------------- capa 1
  @HostListener('keydown', ['$event'])
  alTeclear(ev: KeyboardEvent): void {
    // Atajos, navegación y borrado quedan fuera: solo se filtran los caracteres.
    if (ev.ctrlKey || ev.metaKey || ev.altKey || ev.key.length !== 1) { return; }
    const campo = this.el as HTMLInputElement;

    // La coma NO se admite aunque aquí se escriba con coma decimal: un
    // `type="number"` solo entiende el punto, y dejarla pasar deja el campo en
    // `badInput` para siempre — el usuario ve «12,99» y el modelo recibe null.
    if (/[0-9]/.test(ev.key)
        || (ev.key === '.' && this.tope > 0 && !campo.value.includes('.'))
        || (ev.key === '-' && this.admiteNegativo && !campo.value.includes('-'))) {
      // Un dígito de más en la parte entera se descarta AQUÍ, con aviso. La
      // comprobación se salta cuando ya hay separador decimal porque entonces
      // el dígito puede ir a los decimales, y desde un `type="number"` no se
      // puede saber dónde está el cursor: leer `selectionStart` sobre él
      // devuelve null en unos navegadores y lanza en otros. Ese caso lo recoge
      // el recorte del manejador de `input`.
      if (/[0-9]/.test(ev.key) && !campo.value.includes('.')
          && this.digitosEnteros(campo.value) >= this.topeEnteros) {
        this.ultimaTecla = null;
        this.truncado = true;
        this.tocado = true;
        ev.preventDefault();
        this.repintar();
        return;
      }
      this.ultimaTecla = ev.key;
      return;
    }
    this.ultimaTecla = null;
    ev.preventDefault();
  }

  // ---------------------------------------------------------------- capa 2
  @HostListener('input')
  alEscribir(): void {
    if (this.reemitiendo) { return; }
    const campo = this.el as HTMLInputElement;
    const tecla = this.ultimaTecla;
    this.ultimaTecla = null;   // se olvida SIEMPRE: lo que venga sin teclado no la hereda

    if (campo.validity.badInput) {
      // Un número a medio escribir (`12.`, `-`) se deja estar; lo demás se deshace.
      if (!(tecla && TECLAS_DE_NUMERO.test(tecla))) {
        this.forzar(this.ultimoBueno);
      }
      this.repintar();
      return;
    }

    const limpio = this.recortar(campo.value);
    if (limpio !== campo.value) { this.forzar(limpio); }
    this.ultimoBueno = campo.value;
    this.repintar();
  }

  // ---------------------------------------------------------------- capa 3
  // Sin `@HostListener`: el de la clase base ya escucha `blur` y Angular hereda
  // los host listeners; declararlo otra vez lo ejecutaría dos veces por salida.
  protected override alSalir(): void {
    const campo = this.el as HTMLInputElement;

    // Lo que quedó a medio escribir se deshace AQUÍ, que es cuando deja de ser
    // «a medio escribir» y pasa a ser lo que el usuario dejó puesto.
    if (campo.validity.badInput) {
      this.forzar(this.ultimoBueno);
    } else if (campo.value !== '') {
      const n = Number(campo.value);
      const min = this.minimo;
      const max = this.maximo;
      let ajustado = n;
      if (min != null && n < min) { ajustado = min; }
      if (max != null && n > max) { ajustado = max; }
      if (ajustado !== n) { this.forzar(String(ajustado)); }
    }
    this.ultimoBueno = campo.value;
    super.alSalir();
  }

  /** Dígitos de la parte entera de un valor, ignorando signo y decimales. */
  private digitosEnteros(valor: string): number {
    const punto = valor.indexOf('.');
    const enteros = punto < 0 ? valor : valor.slice(0, punto);
    return enteros.replace(/[^0-9]/g, '').length;
  }

  /**
   * Recorta lo que sobre —decimales y dígitos enteros— sin redondear: no se
   * inventa un valor que el usuario no escribió. Es la red de seguridad de lo
   * que no pasa por el teclado (pegar, arrastrar, autocompletar), donde el
   * filtro de `keydown` no interviene.
   */
  private recortar(valor: string): string {
    const negativo = valor.startsWith('-');
    const cuerpo = negativo ? valor.slice(1) : valor;
    const punto = cuerpo.indexOf('.');
    let enteros = punto < 0 ? cuerpo : cuerpo.slice(0, punto);
    let decimales = punto < 0 ? '' : cuerpo.slice(punto);

    const tope = this.topeEnteros;
    this.truncado = enteros.length > tope;
    if (this.truncado) { enteros = enteros.slice(0, tope); }

    if (this.tope === 0) { decimales = ''; }
    else if (decimales) { decimales = decimales.slice(0, 1 + this.tope); }

    return (negativo ? '-' : '') + enteros + decimales;
  }

  /**
   * Escribe el valor corregido y vuelve a emitir `input` para que el accesor de
   * Angular lo recoja: sin esta reemisión el modelo se queda con lo sucio.
   */
  private forzar(valor: string): void {
    const campo = this.el as HTMLInputElement;
    campo.value = valor;
    this.reemitiendo = true;
    campo.dispatchEvent(new Event('input', { bubbles: true }));
    this.reemitiendo = false;
  }

  // ------------------------------------------------------------- validador
  /**
   * Lo que hace INVÁLIDO al control. Se calcula con la bandera de tope apagada
   * a propósito: un número completo hasta su último dígito admitido es un
   * número correcto, y marcarlo inválido bloquearía el «Aceptar» del
   * formulario que lo contiene.
   */
  validate(_c: AbstractControl): ValidationErrors | null {
    const choco = this.truncado;
    this.truncado = false;
    const motivo = this.motivoActual();
    this.truncado = choco;
    return motivo ? { numero: motivo } : null;
  }

  protected motivoActual(): string | null {
    const campo = this.el as HTMLInputElement;
    if (campo.validity.badInput) { return 'Escribe un número.'; }
    if (campo.value === '') { return null; }          // vacío lo decide `required`
    const n = Number(campo.value);
    if (!Number.isFinite(n)) { return 'Escribe un número.'; }
    if (this.tope === 0 && !Number.isInteger(n)) { return 'Debe ser un número entero.'; }
    const min = this.minimo;
    const max = this.maximo;
    if (min != null && n < min) { return `El mínimo es ${min}.`; }
    if (max != null && n > max) { return `El máximo es ${max}.`; }
    // Igual que en los campos de texto: chocar con el tope no invalida el
    // valor —por eso no aparece en `validate`—, pero callarlo deja al usuario
    // pulsando una tecla que no hace nada.
    if (this.truncado) {
      return max != null
        ? `El máximo es ${max}.`
        : `Como mucho ${this.topeEnteros} dígitos.`;
    }
    return null;
  }
}
