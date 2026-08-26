/**
 * Los perfiles de texto: qué caracteres admite cada clase de campo y cuándo lo
 * escrito, aun siendo de caracteres legales, todavía no sirve.
 *
 * Están aquí y no dentro de la directiva por una razón práctica: son la tabla
 * que hay que leer para saber qué se permite en toda la aplicación, y al mismo
 * tiempo lo que la prueba de navegador contrasta. Si el juego de caracteres se
 * escribiera en cada plantilla, tres pantallas acabarían admitiendo tres cosas
 * distintas para el mismo dato.
 *
 * `prohibido` es lo que se BORRA mientras se escribe; `validar` es lo que se
 * AVISA al salir del campo. Son capas distintas: quitar una letra de un teléfono
 * es obvio y no molesta, pero borrar un teléfono de 4 dígitos porque aún está a
 * medio escribir sería insufrible.
 */
export interface PerfilTexto {
  /** Caracteres que se retiran del valor. */
  prohibido: RegExp;
  /** Normalización previa (espacios a guiones en un slug, por ejemplo). */
  previo?: (v: string) => string;
  /** Mayúsculas / minúsculas forzadas. */
  caja?: 'mayus' | 'minus';
  /**
   * Largo máximo del campo. **Todo perfil declara uno**: un campo sin tope
   * admite un pegado de un millón de caracteres, que el navegador acepta sin
   * pestañear y el motor rechaza con un error de longitud que no dice cuál de
   * los campos del formulario sobra.
   *
   * El valor es el TECHO de la clase de dato, no el de una columna concreta:
   * cuando la columna que hay detrás es más corta, la plantilla lo aprieta con
   * su propio `maxlength` y ése manda (ver `CampoTextoDirective.largo`).
   */
  largo: number;
  /** Qué se le dice al usuario cuando teclea algo que no entra. */
  aviso: string;
  /** Comprobación del valor COMPLETO; devuelve el motivo o null. */
  validar?: (v: string) => string | null;
}

/**
 * Letras latinas con los diacríticos del español. Los tres tramos salen de
 * partir el bloque Latin-1 por los DOS signos matemáticos que lo cruzan
 * (U+00D7 «×» y U+00F7 «÷»): con un solo rango À-ſ, «Bodega ×» sería
 * un nombre válido.
 */
const LETRAS = 'A-Za-zÀ-ÖØ-öø-ſ';

const soloDigitos = (v: string) => v.replace(/\D/g, '');

