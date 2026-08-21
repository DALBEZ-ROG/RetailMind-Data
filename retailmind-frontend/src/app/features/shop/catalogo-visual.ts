/**
 * Identidad visual del catálogo: qué icono y qué color lleva cada categoría.
 *
 * El mapa va por NOMBRE NORMALIZADO y no por id. El motivo es que los ids no
 * son una serie: conviven 1-12 (categorías del catálogo original) con
 * 60001-60006 (las que sembró la Fase 0 de la carga masiva), así que el mapa
 * por id que había antes dejaba SEIS categorías —900 productos la mayor— con
 * el icono genérico de caja. Si mañana entra una categoría nueva, cae en el
 * reparto determinista por hash y sigue teniendo color propio, nunca gris.
 */

export interface PaletaCategoria {
  /** Icono de Material Icons (fuente legacy: la que carga index.html). */
  icono: string;
  /** Fondo del área de imagen de la tarjeta. */
  fondo: string;
  /** Tinta del icono y del rótulo de categoría. */
  tinta: string;
  /** Borde suave a juego. */
  borde: string;
}

/** Seis familias cromáticas; el reparto por hash usa esta misma lista. */
const FAMILIAS: Array<{ fondo: string; tinta: string; borde: string }> = [
  { fondo: 'linear-gradient(140deg,#e8f0fe 0%,#dce9fd 100%)', tinta: '#1a4fa0', borde: 'rgba(26,79,160,.22)' },
  { fondo: 'linear-gradient(140deg,#fff2e2 0%,#ffe7cc 100%)', tinta: '#a85708', borde: 'rgba(168,87,8,.22)' },
  { fondo: 'linear-gradient(140deg,#e6f6ec 0%,#d7f0e2 100%)', tinta: '#1c6b3f', borde: 'rgba(28,107,63,.22)' },
  { fondo: 'linear-gradient(140deg,#fdeaf1 0%,#fadbe7 100%)', tinta: '#a34a63', borde: 'rgba(163,74,99,.22)' },
  { fondo: 'linear-gradient(140deg,#eeeafc 0%,#e3dcfa 100%)', tinta: '#5738a8', borde: 'rgba(87,56,168,.22)' },
  { fondo: 'linear-gradient(140deg,#e4f5f7 0%,#d5eef2 100%)', tinta: '#0f6b78', borde: 'rgba(15,107,120,.22)' }
];

/** nombre normalizado → icono + familia cromática. */
const POR_NOMBRE: Record<string, { icono: string; familia: number }> = {
  'electronica':                     { icono: 'devices',              familia: 0 },
  'abarrotes':                       { icono: 'shopping_basket',      familia: 1 },
  'consumo diario':                  { icono: 'local_grocery_store',  familia: 1 },
  'deportes':                        { icono: 'sports_soccer',        familia: 2 },
  'accesorios':                      { icono: 'watch',                familia: 4 },
  'repuestos y accesorios':          { icono: 'build',                familia: 5 },
  'belleza':                         { icono: 'spa',                  familia: 3 },
  'cuidado personal':                { icono: 'sanitizer',            familia: 3 },
  'hogar':                           { icono: 'home',                 familia: 5 },
  'limpieza del hogar':              { icono: 'cleaning_services',    familia: 2 },
  'linea blanca y electrodomesticos':{ icono: 'kitchen',              familia: 0 },
  'ferreteria y herramienta':        { icono: 'construction',         familia: 1 },
  'calzado':                         { icono: 'directions_walk',      familia: 5 },
  'ropa':                            { icono: 'checkroom',            familia: 4 },
  'ropa hombre':                     { icono: 'checkroom',            familia: 0 },
  'ropa mujer':                      { icono: 'checkroom',            familia: 3 }
};

/** Sin acentos, sin mayúsculas: «Electrónica» y «ELECTRONICA» son la misma. */
function normalizar(texto: string | null | undefined): string {
  return (texto || '')
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .toLowerCase().trim();
}

/** Hash estable para lo que no esté en el mapa: mismo nombre, mismo color. */
function hash(texto: string): number {
  let h = 0;
  for (let i = 0; i < texto.length; i++) h = (h * 31 + texto.charCodeAt(i)) >>> 0;
  return h;
}

export function paletaCategoria(nombre?: string | null, id?: number | null): PaletaCategoria {
  const clave = normalizar(nombre);
  const entrada = POR_NOMBRE[clave];
  if (entrada) {
    return { icono: entrada.icono, ...FAMILIAS[entrada.familia] };
  }
  const semilla = clave ? hash(clave) : Number(id || 0);
  return { icono: 'inventory_2', ...FAMILIAS[semilla % FAMILIAS.length] };
}

export function iconoCategoria(nombre?: string | null, id?: number | null): string {
  return paletaCategoria(nombre, id).icono;
}
