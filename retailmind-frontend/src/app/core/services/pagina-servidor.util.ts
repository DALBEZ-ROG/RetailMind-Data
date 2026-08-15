/**
 * Estado del paginador cuando la página la recorta el SERVIDOR.
 *
 * <h3>Por qué existe, y en qué se diferencia de `PaginaLocal`</h3>
 * `PaginaLocal` recorta una lista YA DESCARGADA: arregla el DOM y no la
 * descarga. Con los volúmenes de hoy —263.077 reseñas (82,07 MB medidos),
 * 179.851 tickets (78,98 MB), 145.734 devoluciones (49,53 MB)— el problema que
 * queda es el viaje: la pantalla espera segundos y el navegador retiene esos
 * megas aunque solo pinte 25 filas. Esta clase es el estado de la pantalla
 * cuando quien recorta es `comun.Paginacion` en el backend, con el mismo sobre
 * `{items, total, page, size}`.
 *
 * <h3>El filtro NO vive aquí, y es la trampa de todo el cambio</h3>
 * Con `PaginaLocal` la pantalla filtraba el array completo y el resultado era
 * correcto. Al paginar en servidor, ese mismo filtro mira SOLO la página
 * visible y devuelve un resultado plausible y FALSO, sin error ninguno. Por eso
 * esta clase no ofrece ningún método de filtrado: los criterios viajan al
 * endpoint y el `total` que se guarda aquí es el del conjunto filtrado.
 *
 * <h3>`total = -1` significa «no se recontó»</h3>
 * El conteo es lo caro bajo RLS (0,3-4,7 s según la tabla), y al cambiar de
 * página el conjunto no ha cambiado. `aplicar()` conserva el total anterior
 * cuando el servidor manda -1.
 */
export class PaginaServidor<T> {

  /** La página visible, tal cual la devolvió el servidor. */
  filas: T[] = [];

  /** Conteo del conjunto filtrado, no de la página. */
  total = 0;

  /**
   * `total` es un MÍNIMO porque el conteo se cortó en el tope del servidor.
   * Ver `Pagina.totalEsMinimo`.
   */
  totalEsMinimo = false;

  /** Lo que se pinta junto al título: «2.351» o «más de 200.000». */
  get etiquetaTotal(): string {
    const n = this.total.toLocaleString('es-EC');
    return this.totalEsMinimo ? `más de ${n}` : n;
  }

  pagina = 0;
  tam = 25;

  readonly opciones = [25, 50, 100];

  /** Aplica el sobre del servidor. */
  aplicar(sobre: { items: T[]; total: number; totalEsMinimo?: boolean } | null | undefined): void {
    this.filas = sobre?.items ?? [];
    // total = -1 significa «no se recontó»: se conserva el que ya había, y con
    // él su carácter de exacto o de mínimo.
    if (sobre && sobre.total >= 0) {
      this.total = sobre.total;
      this.totalEsMinimo = !!sobre.totalEsMinimo;
    }
  }

  /** Vuelve a la primera página. Se llama al cambiar cualquier filtro. */
  reiniciar(): void { this.pagina = 0; }

  /** Registra el cursor del paginador; recargar es cosa de la pantalla. */
  alPaginar(e: { pageIndex: number; pageSize: number }): void {
    this.pagina = e.pageIndex;
    this.tam = e.pageSize;
  }

  /**
   * Retrocede si la página actual se quedó vacía tras una acción que reduce el
   * conjunto (moderar, resolver, facturar). Devuelve true si hubo que mover el
   * cursor, para que la pantalla vuelva a pedir.
   */
  ajustarTrasBorrado(): boolean {
    if (this.filas.length === 0 && this.pagina > 0) {
      this.pagina--;
      return true;
    }
    return false;
  }
}
