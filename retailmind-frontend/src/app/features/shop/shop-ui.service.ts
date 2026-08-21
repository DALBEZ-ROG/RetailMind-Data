import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { ShopService } from './shop.service';
import { paletaCategoria, PaletaCategoria } from './catalogo-visual';

/**
 * Estado de PRESENTACIÓN de la tienda (solo frontend, sin API propia).
 *
 * Existe porque los contadores del carrito y de la wishlist se pintan en la
 * BARRA SUPERIOR —que vive en `app.component`— mientras que quien los mueve es
 * el catálogo, el detalle de producto o la propia wishlist. Sin un punto único,
 * agregar algo al carrito desde una tarjeta dejaba el globo de la barra con la
 * cifra vieja hasta recargar la página.
 *
 * No guarda productos ni precios: eso lo sirve el backend y se pide cuando toca.
 * Aquí solo viven dos números y el conjunto de ids en wishlist, que es lo que
 * necesitan los corazones de las tarjetas para pintarse llenos o vacíos.
 */
@Injectable({ providedIn: 'root' })
export class ShopUiService {

  private readonly _carrito = new BehaviorSubject<number>(0);
  private readonly _wishlist = new BehaviorSubject<Set<number>>(new Set());

  /**
   * Nº de LÍNEAS del carrito (no unidades) — es el globo de la barra.
   *
   * Se relee del servidor tras cada alta en vez de sumarle uno en el cliente:
   * agregar un producto que YA estaba en el carrito no crea una línea, sube su
   * cantidad, así que el «+1» optimista dejaba el globo marcando una línea de
   * más hasta la siguiente recarga.
   */
  readonly carritoCount$ = this._carrito.asObservable();
  /** Ids de variante en wishlist; alimenta el corazón de cada tarjeta. */
  readonly wishlistIds$ = this._wishlist.asObservable();

  constructor(private shop: ShopService) {
    this._elegidaId = this.leerEleccionGuardada();
  }

  get carritoCount(): number { return this._carrito.value; }
  get wishlistIds(): Set<number> { return this._wishlist.value; }
  get wishlistCount(): number { return this._wishlist.value.size; }

  /** Relee ambos contadores del servidor. Silencioso: un fallo no molesta. */
  refrescarTodo(): void {
    this.refrescarCarrito();
    this.refrescarWishlist();
  }

  refrescarCarrito(): void {
    this.shop.getCarrito().subscribe({
      next: (items) => this._carrito.next(items?.length ?? 0),
      error: () => {}
    });
  }

  refrescarWishlist(): void {
    this.shop.getWishlist().subscribe({
      next: (items) => this._wishlist.next(new Set((items ?? []).map(i => Number(i.productoId)))),
      error: () => {}
    });
  }

  fijarCarrito(n: number): void {
    this._carrito.next(Math.max(0, n));
  }

  marcarWishlist(varianteId: number, dentro: boolean): void {
    const copia = new Set(this._wishlist.value);
    if (dentro) copia.add(Number(varianteId)); else copia.delete(Number(varianteId));
    this._wishlist.next(copia);
  }

  estaEnWishlist(varianteId: number): boolean {
    return this._wishlist.value.has(Number(varianteId));
  }

  // ── Dirección de envío de la barra superior ──────────────────────────────
  // La barra enseña a DÓNDE va a llegar el pedido, que es lo que decide el
  // transportista y la tarifa, y deja cambiarla sin llegar al pago. La
  // elección se guarda AQUÍ y no en la base: marcar una dirección como
  // predeterminada es otra cosa —un dato del perfil, con su pantalla— y esto
  // es una preferencia de la sesión. El checkout la lee para preseleccionarla;
  // si no hay ninguna elegida, sigue mandando `esPredeterminada`, como antes.
  private static readonly CLAVE_DIR = 'rm_dir_envio';

  private readonly _direcciones = new BehaviorSubject<any[]>([]);
  readonly direcciones$ = this._direcciones.asObservable();
  private _elegidaId: number | null = null;
  private direccionesPedidas = false;

  get direcciones(): any[] { return this._direcciones.value; }

  cargarDirecciones(forzar = false): void {
    if (this.direccionesPedidas && !forzar) return;
    this.direccionesPedidas = true;
    this.shop.getDirecciones().subscribe({
      next: (dirs) => {
        this._direcciones.next(dirs || []);
        // La elección guardada solo vale si la dirección SIGUE existiendo: se
        // pueden dar de baja desde el perfil, y una barra anunciando un envío
        // a una dirección borrada llevaría al pago a proponer otra distinta.
        if (this._elegidaId != null
            && !(dirs || []).some(d => Number(d.id) === this._elegidaId)) {
          this.elegirDireccion(null);
        }
      },
      error: () => this.direccionesPedidas = false
    });
  }

  /**
   * Recuerda la elección entre recargas. Va en `localStorage` y no solo en
   * memoria porque el cliente puede refrescar la pestaña entre mirar el
   * catálogo y pagar, y volver a encontrar OTRA dirección en la barra es
   * justo lo que este bloque venía a evitar. Se limpia sola si la dirección
   * deja de existir (ver `cargarDirecciones`).
   */
  elegirDireccion(id: number | null): void {
    this._elegidaId = id != null ? Number(id) : null;
    try {
      if (this._elegidaId == null) localStorage.removeItem(ShopUiService.CLAVE_DIR);
      else localStorage.setItem(ShopUiService.CLAVE_DIR, String(this._elegidaId));
    } catch { /* almacenamiento no disponible: se queda en memoria */ }
  }

  private leerEleccionGuardada(): number | null {
    try {
      const v = localStorage.getItem(ShopUiService.CLAVE_DIR);
      return v ? Number(v) : null;
    } catch { return null; }
  }

  /** La elegida en la barra, o la predeterminada, o la primera que haya. */
  get direccionEnvio(): any | null {
    const dirs = this._direcciones.value;
    if (!dirs.length) return null;
    return dirs.find(d => Number(d.id) === this._elegidaId)
        || dirs.find(d => d.esPredeterminada)
        || dirs[0];
  }

  get direccionElegidaId(): number | null {
    return this.direccionEnvio ? Number(this.direccionEnvio.id) : null;
  }

  // ── Nombre de departamento por id ────────────────────────────────────────
  // Carrito y wishlist devuelven `categoriaId` pero NO el nombre, y la
  // identidad visual (icono y color) se decide por NOMBRE — a propósito: los
  // ids no son una serie y hoy conviven 1-12 con 60001-60006. Sin esta tabla,
  // el mismo producto salía con su icono en el catálogo y con una caja gris en
  // el carrito. Se resuelve pidiendo los departamentos UNA vez a
  // `/api/catalogo/categorias`, que es el mismo origen que ve el catálogo, en
  // vez de escribir los ids en el código.
  private readonly nombres = new Map<number, string>();
  private categoriasPedidas = false;

  cargarCategorias(): void {
    if (this.categoriasPedidas) return;
    this.categoriasPedidas = true;
    this.shop.getCategorias().subscribe({
      next: (cats) => (cats || []).forEach(c => this.nombres.set(Number(c.categoriaId), c.nombre)),
      // Un fallo aquí solo cuesta el icono: la tarjeta sigue pintándose.
      error: () => this.categoriasPedidas = false
    });
  }

  /** Identidad visual de un producto venga de donde venga su fila. */
  paleta(item: any): PaletaCategoria {
    const nombre = item?.categoriaNombre || this.nombres.get(Number(item?.categoriaId));
    return paletaCategoria(nombre, item?.categoriaId);
  }
}
