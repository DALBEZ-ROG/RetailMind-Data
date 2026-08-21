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
  /** Largo máximo, cuando el dato lo tiene por definición. */
  largo?: number;
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
    aviso: 'Los signos < y > no se admiten.'
  },

  /** Identificadores internos escritos a mano: letras, dígitos, - y _ */
  alfanumerico: {
    prohibido: /[^A-Za-z0-9 _-]/g,
    aviso: 'Solo letras, números, guion y guion bajo.'
  },

  /** Código corto en mayúsculas (bodega, método de envío, cupón). */
  codigo: {
    prohibido: /[^A-Za-z0-9_-]/g,
    caja: 'mayus',
    aviso: 'Solo letras, números, guion y guion bajo, en mayúsculas.'
  },

  /** SKU y código de barras: como el código, pero admitiendo el punto. */
  sku: {
    prohibido: /[^A-Za-z0-9._-]/g,
    caja: 'mayus',
    aviso: 'Solo letras, números y los signos - _ .'
  },

  /** Slug de URL: minúsculas, dígitos y guiones. */
  slug: {
    prohibido: /[^a-z0-9-]/g,
    previo: v => v.replace(/[\s_]+/g, '-').replace(/-{2,}/g, '-'),
    caja: 'minus',
    aviso: 'Solo minúsculas, números y guiones.',
    validar: v => v !== '' && (v.startsWith('-') || v.endsWith('-'))
      ? 'No puede empezar ni terminar en guion.' : null
  },

  /** Teléfono: dígitos y los signos de formato que la gente escribe de verdad. */
  telefono: {
    prohibido: /[^0-9+() -]/g,
    largo: 30,
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

  /** Referencia de pago / comprobante: identificador que da un tercero. */
  referencia: {
    prohibido: /[^A-Za-z0-9 ._-]/g,
    largo: 100,
    aviso: 'Solo letras, números y los signos - _ .'
  }
};

export type NombrePerfilTexto = keyof typeof PERFILES_TEXTO;