export const PERFILES_TEXTO: Record<string, PerfilTexto> = {

  /** Nombres de persona, de marca, de producto, de bodega… */
  nombre: {
    // El guion va AL FINAL de la clase y sin barra invertida: en medio sería el
    // rango `)`-`/`, que cuela tres signos de más sin que nada lo delate.
    prohibido: new RegExp(`[^${LETRAS}0-9 .,'&()/-]`, 'g'),
    // 150 = la columna más larga de esta clase (direccion.destinatario,
    // calle_principal); las de 100 —cliente.nombre, marca.nombre— lo aprietan
    // desde su plantilla.
    largo: 150,
    aviso: 'Solo letras, números y los signos . , \' & ( ) - /',
    validar: v => v.trim().length > 0 && v.trim().length < 2
      ? 'Escribe al menos 2 caracteres.' : null
  },

  /**
   * Texto libre: descripciones, observaciones, motivos, notas. Aquí no se
   * recorta el vocabulario —una observación necesita poder decir «llegó al 50 %
   * y el resto no»—; lo único que se retira son los ángulos, que no aportan
   * nada a una nota y son la mitad de cualquier intento de colar marcado.
   */
  libre: {
    prohibido: /[<>]/g,
    // Aquí el tope no sale de ninguna columna —casi todas son `text`— sino de
    // lo que una persona escribe de verdad en una nota o una descripción:
    // 2.000 caracteres son unas dos páginas.
    largo: 2000,
    aviso: 'Los signos < y > no se admiten.'
  },

  /** Identificadores internos escritos a mano: letras, dígitos, - y _ */
  alfanumerico: {
    prohibido: /[^A-Za-z0-9 _-]/g,
    largo: 60,
    aviso: 'Solo letras, números, guion y guion bajo.'
  },

  /** Código corto en mayúsculas (bodega, método de envío, cupón). */
  codigo: {
    prohibido: /[^A-Za-z0-9_-]/g,
    largo: 50,          // cupon.codigo; bodega y metodo_envio son de 20 y lo declaran
    caja: 'mayus',
    aviso: 'Solo letras, números, guion y guion bajo, en mayúsculas.'
  },

  /** SKU y código de barras: como el código, pero admitiendo el punto. */
  sku: {
    prohibido: /[^A-Za-z0-9._-]/g,
    largo: 50,          // producto_variante.sku y codigo_barras
    caja: 'mayus',
    aviso: 'Solo letras, números y los signos - _ .'
  },

  /** Slug de URL: minúsculas, dígitos y guiones. */
  slug: {
    prohibido: /[^a-z0-9-]/g,
    largo: 200,         // producto.slug es varchar(220); marca y categoría, 120
    previo: v => v.replace(/[\s_]+/g, '-').replace(/-{2,}/g, '-'),
    caja: 'minus',
    aviso: 'Solo minúsculas, números y guiones.',
    validar: v => v !== '' && (v.startsWith('-') || v.endsWith('-'))
      ? 'No puede empezar ni terminar en guion.' : null
  },

  /** Teléfono: dígitos y los signos de formato que la gente escribe de verdad. */
  telefono: {
    prohibido: /[^0-9+() -]/g,
    largo: 20,          // las 7 columnas `telefono` del esquema son varchar(20)
    aviso: 'Solo números y los signos + ( ) -',
    validar: v => {
      if (v.trim() === '') { return null; }
      const d = soloDigitos(v);
      if (d.length < 7) { return 'Un teléfono tiene al menos 7 dígitos.'; }
      if (d.length > 15) { return 'Un teléfono no pasa de 15 dígitos.'; }
      return null;
    }
  },

  /** Solo dígitos, sin largo exigido (CVV, correlativos escritos a mano). */
  digitos: {
    prohibido: /\D/g,
    largo: 20,          // cliente.numero_identificacion; el CVV lo aprieta a 4
    aviso: 'Solo números.'
  },

  /** RUC / cédula ecuatorianos: dígitos y nada más. */
  ruc: {
    prohibido: /\D/g,
    largo: 13,
    aviso: 'Solo números.',
    validar: v => {
      if (v === '') { return null; }
      if (v.length !== 10 && v.length !== 13) {
        return 'La cédula tiene 10 dígitos y el RUC 13.';
      }
      return null;
    }
  },

  /** Código postal: dígitos y letras, sin signos. */
  postal: {
    prohibido: /[^A-Za-z0-9-]/g,
    largo: 10,
    caja: 'mayus',
    aviso: 'Solo letras, números y guion.'
  },

  /** Número de domicilio: corto y sin signos raros. */
  numeroCasa: {
    prohibido: /[^A-Za-z0-9 -]/g,
    largo: 20,
    aviso: 'Solo letras, números y guion.'
  },

  /** Correo electrónico. */
  email: {
    prohibido: /[^A-Za-z0-9@._+-]/g,
    caja: 'minus',
    largo: 255,
    aviso: 'Un correo no lleva espacios ni signos fuera de @ . _ + -',
    validar: v => {
      if (v === '') { return null; }
      return /^[A-Za-z0-9._+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/.test(v)
        ? null : 'Escribe un correo con la forma nombre@dominio.com';
    }
  },

  /** Dirección web. */
  url: {
    // Este perfil se declara al revés que los demás —lista BLANCA— a propósito:
    // enumerar lo prohibido en una URL es enumerar casi todo el teclado, y basta
    // olvidar un signo para dejar pasar justo el que importaba.
    prohibido: /[^A-Za-z0-9:/?#@!$&*+,;=._~%-]/g,
    largo: 500,
    aviso: 'Una dirección web solo admite los signos : / ? # @ ! $ & * + , ; = . _ ~ % -',
    validar: v => {
      if (v === '') { return null; }
      return /^https?:\/\/[^\s.]+\.[^\s]{2,}$/.test(v)
        ? null : 'Debe empezar por http:// o https:// e incluir un dominio.';
    }
  },

  /**
   * Número de tarjeta, agrupado de cuatro en cuatro mientras se escribe.
   *
   * Está aquí y no en el componente del checkout por una razón concreta: ahí
   * el formateo colgaba de un `(input)` de la plantilla, y **un manejador de
   * plantilla corre ANTES de que `ngModel` escriba en el modelo**. O sea que
   * leía el valor de la tecla anterior, recortaba ESE y luego `ngModel`
   * machacaba el resultado con lo recién tecleado: el tope de 16 dígitos no
   * llegaba a aplicarse nunca y se podían escribir tantos como cupieran.
   * La directiva no tiene ese problema porque trabaja sobre `el.value` —el
   * DOM, no el modelo— y vuelve a emitir `input` con el valor ya limpio.
   */
  tarjeta: {
    prohibido: /[^0-9 ]/g,
    previo: v => (v.replace(/\D/g, '').slice(0, 16).replace(/(\d{4})(?=\d)/g, '$1 ')),
    largo: 19,                    // 16 dígitos + los 3 espacios que los separan
    aviso: 'Una tarjeta son 16 dígitos.',
    validar: v => /^\d{16}$/.test(v.replace(/\s/g, ''))
      ? null : 'Una tarjeta son 16 dígitos.'
  },

  /**
   * Vencimiento de tarjeta MM/AA. La barra la pone el perfil en cuanto hay un
   * tercer dígito —nunca con solo dos, o no se podría borrar: al quitarla, el
   * saneado la repondría en el acto y la tecla de retroceso no haría nada—.
   */
  vencimiento: {
    prohibido: /[^0-9/]/g,
    previo: v => {
      const d = v.replace(/\D/g, '').slice(0, 4);
      return d.length > 2 ? d.slice(0, 2) + '/' + d.slice(2) : d;
    },
    largo: 5,
    aviso: 'Escribe el mes y el año con la forma MM/AA.',
    validar: v => /^(0[1-9]|1[0-2])\/\d{2}$/.test(v)
      ? null : 'Escribe el mes y el año con la forma MM/AA (el mes va de 01 a 12).'
  },

  /** Referencia de pago / comprobante: identificador que da un tercero. */
  referencia: {
    prohibido: /[^A-Za-z0-9 ._-]/g,
    largo: 100,
    aviso: 'Solo letras, números y los signos - _ .'
  }
};

export type NombrePerfilTexto = keyof typeof PERFILES_TEXTO;
