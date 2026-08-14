/**
 * Recorte de una lista YA DESCARGADA para que la tabla pinte una página y no
 * el listado entero.
 *
 * <h3>Por qué existe</h3>
 * Seis pantallas operativas pintaban en el DOM todas las filas que devolvía su
 * endpoint. Con los volúmenes de hoy —134.588 órdenes de compra, 145.734
 * devoluciones, 179.851 tickets, 263.077 reseñas— eso son MILLONES de nodos:
 * medido en `/operativo/compras/ordenes`, el DOM pasaba de 3.855 a 3.230.825
 * nodos y el montón de 14,7 MB a 1.333 MB, con lo que la pestaña quedaba
 * muerta. No es un problema de la consulta ni del backend: es que se pinta
 * todo lo que llega.
 *
 * <h3>La página es un CAMPO, nunca un getter</h3>
 * `filas` se recalcula en `fijar()` y en `alPaginar()`, y entre medias es
 * siempre el MISMO array. Devolverla desde un getter —que es como estaba
 * escrito `pedidos-venta`— la reconstruye en cada ciclo de detección de
 * cambios: `MatTable` ve una identidad nueva, vuelve a tirar y a levantar las
 * celdas de la página, y se repinta sin parar (trampa §8.6 de `PATRON_UI.md`).
 *
 * <h3>Lo que NO resuelve</h3>
 * La descarga sigue siendo íntegra: el endpoint devuelve las 134.588 filas
 * (27,18 MB medidos) y esto solo evita pintarlas. Acotar la CONSULTA es cosa
 * del backend y queda fuera de esta sesión.
 */
export class PaginaLocal<T> {

  /** El listado completo tal cual lo devolvió el endpoint. */
  todas: T[] = [];

  /** La página visible. Campo estable: se reasigna solo al paginar. */
  filas: T[] = [];

  pagina = 0;
  tam = 25;

  readonly opciones = [25, 50, 100];

  /** Cuántas filas hay en total (para el rótulo del paginador). */
  get total(): number { return this.todas.length; }

  /** Carga un listado nuevo y vuelve a la primera página. */
  fijar(todas: T[] | null | undefined): void {
    this.todas = todas ?? [];
    this.pagina = 0;
    this.recortar();
  }

  alPaginar(e: { pageIndex: number; pageSize: number }): void {
    this.pagina = e.pageIndex;
    this.tam = e.pageSize;
    this.recortar();
  }

  /**
   * Rehace la página SIN mover el cursor. Se usa cuando la pantalla filtra o
   * reordena en cliente sobre el mismo listado.
   */
  refrescar(): void {
    const ultima = Math.max(Math.ceil(this.todas.length / this.tam) - 1, 0);
    if (this.pagina > ultima) { this.pagina = ultima; }
    this.recortar();
  }

  private recortar(): void {
    const inicio = this.pagina * this.tam;
    this.filas = this.todas.slice(inicio, inicio + this.tam);
  }
}
