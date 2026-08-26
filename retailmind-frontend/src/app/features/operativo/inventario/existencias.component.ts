import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import { InventarioService } from '../../../core/services/inventario.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  BodegaRef, ExistenciaBodegaRow, ExistenciaRow, ResumenExistencias
} from '../../../core/models/operativo.model';
import { CampoTextoDirective } from '../../../core/validacion';

/**
 * EXISTENCIAS: qué productos hay y cuántos hay de cada uno.
 *
 * Es la pregunta que faltaba en Inventario. El módulo tenía Transferencias,
 * Ajustes y Kardex —los tres MOVIMIENTOS— y ninguna pantalla contestaba
 * «cuánto tengo»: el stock solo asomaba dentro del formulario de ajuste, y
 * únicamente de la variante que se estuviera ajustando.
 *
 * Tres decisiones que conviene no deshacer:
 *
 *  1. **El grano es la VARIANTE, no la posición de inventario.** Lo que se
 *     vende es el SKU; que esté repartido en dos bodegas es un detalle que se
 *     abre al pulsar la fila. Con una fila por posición, un SKU en tres
 *     bodegas aparecería tres veces y la pregunta «cuántos tengo» habría que
 *     resolverla sumando a ojo.
 *  2. **Todo el filtrado y la paginación son del SERVIDOR.** Son 6.224
 *     variantes; traerlas para filtrar en el navegador es lo que ya obligó a
 *     rehacer el selector de clientes (50.072 filas, 4 MB por apertura).
 *  3. **Ni un importe.** La pantalla la abre BODEGA, que por segregación
 *     financiera no lee precio ni costo. Lo garantiza la consulta del
 *     servidor, que no los selecciona.
 */
@Component({
  selector: 'app-existencias',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatTooltipModule,
    MatSnackBarModule, MatPaginatorModule, CampoTextoDirective],
  templateUrl: './existencias.component.html',
  styleUrls: ['../operativo-shared.scss', './existencias.component.scss']
})
export class ExistenciasComponent implements OnInit, OnDestroy {

  bodegas: BodegaRef[] = [];
  filas: ExistenciaRow[] = [];
  resumen: ResumenExistencias | null = null;

  busqueda = '';
  bodegaId: number | null = null;
  estado = 'todos';
  orden = 'producto';

  total = 0;
  page = 0;
  size = 25;
  readonly pageSizes = [25, 50, 100];
  loading = true;

  /** Fila desplegada y su desglose por bodega. */
  seleccionada: ExistenciaRow | null = null;
  desglose: ExistenciaBodegaRow[] = [];
  cargandoDesglose = false;

  readonly columnas = ['sku', 'producto', 'bodegas', 'minimo', 'reservado', 'disponible', 'stock'];

  /** Los mismos nombres que la lista blanca del servidor; un valor que no esté
   *  aquí no llega al SQL: el servicio responde 400. */
  readonly estados = [
    { valor: 'todos',        texto: 'Todas las variantes' },
    { valor: 'con_stock',    texto: 'Con existencias' },
    { valor: 'sin_stock',    texto: 'Sin existencias' },
    { valor: 'bajo_minimo',  texto: 'Bajo el mínimo' },
    { valor: 'sobre_maximo', texto: 'Sobre el máximo' }
  ];

  readonly ordenes = [
    { valor: 'producto',   texto: 'Producto (A-Z)' },
    { valor: 'sku',        texto: 'SKU' },
    { valor: 'stock_desc', texto: 'Más existencias primero' },
    { valor: 'stock_asc',  texto: 'Menos existencias primero' }
  ];

  private readonly busqueda$ = new Subject<string>();
  private busquedaSub?: Subscription;

  constructor(private inventario: InventarioService,
              private referencias: ReferenciasService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.busquedaSub = this.busqueda$
      .pipe(debounceTime(300), distinctUntilChanged())
      .subscribe(() => { this.page = 0; this.consultar(); });
    this.referencias.bodegas().subscribe(b => this.bodegas = b);
    this.consultar();
  }

  ngOnDestroy(): void { this.busquedaSub?.unsubscribe(); }

  alEscribirBusqueda(texto: string): void { this.busqueda$.next(texto); }

  alFiltrar(): void { this.page = 0; this.consultar(); }

  alPaginar(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.consultar();
  }

  limpiar(): void {
    this.busqueda = '';
    this.bodegaId = null;
    this.estado = 'todos';
    this.orden = 'producto';
    this.alFiltrar();
  }

  consultar(): void {
    this.loading = true;
    this.inventario.existencias({
      q: this.busqueda, bodegaId: this.bodegaId, estado: this.estado,
      orden: this.orden, page: this.page, size: this.size
    }).subscribe({
      next: pagina => {
        this.filas = pagina.items;
        this.total = pagina.total;
        this.resumen = pagina.resumen;
        this.loading = false;
        // La fila desplegada es un objeto de la carga anterior: se vuelve a
        // apuntar a la de la página nueva o se cierra, para que el desglose
        // no siga abierto sobre un SKU que ya no está en pantalla.
        if (this.seleccionada) {
          const vigente = this.filas.find(f => f.variante_id === this.seleccionada!.variante_id);
          this.seleccionada = vigente ?? null;
          if (!vigente) { this.desglose = []; }
        }
      },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudieron consultar las existencias'),
          'Cerrar', { duration: 5000 });
      }
    });
  }

  /** Pulsar la fila abre (o cierra) el reparto por bodega de ese SKU. */
  alternarDesglose(f: ExistenciaRow): void {
    if (this.seleccionada?.variante_id === f.variante_id) {
      this.seleccionada = null;
      this.desglose = [];
      return;
    }
    this.seleccionada = f;
    this.desglose = [];
    this.cargandoDesglose = true;
    this.inventario.existenciasPorBodega(f.variante_id).subscribe({
      next: d => { this.desglose = d; this.cargandoDesglose = false; },
      error: e => {
        this.cargandoDesglose = false;
        this.snackBar.open(mensajeError(e, 'No se pudo cargar el reparto por bodega'),
          'Cerrar', { duration: 4000 });
      }
    });
  }

  /**
   * Cómo está una variante respecto de sus niveles.
   *
   * «Bajo el mínimo» solo se afirma cuando HAY un mínimo declarado: con
   * `stock_minimo = 0` la comparación `0 <= 0` marcaría en rojo a toda
   * variante agotada que nadie ha decidido reponer, que es otra cosa.
   */
  situacion(f: ExistenciaRow): 'agotada' | 'bajo' | 'sobre' | 'ok' {
    if (f.stock_actual <= 0) { return 'agotada'; }
    if (f.stock_minimo > 0 && f.stock_actual <= f.stock_minimo) { return 'bajo'; }
    if (f.stock_maximo != null && f.stock_actual > f.stock_maximo) { return 'sobre'; }
    return 'ok';
  }

  textoSituacion(f: ExistenciaRow): string {
    switch (this.situacion(f)) {
      case 'agotada': return 'Agotada';
      case 'bajo':    return 'Bajo mínimo';
      case 'sobre':   return 'Sobre máximo';
      default:        return 'Normal';
    }
  }

  /** Qué se está contando ahora mismo, para que el resumen no mienta. */
  get alcance(): string {
    const bodega = this.bodegaId
      ? this.bodegas.find(b => b.id === this.bodegaId)?.nombre ?? 'la bodega elegida'
      : 'todas las bodegas';
    const filtro = this.estados.find(e => e.valor === this.estado)?.texto ?? '';
    return `${filtro.toLowerCase()} · ${bodega}`;
  }
}
